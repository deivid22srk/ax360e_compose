/*******************************************************************************
 * Copyright 2019-2023 FUJITSU LIMITED
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
#pragma once

#ifndef _XBYAK_AARCH64_LABEL_
#define _XBYAK_AARCH64_LABEL_

#include "xbyak_aarch64_code_array.h"
#include "xbyak_aarch64_inner.h"

struct JmpLabel {
  // type of partially applied function for encoding
  typedef std::function<uint32_t(int64_t)> EncFunc;
  size_t endOfJmp; /* offset from top to the end address of jmp */
  EncFunc encFunc;
  explicit JmpLabel(const EncFunc &encFunc, size_t endOfJmp = 0) : endOfJmp(endOfJmp), encFunc(encFunc) {}
};

class LabelManager;

class Label {
  mutable LabelManager *mgr;
  mutable int id;
  friend class LabelManager;

public:
  Label() : mgr(nullptr), id(0) {}
  Label(const Label &rhs);
  Label &operator=(const Label &rhs);
  ~Label();
  void clear() {
    mgr = nullptr;
    id = 0;
  }
  int getId() const { return id; }
  const uint32_t *getAddress() const;
};

class LabelManager {

  // for Label class
  struct ClabelVal {
    ClabelVal(size_t offset = 0) : offset(offset), refCount(1) {}
    size_t offset;
    int refCount;
  };
  typedef std::unordered_map<int, ClabelVal> ClabelDefList;
  typedef std::unordered_multimap<int, const JmpLabel> ClabelUndefList;
  typedef std::unordered_set<Label *> LabelPtrList;

  CodeArray *base_;
  // global : stateList_.front(), local : stateList_.back()
  mutable int labelId_;
  ClabelDefList clabelDefList_;
  ClabelUndefList clabelUndefList_;
  LabelPtrList labelPtrList_;

  int getId(const Label &label) const {
    if (label.id == 0)
      label.id = labelId_++;
    return label.id;
  }
  template <class DefList, class UndefList, class T> void define_inner(DefList &defList, UndefList &undefList, const T &labelId, size_t addrOffset) {
    // [LONG BRANCH VENEER] When a forward branch reference exceeds the
    // encoding range (CBZ/CBNZ/B.cond ±1MB, TBZ/TBNZ ±32KB), xbyak emits
    // the branch with offset 0 (label not yet defined). When the label is
    // bound, the offset is resolved — and if it's too far, the old code
    // threw ERR_LABEL_IS_TOO_FAR, crashing the process.
    //
    // Fix: emit an 8-byte veneer trampoline at the label position, then
    // rewrite the original branch to B the veneer (±128MB range — always
    // reachable for ≤8MB functions). The veneer contains:
    //   [veneer+0]: the original conditional branch, targeting veneer+8
    //               (the actual code, only 8 bytes away — always in range)
    //   [veneer+4]: B back to the instruction after the original branch
    //               (for the "not taken" path)
    //   [veneer+8]: ... actual code starts here ...
    //
    // Phase 1: Determine if a veneer is needed and handle too-far refs.
    // Phase 2: Add label and resolve all remaining (non-too-far) refs.

    bool needs_veneer = false;
    size_t veneer_size = 0;  // in dd units (instructions)

    // --- Phase 1: Scan for too-far references, emit veneer, rewrite them ---
    // Collect too-far references in a set so Phase 2 can skip them.
    std::unordered_set<size_t> handled_offsets;  // endOfJmp offsets already rewritten

    for (auto itr = undefList.begin(); itr != undefList.end(); ++itr) {
      if (itr->first != labelId) continue;
      const JmpLabel *jmp = &itr->second;
      const size_t orig_offset = jmp->endOfJmp;
      int64_t labelOffset = (addrOffset - orig_offset) * CSIZE;
      try {
        jmp->encFunc(labelOffset);
      } catch (const Error& e) {
        if ((int)e != ERR_LABEL_IS_TOO_FAR) throw;

        needs_veneer = true;
        if (veneer_size == 0) veneer_size = 2;  // 2 instructions = 8 bytes

        // Emit veneer at addrOffset (only once).
        if (base_->getSize() / CSIZE == addrOffset) {
          // Veneer instruction 1: the original conditional branch targeting
          // the actual code (addrOffset + 8 = veneer_size * CSIZE bytes away).
          int64_t veneer_cond_offset = (int64_t)(veneer_size * CSIZE);
          uint32_t veneer_cond = jmp->encFunc(veneer_cond_offset);
          base_->dd(veneer_cond);

          // Veneer instruction 2: unconditional B back to the instruction
          // immediately after the original branch (orig_offset + 1).
          int64_t back_offset = (int64_t)((orig_offset + 1) - (addrOffset + veneer_size)) * (int64_t)CSIZE;
          uint32_t b_imm26 = static_cast<uint32_t>((back_offset >> 2) & 0x3FFFFFF);
          uint32_t back_code = 0x14000000u | b_imm26;  // B back
          base_->dd(back_code);
        }

        // Rewrite the original branch instruction to B the veneer (±128MB).
        int64_t branch_to_veneer = (int64_t)((int64_t)addrOffset - (int64_t)orig_offset) * (int64_t)CSIZE;
        uint32_t b_imm26 = static_cast<uint32_t>((branch_to_veneer >> 2) & 0x3FFFFFF);
        uint32_t b_code = 0x14000000u | b_imm26;  // B veneer
        base_->rewrite(orig_offset, b_code);

        // Mark this offset as handled so Phase 2 skips it.
        handled_offsets.insert(orig_offset);
      }
    }

    // --- Phase 2: Add label and resolve all remaining undefined refs ---
    size_t actual_label_offset = addrOffset + veneer_size;

    typename DefList::value_type item(labelId, actual_label_offset);
    std::pair<typename DefList::iterator, bool> ret = defList.insert(item);
    if (!ret.second)
      throw Error(ERR_LABEL_IS_REDEFINED);

    for (;;) {
      typename UndefList::iterator itr = undefList.find(labelId);
      if (itr == undefList.end())
        break;
      const JmpLabel *jmp = &itr->second;
      const size_t offset = jmp->endOfJmp;

      // Skip references that were already rewritten with B-to-veneer
      // in Phase 1. They should NOT be overwritten.
      if (handled_offsets.count(offset)) {
        undefList.erase(itr);
        continue;
      }

      int64_t labelOffset = (actual_label_offset - offset) * CSIZE;
      uint32_t disp;
      try {
        disp = jmp->encFunc(labelOffset);
      } catch (const Error& e) {
        if ((int)e != ERR_LABEL_IS_TOO_FAR) throw;
        throw;
      }
      base_->rewrite(offset, disp);
      undefList.erase(itr);
    }
  }
  template <class DefList, class T> bool getOffset_inner(const DefList &defList, size_t *offset, const T &label) const {
    typename DefList::const_iterator i = defList.find(label);
    if (i == defList.end())
      return false;
    *offset = i->second.offset;
    return true;
  }
  friend class Label;
  void incRefCount(int id, Label *label) {
    clabelDefList_[id].refCount++;
    labelPtrList_.insert(label);
  }
  void decRefCount(int id, Label *label) {
    labelPtrList_.erase(label);
    ClabelDefList::iterator i = clabelDefList_.find(id);
    if (i == clabelDefList_.end())
      return;
    if (i->second.refCount == 1) {
      clabelDefList_.erase(id);
    } else {
      --i->second.refCount;
    }
  }
  template <class T> bool hasUndefinedLabel_inner(const T &list) const {
#ifndef NDEBUG
    for (typename T::const_iterator i = list.begin(); i != list.end(); ++i) {
      std::cerr << "undefined label:" << i->first << std::endl;
    }
#endif
    return !list.empty();
  }
  // detach all labels linked to LabelManager
  void resetLabelPtrList() {
    for (LabelPtrList::iterator i = labelPtrList_.begin(), ie = labelPtrList_.end(); i != ie; ++i) {
      (*i)->clear();
    }
    labelPtrList_.clear();
  }

public:
  LabelManager() { reset(); }
  ~LabelManager() { resetLabelPtrList(); }
  void reset() {
    base_ = 0;
    labelId_ = 1;
    clabelDefList_.clear();
    clabelUndefList_.clear();
    resetLabelPtrList();
  }

