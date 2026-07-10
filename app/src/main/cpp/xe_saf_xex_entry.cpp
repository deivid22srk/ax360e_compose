/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2020 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */
#include "xe_saf_xex_entry.h"
#include "document_file.h"
#include "xe_saf_xex_device.h"
#include "xenia/base/logging.h"
#include "xenia/base/math.h"
#include "xe_saf_xex_file.h"


namespace xe {
    namespace vfs {

        SAF_XexEntry::SAF_XexEntry(Device* device, Entry* parent,
                                   const std::string_view path,
                                   std::unique_ptr<DocumentFile> saf_path)
                : Entry(device, parent, path), saf_path_(std::move(saf_path)) {}

        SAF_XexEntry::~SAF_XexEntry() = default;

        SAF_XexEntry* SAF_XexEntry::Create(Device* device, Entry* parent,
                                           std::unique_ptr<DocumentFile> saf_path,
                                           SAF_FileInfo file_info) {
            auto path = xe::utf8::join_guest_paths(parent->path(),
                                                   xe::path_to_utf8(file_info.name));
            auto entry = new SAF_XexEntry(device, parent, path, std::move(saf_path));

            entry->create_timestamp_ = file_info.create_timestamp;
            entry->access_timestamp_ = file_info.access_timestamp;
            entry->write_timestamp_ = file_info.write_timestamp;
            if (file_info.type == SAF_FileInfo::Type::kDirectory) {
                entry->attributes_ = kFileAttributeDirectory;
            } else {
                entry->attributes_ = kFileAttributeNormal;
                if (device->is_read_only()) {
                    entry->attributes_ |= kFileAttributeReadOnly;
                }
                entry->size_ = file_info.total_size;
                entry->allocation_size_ =
                        xe::round_up(file_info.total_size, device->bytes_per_sector());
            }
            return entry;
        }

        X_STATUS SAF_XexEntry::Open(uint32_t desired_access, File** out_file) {
            if (is_read_only() && (desired_access & (FileAccess::kFileWriteData |
                                                     FileAccess::kFileAppendData))) {
                XELOGE("Attempting to open file for write access on read-only device");
                return X_STATUS_ACCESS_DENIED;
            }
            //auto file_handle =
            //        xe::filesystem::FileHandle::OpenExisting(host_path_, desired_access);
            int fd=DocumentFile::open_fd(saf_path_);
            if (fd==-1) {
                // TODO(benvanik): pick correct response.
                return X_STATUS_NO_SUCH_FILE;
            }
            *out_file = new SAF_XexFile(desired_access, this, fd);
            return X_STATUS_SUCCESS;
        }
    }  // namespace vfs
}  // namespace xe
