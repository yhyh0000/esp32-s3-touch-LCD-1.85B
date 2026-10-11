#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <initializer_list>
#include <memory>
#include <string>
#include <vector>

#include "moto/ble_protocol/ble_protocol.hpp"
#include "moto_coordinates.hpp"

namespace {

using moto::ble::Bytes;
using moto::ble::ByteView;

struct CodecState {
  explicit CodecState(std::size_t frame_size)
      : maximum_frame_size(std::clamp<std::size_t>(
            frame_size, moto::ble::kFrameOverhead + 1,
            moto::ble::kMaxBleAttributeValueSize)) {}

  std::size_t maximum_frame_size = 20;
  moto::ble::SequenceGenerator sequence;
  moto::ble::Reassembler reassembler;
};

CodecState* state_from_handle(jlong handle) {
  return reinterpret_cast<CodecState*>(handle);
}

jbyteArray make_byte_array(JNIEnv* env, const Bytes& bytes) {
  auto* result = env->NewByteArray(static_cast<jsize>(bytes.size()));
  if (result == nullptr) return nullptr;
  if (!bytes.empty()) {
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()),
                            reinterpret_cast<const jbyte*>(bytes.data()));
  }
  return result;
}

jobjectArray make_frames(JNIEnv* env,
                         const moto::ble::FramesResult& frames) {
  if (!frames.ok()) return nullptr;
  jclass byte_array_class = env->FindClass("[B");
  if (byte_array_class == nullptr) return nullptr;
  auto* result = env->NewObjectArray(
      static_cast<jsize>(frames.value.size()), byte_array_class, nullptr);
  env->DeleteLocalRef(byte_array_class);
  if (result == nullptr) return nullptr;
  for (jsize index = 0; index < static_cast<jsize>(frames.value.size());
       ++index) {
    auto* frame = make_byte_array(env, frames.value[index]);
    if (frame == nullptr) return nullptr;
    env->SetObjectArrayElement(result, index, frame);
    env->DeleteLocalRef(frame);
  }
  return result;
}

std::uint32_t phone_capabilities() {
  return moto::ble::CapabilityNavigation |
         moto::ble::CapabilityRouteGeometry |
         moto::ble::CapabilityTraffic |
         moto::ble::CapabilityMediaState |
         moto::ble::CapabilityTouchCommands |
         moto::ble::CapabilityMusicCommands |
         moto::ble::CapabilityCommandAck |
         moto::ble::CapabilityMapScene |
         moto::ble::CapabilityDeviceSettings;
}

moto::ble::ConnectionStatus phone_status(
    moto::ble::ConnectionState state,
    std::uint32_t session_id,
    std::uint16_t maximum_frame_size) {
  moto::ble::ConnectionStatus status;
  status.role = moto::ble::EndpointRole::Phone;
  status.state = state;
  status.capabilities = phone_capabilities();
  status.session_id = session_id;
  status.max_frame_size = maximum_frame_size;
  status.heartbeat_interval_ms = 1'000;
  return status;
}

jobjectArray encode_message(JNIEnv* env,
                            CodecState* state,
                            const moto::ble::Message& message,
                            std::uint8_t flags = 0) {
  if (state == nullptr) return nullptr;
  const auto payload = moto::ble::encode_message(message);
  if (!payload.ok()) return nullptr;
  const std::uint16_t sequence = state->sequence.next();
  const auto frames = moto::ble::fragment_message(
      moto::ble::message_type(message), sequence,
      ByteView(payload.value), state->maximum_frame_size, flags);
  return make_frames(env, frames);
}

std::string common_event(const char* kind,
                         std::uint16_t sequence,
                         std::uint8_t flags) {
  return std::string("{\"kind\":\"") + kind +
         "\",\"sequence\":" + std::to_string(sequence) +
         ",\"ackRequested\":" +
         (((flags & moto::ble::AckRequested) != 0U) ? "true" : "false");
}

