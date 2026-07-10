/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2026 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#include "xenia/cpu/backend/a64/a64_code_cache.h"

#include <cstring>
#include <vector>

#include "xenia/base/assert.h"
#include "xenia/base/logging.h"
#include "xenia/base/math.h"
#include "xenia/cpu/backend/a64/a64_stack_layout.h"

// libgcc/libunwind APIs for registering DWARF .eh_frame unwind info.
extern "C" void __register_frame(void*);
extern "C" void __deregister_frame(void*);

#if XE_PLATFORM_AX360E
#include "../aarch64_disasm.h"
#include <unwind.h>
#include <android/log.h>
#include <android/log.h>
#define EHLOGI(...) __android_log_print(ANDROID_LOG_INFO, "eh_frame", __VA_ARGS__)
#define EHLOGW(...) __android_log_print(ANDROID_LOG_WARN, "eh_frame", __VA_ARGS__)
extern "C" _Unwind_Reason_Code __gxx_personality_v0(
        int version,
        _Unwind_Action actions,
        uint64_t exceptionClass,
        _Unwind_Exception* exceptionObject,
        _Unwind_Context* context);

static _Unwind_Reason_Code trace(struct _Unwind_Context* ctx,void*){
    uint64_t ip=reinterpret_cast<uint64_t>(_Unwind_GetIP(ctx));
    XELOGI("FRAME: {:16X}",ip);
    std::string insts= aarch64_disasm(ip,reinterpret_cast<uint32_t*>(ip),16);
    XELOGI(insts);
    return _URC_NO_REASON;
}

static _Unwind_Reason_Code __jit_personality(
        int version,
        _Unwind_Action actions,
        uint64_t exceptionClass,
        _Unwind_Exception* exceptionObject,
        _Unwind_Context* context){
    EHLOGI("PERSONALITY called version=%d actions=0x%x exceptionClass=0x%llx",
           version, (unsigned)actions, (unsigned long long)exceptionClass);
    XELOGI("PERSONALITY {} {:x}",uint32_t( actions),(unsigned long long)exceptionClass);
    if(actions&_UA_CLEANUP_PHASE){
        EHLOGI("_UA_CLEANUP_PHASE IP=0x%llx",
               (unsigned long long)reinterpret_cast<uint64_t>(_Unwind_GetIP(context)));
        XELOGI("_UA_CLEANUP_PHASE IPs={:16X}",reinterpret_cast<uint64_t>(_Unwind_GetIP(context)));
        _Unwind_Backtrace(trace,nullptr);
    }
    _Unwind_Reason_Code result = __gxx_personality_v0(version,actions,exceptionClass,exceptionObject,context);
    EHLOGI("PERSONALITY result=%d", (int)result);
    XELOGI("PERSONALITY result={}",(int)result);
    return result;
}
#endif

namespace xe {
namespace cpu {
namespace backend {
namespace a64 {

// Maximum size of DWARF .eh_frame data per function (CIE + FDE + terminator).
static constexpr uint32_t kMaxUnwindInfoSize = 256;

// DWARF register numbers for AArch64.
static constexpr uint8_t kDwarfRegX19 = 19;
static constexpr uint8_t kDwarfRegX20 = 20;
static constexpr uint8_t kDwarfRegX21 = 21;
static constexpr uint8_t kDwarfRegX22 = 22;
static constexpr uint8_t kDwarfRegX23 = 23;
static constexpr uint8_t kDwarfRegX24 = 24;
static constexpr uint8_t kDwarfRegX25 = 25;
static constexpr uint8_t kDwarfRegX26 = 26;
static constexpr uint8_t kDwarfRegX27 = 27;
static constexpr uint8_t kDwarfRegX28 = 28;
static constexpr uint8_t kDwarfRegFP = 29;  // x29 / frame pointer
static constexpr uint8_t kDwarfRegLR = 30;  // x30 / link register
static constexpr uint8_t kDwarfRegSP = 31;  // stack pointer
static constexpr uint8_t kDwarfRegD8 = 72;  // d8-d15 are callee-saved
static constexpr uint8_t kDwarfRegD9 = 73;
static constexpr uint8_t kDwarfRegD10 = 74;
static constexpr uint8_t kDwarfRegD11 = 75;
static constexpr uint8_t kDwarfRegD12 = 76;
static constexpr uint8_t kDwarfRegD13 = 77;
static constexpr uint8_t kDwarfRegD14 = 78;
static constexpr uint8_t kDwarfRegD15 = 79;

// DWARF CFA opcodes.
static constexpr uint8_t kDW_CFA_advance_loc1 = 0x02;
static constexpr uint8_t kDW_CFA_advance_loc2 = 0x03;
static constexpr uint8_t kDW_CFA_def_cfa = 0x0c;
static constexpr uint8_t kDW_CFA_def_cfa_offset = 0x0e;
static constexpr uint8_t kDW_CFA_nop = 0x00;
static constexpr uint8_t kDW_CFA_offset_extended=0x05;
// DWARF pointer encoding constants.s

