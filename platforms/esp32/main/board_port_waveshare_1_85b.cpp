// SPDX-License-Identifier: Apache-2.0
// Panel initialization values below are adapted from Waveshare's
// ESP32-S3-Touch-LCD-1.85B BSP (Apache-2.0):
// https://github.com/waveshareteam/ESP32-S3-Touch-LCD-1.85B

#include "board_port.h"

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <limits>

#include "driver/gpio.h"
#include "driver/i2c_master.h"
#include "driver/ledc.h"
#include "driver/spi_master.h"
#include "esp_check.h"
#include "esp_err.h"
#include "esp_heap_caps.h"
#include "esp_lcd_panel_io.h"
#include "esp_lcd_panel_ops.h"
#include "esp_lcd_st77916.h"
#include "esp_lcd_touch.h"
#include "esp_lcd_touch_cst816s.h"
#include "esp_log.h"
#include "esp_lv_adapter.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

namespace {

constexpr char kTag[] = "board_1_85b";
constexpr spi_host_device_t kLcdSpiHost = SPI2_HOST;
constexpr gpio_num_t kLcdCs = GPIO_NUM_21;
constexpr gpio_num_t kLcdClock = GPIO_NUM_40;
constexpr gpio_num_t kLcdData0 = GPIO_NUM_46;
constexpr gpio_num_t kLcdData1 = GPIO_NUM_45;
constexpr gpio_num_t kLcdData2 = GPIO_NUM_42;
constexpr gpio_num_t kLcdData3 = GPIO_NUM_41;
constexpr gpio_num_t kLcdReset = GPIO_NUM_3;
constexpr gpio_num_t kBacklight = GPIO_NUM_5;
constexpr gpio_num_t kTouchScl = GPIO_NUM_10;
constexpr gpio_num_t kTouchSda = GPIO_NUM_11;
constexpr gpio_num_t kTouchReset = GPIO_NUM_1;
constexpr gpio_num_t kPageButton = GPIO_NUM_0;
// LVGL's 50 px / 3 px-per-sample defaults miss short swipes on this 360 px
// round screen. Keep the tuning local to the 1.85B input device.
constexpr std::uint8_t kSwipeMinDistancePx = 30;
constexpr std::uint8_t kSwipeMinVelocityPx = 1;
constexpr int kI2cClockHz = 400'000;
constexpr int kLcdClockHz = 80'000'000;
// A 50-row PSRAM strip requires a 36 KB temporary internal DMA buffer. With
// NimBLE running, that allocation failed on the physical 1.85B. Keep the draw
// buffer in DMA-capable internal RAM and transfer ten rows at a time instead.
constexpr std::size_t kDrawBufferHeight = 10;
constexpr std::size_t kLcdTransferBytes =
    MOTO_DISPLAY_WIDTH * kDrawBufferHeight * MOTO_DISPLAY_BITS_PER_PIXEL / 8;
constexpr std::size_t kLvglExtraPoolBytes = 2U * 1024U * 1024U;
constexpr ledc_mode_t kBacklightLedcMode = LEDC_LOW_SPEED_MODE;
constexpr ledc_timer_t kBacklightLedcTimer = LEDC_TIMER_0;
constexpr ledc_channel_t kBacklightLedcChannel = LEDC_CHANNEL_0;
constexpr std::uint32_t kBacklightMaxDuty = (1U << 10) - 1U;

static_assert(MOTO_DISPLAY_WIDTH == 360 && MOTO_DISPLAY_HEIGHT == 360,
              "The 1.85B panel and UI canvas must both be 360x360");
static_assert(MOTO_DISPLAY_BITS_PER_PIXEL == 16,
              "The 1.85B panel uses RGB565");
static_assert(kLvglExtraPoolBytes <= LV_MEM_POOL_EXPAND_SIZE,
              "LVGL must permit the extra PSRAM pool");

lv_display_t* display = nullptr;
esp_lcd_panel_handle_t panel = nullptr;
i2c_master_bus_handle_t i2c_bus = nullptr;
void* lvgl_extra_pool_storage = nullptr;
bool init_started = false;
bool display_revealed = false;
bool page_button_ready = false;
std::atomic<bool> display_dark{false};
std::atomic<bool> touch_wake_requested{false};
std::atomic<std::uint32_t> last_touch_ms{0};
std::atomic<std::uint32_t> touch_error_count{0};
std::atomic<std::uint32_t> last_touch_error_log_ms{0};
// These fields are only accessed by the LVGL input worker.
bool suppress_wake_touch = false;

esp_err_t report_touch_error(esp_err_t error, const char* operation) {
  const std::uint32_t now_ms =
      static_cast<std::uint32_t>(esp_timer_get_time() / 1'000);
  const std::uint32_t count = touch_error_count.fetch_add(1) + 1U;
  const std::uint32_t last_log = last_touch_error_log_ms.load();
  if (last_log == 0 || now_ms - last_log >= 1'000U) {
    last_touch_error_log_ms.store(now_ms);
    ESP_LOGW(kTag, "CST816S I2C error operation=%s rc=%s count=%u",
             operation == nullptr ? "unknown" : operation,
             esp_err_to_name(error), count);
  }
  return error;
}

// Exact Waveshare panel revision 1 register sequence; order matters.
static const std::uint8_t kInitDataVersion1[] = {
    0x28, 0x28, 0xD1, 0xE0, 0x61, 0x82, 0x00, 0x01, 0x01, 0x49, 0x4A, 0x1F, 0x46, 0x34, 0xD5, 0x30,
    0x04, 0x00, 0x08, 0x08, 0x00, 0x80, 0x10, 0x37, 0x80, 0x10, 0x37, 0xA9, 0x41, 0x01, 0xA9, 0x41,
    0x01, 0x91, 0x68, 0x68, 0x00, 0xA5, 0x10, 0x00, 0x02, 0x70, 0x09, 0x12, 0x0C, 0x0B, 0x27, 0x38,
    0x54, 0x4E, 0x19, 0x15, 0x15, 0x2C, 0x2F, 0x70, 0x08, 0x11, 0x0C, 0x0B, 0x27, 0x38, 0x43, 0x4C,
    0x18, 0x14, 0x14, 0x2B, 0x2D, 0x10, 0x10, 0x08, 0x00, 0x0B, 0x00, 0xE0, 0x06, 0x21, 0x00, 0x05,
    0x82, 0xDF, 0x89, 0x20, 0x14, 0xFF, 0x00, 0xFF, 0x00, 0x00, 0x30, 0x00, 0x00, 0x00, 0x00, 0x42,
    0xE0, 0x40, 0x40, 0x02, 0x00, 0x40, 0x03, 0x00, 0x00, 0x00, 0x00, 0x42, 0xE0, 0x40, 0x40, 0x02,
    0x00, 0x40, 0x03, 0x00, 0x00, 0x00, 0x00, 0x38, 0x00, 0x04, 0x02, 0xDC, 0x00, 0x00, 0x00, 0x38,
    0x00, 0x06, 0x02, 0xDE, 0x00, 0x00, 0x00, 0x38, 0x00, 0x08, 0x02, 0xE0, 0x00, 0x00, 0x00, 0x38,
    0x00, 0x0A, 0x02, 0xE2, 0x00, 0x00, 0x00, 0x38, 0x00, 0x03, 0x02, 0xDB, 0x00, 0x00, 0x00, 0x38,
    0x00, 0x05, 0x02, 0xDD, 0x00, 0x00, 0x00, 0x38, 0x00, 0x07, 0x02, 0xDF, 0x00, 0x00, 0x00, 0x38,
    0x00, 0x09, 0x02, 0xE1, 0x00, 0x00, 0x00, 0x22, 0xAA, 0x65, 0x74, 0x47, 0x56, 0x00, 0x88, 0x99,
    0x33, 0x11, 0xAA, 0x65, 0x74, 0x47, 0x56, 0x00, 0x88, 0x99, 0x33, 0x01, 0x00, 0x00,
};
static const st77916_lcd_init_cmd_t kInitCommandsVersion1[] = {
    {0xF0, kInitDataVersion1 + 0, 1, 0},
    {0xF2, kInitDataVersion1 + 1, 1, 0},
    {0x7C, kInitDataVersion1 + 2, 1, 0},
    {0x83, kInitDataVersion1 + 3, 1, 0},
    {0x84, kInitDataVersion1 + 4, 1, 0},
    {0xF2, kInitDataVersion1 + 5, 1, 0},
    {0xF0, kInitDataVersion1 + 6, 1, 0},
    {0xF0, kInitDataVersion1 + 7, 1, 0},
    {0xF1, kInitDataVersion1 + 8, 1, 0},
    {0xB0, kInitDataVersion1 + 9, 1, 0},
    {0xB1, kInitDataVersion1 + 10, 1, 0},
    {0xB2, kInitDataVersion1 + 11, 1, 0},
    {0xB4, kInitDataVersion1 + 12, 1, 0},
    {0xB5, kInitDataVersion1 + 13, 1, 0},
    {0xB6, kInitDataVersion1 + 14, 1, 0},
    {0xB7, kInitDataVersion1 + 15, 1, 0},
    {0xB8, kInitDataVersion1 + 16, 1, 0},
    {0xBA, kInitDataVersion1 + 17, 1, 0},
    {0xBB, kInitDataVersion1 + 18, 1, 0},
    {0xBC, kInitDataVersion1 + 19, 1, 0},
    {0xBD, kInitDataVersion1 + 20, 1, 0},
    {0xC0, kInitDataVersion1 + 21, 1, 0},
    {0xC1, kInitDataVersion1 + 22, 1, 0},
    {0xC2, kInitDataVersion1 + 23, 1, 0},
    {0xC3, kInitDataVersion1 + 24, 1, 0},
    {0xC4, kInitDataVersion1 + 25, 1, 0},
    {0xC5, kInitDataVersion1 + 26, 1, 0},
    {0xC6, kInitDataVersion1 + 27, 1, 0},
    {0xC7, kInitDataVersion1 + 28, 1, 0},
    {0xC8, kInitDataVersion1 + 29, 1, 0},
    {0xC9, kInitDataVersion1 + 30, 1, 0},
    {0xCA, kInitDataVersion1 + 31, 1, 0},
    {0xCB, kInitDataVersion1 + 32, 1, 0},
    {0xD0, kInitDataVersion1 + 33, 1, 0},
    {0xD1, kInitDataVersion1 + 34, 1, 0},
    {0xD2, kInitDataVersion1 + 35, 1, 0},
    {0xF5, kInitDataVersion1 + 36, 2, 0},
    {0xF1, kInitDataVersion1 + 38, 1, 0},
    {0xF0, kInitDataVersion1 + 39, 1, 0},
    {0xF0, kInitDataVersion1 + 40, 1, 0},
    {0xE0, kInitDataVersion1 + 41, 14, 0},
    {0xE1, kInitDataVersion1 + 55, 14, 0},
    {0xF0, kInitDataVersion1 + 69, 1, 0},
    {0xF3, kInitDataVersion1 + 70, 1, 0},
    {0xE0, kInitDataVersion1 + 71, 1, 0},
    {0xE1, kInitDataVersion1 + 72, 1, 0},
    {0xE2, kInitDataVersion1 + 73, 1, 0},
    {0xE3, kInitDataVersion1 + 74, 1, 0},
    {0xE4, kInitDataVersion1 + 75, 1, 0},
    {0xE5, kInitDataVersion1 + 76, 1, 0},
    {0xE6, kInitDataVersion1 + 77, 1, 0},
    {0xE7, kInitDataVersion1 + 78, 1, 0},
    {0xE8, kInitDataVersion1 + 79, 1, 0},
    {0xE9, kInitDataVersion1 + 80, 1, 0},
    {0xEA, kInitDataVersion1 + 81, 1, 0},
    {0xEB, kInitDataVersion1 + 82, 1, 0},
    {0xEC, kInitDataVersion1 + 83, 1, 0},
    {0xED, kInitDataVersion1 + 84, 1, 0},
    {0xEE, kInitDataVersion1 + 85, 1, 0},
    {0xEF, kInitDataVersion1 + 86, 1, 0},
    {0xF8, kInitDataVersion1 + 87, 1, 0},
    {0xF9, kInitDataVersion1 + 88, 1, 0},
    {0xFA, kInitDataVersion1 + 89, 1, 0},
    {0xFB, kInitDataVersion1 + 90, 1, 0},
    {0xFC, kInitDataVersion1 + 91, 1, 0},
    {0xFD, kInitDataVersion1 + 92, 1, 0},
    {0xFE, kInitDataVersion1 + 93, 1, 0},
    {0xFF, kInitDataVersion1 + 94, 1, 0},
    {0x60, kInitDataVersion1 + 95, 1, 0},
    {0x61, kInitDataVersion1 + 96, 1, 0},
    {0x62, kInitDataVersion1 + 97, 1, 0},
    {0x63, kInitDataVersion1 + 98, 1, 0},
    {0x64, kInitDataVersion1 + 99, 1, 0},
    {0x65, kInitDataVersion1 + 100, 1, 0},
    {0x66, kInitDataVersion1 + 101, 1, 0},
    {0x67, kInitDataVersion1 + 102, 1, 0},
    {0x68, kInitDataVersion1 + 103, 1, 0},
    {0x69, kInitDataVersion1 + 104, 1, 0},
    {0x6A, kInitDataVersion1 + 105, 1, 0},
    {0x6B, kInitDataVersion1 + 106, 1, 0},
    {0x70, kInitDataVersion1 + 107, 1, 0},
    {0x71, kInitDataVersion1 + 108, 1, 0},
    {0x72, kInitDataVersion1 + 109, 1, 0},
    {0x73, kInitDataVersion1 + 110, 1, 0},
    {0x74, kInitDataVersion1 + 111, 1, 0},
    {0x75, kInitDataVersion1 + 112, 1, 0},
    {0x76, kInitDataVersion1 + 113, 1, 0},
    {0x77, kInitDataVersion1 + 114, 1, 0},
    {0x78, kInitDataVersion1 + 115, 1, 0},
    {0x79, kInitDataVersion1 + 116, 1, 0},
    {0x7A, kInitDataVersion1 + 117, 1, 0},
    {0x7B, kInitDataVersion1 + 118, 1, 0},
    {0x80, kInitDataVersion1 + 119, 1, 0},
    {0x81, kInitDataVersion1 + 120, 1, 0},
    {0x82, kInitDataVersion1 + 121, 1, 0},
    {0x83, kInitDataVersion1 + 122, 1, 0},
    {0x84, kInitDataVersion1 + 123, 1, 0},
    {0x85, kInitDataVersion1 + 124, 1, 0},
    {0x86, kInitDataVersion1 + 125, 1, 0},
    {0x87, kInitDataVersion1 + 126, 1, 0},
    {0x88, kInitDataVersion1 + 127, 1, 0},
    {0x89, kInitDataVersion1 + 128, 1, 0},
    {0x8A, kInitDataVersion1 + 129, 1, 0},
    {0x8B, kInitDataVersion1 + 130, 1, 0},
    {0x8C, kInitDataVersion1 + 131, 1, 0},
    {0x8D, kInitDataVersion1 + 132, 1, 0},
    {0x8E, kInitDataVersion1 + 133, 1, 0},
    {0x8F, kInitDataVersion1 + 134, 1, 0},
    {0x90, kInitDataVersion1 + 135, 1, 0},
    {0x91, kInitDataVersion1 + 136, 1, 0},
    {0x92, kInitDataVersion1 + 137, 1, 0},
    {0x93, kInitDataVersion1 + 138, 1, 0},
    {0x94, kInitDataVersion1 + 139, 1, 0},
    {0x95, kInitDataVersion1 + 140, 1, 0},
    {0x96, kInitDataVersion1 + 141, 1, 0},
    {0x97, kInitDataVersion1 + 142, 1, 0},
    {0x98, kInitDataVersion1 + 143, 1, 0},
    {0x99, kInitDataVersion1 + 144, 1, 0},
    {0x9A, kInitDataVersion1 + 145, 1, 0},
    {0x9B, kInitDataVersion1 + 146, 1, 0},
    {0x9C, kInitDataVersion1 + 147, 1, 0},
    {0x9D, kInitDataVersion1 + 148, 1, 0},
    {0x9E, kInitDataVersion1 + 149, 1, 0},
    {0x9F, kInitDataVersion1 + 150, 1, 0},
    {0xA0, kInitDataVersion1 + 151, 1, 0},
    {0xA1, kInitDataVersion1 + 152, 1, 0},
    {0xA2, kInitDataVersion1 + 153, 1, 0},
    {0xA3, kInitDataVersion1 + 154, 1, 0},
    {0xA4, kInitDataVersion1 + 155, 1, 0},
    {0xA5, kInitDataVersion1 + 156, 1, 0},
    {0xA6, kInitDataVersion1 + 157, 1, 0},
    {0xA7, kInitDataVersion1 + 158, 1, 0},
    {0xA8, kInitDataVersion1 + 159, 1, 0},
    {0xA9, kInitDataVersion1 + 160, 1, 0},
    {0xAA, kInitDataVersion1 + 161, 1, 0},
    {0xAB, kInitDataVersion1 + 162, 1, 0},
    {0xAC, kInitDataVersion1 + 163, 1, 0},
    {0xAD, kInitDataVersion1 + 164, 1, 0},
    {0xAE, kInitDataVersion1 + 165, 1, 0},
    {0xAF, kInitDataVersion1 + 166, 1, 0},
    {0xB0, kInitDataVersion1 + 167, 1, 0},
    {0xB1, kInitDataVersion1 + 168, 1, 0},
    {0xB2, kInitDataVersion1 + 169, 1, 0},
    {0xB3, kInitDataVersion1 + 170, 1, 0},
    {0xB4, kInitDataVersion1 + 171, 1, 0},
    {0xB5, kInitDataVersion1 + 172, 1, 0},
    {0xB6, kInitDataVersion1 + 173, 1, 0},
    {0xB7, kInitDataVersion1 + 174, 1, 0},
    {0xB8, kInitDataVersion1 + 175, 1, 0},
    {0xB9, kInitDataVersion1 + 176, 1, 0},
    {0xBA, kInitDataVersion1 + 177, 1, 0},
    {0xBB, kInitDataVersion1 + 178, 1, 0},
    {0xBC, kInitDataVersion1 + 179, 1, 0},
    {0xBD, kInitDataVersion1 + 180, 1, 0},
    {0xBE, kInitDataVersion1 + 181, 1, 0},
    {0xBF, kInitDataVersion1 + 182, 1, 0},
    {0xC0, kInitDataVersion1 + 183, 1, 0},
    {0xC1, kInitDataVersion1 + 184, 1, 0},
    {0xC2, kInitDataVersion1 + 185, 1, 0},
    {0xC3, kInitDataVersion1 + 186, 1, 0},
    {0xC4, kInitDataVersion1 + 187, 1, 0},
    {0xC5, kInitDataVersion1 + 188, 1, 0},
    {0xC6, kInitDataVersion1 + 189, 1, 0},
    {0xC7, kInitDataVersion1 + 190, 1, 0},
    {0xC8, kInitDataVersion1 + 191, 1, 0},
    {0xC9, kInitDataVersion1 + 192, 1, 0},
    {0xD0, kInitDataVersion1 + 193, 1, 0},
    {0xD1, kInitDataVersion1 + 194, 1, 0},
    {0xD2, kInitDataVersion1 + 195, 1, 0},
    {0xD3, kInitDataVersion1 + 196, 1, 0},
    {0xD4, kInitDataVersion1 + 197, 1, 0},
    {0xD5, kInitDataVersion1 + 198, 1, 0},
    {0xD6, kInitDataVersion1 + 199, 1, 0},
    {0xD7, kInitDataVersion1 + 200, 1, 0},
    {0xD8, kInitDataVersion1 + 201, 1, 0},
    {0xD9, kInitDataVersion1 + 202, 1, 0},
    {0xF3, kInitDataVersion1 + 203, 1, 0},
    {0xF0, kInitDataVersion1 + 204, 1, 0},
    {0x35, kInitDataVersion1 + 205, 1, 0},
    {0x21, nullptr, 0, 0},
    {0x11, nullptr, 0, 120},
    {0x29, nullptr, 0, 0},
};

// Exact Waveshare panel revision 2 register sequence; order matters.
static const std::uint8_t kInitDataVersion2[] = {
    0x28, 0x28, 0xF0, 0xD1, 0xE0, 0x61, 0x82, 0x00, 0x01, 0x01, 0x56, 0x4D, 0x24, 0x87, 0x44, 0x8B,
    0x40, 0x86, 0x00, 0x08, 0x08, 0x00, 0x80, 0x10, 0x37, 0x80, 0x10, 0x37, 0xA9, 0x41, 0x01, 0xA9,
    0x41, 0x01, 0x91, 0x68, 0x68, 0x00, 0xA5, 0x4F, 0x4F, 0x10, 0x00, 0x02, 0xF0, 0x0A, 0x10, 0x09,
    0x09, 0x36, 0x35, 0x33, 0x4A, 0x29, 0x15, 0x15, 0x2E, 0x34, 0xF0, 0x0A, 0x0F, 0x08, 0x08, 0x05,
    0x34, 0x33, 0x4A, 0x39, 0x15, 0x15, 0x2D, 0x33, 0x10, 0x10, 0x07, 0x00, 0x00, 0x00, 0xE0, 0x06,
    0x21, 0x01, 0x05, 0x02, 0xDA, 0x00, 0x00, 0x0F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x40, 0x04, 0x00, 0x42, 0xD9, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40, 0x03,
    0x00, 0x42, 0xD8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x48, 0x00, 0x06, 0x02, 0xD6, 0x04,
    0x00, 0x00, 0x48, 0x00, 0x08, 0x02, 0xD8, 0x04, 0x00, 0x00, 0x48, 0x00, 0x0A, 0x02, 0xDA, 0x04,
    0x00, 0x00, 0x48, 0x00, 0x0C, 0x02, 0xDC, 0x04, 0x00, 0x00, 0x48, 0x00, 0x05, 0x02, 0xD5, 0x04,
    0x00, 0x00, 0x48, 0x00, 0x07, 0x02, 0xD7, 0x04, 0x00, 0x00, 0x48, 0x00, 0x09, 0x02, 0xD9, 0x04,
    0x00, 0x00, 0x48, 0x00, 0x0B, 0x02, 0xDB, 0x04, 0x00, 0x00, 0x10, 0x47, 0x56, 0x65, 0x74, 0x88,
    0x99, 0x01, 0xBB, 0xAA, 0x10, 0x47, 0x56, 0x65, 0x74, 0x88, 0x99, 0x01, 0xBB, 0xAA, 0x01, 0x00,
    0x00, 0x00, 0x00, 0x00,
};
static const st77916_lcd_init_cmd_t kInitCommandsVersion2[] = {
    {0xF0, kInitDataVersion2 + 0, 1, 0},
    {0xF2, kInitDataVersion2 + 1, 1, 0},
    {0x73, kInitDataVersion2 + 2, 1, 0},
    {0x7C, kInitDataVersion2 + 3, 1, 0},
    {0x83, kInitDataVersion2 + 4, 1, 0},
    {0x84, kInitDataVersion2 + 5, 1, 0},
    {0xF2, kInitDataVersion2 + 6, 1, 0},
    {0xF0, kInitDataVersion2 + 7, 1, 0},
    {0xF0, kInitDataVersion2 + 8, 1, 0},
    {0xF1, kInitDataVersion2 + 9, 1, 0},
    {0xB0, kInitDataVersion2 + 10, 1, 0},
    {0xB1, kInitDataVersion2 + 11, 1, 0},
    {0xB2, kInitDataVersion2 + 12, 1, 0},
    {0xB4, kInitDataVersion2 + 13, 1, 0},
    {0xB5, kInitDataVersion2 + 14, 1, 0},
    {0xB6, kInitDataVersion2 + 15, 1, 0},
    {0xB7, kInitDataVersion2 + 16, 1, 0},
    {0xB8, kInitDataVersion2 + 17, 1, 0},
    {0xBA, kInitDataVersion2 + 18, 1, 0},
    {0xBB, kInitDataVersion2 + 19, 1, 0},
    {0xBC, kInitDataVersion2 + 20, 1, 0},
    {0xBD, kInitDataVersion2 + 21, 1, 0},
    {0xC0, kInitDataVersion2 + 22, 1, 0},
    {0xC1, kInitDataVersion2 + 23, 1, 0},
    {0xC2, kInitDataVersion2 + 24, 1, 0},
    {0xC3, kInitDataVersion2 + 25, 1, 0},
    {0xC4, kInitDataVersion2 + 26, 1, 0},
    {0xC5, kInitDataVersion2 + 27, 1, 0},
    {0xC6, kInitDataVersion2 + 28, 1, 0},
    {0xC7, kInitDataVersion2 + 29, 1, 0},
    {0xC8, kInitDataVersion2 + 30, 1, 0},
    {0xC9, kInitDataVersion2 + 31, 1, 0},
    {0xCA, kInitDataVersion2 + 32, 1, 0},
    {0xCB, kInitDataVersion2 + 33, 1, 0},
    {0xD0, kInitDataVersion2 + 34, 1, 0},
    {0xD1, kInitDataVersion2 + 35, 1, 0},
    {0xD2, kInitDataVersion2 + 36, 1, 0},
    {0xF5, kInitDataVersion2 + 37, 2, 0},
    {0xDD, kInitDataVersion2 + 39, 1, 0},
    {0xDE, kInitDataVersion2 + 40, 1, 0},
    {0xF1, kInitDataVersion2 + 41, 1, 0},
    {0xF0, kInitDataVersion2 + 42, 1, 0},
    {0xF0, kInitDataVersion2 + 43, 1, 0},
    {0xE0, kInitDataVersion2 + 44, 14, 0},
    {0xE1, kInitDataVersion2 + 58, 14, 0},
    {0xF0, kInitDataVersion2 + 72, 1, 0},
    {0xF3, kInitDataVersion2 + 73, 1, 0},
    {0xE0, kInitDataVersion2 + 74, 1, 0},
    {0xE1, kInitDataVersion2 + 75, 1, 0},
    {0xE2, kInitDataVersion2 + 76, 1, 0},
    {0xE3, kInitDataVersion2 + 77, 1, 0},
    {0xE4, kInitDataVersion2 + 78, 1, 0},
    {0xE5, kInitDataVersion2 + 79, 1, 0},
    {0xE6, kInitDataVersion2 + 80, 1, 0},
    {0xE7, kInitDataVersion2 + 81, 1, 0},
    {0xE8, kInitDataVersion2 + 82, 1, 0},
    {0xE9, kInitDataVersion2 + 83, 1, 0},
    {0xEA, kInitDataVersion2 + 84, 1, 0},
    {0xEB, kInitDataVersion2 + 85, 1, 0},
    {0xEC, kInitDataVersion2 + 86, 1, 0},
    {0xED, kInitDataVersion2 + 87, 1, 0},
    {0xEE, kInitDataVersion2 + 88, 1, 0},
    {0xEF, kInitDataVersion2 + 89, 1, 0},
    {0xF8, kInitDataVersion2 + 90, 1, 0},
    {0xF9, kInitDataVersion2 + 91, 1, 0},
    {0xFA, kInitDataVersion2 + 92, 1, 0},
    {0xFB, kInitDataVersion2 + 93, 1, 0},
    {0xFC, kInitDataVersion2 + 94, 1, 0},
    {0xFD, kInitDataVersion2 + 95, 1, 0},
    {0xFE, kInitDataVersion2 + 96, 1, 0},
    {0xFF, kInitDataVersion2 + 97, 1, 0},
    {0x60, kInitDataVersion2 + 98, 1, 0},
    {0x61, kInitDataVersion2 + 99, 1, 0},
    {0x62, kInitDataVersion2 + 100, 1, 0},
    {0x63, kInitDataVersion2 + 101, 1, 0},
    {0x64, kInitDataVersion2 + 102, 1, 0},
    {0x65, kInitDataVersion2 + 103, 1, 0},
    {0x66, kInitDataVersion2 + 104, 1, 0},
    {0x67, kInitDataVersion2 + 105, 1, 0},
    {0x68, kInitDataVersion2 + 106, 1, 0},
    {0x69, kInitDataVersion2 + 107, 1, 0},
    {0x6A, kInitDataVersion2 + 108, 1, 0},
    {0x6B, kInitDataVersion2 + 109, 1, 0},
    {0x70, kInitDataVersion2 + 110, 1, 0},
    {0x71, kInitDataVersion2 + 111, 1, 0},
    {0x72, kInitDataVersion2 + 112, 1, 0},
    {0x73, kInitDataVersion2 + 113, 1, 0},
    {0x74, kInitDataVersion2 + 114, 1, 0},
    {0x75, kInitDataVersion2 + 115, 1, 0},
    {0x76, kInitDataVersion2 + 116, 1, 0},
    {0x77, kInitDataVersion2 + 117, 1, 0},
    {0x78, kInitDataVersion2 + 118, 1, 0},
    {0x79, kInitDataVersion2 + 119, 1, 0},
    {0x7A, kInitDataVersion2 + 120, 1, 0},
    {0x7B, kInitDataVersion2 + 121, 1, 0},
    {0x80, kInitDataVersion2 + 122, 1, 0},
    {0x81, kInitDataVersion2 + 123, 1, 0},
    {0x82, kInitDataVersion2 + 124, 1, 0},
    {0x83, kInitDataVersion2 + 125, 1, 0},
    {0x84, kInitDataVersion2 + 126, 1, 0},
    {0x85, kInitDataVersion2 + 127, 1, 0},
    {0x86, kInitDataVersion2 + 128, 1, 0},
    {0x87, kInitDataVersion2 + 129, 1, 0},
    {0x88, kInitDataVersion2 + 130, 1, 0},
    {0x89, kInitDataVersion2 + 131, 1, 0},
    {0x8A, kInitDataVersion2 + 132, 1, 0},
    {0x8B, kInitDataVersion2 + 133, 1, 0},
    {0x8C, kInitDataVersion2 + 134, 1, 0},
    {0x8D, kInitDataVersion2 + 135, 1, 0},
    {0x8E, kInitDataVersion2 + 136, 1, 0},
    {0x8F, kInitDataVersion2 + 137, 1, 0},
    {0x90, kInitDataVersion2 + 138, 1, 0},
    {0x91, kInitDataVersion2 + 139, 1, 0},
    {0x92, kInitDataVersion2 + 140, 1, 0},
    {0x93, kInitDataVersion2 + 141, 1, 0},
    {0x94, kInitDataVersion2 + 142, 1, 0},
    {0x95, kInitDataVersion2 + 143, 1, 0},
    {0x96, kInitDataVersion2 + 144, 1, 0},
    {0x97, kInitDataVersion2 + 145, 1, 0},
    {0x98, kInitDataVersion2 + 146, 1, 0},
    {0x99, kInitDataVersion2 + 147, 1, 0},
    {0x9A, kInitDataVersion2 + 148, 1, 0},
    {0x9B, kInitDataVersion2 + 149, 1, 0},
    {0x9C, kInitDataVersion2 + 150, 1, 0},
    {0x9D, kInitDataVersion2 + 151, 1, 0},
    {0x9E, kInitDataVersion2 + 152, 1, 0},
    {0x9F, kInitDataVersion2 + 153, 1, 0},
    {0xA0, kInitDataVersion2 + 154, 1, 0},
    {0xA1, kInitDataVersion2 + 155, 1, 0},
    {0xA2, kInitDataVersion2 + 156, 1, 0},
    {0xA3, kInitDataVersion2 + 157, 1, 0},
    {0xA4, kInitDataVersion2 + 158, 1, 0},
    {0xA5, kInitDataVersion2 + 159, 1, 0},
    {0xA6, kInitDataVersion2 + 160, 1, 0},
    {0xA7, kInitDataVersion2 + 161, 1, 0},
    {0xA8, kInitDataVersion2 + 162, 1, 0},
    {0xA9, kInitDataVersion2 + 163, 1, 0},
    {0xAA, kInitDataVersion2 + 164, 1, 0},
    {0xAB, kInitDataVersion2 + 165, 1, 0},
    {0xAC, kInitDataVersion2 + 166, 1, 0},
    {0xAD, kInitDataVersion2 + 167, 1, 0},
    {0xAE, kInitDataVersion2 + 168, 1, 0},
    {0xAF, kInitDataVersion2 + 169, 1, 0},
    {0xB0, kInitDataVersion2 + 170, 1, 0},
    {0xB1, kInitDataVersion2 + 171, 1, 0},
    {0xB2, kInitDataVersion2 + 172, 1, 0},
    {0xB3, kInitDataVersion2 + 173, 1, 0},
    {0xB4, kInitDataVersion2 + 174, 1, 0},
    {0xB5, kInitDataVersion2 + 175, 1, 0},
    {0xB6, kInitDataVersion2 + 176, 1, 0},
    {0xB7, kInitDataVersion2 + 177, 1, 0},
    {0xB8, kInitDataVersion2 + 178, 1, 0},
    {0xB9, kInitDataVersion2 + 179, 1, 0},
    {0xBA, kInitDataVersion2 + 180, 1, 0},
    {0xBB, kInitDataVersion2 + 181, 1, 0},
    {0xBC, kInitDataVersion2 + 182, 1, 0},
    {0xBD, kInitDataVersion2 + 183, 1, 0},
    {0xBE, kInitDataVersion2 + 184, 1, 0},
    {0xBF, kInitDataVersion2 + 185, 1, 0},
    {0xC0, kInitDataVersion2 + 186, 1, 0},
    {0xC1, kInitDataVersion2 + 187, 1, 0},
    {0xC2, kInitDataVersion2 + 188, 1, 0},
    {0xC3, kInitDataVersion2 + 189, 1, 0},
    {0xC4, kInitDataVersion2 + 190, 1, 0},
    {0xC5, kInitDataVersion2 + 191, 1, 0},
    {0xC6, kInitDataVersion2 + 192, 1, 0},
    {0xC7, kInitDataVersion2 + 193, 1, 0},
    {0xC8, kInitDataVersion2 + 194, 1, 0},
    {0xC9, kInitDataVersion2 + 195, 1, 0},
    {0xD0, kInitDataVersion2 + 196, 1, 0},
    {0xD1, kInitDataVersion2 + 197, 1, 0},
    {0xD2, kInitDataVersion2 + 198, 1, 0},
    {0xD3, kInitDataVersion2 + 199, 1, 0},
    {0xD4, kInitDataVersion2 + 200, 1, 0},
    {0xD5, kInitDataVersion2 + 201, 1, 0},
    {0xD6, kInitDataVersion2 + 202, 1, 0},
    {0xD7, kInitDataVersion2 + 203, 1, 0},
    {0xD8, kInitDataVersion2 + 204, 1, 0},
    {0xD9, kInitDataVersion2 + 205, 1, 0},
    {0xF3, kInitDataVersion2 + 206, 1, 0},
    {0xF0, kInitDataVersion2 + 207, 1, 0},
    {0x35, kInitDataVersion2 + 208, 1, 0},
    {0x21, kInitDataVersion2 + 209, 1, 0},
    {0x11, kInitDataVersion2 + 210, 1, 120},
    {0x29, kInitDataVersion2 + 211, 1, 0},
};

esp_err_t initialize_backlight() {
  // Initialize LEDC before resetting the panel, keeping its LED backlight off.
  ledc_timer_config_t timer_config{};
  timer_config.speed_mode = kBacklightLedcMode;
  timer_config.timer_num = kBacklightLedcTimer;
  timer_config.duty_resolution = LEDC_TIMER_10_BIT;
  timer_config.freq_hz = 5'000;
  timer_config.clk_cfg = LEDC_AUTO_CLK;
  ESP_RETURN_ON_ERROR(ledc_timer_config(&timer_config), kTag,
                      "backlight PWM timer initialization failed");

  ledc_channel_config_t channel_config{};
  channel_config.gpio_num = kBacklight;
  channel_config.speed_mode = kBacklightLedcMode;
  channel_config.channel = kBacklightLedcChannel;
  channel_config.intr_type = LEDC_INTR_DISABLE;
  channel_config.timer_sel = kBacklightLedcTimer;
  channel_config.duty = 0;
  channel_config.hpoint = 0;
  ESP_RETURN_ON_ERROR(ledc_channel_config(&channel_config), kTag,
                      "backlight PWM channel initialization failed");
  return ESP_OK;
}

esp_err_t read_touch_for_screen_wake(esp_lcd_touch_handle_t touch,
                                     esp_lcd_touch_point_data_t* points,
                                     std::uint8_t* count,
                                     std::uint8_t max_count, void*) {
  const esp_err_t read_result = esp_lcd_touch_read_data(touch);
  if (read_result != ESP_OK) {
    return report_touch_error(read_result, "read_data");
  }
  const esp_err_t data_result =
      esp_lcd_touch_get_data(touch, points, count, max_count);
  if (data_result != ESP_OK) {
    return report_touch_error(data_result, "get_data");
  }
  const std::uint32_t now_ms =
      static_cast<std::uint32_t>(esp_timer_get_time() / 1'000);
  const bool pressed = *count != 0;
  if (pressed) {
    last_touch_ms.store(now_ms, std::memory_order_release);
    if (display_dark.load(std::memory_order_acquire)) {
      touch_wake_requested.store(true, std::memory_order_release);
      suppress_wake_touch = true;
    }
  }
  // A wake touch must not also activate a music control or swipe a page.
  // Polling continues until the controller reports release, so the entire
  // waking gesture is consumed even when the user holds a finger down.
  if (suppress_wake_touch) {
    *count = 0;
    if (!pressed) {
      suppress_wake_touch = false;
    }
  }
  return ESP_OK;
}

esp_err_t reset_panel_for_id_read() {
  gpio_config_t reset_config{};
  reset_config.pin_bit_mask = 1ULL << kLcdReset;
  reset_config.mode = GPIO_MODE_OUTPUT;
  reset_config.pull_up_en = GPIO_PULLUP_DISABLE;
  reset_config.pull_down_en = GPIO_PULLDOWN_DISABLE;
  reset_config.intr_type = GPIO_INTR_DISABLE;
  ESP_RETURN_ON_ERROR(gpio_config(&reset_config), kTag,
                      "panel reset pin configuration failed");
  ESP_RETURN_ON_ERROR(gpio_set_level(kLcdReset, 0), kTag,
                      "panel reset assertion failed");
  vTaskDelay(pdMS_TO_TICKS(10));
  ESP_RETURN_ON_ERROR(gpio_set_level(kLcdReset, 1), kTag,
                      "panel reset release failed");
  vTaskDelay(pdMS_TO_TICKS(10));
  return ESP_OK;
}

esp_err_t initialize_hidden_panel(esp_lcd_panel_io_handle_t* panel_io_out) {
  ESP_RETURN_ON_ERROR(reset_panel_for_id_read(), kTag,
                      "panel reset before ID read failed");

  spi_bus_config_t bus_config{};
  bus_config.sclk_io_num = kLcdClock;
  bus_config.data0_io_num = kLcdData0;
  bus_config.data1_io_num = kLcdData1;
  bus_config.data2_io_num = kLcdData2;
  bus_config.data3_io_num = kLcdData3;
  bus_config.data4_io_num = -1;
  bus_config.data5_io_num = -1;
  bus_config.data6_io_num = -1;
  bus_config.data7_io_num = -1;
  bus_config.max_transfer_sz = kLcdTransferBytes;
  bus_config.flags = SPICOMMON_BUSFLAG_QUAD;
  ESP_RETURN_ON_ERROR(spi_bus_initialize(kLcdSpiHost, &bus_config,
                                         SPI_DMA_CH_AUTO), kTag,
                      "ST77916 QSPI bus initialization failed");

  esp_lcd_panel_io_spi_config_t io_config{};
  io_config.cs_gpio_num = kLcdCs;
  io_config.dc_gpio_num = -1;
  io_config.spi_mode = 0;
  io_config.pclk_hz = 3'000'000;
  io_config.trans_queue_depth = 1;
  io_config.lcd_cmd_bits = 32;
  io_config.lcd_param_bits = 8;
  io_config.flags.quad_mode = true;

  esp_lcd_panel_io_handle_t slow_io = nullptr;
  ESP_RETURN_ON_ERROR(
      esp_lcd_new_panel_io_spi(
          static_cast<esp_lcd_spi_bus_handle_t>(kLcdSpiHost), &io_config,
          &slow_io),
      kTag, "ST77916 ID-read IO initialization failed");

  // Waveshare reads DCS 0x04 with QSPI opcode 0x0B and a 3 MHz clock.
  // Its two known LCD batches require distinct register sequences.
  std::uint8_t id[4]{};
  const int read_id_command = (0x0B << 24) | (0x04 << 8);
  const esp_err_t read_result =
      esp_lcd_panel_io_rx_param(slow_io, read_id_command, id, sizeof(id));
  const esp_err_t slow_io_delete_result = esp_lcd_panel_io_del(slow_io);
  ESP_RETURN_ON_ERROR(slow_io_delete_result, kTag,
                      "ST77916 temporary ID-read IO cleanup failed");
  if (read_result != ESP_OK) {
    ESP_LOGE(kTag, "ST77916 ID read failed (%s); panel stays dark",
             esp_err_to_name(read_result));
    spi_bus_free(kLcdSpiHost);
    return read_result;
  }

  const st77916_lcd_init_cmd_t* init_commands = nullptr;
  std::size_t init_command_count = 0;
  if (id[0] == 0x00 && id[1] == 0x7F && id[2] == 0x7F &&
      id[3] == 0x7F) {
    init_commands = kInitCommandsVersion1;
    init_command_count = sizeof(kInitCommandsVersion1) /
                         sizeof(kInitCommandsVersion1[0]);
    ESP_LOGI(kTag, "ST77916 Waveshare panel revision 1 detected");
  } else if (id[0] == 0x00 && id[1] == 0x02 && id[2] == 0x7F &&
             id[3] == 0x7F) {
    init_commands = kInitCommandsVersion2;
    init_command_count = sizeof(kInitCommandsVersion2) /
                         sizeof(kInitCommandsVersion2[0]);
    ESP_LOGI(kTag, "ST77916 Waveshare panel revision 2 detected");
  } else {
    ESP_LOGE(kTag, "unknown ST77916 panel ID: %02x %02x %02x %02x",
             id[0], id[1], id[2], id[3]);
    spi_bus_free(kLcdSpiHost);
    return ESP_ERR_INVALID_RESPONSE;
  }

  io_config.pclk_hz = kLcdClockHz;
  esp_lcd_panel_io_handle_t fast_io = nullptr;
  ESP_RETURN_ON_ERROR(
      esp_lcd_new_panel_io_spi(
          static_cast<esp_lcd_spi_bus_handle_t>(kLcdSpiHost), &io_config,
          &fast_io),
      kTag, "ST77916 80 MHz QSPI panel IO initialization failed");

  st77916_vendor_config_t vendor_config{};
  vendor_config.init_cmds = init_commands;
  vendor_config.init_cmds_size = init_command_count;
  vendor_config.flags.use_qspi_interface = 1;

  esp_lcd_panel_dev_config_t panel_config{};
  panel_config.reset_gpio_num = kLcdReset;
  panel_config.rgb_ele_order = LCD_RGB_ELEMENT_ORDER_RGB;
  panel_config.bits_per_pixel = MOTO_DISPLAY_BITS_PER_PIXEL;
  panel_config.vendor_config = &vendor_config;
  ESP_RETURN_ON_ERROR(esp_lcd_new_panel_st77916(fast_io, &panel_config,
                                                 &panel), kTag,
                      "ST77916 panel creation failed");
  ESP_RETURN_ON_ERROR(esp_lcd_panel_reset(panel), kTag,
                      "ST77916 panel reset failed");
  ESP_RETURN_ON_ERROR(esp_lcd_panel_init(panel), kTag,
                      "ST77916 panel initialization failed");
  ESP_RETURN_ON_ERROR(esp_lcd_panel_disp_on_off(panel, false), kTag,
                      "ST77916 could not remain hidden after initialization");

  *panel_io_out = fast_io;
  return ESP_OK;
}

esp_err_t initialize_touch(esp_lcd_touch_handle_t* touch_out) {
  i2c_master_bus_config_t bus_config{};
  bus_config.clk_source = I2C_CLK_SRC_DEFAULT;
  bus_config.i2c_port = I2C_NUM_0;
  bus_config.scl_io_num = kTouchScl;
  bus_config.sda_io_num = kTouchSda;
  bus_config.glitch_ignore_cnt = 7;
  bus_config.flags.enable_internal_pullup = true;
  ESP_RETURN_ON_ERROR(i2c_new_master_bus(&bus_config, &i2c_bus), kTag,
                      "shared touch/IMU I2C bus initialization failed");

  esp_lcd_panel_io_i2c_config_t io_config{};
  io_config.dev_addr = ESP_LCD_TOUCH_IO_I2C_CST816S_ADDRESS;
  io_config.scl_speed_hz = kI2cClockHz;
  io_config.control_phase_bytes = 1;
  io_config.lcd_cmd_bits = 8;
  io_config.flags.disable_control_phase = true;
  esp_lcd_panel_io_handle_t touch_io = nullptr;
  ESP_RETURN_ON_ERROR(esp_lcd_new_panel_io_i2c(i2c_bus, &io_config,
                                                &touch_io), kTag,
                      "CST816S I2C panel IO initialization failed");

  esp_lcd_touch_config_t touch_config{};
  touch_config.x_max = MOTO_DISPLAY_WIDTH;
  touch_config.y_max = MOTO_DISPLAY_HEIGHT;
  touch_config.rst_gpio_num = kTouchReset;
  // Poll at LVGL's 25 ms input cadence. This guarantees observing release so
  // the first touch used to wake a dark screen cannot leak into the UI.
  touch_config.int_gpio_num = GPIO_NUM_NC;
  touch_config.levels.reset = 0;
  touch_config.levels.interrupt = 0;
  // Waveshare's exact 1.85B BSP uses native 0-degree orientation.
  touch_config.flags.swap_xy = 0;
  touch_config.flags.mirror_x = 0;
  touch_config.flags.mirror_y = 0;
  return esp_lcd_touch_new_i2c_cst816s(touch_io, &touch_config, touch_out);
}

}  // namespace

extern "C" esp_err_t board_port_init(void) {
  if (display != nullptr) return ESP_OK;
  if (init_started) return ESP_ERR_INVALID_STATE;
  init_started = true;

  const esp_lv_adapter_config_t adapter_config =
      ESP_LV_ADAPTER_DEFAULT_CONFIG();
  ESP_RETURN_ON_ERROR(esp_lv_adapter_init(&adapter_config), kTag,
                      "LVGL adapter initialization failed");

  static_assert(LV_USE_STDLIB_MALLOC == LV_STDLIB_BUILTIN,
                "Review LVGL pool setup after allocator changes");
  lvgl_extra_pool_storage = heap_caps_malloc(
      kLvglExtraPoolBytes, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT);
  if (lvgl_extra_pool_storage == nullptr ||
      lv_mem_add_pool(lvgl_extra_pool_storage, kLvglExtraPoolBytes) == nullptr) {
    heap_caps_free(lvgl_extra_pool_storage);
    lvgl_extra_pool_storage = nullptr;
    ESP_LOGE(kTag, "could not reserve the LVGL PSRAM pool");
    return ESP_ERR_NO_MEM;
  }

  ESP_RETURN_ON_ERROR(initialize_backlight(), kTag,
                      "LCD backlight initialization failed");
  esp_lcd_panel_io_handle_t panel_io = nullptr;
  ESP_RETURN_ON_ERROR(initialize_hidden_panel(&panel_io), kTag,
                      "Waveshare ST77916 initialization failed");

  esp_lv_adapter_display_config_t display_config =
      ESP_LV_ADAPTER_DISPLAY_SPI_WITHOUT_PSRAM_DEFAULT_CONFIG(
          panel, panel_io, MOTO_DISPLAY_WIDTH, MOTO_DISPLAY_HEIGHT,
          ESP_LV_ADAPTER_ROTATE_0);
  display_config.profile.buffer_height = kDrawBufferHeight;
  display = esp_lv_adapter_register_display(&display_config);
  if (display == nullptr) {
    ESP_LOGE(kTag, "LVGL could not register the ST77916 display");
    return ESP_FAIL;
  }

  esp_lcd_touch_handle_t touch = nullptr;
  ESP_RETURN_ON_ERROR(initialize_touch(&touch), kTag,
                      "Waveshare CST816S initialization failed");
  esp_lv_adapter_touch_config_t touch_config =
      ESP_LV_ADAPTER_TOUCH_DEFAULT_CONFIG(display, touch);
  touch_config.callbacks.custom_touch_read = read_touch_for_screen_wake;
  lv_indev_t* const touch_input = esp_lv_adapter_register_touch(&touch_config);
  if (touch_input == nullptr) {
    ESP_LOGE(kTag, "LVGL could not register the CST816S touch device");
    return ESP_FAIL;
  }
  lv_indev_set_gesture_min_distance(touch_input, kSwipeMinDistancePx);
  lv_indev_set_gesture_min_velocity(touch_input, kSwipeMinVelocityPx);

  // BOOT is GPIO0. It is sampled for download mode only during reset; after
  // startup the existing key can also be read without changing its boot role.
  gpio_config_t page_button_config{};
  page_button_config.pin_bit_mask = 1ULL << kPageButton;
  page_button_config.mode = GPIO_MODE_INPUT;
  page_button_config.pull_up_en = GPIO_PULLUP_ENABLE;
  page_button_config.pull_down_en = GPIO_PULLDOWN_DISABLE;
  page_button_config.intr_type = GPIO_INTR_DISABLE;
  const esp_err_t page_button_result = gpio_config(&page_button_config);
  if (page_button_result == ESP_OK) {
    page_button_ready = true;
  } else {
    ESP_LOGW(kTag, "BOOT page button unavailable: %s",
             esp_err_to_name(page_button_result));
  }

  lv_obj_t* const startup_screen = lv_display_get_screen_active(display);
  lv_obj_set_style_bg_color(startup_screen, lv_color_black(), 0);
  lv_obj_set_style_bg_opa(startup_screen, LV_OPA_COVER, 0);
  lv_obj_remove_flag(startup_screen, LV_OBJ_FLAG_SCROLLABLE);
  ESP_RETURN_ON_ERROR(esp_lv_adapter_start(), kTag,
                      "LVGL worker task could not start");

  if (lv_display_get_horizontal_resolution(display) != MOTO_DISPLAY_WIDTH ||
      lv_display_get_vertical_resolution(display) != MOTO_DISPLAY_HEIGHT ||
      lv_display_get_color_format(display) != LV_COLOR_FORMAT_RGB565) {
    ESP_LOGE(kTag, "ST77916 display violates the 360x360 RGB565 contract");
    display = nullptr;
    return ESP_ERR_INVALID_SIZE;
  }

  ESP_LOGI(kTag,
           "ST77916 + CST816S ready at %dx%d RGB565 (single %u-row internal "
           "draw buffer, %d MHz QSPI, queue depth 1)",
           MOTO_DISPLAY_WIDTH, MOTO_DISPLAY_HEIGHT,
           static_cast<unsigned>(kDrawBufferHeight), kLcdClockHz / 1'000'000);
  return ESP_OK;
}

extern "C" lv_display_t* board_port_get_display(void) { return display; }

extern "C" esp_err_t board_port_reveal_display(void) {
  if (panel == nullptr || display == nullptr) return ESP_ERR_INVALID_STATE;
  if (display_revealed) return ESP_OK;
  ESP_RETURN_ON_ERROR(esp_lcd_panel_disp_on_off(panel, true), kTag,
                      "ST77916 display-on command failed");
  ESP_RETURN_ON_ERROR(ledc_set_duty(kBacklightLedcMode, kBacklightLedcChannel,
                                    kBacklightMaxDuty), kTag,
                      "LCD backlight duty update failed");
  ESP_RETURN_ON_ERROR(ledc_update_duty(kBacklightLedcMode,
                                       kBacklightLedcChannel), kTag,
                      "LCD backlight enable failed");
  display_revealed = true;
  return ESP_OK;
}

extern "C" esp_err_t board_port_set_display_brightness(std::uint8_t percent) {
  if (!display_revealed) return ESP_ERR_INVALID_STATE;
  if (percent > 100U) return ESP_ERR_INVALID_ARG;
  const bool was_dark = display_dark.load(std::memory_order_acquire);
  const bool going_dark = percent == 0U;
  // Mark the screen dark before the PWM reaches zero, so a simultaneous touch
  // is consumed rather than reaching an invisible UI control.
  if (going_dark) display_dark.store(true, std::memory_order_release);
  const std::uint32_t duty = kBacklightMaxDuty * percent / 100U;
  const esp_err_t duty_result =
      ledc_set_duty(kBacklightLedcMode, kBacklightLedcChannel, duty);
  if (duty_result != ESP_OK) {
    display_dark.store(was_dark, std::memory_order_release);
    return duty_result;
  }
  const esp_err_t update_result =
      ledc_update_duty(kBacklightLedcMode, kBacklightLedcChannel);
  if (update_result != ESP_OK) {
    display_dark.store(was_dark, std::memory_order_release);
    return update_result;
  }
  if (!going_dark) display_dark.store(false, std::memory_order_release);
  return ESP_OK;
}

extern "C" std::uint32_t board_port_last_touch_ms(void) {
  return last_touch_ms.load(std::memory_order_acquire);
}

extern "C" bool board_port_take_touch_wake_request(void) {
  return touch_wake_requested.exchange(false, std::memory_order_acq_rel);
}

extern "C" bool board_port_lock(std::uint32_t timeout_ms) {
  if (display == nullptr) return false;
  const std::int32_t adapter_timeout =
      timeout_ms == UINT32_MAX
          ? -1
          : static_cast<std::int32_t>(
                timeout_ms > static_cast<std::uint32_t>(
                                 std::numeric_limits<std::int32_t>::max())
                    ? std::numeric_limits<std::int32_t>::max()
                    : timeout_ms);
  return esp_lv_adapter_lock(adapter_timeout) == ESP_OK;
}

extern "C" void board_port_unlock(void) {
  if (display != nullptr) esp_lv_adapter_unlock();
}

extern "C" bool board_port_power_button_pressed(void) { return false; }

extern "C" bool board_port_has_power_button(void) { return false; }

extern "C" bool board_port_has_page_button(void) {
  return page_button_ready;
}

extern "C" bool board_port_page_button_pressed(void) {
  return page_button_ready && gpio_get_level(kPageButton) == 0;
}

extern "C" i2c_master_bus_handle_t board_port_i2c_get_handle(void) {
  return i2c_bus;
}

extern "C" esp_err_t board_port_power_off(void) {
  return ESP_ERR_NOT_SUPPORTED;
}
