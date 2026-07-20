// SPDX-License-Identifier: WTFPL
//
// xe_multi_disc.h
//
// Multi-disc manager for ax360e (xenon360 / xenia-canary Android port).
//
// Background
// ----------
// The Xenia canary codebase already exports XamSwapDisc_entry in
// xenia/kernel/xam/xam_content.cc. When a multi-disc Xbox 360 title needs to
// read content from disc N, the kernel calls XamSwapDisc(disc_number=N, ...).
// The original desktop implementation of XamSwapDisc calls
// Emulator::GetNewDiscPath(), which uses xe::ui::FilePicker — but
// file_picker_android.cc is a stub whose Show() always returns false, so on
// Android the disc swap silently fails and multi-disc titles cannot progress
// past the disc-change prompt.
//
// Solution
// --------
// MultiDiscManager pre-scans sibling disc image files at game launch time.
// It uses the parent directory exposed via the Storage Access Framework
// (DocumentFile::getParentFile + listFiles), parses each ISO/ZAR for its
// embedded default.xex, reads the xex2_opt_execution_info optional header,
// and builds a map keyed by (media_id, disc_number). When the game requests
// a disc swap, the manager returns the matching DocumentFile so the existing
// SAF-based MountPath overload can be used — no UI interaction required.
//
// The (media_id, disc_number) pair uniquely identifies a disc within a
// multi-disc release. media_id is shared by every disc of the same game,
// while disc_number (1-indexed) distinguishes individual discs.

#ifndef AX360E_XE_MULTI_DISC_H
#define AX360E_XE_MULTI_DISC_H

#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "document_file.h"

namespace xe {

class MultiDiscManager {
 public:
  struct DiscInfo {
    uint32_t media_id = 0;
    uint32_t disc_number = 0;
    uint32_t disc_count = 0;
    std::string name;
    // Clone of the DocumentFile. Each FindDisc() call returns a fresh clone
    // (via DocumentFile::clone) so the registry's copy is never consumed by
    // the consumer (MountPath takes std::unique_ptr and would otherwise
    // destroy the stored entry).
    std::unique_ptr<DocumentFile> file;
  };

  static MultiDiscManager& Instance();

  // Called after a game has been launched and the executable module is
  // loaded. |game_file| must be a clone of the DocumentFile that was passed
  // to Emulator::LaunchDiscImage/LaunchDiscArchive — MultiDiscManager takes
  // ownership of this clone. |current_media_id| and |current_disc_number|
  // come from the loaded module's xex2_opt_execution_info. The manager uses
  // these to (a) validate that the launched disc matches its own scan
  // results, and (b) short-circuit when the game requests the same disc.
  //
  // This call is potentially slow (it mmaps and parses every sibling ISO),
  // so callers should not invoke it on latency-sensitive threads. The
  // typical caller is the on_launch UI-thread listener in ax360e_emu.cpp.
  void RegisterLaunchedGame(std::unique_ptr<DocumentFile> game_file_clone,
                            uint32_t current_media_id,
                            uint32_t current_disc_number);

  // Returns a *fresh clone* of the DocumentFile for the requested disc, or
  // nullptr if no matching disc is registered. The returned file is safe to
  // pass to Emulator::MountPath (which consumes it via std::unique_ptr).
  //
  // |media_id| must match the launched game's media_id — discs from other
  // games are never returned, even if they happen to share a disc_number.
  std::unique_ptr<DocumentFile> FindDisc(uint32_t media_id,
                                         uint32_t disc_number);

  // Returns true if the manager currently has any discs registered.
  bool HasDiscs();

  // Returns the number of registered discs for the given media_id.
  uint32_t GetDiscCount(uint32_t media_id);

  // Clears all registered discs. Called when a game exits or before a new
  // game is registered.
  void Clear();

  // For diagnostics / logging.
  std::string Describe();

 private:
  MultiDiscManager() = default;
  ~MultiDiscManager() = default;
  MultiDiscManager(const MultiDiscManager&) = delete;
  MultiDiscManager& operator=(const MultiDiscManager&) = delete;

  // Tries to parse the xex2_opt_execution_info from the default.xex embedded
  // in the given ISO file. |file| is the SAF DocumentFile pointing at an
  // .iso image. On success returns true and writes media_id/disc_number/
  // disc_count. On failure returns false (file not a valid XDVDFS, no
  // default.xex, parse error, etc.).
  //
  // The parsing is intentionally minimal — we only walk the root directory
  // looking for "default.xex" (case-insensitive). We do NOT build a full
  // VFS entry tree, since we only need the executable's xex2 optional
  // headers.
  bool ParseIsoDiscInfo(const std::unique_ptr<DocumentFile>& file,
                        uint32_t* out_media_id,
                        uint32_t* out_disc_number,
                        uint32_t* out_disc_count,
                        std::string* out_name);

  std::mutex mutex_;

  // Keyed by (media_id, disc_number) for O(log n) lookup.
  // Stored as a multimap-equivalent (std::map with pair key) — duplicate
  // inserts for the same key overwrite the previous entry, which is the
  // desired behavior (last scan wins).
  std::map<std::pair<uint32_t, uint32_t>, DiscInfo> disc_map_;

  // The media_id of the currently-launched game. FindDisc() will only
  // return discs whose media_id matches this. Set by RegisterLaunchedGame.
  uint32_t active_media_id_ = 0;
};

}  // namespace xe

#endif  // AX360E_XE_MULTI_DISC_H
