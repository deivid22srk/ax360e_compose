/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2026 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#ifndef XENIA_BASE_PLATFORM_ARM64_H_
#define XENIA_BASE_PLATFORM_ARM64_H_
#include <cstdint>

namespace xe {
namespace arm64 {
enum A64FeatureFlags : uint64_t {
  // bit 0 — FEAT_LSE (Large System Extensions atomics: CAS, LDADD, etc.)
  kA64EmitLSE = 1 << 0,
  // bit 1 — FPCR.FZ flushes denormal inputs (skip software flush)
  kA64FZFlushesInputs = 1 << 1,
  // bit 2 — FEAT_LRCPC (Release-Consistent Processor Consistent, v8.3):
  // LDAPR / LDAPRB / LDAPRH load-acquire with RCpc semantics. Cheaper
  // than LDAR for one-way acquire fences: LDAR implies a DMB-like full
  // barrier, LDAPR only orders later loads/stores against this load.
  // Used in OPCODE_RESERVED_LOAD to lower lwarx load-acquire cost.
  kA64EmitRCPC = 1 << 2,
  // bit 3 — FEAT_LRCPC2 (v8.4): LDAPR with immediate offsets + STLR
  // improvements. Future use.
  kA64EmitRCPC2 = 1 << 3,
};

XE_NOALIAS
uint64_t GetFeatureFlags();
XE_COLD
void InitFeatureFlags();

}  // namespace arm64
}  // namespace xe

#endif  // XENIA_BASE_PLATFORM_ARM64_H_
