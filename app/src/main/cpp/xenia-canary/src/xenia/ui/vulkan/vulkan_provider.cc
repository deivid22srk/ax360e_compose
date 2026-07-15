/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2025 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#include "xenia/ui/vulkan/vulkan_provider.h"

#include <vector>

#include "xenia/base/cvar.h"
#include "xenia/base/logging.h"
#include "xenia/base/platform.h"
#include "xenia/ui/vulkan/vulkan_immediate_drawer.h"
#include "xenia/ui/vulkan/vulkan_presenter.h"

#if XE_PLATFORM_ANDROID || XE_PLATFORM_AX360E
// Same include style as vulkan_instance.cc (resolved via app/src/main/cpp include root
// or adrenotools public headers when linked).
#include <adrenotools/bcenabler.h>
#endif

DEFINE_bool(
    vulkan_validation, false,
    "Enable the Vulkan validation layer (VK_LAYER_KHRONOS_validation). "
    "Messages will be written to the Xenia log if 'vulkan_log_debug_messages' "
    "is enabled, or to the OS debug output otherwise.",
    "Vulkan");

DEFINE_int32(vulkan_device, -1,
             "Index of the preferred Vulkan physical device, or -1 to use any "
             "compatible device.",
             "Vulkan");

namespace xe {
namespace ui {
namespace vulkan {

std::unique_ptr<VulkanProvider> VulkanProvider::Create(
    const bool with_gpu_emulation, const bool with_presentation) {
  std::unique_ptr<VulkanProvider> provider(new VulkanProvider());

  provider->vulkan_instance_ =
      VulkanInstance::Create(with_presentation, cvars::vulkan_validation);
  if (!provider->vulkan_instance_) {
    return nullptr;
  }

#if XE_PLATFORM_ANDROID || XE_PLATFORM_AX360E
  // Adreno BCeNabler: patch the proprietary driver so BC1/BC2/BC3/BC4/BC5
  // (Xbox 360 DXT) report as supported via vkGetPhysicalDeviceFormatProperties.
  // Without this, S3TC textures fall back to R8G8B8A8 (4-8x more memory) on many
  // Adreno blobs that don't expose BCn by default.
  // Must run after the Vulkan instance exists (function pointer available) but
  // before any format queries / texture cache initialization.
  {
    const VulkanInstance::Functions& ifn_bc =
        provider->vulkan_instance_->functions();
    // Enumerate devices just for vendor/driver version checks.
    uint32_t phys_count = 0;
    ifn_bc.vkEnumeratePhysicalDevices(provider->vulkan_instance_->instance(),
                                      &phys_count, nullptr);
    if (phys_count > 0) {
      std::vector<VkPhysicalDevice> phys(phys_count);
      ifn_bc.vkEnumeratePhysicalDevices(provider->vulkan_instance_->instance(),
                                        &phys_count, phys.data());
      for (const VkPhysicalDevice pd : phys) {
        VkPhysicalDeviceProperties props = {};
        ifn_bc.vkGetPhysicalDeviceProperties(pd, &props);
        if (props.vendorID != 0x5143) {
          continue;  // Qualcomm only
        }
        // Qualcomm driverVersion packing used by BCeNabler / AdrenoTools:
        // major = version >> 22, minor = (version >> 12) & 0x3ff
        // Example: 0x80212000 -> major 512, minor 530.
        const uint32_t ver = props.driverVersion;
        const uint32_t major = ver >> 22;
        const uint32_t minor = (ver >> 12) & 0x3ff;

        const adrenotools_bcn_type bcn_type =
            adrenotools_get_bcn_type(major, minor, props.vendorID);
        XELOGI(
            "Adreno BCeNabler: device '{}' driverVersion=0x{:X} "
            "parsed major={} minor={} -> {}",
            props.deviceName, ver, major, minor,
            bcn_type == ADRENOTOOLS_BCN_PATCH
                ? "PATCH (will enable BCn)"
                : bcn_type == ADRENOTOOLS_BCN_BLOB
                      ? "BLOB (driver claims BCn already)"
                      : "INCOMPATIBLE");

        // Always attempt the patch when type is PATCH. When type is BLOB but
        // format queries later still fail, the texture cache still has fallbacks.
        // Additionally: if major==512 regardless of minor threshold, try patch
        // when BC1 is not currently reported (helps drivers that advertise BLOB
        // incorrectly for this app's loader path).
        bool should_patch = (bcn_type == ADRENOTOOLS_BCN_PATCH);
        if (bcn_type == ADRENOTOOLS_BCN_BLOB ||
            bcn_type == ADRENOTOOLS_BCN_INCOMPATIBLE) {
          // Probe BC1 before device creation; if unsupported, try patch anyway
          // for vendor Qualcomm (safe no-op / fail on truly incompatible).
          VkFormatProperties fp = {};
          ifn_bc.vkGetPhysicalDeviceFormatProperties(
              pd, VK_FORMAT_BC1_RGBA_UNORM_BLOCK, &fp);
          const bool bc1_ok =
              (fp.optimalTilingFeatures &
               VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) != 0;
          if (!bc1_ok) {
            XELOGW(
                "Adreno BCeNabler: BC1 not exposed despite type={}; "
                "attempting patch anyway (vendor 0x{:X})",
                int(bcn_type), props.vendorID);
            should_patch = true;
          }
        }
        if (should_patch) {
          void* fn = reinterpret_cast<void*>(
              ifn_bc.vkGetPhysicalDeviceFormatProperties);
          if (adrenotools_patch_bcn(fn)) {
            XELOGI("Adreno BCeNabler: patch applied successfully");
            // Verify
            VkFormatProperties fp = {};
            ifn_bc.vkGetPhysicalDeviceFormatProperties(
                pd, VK_FORMAT_BC1_RGBA_UNORM_BLOCK, &fp);
            XELOGI(
                "Adreno BCeNabler: post-patch BC1 optimalTilingFeatures=0x{:X}",
                uint32_t(fp.optimalTilingFeatures));
          } else {
            XELOGW("Adreno BCeNabler: patch failed");
          }
        }
        break;  // first Qualcomm device is enough
      }
    }
  }
#endif  // XE_PLATFORM_ANDROID || XE_PLATFORM_AX360E

  std::vector<VkPhysicalDevice> physical_devices;
  provider->vulkan_instance_->EnumeratePhysicalDevices(physical_devices);

  if (physical_devices.empty()) {
    XELOGW("No Vulkan physical devices available");
    return nullptr;
  }

  const VulkanInstance::Functions& ifn =
      provider->vulkan_instance_->functions();

  XELOGW(
      "Available Vulkan physical devices (use the 'vulkan_device' "
      "configuration variable to force a specific device):");
  for (size_t physical_device_index = 0;
       physical_device_index < physical_devices.size();
       ++physical_device_index) {
    VkPhysicalDeviceProperties physical_device_properties;
    ifn.vkGetPhysicalDeviceProperties(physical_devices[physical_device_index],
                                      &physical_device_properties);
    XELOGW("* {}: {}", physical_device_index,
           physical_device_properties.deviceName);
  }

  const int32_t preferred_physical_device_index = cvars::vulkan_device;
  if (preferred_physical_device_index >= 0 &&
      uint32_t(preferred_physical_device_index) < physical_devices.size()) {
    provider->vulkan_device_ = VulkanDevice::CreateIfSupported(
        provider->vulkan_instance_.get(),
        physical_devices[preferred_physical_device_index], with_gpu_emulation,
        with_presentation);
  }

  if (!provider->vulkan_device_) {
    for (const VkPhysicalDevice physical_device : physical_devices) {
      provider->vulkan_device_ = VulkanDevice::CreateIfSupported(
          provider->vulkan_instance_.get(), physical_device, with_gpu_emulation,
          with_presentation);
      if (provider->vulkan_device_) {
        break;
      }
    }

    if (!provider->vulkan_device_) {
      XELOGW(
          "Couldn't choose a compatible Vulkan physical device or initialize a "
          "Vulkan logical device");
      return nullptr;
    }
  }

  if (with_presentation) {
    provider->ui_samplers_ = UISamplers::Create(provider->vulkan_device_.get());
    if (!provider->ui_samplers_) {
      return nullptr;
    }
  }

  return provider;
}

std::unique_ptr<Presenter> VulkanProvider::CreatePresenter(
    Presenter::HostGpuLossCallback host_gpu_loss_callback) {
  return VulkanPresenter::Create(host_gpu_loss_callback, vulkan_device(),
                                 ui_samplers());
}

std::unique_ptr<ImmediateDrawer> VulkanProvider::CreateImmediateDrawer() {
  return VulkanImmediateDrawer::Create(vulkan_device(), ui_samplers());
}

}  // namespace vulkan
}  // namespace ui
}  // namespace xe
