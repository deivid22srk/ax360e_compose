// SPDX-License-Identifier: WTFPL
//
// xe_multi_disc.cpp
//
// Implementation of MultiDiscManager — see xe_multi_disc.h for the high-level
// design.
//
// ISO parsing path
// ----------------
// Every Xbox 360 ISO (XGD1/XGD2/XGD3) is an XDVDFS (Xbox Disc File System)
// image. The layout is:
//
//   game_offset      : start of the game partition (one of 0x0, 0xFB20,
//                      0x20600, 0x2080000, 0xFD90000 — these correspond to
//                      the different XGD region layouts)
//   game_offset + 32*2048 : sector 32 — contains the magic
//                      "MICROSOFT*XBOX*MEDIA" (20 bytes) followed by the
//                      root directory descriptor (root_sector, root_size)
//   game_offset + root_sector*2048 : root directory table
//
// The root directory is a binary tree of 4-byte-aligned directory entries:
//
//   offset  size  field
//   0x00    2     node_l (left subtree entry ordinal, 0 = no left child)
//   0x02    2     node_r (right subtree entry ordinal, 0 = no right child)
//   0x04    4     sector (file/folder data offset in sectors, from
//                       game_offset)
//   0x08    4     length (file size in bytes, or subtree size for folders)
//   0x0C    1     attributes (bit 0x10 = directory)
//   0x0D    1     name_length (bytes, not including null terminator)
//   0x0E    var   name (ASCII, NOT null-terminated)
//   pad to 4-byte alignment
//
// We walk the tree (depth-first, left then right) looking for an entry
// named "default.xex" (case-insensitive). When found, we read its sector
// and length, then mmap game_offset + sector*2048 to read the XEX header.
//
// XEX header parsing
// ------------------
// The XEX header (xex2_header in xenia/kernel/util/xex2_info.h) starts with
// magic 'XEX2' (0x58455832, big-endian). At offset 0x14 there's
// header_count (number of optional headers), and at offset 0x18 there's
// the start of the optional headers array. Each optional header is 8 bytes:
//
//   offset  size  field
//   0x00    4     key (one of xex2_header_keys; we want
//                       XEX_HEADER_EXECUTION_INFO = 0x00040006)
//   0x04    4     value or offset (depends on key). For
//                       XEX_HEADER_EXECUTION_INFO, this is an offset
//                       (relative to the start of the xex2_header) pointing
//                       to an xex2_opt_execution_info struct.
//
// The xex2_opt_execution_info struct (24 bytes) contains:
//
//   offset  size  field
//   0x00    4     media_id (shared by all discs of the same game)
//   0x04    4     version_value
//   0x08    4     base_version_value
//   0x0C    4     title_id
//   0x10    1     platform
//   0x11    1     executable_table
//   0x12    1     disc_number (1-indexed)
//   0x13    1     disc_count (total discs in the release)
//   0x14    4     savegame_id
//
// We only need media_id, disc_number, and disc_count, so we can stop
// parsing once we find that header.

#include "xe_multi_disc.h"

#include <algorithm>
#include <cctype>
#include <cstring>
#include <sys/mman.h>

#include "xenia/base/logging.h"
#include "xenia/base/mapped_memory.h"
#include "xenia/base/memory.h"
#include "xenia/kernel/util/xex2_info.h"

