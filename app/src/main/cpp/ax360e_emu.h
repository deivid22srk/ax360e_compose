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

    // [ANDROID SURFACE RECOVERY] When the guest output thread (GPU Commands
    // thread) detects that the Vulkan surface is outdated (vkAcquireNextImageKHR
    // returns VK_ERROR_OUT_OF_DATE_KHR), it calls window->RequestPaint() to
    // ask the UI thread to recover. The UI thread then tries to recreate the
    // swapchain, but if the VkSurface itself is invalid (e.g., the ANativeWindow
    // was recycled by Android's SurfaceFlinger), the swapchain recreation fails
    // and the presenter enters kUnconnectedRetryAtStateChange — a stuck state
    // that only a surface change event can recover from.
    //
    // On Android, surface change events only come from the Java side
    // (SurfaceHolder.Callback.surfaceChanged), which is NOT called when the
    // surface becomes outdated organically (e.g., due to power management,
    // driver issues, or SurfaceFlinger recycling the surface buffer queue).
    //
    // To recover, we detect paint requests from the guest output thread
    // (by checking the caller's thread ID against the UI thread ID) and
    // force a full surface recreation via UpdateSurface() before painting.
    // This destroys the old VkSurface and creates a new one from the current
    // ANativeWindow, breaking the stuck state.
    //
    // A cooldown of 500ms prevents excessive surface recreation if the
    // recovery itself fails (e.g., if the ANativeWindow is truly gone).
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
