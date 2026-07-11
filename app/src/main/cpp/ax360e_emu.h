// SPDX-License-Identifier: WTFPL

#ifndef AX360E_AX360E_EMU_H
#define AX360E_AX360E_EMU_H

#include "xenia/app/emulator_window.h"
#include "xenia/ui/windowed_app_context.h"
#include "xenia/ui/windowed_app.h"
#include "xenia/ui/menu_item.h"
#include "xenia/ui/surface_android.h"

class AndroidWindowedAppContext final : public xe::ui::WindowedAppContext {
public:
    struct EventItem {
        int type;
        bool* completed;
    };

    std::queue<EventItem> events;
    static const int EVENT_EXECUTE_PENDING_FUNCTIONS = 1;
    static const int EVENT_QUIT = 2;
    static const int EVENT_PAINT = 3;
    static const int EVENT_SURFACE_CHANGED = 4;

    std::mutex mutex;
    std::condition_variable cv;

    std::atomic<bool> paint_pending_{false};

    // [ANDROID SURFACE RECOVERY v2]
    //
    // ## Root Cause (revised after investigating the hang)
    //
    // The previous fix (commit 61c4a852) forced UpdateSurface() on the UI
    // thread whenever the guest output thread reported an outdated surface.
    // However, UpdateSurface() calls SetWindowSurfaceFromUIThread(nullptr)
    // which internally calls AwaitAllSubmissionsCompletion(). That function
    // used vkWaitForFences(..., UINT64_MAX) — an INFINITE wait. On Android,
    // when SurfaceFlinger recycles the ANativeWindow's buffer queue, the GPU
    // may have pending submissions whose fences will NEVER be signaled
    // (because the swapchain images are being retired by the WSI). The
    // infinite wait hangs the UI thread permanently — the log ends at
    // "Creating Android surface..." and the game freezes.
    //
    // Additionally, surfaceDestroyed (Java) only set ae::window = nullptr
    // without notifying the native side, so the native presenter kept trying
    // to use a stale ANativeWindow.
    //
    // ## Fix
    //
    // 1. vulkan_submission_tracker.cc: vkWaitForFences now uses a 5-second
    //    timeout instead of UINT64_MAX. If the fence times out, we proceed
    //    anyway (the swapchain will be destroyed regardless).
    //
    // 2. EmulatorActivity.kt surfaceDestroyed: now calls surface_changed()
    //    to push EVENT_SURFACE_CHANGED, so the native side disconnects the
    //    presenter proactively — before the guest output thread tries to
    //    recover with a stale ANativeWindow.
    //
    // 3. main_loop EVENT_PAINT handler: before calling UpdateSurface(),
    //    validate that ae::window is non-null AND has a valid size. If the
    //    window is gone (surfaceDestroyed was called), skip the forced
    //    recovery and let the normal PaintFromUIThread recovery path handle
    //    it. When surfaceCreated provides a new ANativeWindow,
    //    EVENT_SURFACE_CHANGED will be pushed and the presenter will
    //    reconnect cleanly.
    //
    // 4. Deferred retry: if the guest output thread keeps requesting paint
    //    (surface still outdated after recovery attempt), the cooldown
    //    prevents excessive UpdateSurface() calls. The paint_pending_ flag
    //    ensures each request is processed once.
    std::atomic<bool> paint_from_guest_thread_{false};
    std::atomic<int64_t> last_surface_recovery_ns_{0};
    static constexpr int64_t SURFACE_RECOVERY_COOLDOWN_NS = 500'000'000LL; // 500ms

    void NotifyUILoopOfPendingFunctions() override;

    void PlatformQuitFromUIThread() override;

    void setup_ui_thr_id(std::thread::id id);

    void request_paint();

    void request_surface_changed();

    void main_loop();

    AndroidWindowedAppContext();

    ~AndroidWindowedAppContext();

};