namespace xe {

namespace {

constexpr size_t kSectorSize = 2048;
constexpr size_t kRootSectorIndex = 32;
constexpr size_t kXdvdfsMagicLen = 20;
const char kXdvdfsMagic[kXdvdfsMagicLen + 1] = "MICROSOFT*XBOX*MEDIA";

// Likely game partition start offsets, in priority order. These correspond
// to the different XGD region layouts (XGD1, XGD2, XGD3, and the "raw"
// extracted image with no XGD wrapper). Must match the list in
// xe_saf_disc_image_device.cpp.
constexpr size_t kLikelyGameOffsets[] = {
    0x00000000, 0x0000FB20, 0x00020600, 0x02080000, 0x0FD90000,
};

// Case-insensitive ASCII string compare of |n| bytes.
bool iequals_n(const char* a, const char* b, size_t n) {
  for (size_t i = 0; i < n; ++i) {
    if (std::tolower(static_cast<unsigned char>(a[i])) !=
        std::tolower(static_cast<unsigned char>(b[i]))) {
      return false;
    }
  }
  return true;
}

// Reads a little-endian uint32_t from a possibly-unaligned pointer.
uint32_t load_le_u32(const uint8_t* p) {
  return static_cast<uint32_t>(p[0]) |
         (static_cast<uint32_t>(p[1]) << 8) |
         (static_cast<uint32_t>(p[2]) << 16) |
         (static_cast<uint32_t>(p[3]) << 24);
}

uint16_t load_le_u16(const uint8_t* p) {
  return static_cast<uint16_t>(p[0]) |
         (static_cast<uint16_t>(p[1]) << 8);
}

// Tries to locate the XDVDFS game partition offset by checking for the
// magic at sector 32 starting from each candidate offset. Returns
// SIZE_MAX if no match was found.
size_t FindGameOffset(const uint8_t* data, size_t size) {
  for (size_t off : kLikelyGameOffsets) {
    size_t magic_off = off + (kRootSectorIndex * kSectorSize);
    if (magic_off + kXdvdfsMagicLen > size) {
      continue;
    }
    if (std::memcmp(data + magic_off, kXdvdfsMagic, kXdvdfsMagicLen) == 0) {
      return off;
    }
  }
  return SIZE_MAX;
}

// One directory entry in the XDVDFS binary tree. The on-disk layout is
// tightly packed and unaligned, so we copy fields out into a struct.
struct DirEntry {
  uint16_t node_l;
  uint16_t node_r;
  uint32_t sector;
  uint32_t length;
  uint8_t attributes;
  uint8_t name_length;
  const char* name;  // pointer into the underlying mmap (no copy)
  size_t entry_size;  // total bytes consumed by this entry (for alignment)
};

// Reads a single directory entry from |buffer| at |ordinal * 4|. Returns
// false if the read would go out of bounds.
bool ReadDirEntry(const uint8_t* buffer, size_t buffer_size,
                  uint16_t ordinal, DirEntry* out) {
  // Each directory entry is 4-byte aligned by ordinal.
  size_t entry_off = static_cast<size_t>(ordinal) * 4;
  if (entry_off + 14 > buffer_size) {
    return false;
  }
  out->node_l = load_le_u16(buffer + entry_off + 0);
  out->node_r = load_le_u16(buffer + entry_off + 2);
  out->sector = load_le_u32(buffer + entry_off + 4);
  out->length = load_le_u32(buffer + entry_off + 8);
  out->attributes = buffer[entry_off + 12];
  out->name_length = buffer[entry_off + 13];
  out->name = reinterpret_cast<const char*>(buffer + entry_off + 14);
  // Total entry size = 14 + name_length, rounded up to 4-byte alignment.
  size_t raw_size = 14 + out->name_length;
  out->entry_size = (raw_size + 3) & ~static_cast<size_t>(3);
  return true;
}

// Walks the directory tree starting at |ordinal| looking for a file named
// |target_name| (case-insensitive). On success returns true and writes
// |out_sector|/|out_length| pointing to the file's data within the ISO
// (relative to game_offset). Recursion depth is bounded by the tree
// height (in practice <20 levels).
//
// |visited_bitmap| is a 256-bit bitmap used to prevent infinite loops on
// malformed images that contain cycles. XDVDFS ordinals are 16-bit but in
// practice directories have <256 entries; if a directory has more, we
// fall back to a depth limit.
bool FindFileInTree(const uint8_t* buffer, size_t buffer_size,
                    uint16_t ordinal, const char* target_name,
                    size_t target_name_len, uint32_t* out_sector,
                    uint32_t* out_length, int depth = 0,
                    uint64_t visited_bitmap[4] = nullptr) {
  if (depth > 32) {
    return false;  // sanity limit
  }
  if (ordinal == 0) {
    return false;
  }

  // Cycle detection — mark ordinal as visited.
  if (visited_bitmap != nullptr) {
    if (ordinal < 256) {
      uint64_t mask = 1ULL << (ordinal & 63);
      if (visited_bitmap[ordinal >> 6] & mask) {
        return false;  // already visited — cycle
      }
      visited_bitmap[ordinal >> 6] |= mask;
    }
  }

  DirEntry e;
  if (!ReadDirEntry(buffer, buffer_size, ordinal, &e)) {
    return false;
  }

  // Check this entry (in-order traversal — left, self, right).
  if (e.node_l != 0) {
    if (FindFileInTree(buffer, buffer_size, e.node_l, target_name,
                      target_name_len, out_sector, out_length, depth + 1,
                      visited_bitmap)) {
      return true;
    }
  }

  // Self — only match files (not directories).
  if (!(e.attributes & 0x10) && e.name_length == target_name_len &&
      iequals_n(e.name, target_name, target_name_len)) {
    *out_sector = e.sector;
    *out_length = e.length;
    return true;
  }

  if (e.node_r != 0) {
    if (FindFileInTree(buffer, buffer_size, e.node_r, target_name,
                      target_name_len, out_sector, out_length, depth + 1,
                      visited_bitmap)) {
      return true;
    }
  }

  return false;
}

// Parses the xex2_header at |xex_data| / |xex_size| and writes
// media_id/disc_number/disc_count into the out params. Returns false if
// the XEX is malformed or doesn't contain an EXECUTION_INFO header.
bool ParseXexExecutionInfo(const uint8_t* xex_data, size_t xex_size,
                           uint32_t* out_media_id,
                           uint32_t* out_disc_number,
                           uint32_t* out_disc_count) {
  if (xex_data == nullptr || xex_size < sizeof(xex2_header)) {
    return false;
  }
  // xex2_header is big-endian. hdr->magic is a xe::be<uint32_t>, which
  // auto-converts to the native representation when read. We compare the
  // native value against make_fourcc(...) (also native), so this works on
  // both little- and big-endian hosts.
  //
  // Magic values: 'XEX2' (v2, Xbox 360), 'XEX1' (v1), 'XEX0' (v0). We
  // accept all three — the optional header layout is the same.
  const xex2_header* hdr =
      reinterpret_cast<const xex2_header*>(xex_data);
  uint32_t magic = static_cast<uint32_t>(hdr->magic);
  constexpr uint32_t kXex2Magic = make_fourcc('X', 'E', 'X', '2');
  constexpr uint32_t kXex1Magic = make_fourcc('X', 'E', 'X', '1');
  constexpr uint32_t kXex0Magic = make_fourcc('X', 'E', 'X', '0');
  if (magic != kXex2Magic && magic != kXex1Magic && magic != kXex0Magic) {
    return false;
  }

  uint32_t header_count = static_cast<uint32_t>(hdr->header_count);
  // The optional headers array starts immediately after the fixed header
  // (24 bytes = 6 * sizeof(uint32_t)). Each opt header is 8 bytes.
  for (uint32_t i = 0; i < header_count; ++i) {
    size_t opt_off = sizeof(uint32_t) * 6 + i * sizeof(xex2_opt_header);
    if (opt_off + sizeof(xex2_opt_header) > xex_size) {
      break;
    }
    const xex2_opt_header* cur =
        reinterpret_cast<const xex2_opt_header*>(xex_data + opt_off);
    uint32_t key = static_cast<uint32_t>(cur->key);
    if (key == XEX_HEADER_EXECUTION_INFO) {
      // For EXECUTION_INFO, the value field is an offset (relative to the
      // start of the xex2_header) pointing to an xex2_opt_execution_info.
      uint32_t exec_off = static_cast<uint32_t>(cur->offset);
      if (exec_off + sizeof(xex2_opt_execution_info) > xex_size) {
        return false;
      }
      const xex2_opt_execution_info* exec =
          reinterpret_cast<const xex2_opt_execution_info*>(
              xex_data + exec_off);
      *out_media_id = static_cast<uint32_t>(exec->media_id);
      *out_disc_number = exec->disc_number;
      *out_disc_count = exec->disc_count;
      return true;
    }
  }

  return false;
}

}  // namespace

MultiDiscManager& MultiDiscManager::Instance() {
  static MultiDiscManager instance;
  return instance;
}

bool MultiDiscManager::ParseIsoDiscInfo(
    const std::unique_ptr<DocumentFile>& file, uint32_t* out_media_id,
    uint32_t* out_disc_number, uint32_t* out_disc_count,
    std::string* out_name) {
  if (!file || !out_media_id || !out_disc_number || !out_disc_count) {
    return false;
  }

  // Get a file descriptor we can mmap.
  int fd = DocumentFile::open_fd(file);
  if (fd == -1) {
    XELOGW("MultiDisc: open_fd failed for {}", file->getName());
    return false;
  }

  auto mmap = xe::MappedMemory::OpenForUnixFd(fd);
  if (!mmap) {
    XELOGW("MultiDisc: mmap failed for {}", file->getName());
    return false;
  }

  const uint8_t* data = mmap->data();
  size_t size = mmap->size();

  // 1. Find the game partition offset.
  size_t game_offset = FindGameOffset(data, size);
  if (game_offset == SIZE_MAX) {
    XELOGW("MultiDisc: no XDVDFS magic found in {} (size={})",
           file->getName(), size);
    return false;
  }

  // 2. Read the FS descriptor at sector 32 to get root_sector / root_size.
  size_t fs_off = game_offset + (kRootSectorIndex * kSectorSize);
  if (fs_off + 28 > size) {
    return false;
  }
  // Layout at sector 32 (after the 20-byte magic):
  //   +20: uint32 root_sector
  //   +24: uint32 root_size
  uint32_t root_sector = load_le_u32(data + fs_off + 20);
  uint32_t root_size = load_le_u32(data + fs_off + 24);
  if (root_size < 13 || root_size > 32 * 1024 * 1024) {
    XELOGW("MultiDisc: suspicious root_size {} in {}", root_size,
           file->getName());
    return false;
  }

  // 3. Walk the root directory tree looking for default.xex.
  size_t root_off = game_offset + (static_cast<size_t>(root_sector) *
                                   kSectorSize);
  if (root_off + root_size > size) {
    XELOGW("MultiDisc: root directory out of bounds in {}", file->getName());
    return false;
  }

  const char kTargetName[] = "default.xex";
  const size_t kTargetNameLen = sizeof(kTargetName) - 1;
  uint32_t xex_sector = 0;
  uint32_t xex_length = 0;
  uint64_t visited[4] = {0, 0, 0, 0};
  if (!FindFileInTree(data + root_off, root_size, 0, kTargetName,
                      kTargetNameLen, &xex_sector, &xex_length, 0,
                      visited)) {
    XELOGW("MultiDisc: default.xex not found in root of {}",
           file->getName());
    return false;
  }

  // 4. Read the XEX header at game_offset + xex_sector * sector_size.
  size_t xex_off = game_offset + (static_cast<size_t>(xex_sector) *
                                  kSectorSize);
  if (xex_off + sizeof(xex2_header) > size) {
    return false;
  }
  // We only need to read enough to find the EXECUTION_INFO header. The
  // optional headers are right after the fixed xex2_header (24 bytes),
  // and each opt header is 8 bytes. Reading the first 4 KiB is enough
  // for any realistic XEX (XEX optional headers are usually in the first
  // 1-2 KiB).
  size_t xex_readable = std::min<size_t>(xex_length, 4096);
  if (xex_off + xex_readable > size) {
    xex_readable = size - xex_off;
  }
  if (xex_readable < sizeof(xex2_header)) {
    return false;
  }

  uint32_t media_id = 0, disc_number = 0, disc_count = 0;
  if (!ParseXexExecutionInfo(data + xex_off, xex_readable, &media_id,
                             &disc_number, &disc_count)) {
    XELOGW("MultiDisc: failed to parse XEX execution info from {}",
           file->getName());
    return false;
  }

  *out_media_id = media_id;
  *out_disc_number = disc_number;
  *out_disc_count = disc_count;
  if (out_name) {
    *out_name = file->getName();
  }
  return true;
}

void MultiDiscManager::RegisterLaunchedGame(
    std::unique_ptr<DocumentFile> game_file_clone, uint32_t current_media_id,
    uint32_t current_disc_number) {
  std::lock_guard<std::mutex> lock(mutex_);
  disc_map_.clear();
  active_media_id_ = current_media_id;

  if (!game_file_clone) {
    XELOGW("MultiDisc: RegisterLaunchedGame called with null file");
    return;
  }

  // Capture the launched file's name and parent directory BEFORE moving it
  // into the map — once moved, we'd need to look it up again.
  std::string launched_name = game_file_clone->getName();
  auto parent = game_file_clone->getParentFile();

  // Always register the launched disc itself so the manager's state is
  // self-consistent (the game might re-request the current disc to
  // "confirm" it).
  DiscInfo self;
  self.media_id = current_media_id;
  self.disc_number = current_disc_number;
  self.disc_count = 0;  // unknown without parsing the launched file
  self.name = launched_name;
  self.file = std::move(game_file_clone);
  XELOGI("MultiDisc: registered launched disc #{} (media_id={:08X}) '{}'",
         self.disc_number, self.media_id, self.name);
  disc_map_[{current_media_id, current_disc_number}] = std::move(self);

  // Walk the parent directory looking for sibling ISO files. We do this
  // on the calling thread (typically the emulator thread, via the
  // on_launch listener). This is potentially slow if the user has a large
  // game directory, so we log progress.
  if (!parent) {
    XELOGW("MultiDisc: no parent directory (root SAF tree?) — skipping scan");
    return;
  }

  auto siblings = parent->listFiles();
  XELOGI("MultiDisc: scanning {} siblings in parent dir", siblings.size());

  uint32_t registered = 0;
  for (auto& sibling : siblings) {
    if (!sibling || !sibling->isFile()) {
      continue;
    }
    std::string name = sibling->getName();
    // Case-insensitive extension check.
    auto ieq = [](const std::string& s, std::string_view ext) {
      return s.size() >= ext.size() &&
             std::equal(s.end() - ext.size(), s.end(), ext.begin(),
                        ext.end(),
                        [](char a, char b) {
                          return std::tolower(static_cast<unsigned char>(a)) ==
                                 std::tolower(static_cast<unsigned char>(b));
                        });
    };
    if (!ieq(name, ".iso")) {
      // We currently only auto-scan .iso files. ZAR (zarchive) and STFS
      // containers are skipped — they're rare for multi-disc releases and
      // require a different parser.
      continue;
    }

    // Don't re-scan the launched file (already registered above).
    if (name == launched_name) {
      continue;
    }

    uint32_t media_id = 0, disc_number = 0, disc_count = 0;
    if (!ParseIsoDiscInfo(sibling, &media_id, &disc_number, &disc_count,
                          nullptr)) {
      continue;
    }

    // Only register discs that share the launched game's media_id.
    if (media_id != current_media_id) {
      XELOGI("MultiDisc: skipping '{}' (media_id={:08X} != launched {:08X})",
             name, media_id, current_media_id);
      continue;
    }

    DiscInfo info;
    info.media_id = media_id;
    info.disc_number = disc_number;
    info.disc_count = disc_count;
    info.name = name;
    info.file = std::move(sibling);
    XELOGI("MultiDisc: registered sibling disc #{} (media_id={:08X}) '{}'",
           info.disc_number, info.media_id, info.name);
    disc_map_[{media_id, disc_number}] = std::move(info);
    ++registered;
  }

  XELOGI("MultiDisc: scan complete — {} sibling discs registered "
         "(+1 launched = {} total)", registered, disc_map_.size());
}

std::unique_ptr<DocumentFile> MultiDiscManager::FindDisc(uint32_t media_id,
                                                         uint32_t disc_number) {
  std::lock_guard<std::mutex> lock(mutex_);
  auto it = disc_map_.find({media_id, disc_number});
  if (it == disc_map_.end() || !it->second.file) {
    return nullptr;
  }
  // Return a fresh clone so the caller can consume it via std::unique_ptr
  // without invalidating our stored entry.
  return DocumentFile::clone(it->second.file);
}

bool MultiDiscManager::HasDiscs() {
  std::lock_guard<std::mutex> lock(mutex_);
  return !disc_map_.empty();
}

uint32_t MultiDiscManager::GetDiscCount(uint32_t media_id) {
  std::lock_guard<std::mutex> lock(mutex_);
  uint32_t count = 0;
  for (const auto& [key, info] : disc_map_) {
    if (key.first == media_id) {
      ++count;
    }
  }
  return count;
}

void MultiDiscManager::Clear() {
  std::lock_guard<std::mutex> lock(mutex_);
  disc_map_.clear();
  active_media_id_ = 0;
}

std::string MultiDiscManager::Describe() {
  std::lock_guard<std::mutex> lock(mutex_);
  std::string out = "MultiDiscManager: ";
  out += std::to_string(disc_map_.size()) + " disc(s) registered";
  for (const auto& [key, info] : disc_map_) {
    out += "\n  media_id=" + fmt::format("{:08X}", key.first) +
           " disc #" + std::to_string(key.second) +
           " name='" + info.name + "'";
  }
  return out;
}

}  // namespace xe
