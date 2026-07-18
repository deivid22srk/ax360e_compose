// SPDX-License-Identifier: BSD-2-Clause
// Copyright © 2021 Billy Laws

#include <fstream>
#include <string>
#include <cstring>
#include <cerrno>
#include <sys/mman.h>
#include <unistd.h>
#include <android/log.h>
#include <adrenotools/bcenabler.h>
#include "gen/bcenabler_patch.h"

#define TAG "adrenotools/bcenabler"
#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO,  TAG, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN,  TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__)

enum adrenotools_bcn_type adrenotools_get_bcn_type(uint32_t major, uint32_t minor, uint32_t vendorId) {
    if (vendorId != 0x5143 || major != 512)
        return ADRENOTOOLS_BCN_INCOMPATIBLE;

    if (minor >= 514)
        return ADRENOTOOLS_BCN_BLOB;

    return ADRENOTOOLS_BCN_PATCH;
}

// Searches /proc/self/maps for the first free page after the given address
static void *find_free_page(uintptr_t address) {
    std::ifstream procMaps("/proc/self/maps");

    uintptr_t end{};

    for (std::string line; std::getline(procMaps, line); ) {
        std::size_t addressSeparator{line.find('-')};
        uintptr_t start{std::strtoull(line.substr(0, addressSeparator).c_str(), nullptr, 16)};

        if (end > address && start != end)
            return reinterpret_cast<void *>(end);

        end = std::strtoull(line.substr(addressSeparator + 1, line.find( ' ')).c_str(), nullptr, 16);
    }

    return nullptr;
}

static void *align_ptr(void *ptr) {
    return reinterpret_cast<void *>(reinterpret_cast<uintptr_t>(ptr) & ~(getpagesize() - 1));
}