class AndroidWindow final : public xe::ui::Window {
public:
    AndroidWindow(xe::ui::WindowedAppContext& app_context, const std::string_view title,
                  uint32_t desired_logical_width, uint32_t desired_logical_height);

protected:
    bool OpenImpl() override;
    void RequestCloseImpl() override;
    std::unique_ptr<xe::ui::Surface> CreateSurfaceImpl(xe::ui::Surface::TypeFlags allowed_types) override;
    void RequestPaintImpl() override;

public:
    void UpdateSurface();
    void Paint();
};

class android_menu_item final : public xe::ui::MenuItem {
public:
    android_menu_item(Type type, const std::string& text, const std::string& hotkey,
                      std::function<void()> callback);
};


class EmulatorApp final : public xe::ui::WindowedApp {
public:
    static std::unique_ptr<xe::ui::WindowedApp> create(xe::ui::WindowedAppContext& app_context);

    std::unique_ptr<xe::Emulator> emu;
    std::unique_ptr<xe::app::EmulatorWindow> emu_window;
    std::unique_ptr<xe::threading::Event> emu_thr_event;

    std::atomic<bool> emu_thr_quit_requested;
    std::thread emu_thr;

    template <typename T, typename... Args>
    class Factory {
    private:
        struct Creator {
            std::string name;
            std::function<bool()> is_available;
            std::function<std::unique_ptr<T>(Args...)> instantiate;
        };

        std::vector<Creator> creators_;

    public:
        void Add(const std::string_view name, std::function<bool()> is_available,
                 std::function<std::unique_ptr<T>(Args...)> instantiate) {
            creators_.push_back({std::string(name), is_available, instantiate});
        }

        void Add(const std::string_view name,
                 std::function<std::unique_ptr<T>(Args...)> instantiate) {
            auto always_available = []() { return true; };
            Add(name, always_available, instantiate);
        }

        template <typename DT>
        void Add(const std::string_view name) {
            Add(name, DT::IsAvailable, [](Args... args) {
                return std::make_unique<DT>(std::forward<Args>(args)...);
            });
        }

        std::unique_ptr<T> Create(const std::string_view name, Args... args) {
            if (!name.empty() && name != "any") {
                auto it = std::find_if(
                        creators_.cbegin(), creators_.cend(),
                        [&name](const auto& f) { return name.compare(f.name) == 0; });
                if (it != creators_.cend() && (*it).is_available()) {
                    return (*it).instantiate(std::forward<Args>(args)...);
                }
                return nullptr;
            } else {
                for (const auto& creator : creators_) {
                    if (!creator.is_available()) continue;
                    auto instance = creator.instantiate(std::forward<Args>(args)...);
                    if (!instance) continue;
                    return instance;
                }
                return nullptr;
            }
        }

        std::vector<std::unique_ptr<T>> CreateAll(const std::string_view name,
                                                  Args... args) {
            std::vector<std::unique_ptr<T>> instances;
            if (!name.empty() && name != "any") {
                auto it = std::find_if(
                        creators_.cbegin(), creators_.cend(),
                        [&name](const auto& f) { return name.compare(f.name) == 0; });
                if (it != creators_.cend() && (*it).is_available()) {
                    auto instance = (*it).instantiate(std::forward<Args>(args)...);
                    if (instance) {
                        instances.emplace_back(std::move(instance));
                    }
                }
            } else {
                for (const auto& creator : creators_) {
                    if (!creator.is_available()) continue;
                    auto instance = creator.instantiate(std::forward<Args>(args)...);
                    if (instance) {
                        instances.emplace_back(std::move(instance));
                    }
                }
            }
            return instances;
        }
    };

    EmulatorApp(xe::ui::WindowedAppContext& app_context);
    bool OnInitialize() override;
    static std::unique_ptr<xe::apu::AudioSystem> create_audio_system(xe::cpu::Processor* processor);
    static std::unique_ptr<xe::gpu::GraphicsSystem> create_graphics_system();
    static std::vector<std::unique_ptr<xe::hid::InputDriver>> create_input_drivers(xe::ui::Window* window);
    void emu_thr_main();
};
#endif //AX360E_AX360E_EMU_H