std::string complete_event(const moto::ble::ReassembledMessage& message) {
  const auto decoded = moto::ble::decode_message(
      message.type, ByteView(message.payload));
  if (!decoded.ok()) {
    return std::string("{\"kind\":\"error\",\"error\":\"") +
           moto::ble::to_string(decoded.error) + "\"}";
  }

  std::string result = common_event(
      "complete", message.sequence, message.flags);
  result += ",\"type\":" +
            std::to_string(static_cast<unsigned>(message.type));

  if (const auto* status =
          std::get_if<moto::ble::ConnectionStatus>(&decoded.value)) {
    result += ",\"role\":" +
              std::to_string(static_cast<unsigned>(status->role));
    result += ",\"state\":" +
              std::to_string(static_cast<unsigned>(status->state));
    result += ",\"minimumVersion\":" +
              std::to_string(status->minimum_version);
    result += ",\"maximumVersion\":" +
              std::to_string(status->maximum_version);
    result += ",\"capabilities\":" +
              std::to_string(static_cast<std::uint64_t>(status->capabilities));
    result += ",\"sessionId\":" +
              std::to_string(static_cast<std::uint64_t>(status->session_id));
    result += ",\"maxFrameSize\":" +
              std::to_string(status->max_frame_size);
    result += ",\"heartbeatIntervalMs\":" +
              std::to_string(status->heartbeat_interval_ms);
  } else if (const auto* heartbeat =
                 std::get_if<moto::ble::Heartbeat>(&decoded.value)) {
    result += ",\"sessionId\":" +
              std::to_string(static_cast<std::uint64_t>(heartbeat->session_id));
    result += ",\"monotonicMs\":" +
              std::to_string(static_cast<std::uint64_t>(heartbeat->monotonic_ms));
  } else if (const auto* ack = std::get_if<moto::ble::Ack>(&decoded.value)) {
    result += ",\"acknowledgedSequence\":" +
              std::to_string(ack->acknowledged_sequence);
    result += ",\"status\":" +
              std::to_string(static_cast<unsigned>(ack->status));
    result += ",\"commandId\":" + std::to_string(ack->command_id);
  }
  result += "}";
  return result;
}

std::vector<std::uint8_t> bytes_from_initializer(
    std::initializer_list<std::uint8_t> bytes) {
  return std::vector<std::uint8_t>(bytes);
}

