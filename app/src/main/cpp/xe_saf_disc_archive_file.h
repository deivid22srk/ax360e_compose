/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2020 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */
#ifndef AX360E_XE_SAF_DISC_ARCHIVE_FILE_H
#define AX360E_XE_SAF_DISC_ARCHIVE_FILE_H
#include "xenia/vfs/file.h"

namespace xe {
    namespace vfs {

        class SAF_DiscZarchiveEntry;

        class SAF_DiscZarchiveFile : public File {
        public:
            SAF_DiscZarchiveFile(uint32_t file_access, SAF_DiscZarchiveEntry* entry);
            ~SAF_DiscZarchiveFile() override;

            void Destroy() override;

            X_STATUS ReadSync(std::span<uint8_t> buffer, size_t byte_offset,
                              size_t* out_bytes_read) override;
            X_STATUS WriteSync(std::span<const uint8_t> buffer, size_t byte_offset,
                               size_t* out_bytes_written) override {
                return X_STATUS_ACCESS_DENIED;
            }
            X_STATUS SetLength(size_t length) override { return X_STATUS_ACCESS_DENIED; }

        private:
            SAF_DiscZarchiveEntry* entry_;
        };

    }  // namespace vfs
}  // namespace xe
#endif //AX360E_XE_SAF_DISC_ARCHIVE_FILE_H
