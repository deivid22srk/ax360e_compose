/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2022 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#include <cstring>

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

// __C_specific_handler (ordinal 0x147)
// C exception handler used by SEH (Structured Exception Handling) on Xbox 360.
// This is called when a C exception occurs in a function with a try/except
// block. The handler walks the scope table and calls the appropriate filter
// or handler.
//
// Full implementation requires walking the guest's scope table and executing
// PPC filter/handler functions, which is complex. For now, return
// ExceptionContinueSearch (0) to let other handlers process the exception.
dword_result_t __C_specific_handler_entry(
    pointer_t<uint8_t> exception_pointers,
    pointer_t<uint8_t> frame_pointer,
    pointer_t<uint8_t> context,
    lpvoid_t dispatcher) {
  return 0;  // ExceptionContinueSearch
}
DECLARE_XBOXKRNL_EXPORT1(__C_specific_handler, kNone, kStub);

// RtlCaptureContext (ordinal 0x119)
// Captures the current thread's context (registers) into a CONTEXT structure.
// On Xbox 360, this captures the PPC register file.
//
// We capture the PPC context that Xenia already maintains.
void RtlCaptureContext_entry(pointer_t<uint8_t> context_ptr,
                             const ppc_context_t& ctx) {
  if (!context_ptr) return;
  // Zero the context structure first
  std::memset(context_ptr, 0, 0x300);  // X_CONTEXT is ~768 bytes
  // Copy GPRs (r0-r31 = 32 * 4 = 128 bytes)
  uint8_t* ptr = context_ptr;
  // Offset 0x80 = GPRs in X_CONTEXT (Windows layout approximation)
  for (int i = 0; i < 32; ++i) {
    xe::store_and_swap<uint32_t>(ptr + 0x80 + i * 4, uint32_t(ctx->r[i]));
  }
  // Offset 0x100 = CR
  xe::store_and_swap<uint32_t>(ptr + 0x100, uint32_t(ctx->cr));
  // Offset 0x108 = LR
  xe::store_and_swap<uint32_t>(ptr + 0x108, ctx->lr);
  // Offset 0x110 = CTR
  xe::store_and_swap<uint32_t>(ptr + 0x110, ctx->ctr);
  // Offset 0x118 = XER
  xe::store_and_swap<uint32_t>(ptr + 0x118, ctx->xer);
  // NIP (PC) at offset 0x120
  xe::store_and_swap<uint32_t>(ptr + 0x120, ctx->pc);
}
DECLARE_XBOXKRNL_EXPORT1(RtlCaptureContext, kNone, kImplemented);

// RtlUnwind (ordinal 0x136)
// Unwinds the stack to a target frame, calling exception handlers along the
// way. Full implementation requires walking the PPC stack and executing
// guest handler functions. For now, this is a no-op stub.
void RtlUnwind_entry(pointer_t<uint8_t> target_frame,
                     lpvoid_t target_ip,
                     pointer_t<uint8_t> exception_record,
                     dword_t return_value,
                     const ppc_context_t& ctx) {
  // No-op: stack unwinding is handled by the JIT's exception mechanism
}
DECLARE_XBOXKRNL_EXPORT1(RtlUnwind, kNone, kStub);

// ObIsTitleObject (ordinal 0x69)
// Checks whether a handle refers to a title (user) object rather than a
// system object. Return true (1) to allow the guest to proceed.
dword_result_t ObIsTitleObject_entry(dword_t handle) {
  return 1;
}
DECLARE_XBOXKRNL_EXPORT1(ObIsTitleObject, kNone, kStub);

// PsCamDeviceRequest (ordinal 0x30D)
// Sends a request to the PlayStation Camera device (used by Kinect games).
// Without camera hardware, return error.
dword_result_t PsCamDeviceRequest_entry(pointer_t<uint8_t> request) {
  return X_E_DEVICE_NOT_CONNECTED;
}
DECLARE_XBOXKRNL_EXPORT1(PsCamDeviceRequest, kNone, kStub);

// LDI (Lempel-Dependent-Integer) decompression — used by some games for
// texture/data decompression. These are compressed data decompression APIs.
// Without a full LDI implementation, return error.

// LDICreateDecompression (ordinal 0xB6)
dword_result_t LDICreateDecompression_entry(
    lpdword_t context, dword_t flags, lpvoid_t callback) {
  return X_STATUS_NOT_IMPLEMENTED;
}
DECLARE_XBOXKRNL_EXPORT1(LDICreateDecompression, kNone, kStub);

// LDIDecompress (ordinal 0xB7)
dword_result_t LDIDecompress_entry(
    dword_t context, pointer_t<uint8_t> dst, dword_t dst_size,
    pointer_t<uint8_t> src, dword_t src_size, lpdword_t dst_used,
    lpdword_t src_used) {
  return X_STATUS_NOT_IMPLEMENTED;
}
DECLARE_XBOXKRNL_EXPORT1(LDIDecompress, kNone, kStub);

// LDIDestroyDecompression (ordinal 0xB8)
dword_result_t LDIDestroyDecompression_entry(dword_t context) {
  return X_STATUS_SUCCESS;
}
DECLARE_XBOXKRNL_EXPORT1(LDIDestroyDecompression, kNone, kStub);

}  // namespace xboxkrnl
}  // namespace kernel
}  // namespace xe

DECLARE_XBOXKRNL_EMPTY_REGISTER_EXPORTS(Misc);
