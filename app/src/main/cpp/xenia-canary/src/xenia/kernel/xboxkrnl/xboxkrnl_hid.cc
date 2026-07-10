/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2022 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#include "xenia/base/logging.h"
#include "xenia/kernel/util/shim_utils.h"
#include "xenia/kernel/xboxkrnl/xboxkrnl_private.h"
#include "xenia/xbox.h"

namespace xe {
namespace kernel {
namespace xboxkrnl {

dword_result_t HidReadKeys_entry(dword_t unk1, unknown_t unk2, unknown_t unk3) {
  /* TODO(gibbed):
   * Games check for the following errors:
   *   0xC000009D - translated to 0x48F  - ERROR_DEVICE_NOT_CONNECTED
   *   0x103      - translated to 0x10D2 - ERROR_EMPTY
   * Other errors appear to be ignored?
   *
   * unk1 is 0
   * unk2 is a pointer to &unk3[2], possibly a 6-byte buffer
   * unk3 is a pointer to a 20-byte buffer
   */
  return 0xC000009D;
}
DECLARE_XBOXKRNL_EXPORT1(HidReadKeys, kInput, kStub);

// ============================================================================
// XInputdFF* (DirectInput Force-Feedback) stubs - ordinals 0x0282..0x0289
// ============================================================================
//
// These eight exports are the Xbox 360's DirectInput force-feedback (FF)
// API used by racing wheels and other haptic controllers. Forza Horizon
// imports them all and resolves them on every controller event - without
// stubs the loader logs
//   "GetProcAddressByOrdinal(0282(XInputdFFGetDeviceInfo)) is not implemented"
// on every controller poll (see Forza Horizon log lines 1506-1523).
//
// We stub all eight to return X_STATUS_SUCCESS (or 0) so the guest's FF
// code continues without crashing. The actual rumble is handled by XInput
// (XInputSetState) which the HID subsystem already implements.
namespace {

// Standard HRESULT-style "device not connected" return for FF queries.
// Games handle this gracefully (they fall back to XInput rumble).
constexpr uint32_t kFFDeviceNotConnected = 0xC000009D;

}  // namespace

// 0x0282 - Get info about the FF device. Return "not connected" so the
// game skips FF setup and falls back to plain rumble.
dword_result_t XInputdFFGetDeviceInfo_entry(dword_t user_index,
                                            pointer_t<uint8_t> info_ptr,
                                            lpdword_t info_size_ptr) {
  return kFFDeviceNotConnected;
}
DECLARE_XBOXKRNL_EXPORT1(XInputdFFGetDeviceInfo, kInput, kStub);

// 0x0283 - Submit an effect (ramp, periodic, constant force, etc.).
// Accept the call silently; the actual rumble is driven via XInput.
dword_result_t XInputdFFSetEffect_entry(dword_t user_index,
                                        dword_t effect_handle,
                                        pointer_t<uint8_t> effect_params) {
  return 0;
}
DECLARE_XBOXKRNL_EXPORT1(XInputdFFSetEffect, kInput, kStub);

// 0x0284 - Update a running effect (start, stop, pause).
dword_result_t XInputdFFUpdateEffect_entry(dword_t user_index,
                                           dword_t effect_handle,
                                           dword_t operation,
                                           dword_t iterations) {
  return 0;
}
DECLARE_XBOXKRNL_EXPORT1(XInputdFFUpdateEffect, kInput, kStub);

// 0x0285 - Effect operation (combined set + update).
dword_result_t XInputdFFEffectOperation_entry(
    dword_t user_index, dword_t effect_handle, dword_t operation,
    dword_t iterations, pointer_t<uint8_t> effect_params) {
  return 0;
}
DECLARE_XBOXKRNL_EXPORT1(XInputdFFEffectOperation, kInput, kStub);

// 0x0286 - Device control (enable/disable/reset all FF effects).
dword_result_t XInputdFFDeviceControl_entry(dword_t user_index,
                                            dword_t operation) {
  return 0;
}
DECLARE_XBOXKRNL_EXPORT1(XInputdFFDeviceControl, kInput, kStub);

// 0x0287 - Set global device gain (0-100).
dword_result_t XInputdFFSetDeviceGain_entry(dword_t user_index,
                                            dword_t gain) {
  return 0;
}
DECLARE_XBOXKRNL_EXPORT1(XInputdFFSetDeviceGain, kInput, kStub);

// 0x0288 - Cancel pending async FF I/O.
dword_result_t XInputdFFCancelIo_entry(dword_t user_index,
                                       dword_t effect_handle) {
  return 0;
}
DECLARE_XBOXKRNL_EXPORT1(XInputdFFCancelIo, kInput, kStub);

// 0x0289 - Direct rumble (left/right motor speeds). Forward to XInput
// would require a hid subsystem call; we no-op and let XInputSetState
// (already implemented) handle actual rumble.
dword_result_t XInputdFFSetRumble_entry(dword_t user_index,
                                        dword_t left_motor_speed,
                                        dword_t right_motor_speed) {
  return 0;
}
DECLARE_XBOXKRNL_EXPORT1(XInputdFFSetRumble, kInput, kStub);

}  // namespace xboxkrnl
}  // namespace kernel
}  // namespace xe

DECLARE_XBOXKRNL_EMPTY_REGISTER_EXPORTS(Hid);
