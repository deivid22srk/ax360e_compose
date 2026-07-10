/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2020 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#include "xe_saf_disc_archive_entry.h"

#include "xenia/base/math.h"
#include "xe_saf_disc_archive_file.h"

#include "third_party/zarchive/include/zarchive/zarchivereader.h"

namespace xe {
    namespace vfs {

        SAF_DiscZarchiveEntry::SAF_DiscZarchiveEntry(Device* device, Entry* parent,
                                             const std::string_view path)
                : Entry(device, parent, path),
                  data_offset_(0),
                  data_size_(0),
                  handle_(ZARCHIVE_INVALID_NODE) {}

        SAF_DiscZarchiveEntry::~SAF_DiscZarchiveEntry() = default;

        std::unique_ptr<SAF_DiscZarchiveEntry> SAF_DiscZarchiveEntry::Create(
                Device* device, Entry* parent, const std::string_view name) {
            auto path = name;  // xe::utf8::join_guest_paths(parent->path(), name);
            auto entry = std::make_unique<SAF_DiscZarchiveEntry>(device, parent, path);
            return std::move(entry);
        }

        X_STATUS SAF_DiscZarchiveEntry::Open(uint32_t desired_access, File** out_file) {
            *out_file = new SAF_DiscZarchiveFile(desired_access, this);
            return X_STATUS_SUCCESS;
        }

        std::unique_ptr<MappedMemory> SAF_DiscZarchiveEntry::OpenMapped(
                MappedMemory::Mode mode, size_t offset, size_t length) {
            return nullptr;
        }

        bool SAF_DiscZarchiveEntry::DeleteEntryInternal(Entry* entry) { return false; }

    }  // namespace vfs
}  // namespace xe
