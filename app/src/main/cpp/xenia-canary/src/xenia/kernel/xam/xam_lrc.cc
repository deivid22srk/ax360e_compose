/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2022 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

// LRC (Live Reaction Client) anti-cheat / DRM stubs.
//
// These exports are part of Xbox LIVE's LRC (Live Reaction Client) subsystem
// used for anti-cheat integrity checks and session telemetry. Many titles,
// including Forza Horizon, call XamLrcVerifyClientId early during boot and
// take a fatal error path (calling XamLrcLogError) if it returns failure.
//
// Real LRC requires server-side LIVE services that an emulator cannot talk
// to. The safest emulator behavior is to make every LRC export succeed (or
// no-op for void returns) so the title's anti-cheat check is satisfied and
// execution continues past the boot gate.
//
// Without these stubs, Forza Horizon terminates with
//   "undefined extern call to XamLrcLogError"
// at the moment the guest's LRC check returns failure.
//
// Reference: ax360e_compose Forza Horizon log lines 1139-1145, 3505.

#include "xenia/base/logging.h"
#include "xenia/kernel/util/shim_utils.h"
#include "xenia/kernel/xam/xam_private.h"
#include "xenia/xbox.h"

namespace xe {
namespace kernel {
namespace xam {

// XamLrcSetTitlePort (ordinal 0x051F)
// Sets the LIVE port used by LRC. In the emulator we have no LIVE
// transport, so just accept the value and continue.
dword_result_t XamLrcSetTitlePort_entry(dword_t port) {
  XELOGI("XamLrcSetTitlePort({:08X}) stubbed - no LIVE transport", port.value);
  return 0;
}
DECLARE_XAM_EXPORT1(XamLrcSetTitlePort, kNone, kStub);

// XamLrcVerifyClientId (ordinal 0x0520)
// Verifies the client ID with LIVE. Returning success here is critical:
// a failure return triggers the title's anti-cheat error path which calls
// XamLrcLogError and terminates the process (see Forza Horizon log line
// 3505). Returning 0 = X_ERROR_SUCCESS.
dword_result_t XamLrcVerifyClientId_entry(dword_t user_index,
                                          pointer_t<uint8_t> client_id,
                                          dword_t client_id_size) {
  XELOGI("XamLrcVerifyClientId(user={}, size={}) stubbed - returning success",
         user_index.value, client_id_size.value);
  return 0;
}
DECLARE_XAM_EXPORT1(XamLrcVerifyClientId, kNone, kStub);

// XamLrcEncryptDecryptTitleMessage (ordinal 0x0521)
// Encrypts/decrypts a title message using LRC keys. Since we have no LIVE
// keys, we leave the buffer unchanged (the title will treat the
// "encrypted" buffer as opaque and pass it back to LRC for decryption,
// which we will also leave unchanged - round-trip identity).
dword_result_t XamLrcEncryptDecryptTitleMessage_entry(
    dword_t user_index, pointer_t<uint8_t> buffer, dword_t buffer_size,
    dword_t encrypt) {
  XELOGI("XamLrcEncryptDecryptTitleMessage(user={}, size={}, encrypt={}) "
         "stubbed - identity (no LIVE keys)",
         user_index.value, buffer_size.value, encrypt.value);
  return 0;
}
DECLARE_XAM_EXPORT1(XamLrcEncryptDecryptTitleMessage, kNone, kStub);

// XamLrcLogSessionSummary (ordinal 0x05FB)
// Uploads a session summary blob to LIVE telemetry. No LIVE transport,
// no-op.
dword_result_t XamLrcLogSessionSummary_entry(pointer_t<uint8_t> summary,
                                             dword_t summary_size) {
  // No-op - silently drop the telemetry upload.
  return 0;
}
DECLARE_XAM_EXPORT1(XamLrcLogSessionSummary, kNone, kStub);

// XamLrcLogError (ordinal 0x05FC)
// Logs an LRC error code to LIVE. Without this stub the guest would
// crash with "undefined extern call to XamLrcLogError" (Forza Horizon
// log line 3505). We no-op instead of returning failure so the title
// can continue past its anti-cheat error path.
dword_result_t XamLrcLogError_entry(dword_t error_code,
                                    pointer_t<uint8_t> context,
                                    dword_t context_size) {
  XELOGW("XamLrcLogError(error=0x{:08X}) stubbed - suppressed LRC error report",
         error_code.value);
  return 0;
}
DECLARE_XAM_EXPORT1(XamLrcLogError, kNone, kStub);

}  // namespace xam
}  // namespace kernel
}  // namespace xe

DECLARE_XAM_EMPTY_REGISTER_EXPORTS(Lrc);
