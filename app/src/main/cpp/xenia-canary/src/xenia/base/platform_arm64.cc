/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2026 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#include <cfenv>
#include <cmath>
#include <cstring>

#include "xenia/base/cvar.h"
#include "xenia/base/platform.h"
#define XBYAK_NO_OP_NAMES
#include "third_party/xbyak_aarch64/xbyak_aarch64/xbyak_aarch64.h"
#include "third_party/xbyak_aarch64/xbyak_aarch64/xbyak_aarch64_util.h"

// On Linux/Android we use getauxval(AT_HWCAP) to detect CPU features that
// xbyak_aarch64's Cpu class does not expose (FEAT_LRCPC, FEAT_LRCPC2,
// FEAT_LSE2, etc.). This is the same mechanism libc uses internally.
#if XE_PLATFORM_LINUX || XE_PLATFORM_ANDROID
#include <sys/auxv.h>
// HWCAP_* bits from <asm/hwcap.h> — we define them inline rather than
// pulling in kernel headers, which keeps the build self-contained.
#ifndef HWCAP_LRCPC
#define HWCAP_LRCPC (1UL << 28)
#endif
#ifndef HWCAP_ILRCPC
#define HWCAP_ILRCPC (1UL << 29)
#endif
#endif  // XE_PLATFORM_LINUX || XE_PLATFORM_ANDROID

DEFINE_int64(a64_extension_mask, -1LL,
             "Allow the detection and utilization of specific instruction set "
             "features.\n"
             "    0 = armv8.0\n"
             "    1 = Large System Extensions(LSE) atomic operations\n"
             "    2 = FPCR.FZ flushes denormal inputs (skip software flush)\n"
             "    4 = RCpc load-acquire (LDAPR) - cheaper than LDAR for one-way acquire\n"
             "    8 = RCpc2 (LDAPR with imm offsets) - reserved for future use\n"
             "   -1 = Detect and utilize all possible processor features\n",
             "a64");
namespace xe {
namespace arm64 {
static uint64_t g_feature_flags = 0U;
static bool g_did_initialize_feature_flags = false;
uint64_t GetFeatureFlags() {
  if (!g_did_initialize_feature_flags) {
    InitFeatureFlags();
  }
  return g_feature_flags;
}
XE_COLD
XE_NOINLINE
void InitFeatureFlags() {
  uint64_t feature_flags_ = 0U;
  {
    Xbyak_aarch64::util::Cpu cpu_;
#define TEST_EMIT_FEATURE(emit, ext)                \
  if ((cvars::a64_extension_mask & emit) == emit) { \
    feature_flags_ |= (cpu_.has(ext) ? emit : 0);   \
  }
    TEST_EMIT_FEATURE(kA64EmitLSE,
                      Xbyak_aarch64::util::XBYAK_AARCH64_HWCAP_ATOMIC);
#undef TEST_EMIT_FEATURE
  }

  // [ANDROID PERF] Detect FEAT_LRCPC (LDAPR) via getauxval(AT_HWCAP).
  //
  // FEAT_LRCPC (ARMv8.3-RCpc) introduces LDAPR/LDAPRB/LDAPRH: load-acquire
  // with RCpc semantics. They are cheaper than LDAR for one-way acquire
  // (load-only ordering), saving ~3-5 cycles per acquire on Cortex-A78/A710
  // by avoiding the implicit DMB ISH that LDAR implies.
  //
  // We use this in OPCODE_RESERVED_LOAD (PPC lwarx) to make the load half
  // of a PPC lwarx/stwcx. pair cheaper. On Adreno 619 (Snapdragon 750G,
  // the device that produced the Forza Horizon log), FEAT_LRCPC is
  // available (Cortex-A78 prime core).
  //
  // We cannot use xbyak_aarch64::util::Cpu::has() because the xbyak
  // version bundled with xenia-canary doesn't expose an RCPC capability
  // enum. Direct getauxval() is portable across Android 5.x+ and Linux.
#if XE_PLATFORM_LINUX || XE_PLATFORM_ANDROID
  if ((cvars::a64_extension_mask & kA64EmitRCPC) == kA64EmitRCPC) {
    unsigned long hwcap = getauxval(AT_HWCAP);
    if (hwcap & HWCAP_LRCPC) {
      feature_flags_ |= kA64EmitRCPC;
    }
  }
  if ((cvars::a64_extension_mask & kA64EmitRCPC2) == kA64EmitRCPC2) {
    unsigned long hwcap = getauxval(AT_HWCAP);
    if (hwcap & HWCAP_ILRCPC) {
      feature_flags_ |= kA64EmitRCPC2;
    }
  }
#endif  // XE_PLATFORM_LINUX || XE_PLATFORM_ANDROID

  // Detect whether FPCR.FZ flushes denormal float32 inputs to zero.
  // The ARM spec says input flushing is implementation-defined.
  // Modern cores (Cortex-A76+, Apple M1+) flush inputs; older ones may not.
  if ((cvars::a64_extension_mask & kA64FZFlushesInputs) ==
      kA64FZFlushesInputs) {
    // Build a denormal float32: smallest positive denormal = 0x00000001.
    uint32_t denorm_bits = 1;
    float denorm;
    std::memcpy(&denorm, &denorm_bits, 4);

    // Save FPCR, enable FZ, add two denormals, check result.
    uint64_t saved_fpcr;
#if XE_COMPILER_MSVC
    saved_fpcr = _ReadStatusReg(ARM64_FPCR);
    _WriteStatusReg(ARM64_FPCR, saved_fpcr | (1ULL << 24));
#else
    asm volatile("mrs %0, fpcr" : "=r"(saved_fpcr));
    uint64_t fz_fpcr = saved_fpcr | (1ULL << 24);
    asm volatile("msr fpcr, %0" ::"r"(fz_fpcr));
#endif

    volatile float a = denorm;
    volatile float b = denorm;
    volatile float result = a + b;

#if XE_COMPILER_MSVC
    _WriteStatusReg(ARM64_FPCR, saved_fpcr);
#else
    asm volatile("msr fpcr, %0" ::"r"(saved_fpcr));
#endif

    if (result == 0.0f) {
      feature_flags_ |= kA64FZFlushesInputs;
    }
  }

  g_feature_flags = feature_flags_;
  g_did_initialize_feature_flags = true;
}
}  // namespace arm64
}  // namespace xe