    static constexpr uint8_t kDW_EH_PE_pcrel = 0x10;
    static constexpr uint8_t kDW_EH_PE_sdata4 = 0x0b;
    static constexpr uint8_t kDW_EH_PE_udata8 = 0x04;
    static constexpr uint8_t kDW_EH_PE_absptr = 0x00;

static size_t WriteULEB128(uint8_t* p, uint64_t value) {
  size_t count = 0;
  do {
    uint8_t byte = value & 0x7F;
    value >>= 7;
    if (value) byte |= 0x80;
    p[count++] = byte;
  } while (value);
  return count;
}

static size_t WriteSLEB128(uint8_t* p, int64_t value) {
  size_t count = 0;
  bool more = true;
  while (more) {
    uint8_t byte = value & 0x7F;
    value >>= 7;
    if ((value == 0 && !(byte & 0x40)) || (value == -1 && (byte & 0x40))) {
      more = false;
    } else {
      byte |= 0x80;
    }
    p[count++] = byte;
  }
  return count;
}

class PosixA64CodeCache : public A64CodeCache {
 public:
  PosixA64CodeCache();
  ~PosixA64CodeCache() override;

  bool Initialize() override;

  void* LookupUnwindInfo(uint64_t host_pc) override { return nullptr; }

 private:
  UnwindReservation RequestUnwindReservation(uint8_t* entry_address) override;
  void PlaceCode(uint32_t guest_address, void* machine_code,
                 const EmitFunctionInfo& func_info, void* code_execute_address,
                 UnwindReservation unwind_reservation) override;

