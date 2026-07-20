/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2021 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#ifndef XENIA_UI_SURFACE_ANDROID_H_
#define XENIA_UI_SURFACE_ANDROID_H_

#include <android/native_window.h>

#include "xenia/ui/surface.h"

namespace xe {
namespace ui {

class AndroidNativeWindowSurface : public Surface {
 public:
  // [RANGO BLACK SCREEN FIX] Acquire a reference to the ANativeWindow in
  // the constructor and release it in the destructor.
  //
  // Without this, the following race condition causes black screens:
  //   1. surfaceCreated (Java) calls setup_surface(holder.surface) which
  //      does `ae::window = ANativeWindow_fromSurface(env, surface)`
  //      (refcount = 1, owned by ae::window).
  //   2. AndroidWindow::CreateSurfaceImpl creates an AndroidNativeWindowSurface
  //      passing `ae::window` directly (no ANativeWindow_acquire).
  //   3. The presenter creates a VkSurfaceKHR from this ANativeWindow. The
  //      Vulkan implementation does NOT acquire its own reference.
  //   4. surfaceDestroyed (Java) calls setup_surface(null) which does
  //      `ANativeWindow_release(ae::window)` (refcount = 0 → destroyed).
  //   5. The VkSurfaceKHR now points to a destroyed ANativeWindow. Any
  //      subsequent vkAcquireNextImageKHR returns VK_ERROR_SURFACE_LOST_KHR
  //      or VK_ERROR_OUT_OF_DATE_KHR.
  //
  // By acquiring our own reference in the constructor, the ANativeWindow
  // stays alive until the surface is destroyed (in the destructor), which
  // is always done before the VkSurfaceKHR is destroyed.
  explicit AndroidNativeWindowSurface(ANativeWindow* window) : window_(window) {
    if (window_) {
      ANativeWindow_acquire(window_);
    }
  }
  ~AndroidNativeWindowSurface() override {
    if (window_) {
      ANativeWindow_release(window_);
      window_ = nullptr;
    }
  }
  AndroidNativeWindowSurface(const AndroidNativeWindowSurface&) = delete;
  AndroidNativeWindowSurface& operator=(const AndroidNativeWindowSurface&) =
      delete;

  TypeIndex GetType() const override { return kTypeIndex_AndroidNativeWindow; }
  ANativeWindow* window() const { return window_; }

 protected:
  bool GetSizeImpl(uint32_t& width_out, uint32_t& height_out) const override;

 private:
  ANativeWindow* window_;
};

}  // namespace ui
}  // namespace xe

#endif  // XENIA_UI_SURFACE_ANDROID_H_
