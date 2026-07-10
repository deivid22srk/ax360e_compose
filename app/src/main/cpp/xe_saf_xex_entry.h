/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2020 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#ifndef AX360E_XE_SAF_XEX_ENTRY_H
#define AX360E_XE_SAF_XEX_ENTRY_H

#include <string>
#include "document_file.h"
#include "xenia/base/filesystem.h"
#include "xenia/vfs/entry.h"

namespace xe {
    namespace vfs {
        struct SAF_FileInfo {
            enum class Type {
                kFile,
                kDirectory,
            };
            Type type;
            std::string name;
            std::string path;
            size_t total_size;
            uint64_t create_timestamp;
            uint64_t access_timestamp;
            uint64_t write_timestamp;
        };
        class SAF_XexDevice;

        class SAF_XexEntry : public Entry {
        public:
            SAF_XexEntry(Device* device, Entry* parent, const std::string_view path,
                         std::unique_ptr<DocumentFile> saf_path);
            ~SAF_XexEntry() override;

            static SAF_XexEntry* Create(Device* device, Entry* parent,
                                        std::unique_ptr<DocumentFile> saf_path,
                                        SAF_FileInfo file_info);

            X_STATUS Open(uint32_t desired_access, File** out_file) override;
            //FIXME
            bool DeleteEntryInternal(Entry* entry) override{return false;}

        private:
            friend class SAF_XexDevice;
            std::unique_ptr<DocumentFile> saf_path_;
        };

    }  // namespace vfs
}  // namespace xe
#endif //AX360E_XE_SAF_XEX_ENTRY_H