  void set(CodeArray *base) { base_ = base; }

  void defineClabel(Label &label) {
    define_inner(clabelDefList_, clabelUndefList_, getId(label), base_->size_);
    label.mgr = this;
    labelPtrList_.insert(&label);
  }
  void assign(Label &dst, const Label &src) {
    ClabelDefList::const_iterator i = clabelDefList_.find(src.id);
    if (i == clabelDefList_.end())
      throw Error(ERR_LABEL_ISNOT_SET_BY_L);
    define_inner(clabelDefList_, clabelUndefList_, dst.id, i->second.offset);
    dst.mgr = this;
    labelPtrList_.insert(&dst);
  }
  bool getOffset(size_t *offset, const Label &label) const { return getOffset_inner(clabelDefList_, offset, getId(label)); }
  void addUndefinedLabel(const Label &label, const JmpLabel &jmp) { clabelUndefList_.insert(ClabelUndefList::value_type(label.id, jmp)); }
  bool hasUndefClabel() const { return hasUndefinedLabel_inner(clabelUndefList_); }
  const uint8_t *getCode() const { return base_->getCode(); }
  bool isReady() const { return !base_->isAutoGrow() || base_->isCalledCalcJmpAddress(); }
};

inline Label::Label(const Label &rhs) {
  id = rhs.id;
  mgr = rhs.mgr;
  if (mgr)
    mgr->incRefCount(id, this);
}
inline Label &Label::operator=(const Label &rhs) {
  if (id)
    throw Error(ERR_LABEL_IS_ALREADY_SET_BY_L);
  id = rhs.id;
  mgr = rhs.mgr;
  if (mgr)
    mgr->incRefCount(id, this);
  return *this;
}
inline Label::~Label() {
  if (id && mgr)
    mgr->decRefCount(id, this);
}
#ifdef __GNUC__
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wcast-align"
#endif
inline const uint32_t *Label::getAddress() const {
  if (mgr == 0 || !mgr->isReady())
    return 0;
  size_t offset;
  if (!mgr->getOffset(&offset, *this))
    return 0;
  // getCode() is always a multiple of 4
  return (const uint32_t *)mgr->getCode() + offset;
}
#ifdef __GNUC__
#pragma GCC diagnostic pop
#endif

#endif
