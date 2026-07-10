/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2020 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */
#ifndef AX360E_XE_SAF_DISC_ARCHIVE_ENTRY_H
#define AX360E_XE_SAF_DISC_ARCHIVE_ENTRY_H

#include <string>
#include <vector>

#include "xenia/base/mapped_memory.h"
#include "xenia/vfs/entry.h"

namespace xe {
    namespace vfs {

        class SAF_DiscZarchiveDevice;

        class SAF_DiscZarchiveEntry : public Entry {
        public:
            SAF_DiscZarchiveEntry(Device* device, Entry* parent, const std::string_view path);
            ~SAF_DiscZarchiveEntry() override;

            static std::unique_ptr<SAF_DiscZarchiveEntry> Create(Device* device,
                                                             Entry* parent,
                                                             const std::string_view name);

            MappedMemory* mmap() const { return nullptr; }
            size_t data_offset() const { return data_offset_; }
            size_t data_size() const { return data_size_; }

            X_STATUS Open(uint32_t desired_access, File** out_file) override;

            bool can_map() const override { return false; }
            std::unique_ptr<MappedMemory> OpenMapped(MappedMemory::Mode mode,
                                                     size_t offset,
                                                     size_t length) override;

        private:
            friend class SAF_DiscZarchiveDevice;
            friend class SAF_DiscZarchiveFile;

            bool DeleteEntryInternal(Entry* entry) override;

            uint32_t handle_;
            size_t data_offset_;
            size_t data_size_;
        };

    }  // namespace vfs
}  // namespace xe

#endif //AX360E_XE_SAF_DISC_ARCHIVE_ENTRY_H
