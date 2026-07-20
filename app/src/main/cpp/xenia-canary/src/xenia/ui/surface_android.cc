/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2021 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#include "xenia/ui/surface_android.h"

#include <android/log.h>
#include <android/native_window.h>

#include "xenia/base/logging.h"

#define ANDROID_SURFACE_LOG_TAG "XeniaSurface"

namespace xe {
namespace ui {

AndroidNativeWindowSurface::AndroidNativeWindowSurface(ANativeWindow* window)
    : window_(window) {
  if (window_) {
    // Acquire a strong reference. ANativeWindow_acquire is safe to call
    // even if we already hold a reference (it's reference-counted). This
    // ensures the underlying Surface object survives even after the Java
    // side calls surfaceDestroyed(), so any pending Vulkan work that
    // references this window can complete gracefully.
    ANativeWindow_acquire(window_);
    XELOGI("[AndroidNativeWindowSurface] Acquired surface {} (size={}x{})",
           static_cast<void*>(window_),
           ANativeWindow_getWidth(window_),
           ANativeWindow_getHeight(window_));
  }
}

AndroidNativeWindowSurface::~AndroidNativeWindowSurface() {
  ReleaseAndDetach();
}

void AndroidNativeWindowSurface::ReleaseAndDetach() {
  if (!window_) {
    return;
  }
  XELOGI("[AndroidNativeWindowSurface] Releasing surface {}",
         static_cast<void*>(window_));
  ANativeWindow* old = window_;
  window_ = nullptr;
  // Release AFTER clearing the member so any concurrent reader that holds
  // the (now-stale) pointer can still observe nullptr on the next access
  // through window(). The release itself is reference-counted and won't
  // actually free the underlying buffer queue if the Java side still holds
  // a reference (it usually does, until surfaceDestroyed returns).
  ANativeWindow_release(old);
}

bool AndroidNativeWindowSurface::GetSizeImpl(uint32_t& width_out,
                                             uint32_t& height_out) const {
  if (!window_) {
    // Surface was either never given a window or has been detached via
    // ReleaseAndDetach(). The presenter treats "no size" as "outdated
    // surface" and triggers the recovery path.
    return false;
  }
  // ANativeWindow_getWidth/getHeight return -22 (BAD_VALUE) if the window
  // has been destroyed on the Java side. They may also return 0 during a
  // transition (e.g. orientation change while the new surface is being
  // measured). Both cases must be treated as "no usable size" —
  // vkCreateSwapchainKHR with width=0 or height=0 fails with
  // VK_ERROR_INITIALIZATION_FAILED.
  int width = ANativeWindow_getWidth(window_);
  int height = ANativeWindow_getHeight(window_);
  if (width <= 0 || height <= 0) {
    XELOGW("[AndroidNativeWindowSurface] Invalid size {}x{} (window={}) — "
           "treating as outdated. This is expected during surface "
           "transitions (orientation change, app backgrounded).",
           width, height, static_cast<void*>(window_));
    return false;
  }
  width_out = uint32_t(width);
  height_out = uint32_t(height);
  return true;
}

}  // namespace ui
}  // namespace xe
