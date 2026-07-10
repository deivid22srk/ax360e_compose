/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2020 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */
#include "xe_saf_xex_device.h"
#include "xe_saf_xex_entry.h"
#include "xenia/base/logging.h"

namespace xe {
    namespace vfs {
        SAF_XexDevice::SAF_XexDevice(const std::string_view mount_path,
                                     std::unique_ptr<DocumentFile> dir)
                : Device(mount_path),
                  name_("STFS"),
                  saf_dir_(std::move(dir)) {}

        SAF_XexDevice::~SAF_XexDevice() = default;

        bool SAF_XexDevice::Initialize() {
            std::unique_ptr<DocumentFile> root_path = DocumentFile::clone(saf_dir_);
            auto root_entry = new SAF_XexEntry(this, nullptr, "", std::move(root_path));
            root_entry->attributes_ = kFileAttributeDirectory;
            root_entry_ = std::unique_ptr<Entry>(root_entry);
            PopulateEntry(root_entry);

            return true;
        }

        void SAF_XexDevice::Dump(StringBuffer* string_buffer) {
            auto global_lock = global_critical_region_.Acquire();
            root_entry_->Dump(string_buffer, 0);
        }

        Entry* SAF_XexDevice::ResolvePath(const std::string_view path) {
            // The filesystem will have stripped our prefix off already, so the path will
            // be in the form:
            // some\PATH.foo
            XELOGFS("SAF_XexDevice::ResolvePath({}) {:X}", path,reinterpret_cast<uint64_t>(root_entry_->ResolvePath(path)));
            return root_entry_->ResolvePath(path);
        }

        void SAF_XexDevice::PopulateEntry(SAF_XexEntry* parent_entry) {
            auto child_files = parent_entry->saf_path_->listFiles();
            for (auto& child_file : child_files) {
                SAF_FileInfo info;

                info.name = child_file->getName();

                info.create_timestamp =
                info.access_timestamp =
                info.write_timestamp = (child_file->lastModified() * 10000000) + 116444736000000000;

                if(child_file->isDirectory()){
                    info.type = SAF_FileInfo::Type::kDirectory;
                    info.total_size = 0;
                }
                else{
                    info.type = SAF_FileInfo::Type::kFile;
                    info.total_size = child_file->length();
                }
                auto child = SAF_XexEntry::Create(
                        this, parent_entry, std::move(child_file),
                        info);
                parent_entry->children_.push_back(std::unique_ptr<Entry>(child));

                if (info.type == SAF_FileInfo::Type::kDirectory) {
                    PopulateEntry(child);
                }
            }
        }

    }  // namespace vfs
}  // namespace xe