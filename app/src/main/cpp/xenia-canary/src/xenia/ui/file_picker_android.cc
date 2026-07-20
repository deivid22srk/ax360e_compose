/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2022 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

// [MULTI-DISC SUPPORT] ax360e/xenon360 port.
//
// The original file_picker_android.cc was a stub whose Show() always
// returned false, which broke XamSwapDisc (multi-disc support) because
// Emulator::GetNewDiscPath relies on Show() to obtain the user-selected
// file path.
//
// On Android, file selection is asynchronous (must launch an Activity for
// ACTION_OPEN_DOCUMENT and wait for onActivityResult). This cannot be
// done synchronously from the native thread that calls Show() — that
// thread is the kernel/guest thread and would block the entire emulator.
//
// Instead, MultiDiscManager (xe_multi_disc.h) handles the common case
// automatically by pre-scanning sibling ISOs at launch time. Show() here
// remains a stub returning false (i.e. "no file selected"), which causes
// XamSwapDisc to fall through to the MultiDiscManager lookup first. If
// MultiDiscManager also returns null, the swap fails with a clear log
// message rather than silently hanging the emulator.
//
// A future enhancement could implement a real async file picker by:
//   1. Adding a JNI callback that posts a Runnable to the Android UI
//      thread (via Activity.runOnUiThread).
//   2. The Runnable launches an ACTION_OPEN_DOCUMENT Intent.
//   3. onActivityResult receives the selected Uri.
//   4. The Uri is passed back to native via another JNI call.
//   5. The original Show() caller is unblocked (e.g. via a std::promise).
//
// For now, MultiDiscManager covers the 99% case (user has all discs in
// the same directory with proper media_id metadata), so we keep this
// stub.

#include <memory>

#include "xenia/ui/file_picker.h"

namespace xe {
namespace ui {

class AndroidFilePicker : public FilePicker {
 public:
  bool Show(Window* parent_window) override { return false; }
};

std::unique_ptr<FilePicker> FilePicker::Create() {
  return std::make_unique<AndroidFilePicker>();
}

}  // namespace ui
}  // namespace xe