std::string string_from_jni(JNIEnv* env, jstring value) {
  if (value == nullptr) return {};
  const char* chars = env->GetStringUTFChars(value, nullptr);
  if (chars == nullptr) return {};
  const std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_moto_gps_protocol_NativeBleCodec_00024Companion_nativeCreate(JNIEnv*, jobject,
                                                        jint maximum_frame_size) {
  return reinterpret_cast<jlong>(new CodecState(
      static_cast<std::size_t>(std::max(maximum_frame_size, 20))));
}

extern "C" JNIEXPORT void JNICALL
Java_com_moto_gps_protocol_NativeBleCodec_00024Companion_nativeDestroy(JNIEnv*, jobject,
                                                        jlong handle) {
  delete state_from_handle(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_moto_gps_protocol_NativeBleCodec_00024Companion_nativeSetMaximumFrameSize(
    JNIEnv*, jobject, jlong handle, jint maximum_frame_size) {
  auto* state = state_from_handle(handle);
  if (state == nullptr) return;
  state->maximum_frame_size = std::clamp<std::size_t>(
      static_cast<std::size_t>(std::max(maximum_frame_size, 20)),
      moto::ble::kFrameOverhead + 1, moto::ble::kMaxBleAttributeValueSize);
}

extern "C" JNIEXPORT void JNICALL
Java_com_moto_gps_protocol_NativeBleCodec_00024Companion_nativeResetInboundState(
    JNIEnv*, jobject, jlong handle) {
  auto* state = state_from_handle(handle);
  if (state != nullptr) state->reassembler.reset();
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_moto_gps_protocol_NativeBleCodec_00024Companion_nativeEncodeConnectionStatus(
    JNIEnv* env, jobject, jlong handle, jint state, jint session_id) {
  auto* codec = state_from_handle(handle);
  if (codec == nullptr) return nullptr;
  const auto status = phone_status(
      static_cast<moto::ble::ConnectionState>(state),
      static_cast<std::uint32_t>(session_id),
      static_cast<std::uint16_t>(codec->maximum_frame_size));
  return encode_message(env, codec, moto::ble::Message{status});
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_moto_gps_protocol_NativeBleCodec_00024Companion_nativeEncodeHeartbeat(
    JNIEnv* env, jobject, jlong handle, jint session_id, jint monotonic_ms) {
  auto* codec = state_from_handle(handle);
  if (codec == nullptr) return nullptr;
  moto::ble::Heartbeat heartbeat;
  heartbeat.session_id = static_cast<std::uint32_t>(session_id);
  heartbeat.monotonic_ms = static_cast<std::uint32_t>(monotonic_ms);
  heartbeat.status_flags = 0;
  return encode_message(env, codec, moto::ble::Message{heartbeat});
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_moto_gps_protocol_NativeBleCodec_00024Companion_nativeEncodeAck(
    JNIEnv* env, jobject, jlong handle, jint acknowledged_sequence,
    jint status, jint command_id) {
  auto* codec = state_from_handle(handle);
  if (codec == nullptr) return nullptr;
  moto::ble::Ack ack;
  ack.acknowledged_sequence = static_cast<std::uint16_t>(acknowledged_sequence);
  ack.status = static_cast<moto::ble::AckStatus>(status);
  ack.command_id = static_cast<std::uint16_t>(command_id);
  return encode_message(env, codec, moto::ble::Message{ack});
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_moto_gps_protocol_NativeBleCodec_00024Companion_nativeEncodeNavigationSnapshot(
    JNIEnv* env, jobject, jlong handle, jintArray values, jstring road_name,
    jstring instruction) {
  auto* codec = state_from_handle(handle);
  if (codec == nullptr || values == nullptr) return nullptr;
  const jsize size = env->GetArrayLength(values);
  if (size != 20) return nullptr;
  std::vector<jint> input(static_cast<std::size_t>(size));
  env->GetIntArrayRegion(values, 0, size, input.data());

  moto::ble::NavigationSnapshot snapshot;
  snapshot.state = static_cast<moto::ble::NavigationState>(input[0]);
  snapshot.network = static_cast<moto::ble::NetworkState>(input[1]);
  snapshot.display_page = static_cast<moto::ble::DisplayPage>(input[2]);
  snapshot.maneuver = static_cast<moto::ble::Maneuver>(input[3]);
  snapshot.traffic = static_cast<moto::ble::TrafficLevel>(input[4]);
  snapshot.flags = static_cast<std::uint16_t>(input[5]);
  snapshot.route_token = static_cast<std::uint32_t>(input[6]);
  snapshot.route_generation = static_cast<std::uint32_t>(input[7]);
  snapshot.maneuver_id = static_cast<std::uint32_t>(input[8]);
  snapshot.distance_to_maneuver_m = static_cast<std::uint32_t>(input[9]);
  snapshot.remaining_distance_m = static_cast<std::uint32_t>(input[10]);
  snapshot.remaining_duration_s = static_cast<std::uint32_t>(input[11]);
  snapshot.route_progress_m = static_cast<std::uint32_t>(input[12]);
  snapshot.total_distance_m = static_cast<std::uint32_t>(input[13]);
  snapshot.speed_deci_kph = static_cast<std::uint16_t>(input[14]);
  snapshot.speed_limit_kph = static_cast<std::uint16_t>(input[15]);
  snapshot.heading_cdeg = static_cast<std::uint16_t>(input[16]);
  snapshot.accuracy_dm = static_cast<std::uint16_t>(input[17]);
  snapshot.cross_track_dm = static_cast<std::uint16_t>(input[18]);
  snapshot.roundabout_exit = static_cast<std::uint8_t>(input[19]);
  snapshot.road_name = string_from_jni(env, road_name);
  snapshot.instruction = string_from_jni(env, instruction);
  return encode_message(env, codec, moto::ble::Message{snapshot});
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_moto_gps_protocol_NativeBleCodec_00024Companion_nativeEncodeRouteGeometry(
    JNIEnv* env, jobject, jlong handle, jintArray header, jintArray points) {
  auto* codec = state_from_handle(handle);
  if (codec == nullptr || header == nullptr || points == nullptr) return nullptr;
  if (env->GetArrayLength(header) != 9) return nullptr;
  const jsize point_values = env->GetArrayLength(points);
  if (point_values <= 0 || point_values % 2 != 0) return nullptr;
  std::vector<jint> header_values(9);
  std::vector<jint> point_values_copy(static_cast<std::size_t>(point_values));
  env->GetIntArrayRegion(header, 0, 9, header_values.data());
  env->GetIntArrayRegion(points, 0, point_values, point_values_copy.data());

  moto::ble::RouteGeometry geometry;
  geometry.coordinate_system =
      static_cast<moto::ble::CoordinateSystem>(header_values[0]);
  geometry.route_token = static_cast<std::uint32_t>(header_values[1]);
  geometry.route_generation = static_cast<std::uint32_t>(header_values[2]);
  geometry.chunk_index = static_cast<std::uint16_t>(header_values[3]);
  geometry.chunk_count = static_cast<std::uint16_t>(header_values[4]);
  geometry.first_point_index = static_cast<std::uint16_t>(header_values[5]);
  geometry.total_point_count = static_cast<std::uint16_t>(header_values[6]);
  geometry.view_origin.latitude_e6 = header_values[7];
  geometry.view_origin.longitude_e6 = header_values[8];
  geometry.points.reserve(static_cast<std::size_t>(point_values / 2));
  for (jsize index = 0; index < point_values; index += 2) {
    geometry.points.push_back({point_values_copy[static_cast<std::size_t>(index)],
                               point_values_copy[static_cast<std::size_t>(index + 1)]});
  }
  return encode_message(env, codec, moto::ble::Message{geometry});
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_moto_gps_protocol_NativeBleCodec_00024Companion_nativePushDeviceFrame(
    JNIEnv* env, jobject, jlong handle, jbyteArray frame, jlong received_at_ms) {
  auto* codec = state_from_handle(handle);
  if (codec == nullptr || frame == nullptr) return nullptr;
  const jsize size = env->GetArrayLength(frame);
  std::vector<std::uint8_t> bytes(static_cast<std::size_t>(size));
  if (size > 0) {
    env->GetByteArrayRegion(frame, 0, size,
                            reinterpret_cast<jbyte*>(bytes.data()));
  }
  const auto result = codec->reassembler.push(
      ByteView(bytes), static_cast<std::uint64_t>(received_at_ms));
  if (result.error != moto::ble::Error::None) {
    const std::string json =
        std::string("{\"kind\":\"error\",\"error\":\"") +
        moto::ble::to_string(result.error) + "\"}";
    return env->NewStringUTF(json.c_str());
  }
  if (!result.complete()) {
    std::string json = common_event(
        result.state == moto::ble::ReassemblyState::DuplicateMessage
            ? "duplicate"
            : "partial",
        result.message.sequence, result.message.flags);
    json += "}";
    return env->NewStringUTF(json.c_str());
  }
  const std::string json = complete_event(result.message);
  return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_moto_gps_protocol_NativeBleCodec_00024Companion_nativeGoldenVectorCheck(JNIEnv*,
                                                                                 jobject) {
  moto::ble::ConnectionStatus status;
  status.role = moto::ble::EndpointRole::Phone;
  status.state = moto::ble::ConnectionState::Ready;
  status.minimum_version = 1;
  status.maximum_version = 1;
  status.capabilities = 0x7FU;
  status.session_id = 0x12345678U;
  status.max_frame_size = 185;
  status.heartbeat_interval_ms = 1'000;

  const auto payload = moto::ble::encode_message(moto::ble::Message{status});
  const auto expected_payload = bytes_from_initializer(
      {0x01, 0x01, 0x01, 0x01, 0x01, 0x7f, 0x00, 0x00, 0x00,
       0x78, 0x56, 0x34, 0x12, 0xb9, 0x00, 0xe8, 0x03});
  if (!payload.ok() || payload.value != expected_payload) return JNI_FALSE;

  const auto frames = moto::ble::fragment_message(
      moto::ble::MessageType::ConnectionStatus, 1,
      ByteView(payload.value), 512);
  const auto expected_frame = bytes_from_initializer(
      {0xb7, 0x01, 0x01, 0x03, 0x01, 0x00, 0x00, 0x00, 0x11, 0x00,
       0x01, 0x01, 0x01, 0x01, 0x01, 0x7f, 0x00, 0x00, 0x00, 0x78,
       0x56, 0x34, 0x12, 0xb9, 0x00, 0xe8, 0x03, 0x54, 0x69});
  return frames.ok() && frames.value.size() == 1 &&
                 frames.value.front() == expected_frame
             ? JNI_TRUE
             : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_moto_gps_protocol_NativeBleCodec_00024Companion_nativeCoordinateSanityCheck(
    JNIEnv*, jobject) {
  const moto::coordinates::Coordinate input{117.1219266, 36.6743309};
  const auto converted = moto::coordinates::wgs84_to_gcj02(input);
  return (converted.ok() && std::isfinite(converted.coordinate.latitude_deg) &&
          std::isfinite(converted.coordinate.longitude_deg))
             ? JNI_TRUE
             : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_moto_gps_protocol_NativeBleCodec_00024Companion_nativeRouteToken(
    JNIEnv* env, jobject, jstring route_id) {
  if (route_id == nullptr) return 0;
  const std::string value = string_from_jni(env, route_id);
  return static_cast<jint>(moto::ble::route_token(value));
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_moto_gps_protocol_NativeBleCodec_00024Companion_nativeWgs84ToGcj02(
    JNIEnv* env, jobject, jdouble longitude_deg, jdouble latitude_deg) {
  const moto::coordinates::Coordinate input{longitude_deg, latitude_deg};
  const auto converted = moto::coordinates::wgs84_to_gcj02(input);
  if (!converted.ok()) return nullptr;
  auto* result = env->NewDoubleArray(2);
  if (result == nullptr) return nullptr;
  const jdouble values[2] = {converted.coordinate.longitude_deg,
                             converted.coordinate.latitude_deg};
  env->SetDoubleArrayRegion(result, 0, 2, values);
  return result;
}
