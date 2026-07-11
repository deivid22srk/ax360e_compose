/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2022 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#include "xenia/kernel/util/shim_utils.h"
#include "xenia/kernel/xboxkrnl/xboxkrnl_private.h"
#include "xenia/kernel/xthread.h"
#include "xenia/xbox.h"

namespace xe {
namespace kernel {
namespace xboxkrnl {

void KeEnableFpuExceptions_entry(
    const ppc_context_t& ctx) {  // dword_t enabled) {
  // TODO(benvanik): can we do anything about exceptions?
  // theres a lot more thats supposed to happen here, the floating point state
  // has to be saved to kthread, the irql changes, the machine state register is
  // changed to enable exceptions

  X_KTHREAD* kthread = ctx->TranslateVirtual(
      ctx->TranslateVirtualGPR<X_KPCR*>(ctx->r[13])->prcb_data.current_thread);
  kthread->fpu_exceptions_on = static_cast<uint32_t>(ctx->r[3]) != 0;
}
DECLARE_XBOXKRNL_EXPORT1(KeEnableFpuExceptions, kNone, kStub);

// KeSaveFloatingPointState / KeRestoreFloatingPointState
//
// On Xbox 360 these save/restore the guest FP/Vmx register file to a
// caller-provided buffer (so kernel-mode code can clobber FP registers
// without losing guest state). The previous implementation in this file
// was Microsoft-specific (__declspec, Windows-only bitfields) and was
// disabled under `#if 0`.
//
// For the Android/POSIX port we provide a minimal cross-platform stub:
//   - KeSaveFloatingPointState: marks the buffer valid and returns success.
//   - KeRestoreFloatingPointState: returns success.
//
// The Xenia JIT already preserves the PPC FP/Vec register file across
// host function calls (see A64Emitter prolog/epilog saving d8-d15 and
// the backend context saving v0-v31), so the kernel buffer save/restore
// is not strictly required for correctness - the guest's "saved" FP
// state is the PPCContext itself, which the JIT already round-trips.
//
// Returning X_STATUS_SUCCESS (0) here prevents Forza Horizon (and other
// titles that call these on every thread context switch) from taking the
// error path. Without these stubs, the import is unresolved and every
// guest call hits "undefined extern" → fatal crash.
//
// Reference: ax360e Forza Horizon log lines 975-976:
//   F 820008EC 832F15E4 090 ( 144) !! KeRestoreFloatingPointState
//   F 820008F0 832F15F4 095 ( 149) !! KeSaveFloatingPointState
dword_result_t KeSaveFloatingPointState_entry(pointer_t<uint8_t> save_state) {
  // Touch the buffer so the guest sees a non-zero SaveState if it later
  // inspects it for "did we save?" validity checks.
  if (save_state) {
    // First DWORD is the "valid" flag in X_FPU_SAVE_STATE on Xbox 360.
    // pointer_t<uint8_t> has operator uint8_t*() which converts to void*
    // implicitly — no .as<>() needed (TypedPointerParam doesn't have one).
    xe::store_and_swap<uint32_t>(static_cast<void*>(save_state), 1u);
  }
  return X_STATUS_SUCCESS;
}
DECLARE_XBOXKRNL_EXPORT1(KeSaveFloatingPointState, kNone, kStub);

dword_result_t KeRestoreFloatingPointState_entry(
    pointer_t<uint8_t> save_state) {
  // No-op: PPCContext FP/Vec state is preserved by the JIT across host
  // calls, so there is nothing to restore here.
  return X_STATUS_SUCCESS;
}
DECLARE_XBOXKRNL_EXPORT1(KeRestoreFloatingPointState, kNone, kStub);

static qword_result_t KeQueryInterruptTime_entry(const ppc_context_t& ctx) {
  auto kstate = ctx->kernel_state;
  uint32_t ts_bundle = kstate->GetKeTimestampBundle();
  X_TIME_STAMP_BUNDLE* bundle =
      ctx->TranslateVirtual<X_TIME_STAMP_BUNDLE*>(ts_bundle);

  return xe::load_and_swap<uint64_t>(&bundle->interrupt_time);
}
DECLARE_XBOXKRNL_EXPORT1(KeQueryInterruptTime, kNone, kImplemented);

// [ETX STUBS] Event Tracing for Xbox (ETX) — producer/consumer API.
//
// ETX is Xbox 360's ETW (Event Tracing for Windows) equivalent. Games can
// register as producers to log telemetry events, and as consumers to receive
// events. Without a real ETX subsystem, these are no-ops.
//
// Without these stubs, the guest would crash with
// "undefined extern call to EtxProducerRegister" (Forza Horizon 2 log line
// 3539, 3541). Returning X_STATUS_SUCCESS (0) lets the game continue.
//
// Reference: xboxkrnl_table.inc ordinals 0x032D-0x0336.

// EtxConsumerDisableEventType (0x032D)
dword_result_t EtxConsumerDisableEventType_entry(unknown_t unk1,
                                                 unknown_t unk2) {
  return X_STATUS_SUCCESS;
}
DECLARE_XBOXKRNL_EXPORT1(EtxConsumerDisableEventType, kNone, kStub);

// EtxConsumerEnableEventType (0x032E)
dword_result_t EtxConsumerEnableEventType_entry(unknown_t unk1,
                                                unknown_t unk2) {
  return X_STATUS_SUCCESS;
}
DECLARE_XBOXKRNL_EXPORT1(EtxConsumerEnableEventType, kNone, kStub);

// EtxConsumerProcessLogs (0x032F)
dword_result_t EtxConsumerProcessLogs_entry(unknown_t unk1) {
  return X_STATUS_SUCCESS;
}
DECLARE_XBOXKRNL_EXPORT1(EtxConsumerProcessLogs, kNone, kStub);

// EtxConsumerRegister (0x0330)
dword_result_t EtxConsumerRegister_entry(unknown_t unk1, unknown_t unk2,
                                         unknown_t unk3, unknown_t unk4) {
  return X_STATUS_SUCCESS;
}
DECLARE_XBOXKRNL_EXPORT1(EtxConsumerRegister, kNone, kStub);

// EtxConsumerUnregister (0x0331)
dword_result_t EtxConsumerUnregister_entry(unknown_t unk1) {
  return X_STATUS_SUCCESS;
}
DECLARE_XBOXKRNL_EXPORT1(EtxConsumerUnregister, kNone, kStub);

// EtxProducerLog (0x0332)
dword_result_t EtxProducerLog_entry(unknown_t unk1, unknown_t unk2) {
  return X_STATUS_SUCCESS;
}
DECLARE_XBOXKRNL_EXPORT1(EtxProducerLog, kNone, kStub);

// EtxProducerLogV (0x0333)
dword_result_t EtxProducerLogV_entry(unknown_t unk1, unknown_t unk2,
                                     unknown_t unk3) {
  return X_STATUS_SUCCESS;
}
DECLARE_XBOXKRNL_EXPORT1(EtxProducerLogV, kNone, kStub);

// EtxProducerRegister (0x0334)
dword_result_t EtxProducerRegister_entry(unknown_t unk1, unknown_t unk2,
                                         unknown_t unk3, unknown_t unk4) {
  return X_STATUS_SUCCESS;
}
DECLARE_XBOXKRNL_EXPORT1(EtxProducerRegister, kNone, kStub);

// EtxProducerUnregister (0x0335)
dword_result_t EtxProducerUnregister_entry(unknown_t unk1) {
  return X_STATUS_SUCCESS;
}
DECLARE_XBOXKRNL_EXPORT1(EtxProducerUnregister, kNone, kStub);

// EtxConsumerFlushBuffers (0x0336)
dword_result_t EtxConsumerFlushBuffers_entry(unknown_t unk1) {
  return X_STATUS_SUCCESS;
}
DECLARE_XBOXKRNL_EXPORT1(EtxConsumerFlushBuffers, kNone, kStub);

}  // namespace xboxkrnl
}  // namespace kernel
}  // namespace xe

DECLARE_XBOXKRNL_EMPTY_REGISTER_EXPORTS(Misc);
