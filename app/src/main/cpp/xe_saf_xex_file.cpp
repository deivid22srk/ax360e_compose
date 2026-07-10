/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2020 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */
#include "xe_saf_xex_file.h"
#include "xe_saf_xex_entry.h"

namespace xe {
    namespace vfs {

        static bool _Read(int handle,size_t file_offset, void* buffer, size_t buffer_length,
                         size_t* out_bytes_read){
            ssize_t out = pread(handle, buffer, buffer_length, file_offset);
            *out_bytes_read = out;
            return out >= 0 ? true : false;
        }
        static bool _Write(int handle,size_t file_offset, const void* buffer, size_t buffer_length,
                          size_t* out_bytes_written) {
            ssize_t out = pwrite(handle, buffer, buffer_length, file_offset);
            *out_bytes_written = out;
            return out >= 0 ? true : false;
        }

        static bool _SetLength(int handle,size_t length) {
            return ftruncate(handle, length) >= 0 ? true : false;
        }

        SAF_XexFile::SAF_XexFile(
                uint32_t file_access, SAF_XexEntry* entry,
                int file_handle)
                : File(file_access, entry), file_handle_(file_handle) {}

        SAF_XexFile::~SAF_XexFile() { close(file_handle_);file_handle_ = -1; }

        void SAF_XexFile::Destroy() { delete this; }

        X_STATUS SAF_XexFile::ReadSync(std::span<uint8_t> buffer,
                                       size_t byte_offset, size_t* out_bytes_read) {
            if (!(file_access_ &
                  (FileAccess::kGenericRead | FileAccess::kFileReadData))) {
                return X_STATUS_ACCESS_DENIED;
            }



            if (_Read(file_handle_,byte_offset, buffer.data(), buffer.size(), out_bytes_read)) {
                return X_STATUS_SUCCESS;
            } else {
                return X_STATUS_END_OF_FILE;
            }
        }

        X_STATUS SAF_XexFile::WriteSync(std::span<const uint8_t> buffer,
                                        size_t byte_offset,
                                        size_t* out_bytes_written) {
            if (!(file_access_ & (FileAccess::kGenericWrite | FileAccess::kFileWriteData |
                                  FileAccess::kFileAppendData))) {
                return X_STATUS_ACCESS_DENIED;
            }

            if (_Write(file_handle_,byte_offset, buffer.data(), buffer.size(),
                                    out_bytes_written)) {
                return X_STATUS_SUCCESS;
            } else {
                return X_STATUS_END_OF_FILE;
            }
        }

        X_STATUS SAF_XexFile::SetLength(size_t length) {
            if (!(file_access_ &
                  (FileAccess::kGenericWrite | FileAccess::kFileWriteData))) {
                return X_STATUS_ACCESS_DENIED;
            }

            if (_SetLength(file_handle_,length)) {
                return X_STATUS_SUCCESS;
            } else {
                return X_STATUS_END_OF_FILE;
            }
        }

    }  // namespace vfs
}  // namespace xe
