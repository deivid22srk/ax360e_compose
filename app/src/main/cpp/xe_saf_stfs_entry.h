/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2020 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#ifndef AX360E_XE_SAF_STFS_ENTRY_H
#define AX360E_XE_SAF_STFS_ENTRY_H

#include <map>
#include <string>
#include <vector>

#include "xenia/vfs/entry.h"
#include "xenia/vfs/file.h"

namespace xe {
    namespace vfs {
        typedef std::map<size_t, int> SAF_MultiFileHandles;

        class SAF_StfsDevice;

        class SAF_StfsEntry : public Entry {
        public:
            SAF_StfsEntry(Device* device, Entry* parent, const std::string_view path,
                               SAF_MultiFileHandles* files);
            ~SAF_StfsEntry() override;

            static std::unique_ptr<SAF_StfsEntry> Create(Device* device,
                                                              Entry* parent,
                                                              const std::string_view name,
                                                         SAF_MultiFileHandles* files);

            SAF_MultiFileHandles* files() const { return files_; }
            size_t data_offset() const { return data_offset_; }
            size_t data_size() const { return data_size_; }
            size_t block() const { return block_; }

            X_STATUS Open(uint32_t desired_access, File** out_file) override;

            bool DeleteEntryInternal(Entry* entry) override{return false;}
            struct BlockRecord {
                size_t file;
                size_t offset;
                size_t length;
            };
            const std::vector<BlockRecord>& block_list() const { return block_list_; }

        private:
            friend class SAF_StfsDevice;

            SAF_MultiFileHandles* files_;
            size_t data_offset_;
            size_t data_size_;
            size_t block_;
            std::vector<BlockRecord> block_list_;
        };

    }  // namespace vfs
}  // namespace xe
#endif //AX360E_XE_SAF_STFS_ENTRY_H
