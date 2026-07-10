/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2020 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#ifndef AX360E_XE_SAF_XEX_FILE_H
#define AX360E_XE_SAF_XEX_FILE_H

#include <string>

#include "xenia/base/filesystem.h"
#include "xenia/vfs/file.h"

namespace xe {
    namespace vfs {

        class SAF_XexEntry;

        class SAF_XexFile : public File {
        public:
            SAF_XexFile(uint32_t file_access, SAF_XexEntry* entry,
                        int file_handle);
            ~SAF_XexFile() override;

            void Destroy() override;

            X_STATUS ReadSync(std::span<uint8_t> buffer, size_t byte_offset,
                              size_t* out_bytes_read) override;
            X_STATUS WriteSync(std::span<const uint8_t> buffer,
                               size_t byte_offset, size_t* out_bytes_written) override;
            X_STATUS SetLength(size_t length) override;

        private:
            int file_handle_;
        };

    }  // namespace vfs
}  // namespace xe
#endif //AX360E_XE_SAF_XEX_FILE_H
