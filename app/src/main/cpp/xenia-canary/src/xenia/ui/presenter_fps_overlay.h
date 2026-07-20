/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2022 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#ifndef XENIA_UI_PRESENTER_FPS_OVERLAY_H_
#define XENIA_UI_PRESENTER_FPS_OVERLAY_H_

#include <cstdint>
#include <memory>
#include <string>

#include "xenia/ui/immediate_drawer.h"
#include "xenia/ui/ui_drawer.h"

namespace xe {
namespace ui {

class MicroprofileDrawer;
class Presenter;

// [XENIA NATIVE FPS OVERLAY]
//
// A UIDrawer that draws a small "FPS: NN" text overlay directly into the
// Vulkan render target used for presentation. This is Xenia's OWN FPS
// counter — rendered by the GPU command stream alongside the guest output,
// NOT by the Compose/Kotlin UI layer that floats above the virtual gamepad.
//
// Why this exists:
//   - The pre-existing FPS overlay was a Compose Text() widget placed in
//     EmulatorScreen (top-left corner). That widget belongs to the Android
//     View hierarchy and is composited ABOVE the SurfaceView that hosts
//     the Vulkan swapchain. As a result, the virtual gamepad (also a
//     Compose AndroidView) draws ON TOP of the FPS text, which is fine
//     visually but means the FPS counter is the host UI's overlay, not
//     Xenia's.
//   - This class draws the FPS counter inside the guest output render
//     target itself, using the same ImmediateDrawer / font texture that
//     MicroprofileDrawer uses. It is the same drawing path the Xenia
//     profiler would use on Windows.
//
// Threading:
//   - Draw() is called from the UI thread (or the guest output thread,
//     depending on Presenter paint mode), inside Presenter::
//     ExecuteUIDrawersFromUIThread. The paint_mode_mutex_ held by the
//     caller serializes access.
//   - GetFPS() on Presenter is an atomic relaxed load, so reading it
//     here is safe.
//
// Lifecycle:
//   - Created lazily by EmulatorApp when an ImmediateDrawer is available
//     (in SetupGraphicsSystemPresenterPainting) and registered as a
//     UIDrawer on the presenter with kZOrderXeniaFpsOverlay.
//   - Removed in ShutdownGraphicsSystemPresenterPainting.
//   - The actual draw is gated by cvars::show_xenia_fps_overlay, so when
//     the user disables it in the settings UI, Draw() is a no-op (but
//     the drawer stays registered — toggling it back on doesn't require
//     re-registering).
class PresenterFPSOverlay : public UIDrawer {
 public:
  // Z-order between the ImGui drawer and the profiler drawer. The FPS
  // overlay should sit on top of the guest output but below the ImGui
  // dialogs (so an open ImGui dialog hides the FPS counter behind it).
  // Numerically: kZOrderImGui=1, kZOrderProfiler=2 — we use 1 so the FPS
  // text draws just below ImGui, on top of the guest output.
  static constexpr size_t kZOrder = 1;

  explicit PresenterFPSOverlay(Presenter* presenter,
                               ImmediateDrawer* immediate_drawer);
  ~PresenterFPSOverlay() override;

  // Set the presenter/immediate drawer pair when the graphics system is
  // (re)initialized. Either may be null to detach.
  void SetPresenterAndImmediateDrawer(Presenter* presenter,
                                      ImmediateDrawer* immediate_drawer);

  // UIDrawer:
  void Draw(UIDrawContext& context) override;

 private:
  void RebuildDrawer();
  void TearDownDrawer();

  Presenter* presenter_ = nullptr;
  ImmediateDrawer* immediate_drawer_ = nullptr;
  std::unique_ptr<MicroprofileDrawer> microprofile_drawer_;
};

}  // namespace ui
}  // namespace xe

#endif  // XENIA_UI_PRESENTER_FPS_OVERLAY_H_
