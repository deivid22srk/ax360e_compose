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

// [ANDROID SURFACE ROBUSTNESS]
//
// Wraps an ANativeWindow* for Vulkan swapchain creation on Android.
//
// Robustness guarantees provided by this wrapper:
//   1. Reference counting: The constructor calls ANativeWindow_acquire() so
//      the wrapper OWNS a strong reference to the underlying window. The
//      destructor calls ANativeWindow_release(). This decouples the lifetime
//      of the Surface object from Java-side surfaceDestroyed() — if the
//      Java SurfaceView's surface is destroyed while the C++ Surface object
//      is still alive, ANativeWindow_getWidth/Height will return -22
//      (BAD_VALUE) instead of crashing. The presenter's size validation
//      then triggers the normal surface-recovery path.
//   2. Null safety: All accessors early-out when window_ is null (e.g.
//      after the wrapper has been detached via ReleaseAndDetach()).
//   3. Size validation: GetSizeImpl() returns false on null, on negative
//      dimensions (which ANativeWindow_getWidth returns on error), and on
//      zero dimensions (the swapchain cannot be created with a zero-sized
//      surface).
//
// Note: ANativeWindow_acquire is a no-op if the caller already holds a
// reference — it's safe to call multiple times. The matching release is
// always called exactly once in the destructor.
class AndroidNativeWindowSurface : public Surface {
 public:
  // Takes ownership of a strong reference on `window` (calls
  // ANativeWindow_acquire). Pass nullptr to construct an "empty" surface
  // that always reports invalid size — useful as a placeholder while a
  // new ANativeWindow is being delivered by surfaceCreated().
  explicit AndroidNativeWindowSurface(ANativeWindow* window);

  ~AndroidNativeWindowSurface() override;

  // Non-copyable (owns a native resource).
  AndroidNativeWindowSurface(const AndroidNativeWindowSurface&) = delete;
  AndroidNativeWindowSurface& operator=(const AndroidNativeWindowSurface&) =
      delete;

  TypeIndex GetType() const override { return kTypeIndex_AndroidNativeWindow; }

  // Returns the wrapped ANativeWindow*, or nullptr after ReleaseAndDetach().
  // The caller MUST NOT call ANativeWindow_release() on the returned pointer
  // — ownership remains with this Surface object.
  ANativeWindow* window() const { return window_; }

  // Releases the strong reference held by this wrapper and sets window_ to
  // null. Safe to call multiple times. Useful when the Java side has
  // signaled surfaceDestroyed() and the C++ side wants to drop its
  // reference before the destructor runs (so the next surfaceCreated()
  // doesn't race with the old surface's GPU work).
  void ReleaseAndDetach();

 protected:
  bool GetSizeImpl(uint32_t& width_out, uint32_t& height_out) const override;

 private:
  ANativeWindow* window_ = nullptr;
};

}  // namespace ui
}  // namespace xe

#endif  // XENIA_UI_SURFACE_ANDROID_H_