  void InitializeUnwindEntry(uint8_t* unwind_entry_address,
                             void* code_execute_address,
                             const EmitFunctionInfo& func_info);

#if XE_PLATFORM_AX360E
  static constexpr uint32_t kFrameOffset=36;
#else
  static constexpr uint32_t kFrameOffset=0;
#endif
  std::vector<void*> registered_frames_;
  uint32_t unwind_table_count_ = 0;
};

std::unique_ptr<A64CodeCache> A64CodeCache::Create() {
  return std::make_unique<PosixA64CodeCache>();
}

PosixA64CodeCache::PosixA64CodeCache() = default;

PosixA64CodeCache::~PosixA64CodeCache() {
  for (auto frame : registered_frames_) {
    __deregister_frame(reinterpret_cast<uint8_t*>(frame)+kFrameOffset);
  }
}

bool PosixA64CodeCache::Initialize() {
  if (!A64CodeCache::Initialize()) {
    return false;
  }
  registered_frames_.reserve(kMaximumFunctionCount);
  return true;
}

A64CodeCache::UnwindReservation PosixA64CodeCache::RequestUnwindReservation(
    uint8_t* entry_address) {
#if defined(NDEBUG)
  if (unwind_table_count_ >= kMaximumFunctionCount) {
    xe::FatalError(
        "Unwind table count exceeded maximum! Please report this to "
        "Xenia developers");
  }
#else
  assert_false(unwind_table_count_ >= kMaximumFunctionCount);
#endif
  UnwindReservation unwind_reservation;
  unwind_reservation.data_size = xe::round_up(kMaxUnwindInfoSize, 16);
  unwind_reservation.table_slot = unwind_table_count_++;
  unwind_reservation.entry_address = entry_address;
  return unwind_reservation;
}

void PosixA64CodeCache::PlaceCode(uint32_t guest_address, void* machine_code,
                                  const EmitFunctionInfo& func_info,
                                  void* code_execute_address,
                                  UnwindReservation unwind_reservation) {
  InitializeUnwindEntry(unwind_reservation.entry_address, code_execute_address,
                        func_info);

  void* unwind_execute_address = unwind_reservation.entry_address -
                                 generated_code_write_base_ +
                                 generated_code_execute_base_;
  __register_frame(reinterpret_cast<uint8_t*>(unwind_execute_address)+kFrameOffset);
  registered_frames_.push_back(unwind_execute_address);
}

void PosixA64CodeCache::InitializeUnwindEntry(
    uint8_t* unwind_entry_address, void* code_execute_address,
    const EmitFunctionInfo& func_info) {
  // Compute execute-side base address of the unwind buffer.
  uint8_t* unwind_execute_base = unwind_entry_address -
                                 generated_code_write_base_ +
                                 generated_code_execute_base_;

  uint8_t* p = unwind_entry_address;
  uint8_t* cie_start = p;

  // === CIE (Common Information Entry) ===
  uint8_t* cie_length_ptr = p;
  p += 4;

  uint8_t* cie_content_start = p;

  // CIE ID = 0.
  *reinterpret_cast<uint32_t*>(p) = 0;
  p += 4;

  // Version = 1.
  *p++ = 1;
#if !XE_PLATFORM_AX360E
  // Augmentation string "zR".
  *p++ = 'z';
  *p++ = 'R';
  *p++ = '\0';

#else
  // Augmentation string "zPLR"
  *p++ = 'z';
  *p++ = 'P';
  *p++ = 'L';
  *p++ = 'R';
  *p++ = '\0';
#endif
  // Code alignment factor = 4 (ARM64 instructions are 4 bytes).
  p += WriteULEB128(p, 4);

  // Data alignment factor = -8.
  p += WriteSLEB128(p, -8);

  // Return address register = x30 (LR).
  p += WriteULEB128(p, kDwarfRegLR);

#if !XE_PLATFORM_AX360E
  // Augmentation data length = 1.
  p += WriteULEB128(p, 1);

  // FDE pointer encoding: pc-relative, signed 32-bit.
  *p++ = kDW_EH_PE_pcrel | kDW_EH_PE_sdata4;
#else
    // Augmentation data for "zPLR":
    //   [personality ptr encoding][personality ptr][LSDA encoding][FDE encoding]
    // Augmentation data length
    p += WriteULEB128(p, 11); // 1 + 8 + 1 + 1 = 11

    // Personality routine pointer encoding: absolute, 8-byte
    *p++ = kDW_EH_PE_absptr | kDW_EH_PE_udata8;

    // Personality routine pointer value (use memcpy for safe unaligned write)
    {
        //uint64_t pers = reinterpret_cast<uint64_t>(__jit_personality);
        uint64_t pers = reinterpret_cast<uint64_t>(__gxx_personality_v0);
        memcpy(p, &pers, 8);
        p += 8;
    }
// LSDA pointer encoding: absolute, 8-byte
    *p++ = kDW_EH_PE_pcrel|kDW_EH_PE_sdata4;
    // FDE pointer encoding: absolute, 8-byte (matches how we write pc_begin)
    *p++ = kDW_EH_PE_absptr | kDW_EH_PE_udata8;
#endif
  // Initial instructions:
  // DW_CFA_def_cfa SP, 0 — at function entry, CFA = SP.
  *p++ = kDW_CFA_def_cfa;
  p += WriteULEB128(p, kDwarfRegSP);
  p += WriteULEB128(p, 0);

  // Pad CIE to pointer-size (8-byte) alignment.
  size_t cie_content_len = static_cast<size_t>(p - cie_content_start);
  size_t cie_padded_len = xe::round_up(cie_content_len, sizeof(void*));
  while (p < cie_content_start + cie_padded_len) {
    *p++ = kDW_CFA_nop;
  }

  *reinterpret_cast<uint32_t*>(cie_length_ptr) =
      static_cast<uint32_t>(p - cie_content_start);

  // === FDE (Frame Description Entry) ===
  uint8_t* fde_length_ptr = p;
  p += 4;

  uint8_t* fde_content_start = p;

  // CIE pointer.
  *reinterpret_cast<uint32_t*>(p) = static_cast<uint32_t>(p - cie_start);
  p += 4;
#if !XE_PLATFORM_AX360E
  // PC begin (pc-relative).
  uint8_t* pc_begin_execute_addr =
      unwind_execute_base + (p - unwind_entry_address);
  *reinterpret_cast<int32_t*>(p) =
      static_cast<int32_t>(reinterpret_cast<intptr_t>(code_execute_address) -
                           reinterpret_cast<intptr_t>(pc_begin_execute_addr));
  p += 4;

  // PC range.
  *reinterpret_cast<uint32_t*>(p) =
      static_cast<uint32_t>(func_info.code_size.total);
  p += 4;

    // Augmentation data length = 0.
    p += WriteULEB128(p, 0);
#else
    // PC begin: absolute address, 8 bytes
    memcpy(p, &code_execute_address, 8);
    p += 8;

    // PC range: 8 bytes
    uint64_t range=func_info.code_size.total;
    memcpy(p, &range, 8);
    p += 8;
    // Augmentation data length = 4.
    p += WriteULEB128(p, 4);
    memset(p,0, 4);
    p += 4;
#endif

  // FDE instructions.
  if (func_info.stack_size > 0) {
    // Advance to the instruction after the stack allocation.
    size_t alloc_offset = func_info.prolog_stack_alloc_offset;
    if (alloc_offset > 0) {
      // ARM64 code alignment factor is 4, so divide by 4.
      uint32_t factored_offset = static_cast<uint32_t>(alloc_offset / 4);
      if (factored_offset < 64) {
        *p++ = 0x40 | static_cast<uint8_t>(factored_offset);
      } else if (factored_offset < 256) {
        *p++ = kDW_CFA_advance_loc1;
        *p++ = static_cast<uint8_t>(factored_offset);
      } else {
        *p++ = kDW_CFA_advance_loc2;
        *reinterpret_cast<uint16_t*>(p) =
            static_cast<uint16_t>(factored_offset);
        p += 2;
      }
    }

    // DW_CFA_def_cfa_offset: CFA = SP + stack_size.
    *p++ = kDW_CFA_def_cfa_offset;
    p += WriteULEB128(p, func_info.stack_size);

    if (func_info.stack_size == StackLayout::THUNK_STACK_SIZE) {
      // Thunk: encode all callee-saved register save locations.
      // See a64_stack_layout.h for the layout.
      size_t cfa = func_info.stack_size;  // 224

      // GPRs: x19-x28 saved as stp pairs at sp+0x00..0x48
      *p++ = 0x80 | kDwarfRegX19;
      p += WriteULEB128(p, (cfa - 0x000) / 8);
      *p++ = 0x80 | kDwarfRegX20;
      p += WriteULEB128(p, (cfa - 0x008) / 8);
      *p++ = 0x80 | kDwarfRegX21;
      p += WriteULEB128(p, (cfa - 0x010) / 8);
      *p++ = 0x80 | kDwarfRegX22;
      p += WriteULEB128(p, (cfa - 0x018) / 8);
      *p++ = 0x80 | kDwarfRegX23;
      p += WriteULEB128(p, (cfa - 0x020) / 8);
      *p++ = 0x80 | kDwarfRegX24;
      p += WriteULEB128(p, (cfa - 0x028) / 8);
      *p++ = 0x80 | kDwarfRegX25;
      p += WriteULEB128(p, (cfa - 0x030) / 8);
      *p++ = 0x80 | kDwarfRegX26;
      p += WriteULEB128(p, (cfa - 0x038) / 8);
      *p++ = 0x80 | kDwarfRegX27;
      p += WriteULEB128(p, (cfa - 0x040) / 8);
      *p++ = 0x80 | kDwarfRegX28;
      p += WriteULEB128(p, (cfa - 0x048) / 8);
      // x29 (FP) and x30 (LR) at sp+0x050, sp+0x058
      *p++ = 0x80 | kDwarfRegFP;
      p += WriteULEB128(p, (cfa - 0x050) / 8);
      *p++ = 0x80 | kDwarfRegLR;
      p += WriteULEB128(p, (cfa - 0x058) / 8);
      // NEON: d8-d15 saved as full q8-q15 via stp pairs at sp+0x060..0xDF.
      // Each Q is 16 bytes; d8-d15 are the low 64 bits of q8-q15.
      // stp q8,q9 at sp+0x060: d8=sp+0x060, d9=sp+0x070
      // stp q10,q11 at sp+0x080: d10=sp+0x080, d11=sp+0x090
      // stp q12,q13 at sp+0x0A0: d12=sp+0x0A0, d13=sp+0x0B0
      // stp q14,q15 at sp+0x0C0: d14=sp+0x0C0, d15=sp+0x0D0

#if XE_PLATFORM_AX360E
        *p++ = kDW_CFA_offset_extended;
        *p++ = kDwarfRegD8;
        p += WriteULEB128(p, (cfa - 0x060) / 8);
        *p++ = kDW_CFA_offset_extended;
        *p++ = kDwarfRegD9;
        p += WriteULEB128(p, (cfa - 0x070) / 8);
        *p++ = kDW_CFA_offset_extended;
        *p++ = kDwarfRegD10;
        p += WriteULEB128(p, (cfa - 0x080) / 8);
        *p++ = kDW_CFA_offset_extended;
        *p++ = kDwarfRegD11;
        p += WriteULEB128(p, (cfa - 0x090) / 8);
        *p++ = kDW_CFA_offset_extended;
        *p++ = kDwarfRegD12;
        p += WriteULEB128(p, (cfa - 0x0A0) / 8);
        *p++ = kDW_CFA_offset_extended;
        *p++ = kDwarfRegD13;
        p += WriteULEB128(p, (cfa - 0x0B0) / 8);
        *p++ = kDW_CFA_offset_extended;
        *p++ = kDwarfRegD14;
        p += WriteULEB128(p, (cfa - 0x0C0) / 8);
        *p++ = kDW_CFA_offset_extended;
        *p++ = kDwarfRegD15;
        p += WriteULEB128(p, (cfa - 0x0D0) / 8);
#else
      *p++ = 0x80 | kDwarfRegD8;
      p += WriteULEB128(p, (cfa - 0x060) / 8);
      *p++ = 0x80 | kDwarfRegD9;
      p += WriteULEB128(p, (cfa - 0x070) / 8);
      *p++ = 0x80 | kDwarfRegD10;
      p += WriteULEB128(p, (cfa - 0x080) / 8);
      *p++ = 0x80 | kDwarfRegD11;
      p += WriteULEB128(p, (cfa - 0x090) / 8);
      *p++ = 0x80 | kDwarfRegD12;
      p += WriteULEB128(p, (cfa - 0x0A0) / 8);
      *p++ = 0x80 | kDwarfRegD13;
      p += WriteULEB128(p, (cfa - 0x0B0) / 8);
      *p++ = 0x80 | kDwarfRegD14;
      p += WriteULEB128(p, (cfa - 0x0C0) / 8);
      *p++ = 0x80 | kDwarfRegD15;
      p += WriteULEB128(p, (cfa - 0x0D0) / 8);
#endif
    } else if (func_info.lr_save_offset > 0) {
      // Record where x30 (LR / return address) is saved.
      // Without this, the unwinder cannot find the return address.
      *p++ = 0x80 | kDwarfRegLR;
      p += WriteULEB128(p,
                        (func_info.stack_size - func_info.lr_save_offset) / 8);
    }
  }

  // Pad FDE.
  size_t fde_content_len = static_cast<size_t>(p - fde_content_start);
  size_t fde_padded_len = xe::round_up(fde_content_len, sizeof(void*));
  while (p < fde_content_start + fde_padded_len) {
    *p++ = kDW_CFA_nop;
  }

  *reinterpret_cast<uint32_t*>(fde_length_ptr) =
      static_cast<uint32_t>(p - fde_content_start);

  // === Terminator ===
  *reinterpret_cast<uint32_t*>(p) = 0;
  p += 4;

  assert_true(static_cast<size_t>(p - unwind_entry_address) <=
              kMaxUnwindInfoSize);
}

}  // namespace a64
}  // namespace backend
}  // namespace cpu
}  // namespace xe
