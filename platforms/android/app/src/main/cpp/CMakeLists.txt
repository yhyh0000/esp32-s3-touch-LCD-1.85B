cmake_minimum_required(VERSION 3.22.1)

project(moto_android_jni LANGUAGES CXX)

set(MOTO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../../../../..")

add_subdirectory(
    "${MOTO_ROOT}/shared/ble_protocol"
    "${CMAKE_CURRENT_BINARY_DIR}/moto_ble_protocol"
)
add_subdirectory(
    "${MOTO_ROOT}/shared/coordinates"
    "${CMAKE_CURRENT_BINARY_DIR}/moto_coordinates"
)

add_library(moto_android_jni SHARED moto_android_jni.cpp)
target_compile_features(moto_android_jni PRIVATE cxx_std_17)
target_compile_options(moto_android_jni PRIVATE -Wall -Wextra -Wpedantic)
target_include_directories(moto_android_jni PRIVATE
    "${MOTO_ROOT}/shared/ble_protocol/include"
    "${MOTO_ROOT}/shared/coordinates/include"
)
target_link_libraries(moto_android_jni PRIVATE
    moto_ble_protocol
    moto_coordinates
    log
)