bool adrenotools_patch_bcn(void *vkGetPhysicalDeviceFormatPropertiesFn) {
    union Branch {
        struct {
            int32_t offset : 26; //!< 26-bit branch offset
            uint8_t sig : 6;  //!< 6-bit signature (0x25 for linked, 0x5 for jump)
        };

        uint32_t raw{};
    };
    static_assert(sizeof(Branch) == 4, "Branch size is invalid");

    // ax360e real-fix: bound the pattern scans so they cannot walk past the
    // function boundary on drivers with a different layout. The previous code
    // used unbounded `while (... != sig) ptr++` loops; on Adreno 619 driver
    // 0x80212000 (Qualcomm BLOB, minor=530) the QGL format-conversion
    // function does NOT contain the expected `0x2a1f03e0` (MOV w0, wzr)
    // sentinel within its body, so the scan walked into unrelated driver
    // code, eventually matched a coincidental instruction, and the patch
    // trampoline got written to the WRONG location. adrenotools_patch_bcn
    // then returned `true` (because the scan "found" something) but BC1
    // optimalTilingFeatures stayed 0x0 — a silent failure that produced the
    // misleading "patch applied successfully / post-patch BC1 = 0x0" log
    // sequence we saw in the Far Cry 2 trace. With bounded scans we now
    // return false on drivers where the expected pattern is not present in
    // the function's prologue/epilogue window, allowing the caller to log
    // "patch failed" honestly and fall back without writing garbage.
    constexpr size_t kMaxBranchScanInsns = 256;     // 1 KB at 4 bytes/insn
    constexpr size_t kMaxClearResultScanInsns = 4096; // 16 KB at 4 bytes/insn

    // Find the nearest unmapped page where we can place patch code
    void *patchPage{find_free_page(reinterpret_cast<uintptr_t>(vkGetPhysicalDeviceFormatPropertiesFn))};
    if (!patchPage) {
        LOGE("patch_bcn: no free page near %p", vkGetPhysicalDeviceFormatPropertiesFn);
        return false;
    }

    // Map patch region
    void *ptr{mmap(patchPage, getpagesize(),  PROT_READ | PROT_WRITE | PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, 0, 0)};
    if (ptr != patchPage) {
        LOGE("patch_bcn: mmap(%p) failed — likely SELinux W^X enforcement", patchPage);
        return false;
    }

    // Allow reading from the blob's .text section since some devices enable ---X
    // Protect two pages just in case we happen to land on a page boundary
    if (mprotect(align_ptr(vkGetPhysicalDeviceFormatPropertiesFn), getpagesize() * 2, PROT_WRITE | PROT_READ | PROT_EXEC)) {
        LOGE("patch_bcn: mprotect(target fn) failed (errno=%d)", errno);
        return false;
    }

    // First branch in this function is targeted at the function we want to patch
    Branch *blInst{reinterpret_cast<Branch *>(vkGetPhysicalDeviceFormatPropertiesFn)};

    constexpr uint8_t BranchLinkSignature{0x25};

    // Search for first instruction with the BL signature — BOUNDED.
    Branch *blInstStart{blInst};
    Branch *blInstEnd{blInstStart + kMaxBranchScanInsns};
    while (blInst < blInstEnd && blInst->sig != BranchLinkSignature)
        blInst++;
    if (blInst >= blInstEnd) {
        // No BL found within the first 1 KB of the function — newer driver
        // layout; the patch cannot be applied safely here.
        LOGW("patch_bcn: BL signature 0x%02x not found within %zu insns of %p — newer driver layout, skipping",
             BranchLinkSignature, kMaxBranchScanInsns, blInstStart);
        return false;
    }

    // Internal QGL format conversion function that we need to patch
    uint32_t *convFormatFn{reinterpret_cast<uint32_t *>(blInst) + blInst->offset};

    // See mprotect call above
    // This time we also set PROT_WRITE so we can write our patch to the page
    if (mprotect(align_ptr(convFormatFn), getpagesize() * 2, PROT_WRITE | PROT_READ | PROT_EXEC)) {
        LOGE("patch_bcn: mprotect(convFormatFn=%p) failed (errno=%d)", convFormatFn, errno);
        return false;
    }

    // This would normally set the default result to 0 (error) in the format not found case
    constexpr uint32_t ClearResultSignature{0x2a1f03e0};

    // We replace it with a branch to our own extended if statement which adds in the extra things for BCn
    // BOUNDED scan — see kMaxClearResultScanInsns rationale above.
    uint32_t *clearResultPtr{convFormatFn};
    uint32_t *clearResultEnd{convFormatFn + kMaxClearResultScanInsns};
    while (clearResultPtr < clearResultEnd && *clearResultPtr != ClearResultSignature)
        clearResultPtr++;
    if (clearResultPtr >= clearResultEnd) {
        // The MOV w0, wzr sentinel was not found within 16 KB of convFormatFn
        // — newer driver layout, give up rather than writing the trampoline
        // to an unrelated location.
        LOGW("patch_bcn: ClearResultSignature 0x%08x not found within %zu insns of %p — newer driver layout, skipping",
             ClearResultSignature, kMaxClearResultScanInsns, convFormatFn);
        return false;
    }

    // Ensure we don't write out of bounds
    if (PatchRawData_size > getpagesize()) {
        LOGE("patch_bcn: PatchRawData_size=%zu > page=%d — patch payload too big",
             PatchRawData_size, getpagesize());
        return false;
    }

    // Copy the patch function to our mapped page
    memcpy(patchPage, PatchRawData, PatchRawData_size);

    // Fixup the patch code so it correctly returns back to the driver after running
    constexpr uint32_t PatchReturnFixupMagic{0xffffffff};
    constexpr uint8_t BranchSignature{0x5};

    uint32_t *fixupTargetPtr{clearResultPtr + 1};
    auto *fixupPtr{reinterpret_cast<uint32_t *>(patchPage)};
    for (long unsigned int i{}; i < (PatchRawData_size / sizeof(uint32_t)); i++, fixupPtr++) {
        if (*fixupPtr == PatchReturnFixupMagic) {
            Branch branchToDriver{
                {
                    .offset = static_cast<int32_t>((reinterpret_cast<intptr_t>(fixupTargetPtr) - reinterpret_cast<intptr_t>(fixupPtr)) / sizeof(int32_t)),
                    .sig = BranchSignature,
                }
            };

            *fixupPtr = branchToDriver.raw;
        }
    }

    Branch branchToPatch{
        {
            .offset = static_cast<int32_t>((reinterpret_cast<intptr_t>(patchPage) - reinterpret_cast<intptr_t>(clearResultPtr)) / sizeof(int32_t)),
            .sig = BranchSignature,
        }
    };

    *clearResultPtr = branchToPatch.raw;

    asm volatile("ISB");

    // Done!
    LOGI("patch_bcn: applied BCn patch — trampoline at %p, target=%p, convFormatFn=%p",
         patchPage, vkGetPhysicalDeviceFormatPropertiesFn, convFormatFn);
    return true;
}
