/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2023 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#include "xe_saf_disc_archive_file.h"
#include "xe_saf_disc_archive_device.h"
#include "xe_saf_disc_archive_entry.h"

namespace xe {
    namespace vfs {

        SAF_DiscZarchiveFile::SAF_DiscZarchiveFile(uint32_t file_access,
                                           SAF_DiscZarchiveEntry* entry)
                : File(file_access, entry), entry_(entry) {}

        SAF_DiscZarchiveFile::~SAF_DiscZarchiveFile() = default;

        void SAF_DiscZarchiveFile::Destroy() { delete this; }

        X_STATUS SAF_DiscZarchiveFile::ReadSync(std::span<uint8_t> buffer,
                                            size_t byte_offset,
                                            size_t* out_bytes_read) {
            if (byte_offset >= entry_->size()) {
                return X_STATUS_END_OF_FILE;
            }

            SAF_DiscZarchiveDevice* zArchDev =
                    dynamic_cast<SAF_DiscZarchiveDevice*>(entry_->device_);

            if (!zArchDev) {
                return X_STATUS_UNSUCCESSFUL;
            }

            const uint64_t bytes_read = zArchDev->reader()->ReadFromFile(
                    entry_->handle_, byte_offset, buffer.size(), buffer.data());
            *out_bytes_read = bytes_read;
            return X_STATUS_SUCCESS;
        }

    }  // namespace vfs
}  // namespace xe
