/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2020 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

//存储访问框架 (SAF)
#ifndef AX360E_XE_SAF_XEX_DEVICE_H
#define AX360E_XE_SAF_XEX_DEVICE_H

#include <string>

#include "xenia/vfs/device.h"
#include "document_file.h"

namespace xe {
    namespace vfs {

        class SAF_XexEntry;

        class SAF_XexDevice : public Device {
        public:
            SAF_XexDevice(const std::string_view mount_path,
                          std::unique_ptr<DocumentFile> dir);
            ~SAF_XexDevice() override;

            bool Initialize() override;
            void Dump(StringBuffer* string_buffer) override;
            Entry* ResolvePath(const std::string_view path) override;

            bool is_read_only() const override { return true; }

            const std::string& name() const override { return name_; }
            uint32_t attributes() const override { return 0; }
            uint32_t component_name_max_length() const override { return 40; }

            uint32_t total_allocation_units() const override { return 128 * 1024; }
            uint32_t available_allocation_units() const override { return 128 * 1024; }
            uint32_t sectors_per_allocation_unit() const override { return 1; }
            uint32_t bytes_per_sector() const override { return 0x200; }

        private:
            void PopulateEntry(SAF_XexEntry* parent_entry);

            std::string name_;
            std::unique_ptr<DocumentFile> saf_dir_;
            std::unique_ptr<Entry> root_entry_;
        };

    }
}
#endif //AX360E_XE_SAF_XEX_DEVICE_H
