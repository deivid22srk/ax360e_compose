/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2020 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */
#include "xe_saf_disc_image_file.h"
#include "xe_saf_disc_image_entry.h"

namespace xe {
    namespace vfs {

        SAF_DiscImageFile::SAF_DiscImageFile(uint32_t file_access, SAF_DiscImageEntry* entry)
                : File(file_access, entry), entry_(entry) {}

        SAF_DiscImageFile::~SAF_DiscImageFile() = default;

        void SAF_DiscImageFile::Destroy() { delete this; }

        X_STATUS SAF_DiscImageFile::ReadSync(std::span<uint8_t> buffer,
                                         size_t byte_offset, size_t* out_bytes_read) {
            if (byte_offset >= entry_->size()) {
                return X_STATUS_END_OF_FILE;
            }
            size_t real_offset = entry_->data_offset() + byte_offset;
            size_t real_length =
                    std::min(buffer.size(), entry_->data_size() - byte_offset);
            std::memcpy(buffer.data(), entry_->mmap()->data() + real_offset, real_length);
            *out_bytes_read = real_length;
            return X_STATUS_SUCCESS;
        }

    }  // namespace vfs
}  // namespace xe