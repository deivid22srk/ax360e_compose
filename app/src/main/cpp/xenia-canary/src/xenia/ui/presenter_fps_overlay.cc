/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2022 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#include "xenia/ui/presenter_fps_overlay.h"

#include <algorithm>
#include <cstdint>
#include <cstring>

#include "third_party/fmt/include/fmt/format.h"

#include "xenia/base/assert.h"
#include "xenia/base/cvar.h"
#include "xenia/base/logging.h"
#include "xenia/ui/microprofile_drawer.h"
#include "xenia/ui/presenter.h"

// [XENIA NATIVE FPS OVERLAY CVAR]
//
// When true, draws a small "FPS: NN" text overlay directly into the Vulkan
// render target via the ImmediateDrawer path. This is Xenia's own FPS
// counter — it lives below the Android View hierarchy, so the virtual
// gamepad (and any other Compose overlay) does not interfere with it.
//
// Default: false. The pre-existing Compose FPS overlay (show_fps_counter
// SharedPreferences key in EmulatorActivity) is independent of this cvar
// and continues to work as before — they can be enabled simultaneously
// without conflict, though that would be redundant.
DEFINE_bool(
    show_xenia_fps_overlay, false,
    "Draw a small FPS counter directly into the guest output render target "
    "via the Xenia presenter. This is Xenia's own FPS counter (rendered by "
    "the GPU), distinct from the on-screen Compose FPS overlay that sits "
    "above the virtual gamepad.",
    "Display");

namespace xe {
namespace ui {

PresenterFPSOverlay::PresenterFPSOverlay(Presenter* presenter,
                                         ImmediateDrawer* immediate_drawer)
    : presenter_(presenter), immediate_drawer_(immediate_drawer) {
  RebuildDrawer();
}

PresenterFPSOverlay::~PresenterFPSOverlay() {
  // Detach from the presenter so it doesn't try to call Draw() after we're
  // gone. RemoveUIDrawerFromUIThread is idempotent — if we were never
  // added (e.g. immediate_drawer_ was null at construction), this is a
  // no-op search.
  if (presenter_) {
    presenter_->RemoveUIDrawerFromUIThread(this);
  }
  TearDownDrawer();
}

void PresenterFPSOverlay::SetPresenterAndImmediateDrawer(
    Presenter* presenter, ImmediateDrawer* immediate_drawer) {
  // If the presenter is changing, detach first to avoid leaving a stale
  // UIDrawer reference on the old presenter.
  if (presenter_ && presenter_ != presenter) {
    presenter_->RemoveUIDrawerFromUIThread(this);
  }
  // Tear down the existing MicroprofileDrawer if the underlying
  // ImmediateDrawer is changing — the font texture is bound to it.
  if (immediate_drawer_ && immediate_drawer_ != immediate_drawer) {
    TearDownDrawer();
  }
  presenter_ = presenter;
  immediate_drawer_ = immediate_drawer;
  if (immediate_drawer_ && !microprofile_drawer_) {
    RebuildDrawer();
  }
  // (Re)register with the new presenter if we have a working drawer.
  if (presenter_ && microprofile_drawer_) {
    presenter_->AddUIDrawerFromUIThread(this, kZOrder);
  }
}

void PresenterFPSOverlay::RebuildDrawer() {
  if (!immediate_drawer_) {
    return;
  }
  microprofile_drawer_ = std::make_unique<MicroprofileDrawer>(immediate_drawer_);
  if (presenter_) {
    presenter_->AddUIDrawerFromUIThread(this, kZOrder);
  }
}

void PresenterFPSOverlay::TearDownDrawer() { microprofile_drawer_.reset(); }

void PresenterFPSOverlay::Draw(UIDrawContext& context) {
  // Cheap early-out: if the user disabled the overlay, do nothing. We
  // don't unregister the UIDrawer because the user might re-enable it
  // from the settings UI, and re-registering from a non-UI thread would
  // be incorrect (AddUIDrawerFromUIThread asserts it's called from the
  // UI thread).
  if (!cvars::show_xenia_fps_overlay) {
    return;
  }
  if (!presenter_ || !immediate_drawer_ || !microprofile_drawer_) {
    return;
  }

  // GetFPS() returns the count of frames successfully presented over the
  // last ~500ms window. Returns 0 if no frames have been presented yet.
  uint32_t fps = presenter_->GetFPS();

  // Render "FPS: NN" — keep it short so it fits in a small corner.
  std::string text = fmt::format("FPS: {}", fps);

  // Use render target pixel coordinates (0,0 = top-left). The MicroprofileDrawer
  // font is 5x8 pixels per glyph (kFontCharWidth=5, kFontCharHeight=8 — see
  // microprofile_drawer.cc), so a 10-char string at x=8,y=8 occupies roughly
  // 50x8 pixels — fits comfortably in the corner without obscuring gameplay.
  const uint32_t coordinate_space_width = context.render_target_width();
  const uint32_t coordinate_space_height = context.render_target_height();
  if (coordinate_space_width == 0 || coordinate_space_height == 0) {
    return;
  }

  microprofile_drawer_->Begin(context, coordinate_space_width,
                              coordinate_space_height);

  // Background box (semi-transparent black) behind the text for readability.
  // Color is RGBA, but MicroprofileDrawer swaps R/B internally for flat
  // boxes — pass the color in the format it expects (handled inside
  // DrawBox). Use 0xC0000000 = ~75% alpha black.
  const int kPad = 4;
  // NOTE: matches MicroprofileDrawer's kFontCharWidth=5 (with +1 advance
  // per char = 6 px per glyph) and kFontCharHeight=8. See DrawTextString
  // in microprofile_drawer.cc.
  const int kCharAdvance = 6;
  const int kCharH = 8;
  const int kTextW = static_cast<int>(text.size()) * kCharAdvance;
  const int kTextH = kCharH;
  const int kBoxX0 = 4;
  const int kBoxY0 = 4;
  const int kBoxX1 = kBoxX0 + kTextW + kPad * 2;
  const int kBoxY1 = kBoxY0 + kTextH + kPad * 2;
  microprofile_drawer_->DrawBox(kBoxX0, kBoxY0, kBoxX1, kBoxY1, 0xC0000000u,
                                MicroprofileDrawer::BoxType::kFlat);

  // FPS text in bright green (0xFF00FF00 in MicroprofileDrawer's RGBA layout).
  microprofile_drawer_->DrawTextString(kBoxX0 + kPad, kBoxY0 + kPad,
                                       0xFF00FF00u, text.c_str(),
                                       static_cast<int>(text.size()));

  microprofile_drawer_->End();
}

}  // namespace ui
}  // namespace xe
