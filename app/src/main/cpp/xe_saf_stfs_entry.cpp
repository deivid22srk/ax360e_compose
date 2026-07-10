/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2020 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#include "xe_saf_stfs_entry.h"
#include "xe_saf_stfs_file.h"
#include <map>

namespace xe {
    namespace vfs {

        SAF_StfsEntry::SAF_StfsEntry(Device* device, Entry* parent,
                                               const std::string_view path,
                                     SAF_MultiFileHandles* files)
                : Entry(device, parent, path),
                  files_(files),
                  data_offset_(0),
                  data_size_(0),
                  block_(0) {}

        SAF_StfsEntry::~SAF_StfsEntry() = default;

        std::unique_ptr<SAF_StfsEntry> SAF_StfsEntry::Create(
                Device* device, Entry* parent, const std::string_view name,
                SAF_MultiFileHandles* files) {
            auto path = xe::utf8::join_guest_paths(parent->path(), name);
            auto entry =
                    std::make_unique<SAF_StfsEntry>(device, parent, path, files);

            return std::move(entry);
        }

        X_STATUS SAF_StfsEntry::Open(uint32_t desired_access, File** out_file) {
            *out_file = new SAF_StfsFile(desired_access, this);
            return X_STATUS_SUCCESS;
        }

    }  // namespace vfs
}  // namespace xe