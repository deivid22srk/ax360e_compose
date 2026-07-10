/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2020 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */
#include "xe_saf_disc_image_entry.h"
#include "xe_saf_disc_image_file.h"
namespace xe {
    namespace vfs {

        SAF_DiscImageEntry::SAF_DiscImageEntry(Device* device, Entry* parent,
                                       const std::string_view path, MappedMemory* mmap)
                : Entry(device, parent, path),
                  mmap_(mmap),
                  data_offset_(0),
                  data_size_(0) {}

        SAF_DiscImageEntry::~SAF_DiscImageEntry() = default;

        std::unique_ptr<SAF_DiscImageEntry> SAF_DiscImageEntry::Create(
                Device* device, Entry* parent, const std::string_view name,
                MappedMemory* mmap) {
            auto path = xe::utf8::join_guest_paths(parent->path(), name);
            auto entry = std::make_unique<SAF_DiscImageEntry>(device, parent, path, mmap);
            return std::move(entry);
        }

        X_STATUS SAF_DiscImageEntry::Open(uint32_t desired_access, File** out_file) {
            *out_file = new SAF_DiscImageFile(desired_access, this);
            return X_STATUS_SUCCESS;
        }

        std::unique_ptr<MappedMemory> SAF_DiscImageEntry::OpenMapped(
                MappedMemory::Mode mode, size_t offset, size_t length) {
            if (mode != MappedMemory::Mode::kRead) {
                // Only allow reads.
                return nullptr;
            }

            size_t real_offset = data_offset_ + offset;
            size_t real_length = length ? std::min(length, data_size_) : data_size_;
            return mmap_->Slice(real_offset, real_length);
        }

    }  // namespace vfs
}  // namespace xe
