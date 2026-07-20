/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2020 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#include <jni.h>

#include <android/asset_manager.h>
#include <android/configuration.h>
#include <android/looper.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <array>
#include <cctype>
#include <chrono>
#include <memory>
#include <string_view>
#include <thread>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <sched.h>
#include <unistd.h>
#include <errno.h>

#include "xenia/apu/nop/nop_audio_system.h"
#include "xenia/base/cvar.h"
#include "xenia/base/logging.h"
#include "xenia/base/profiling.h"
#include "xenia/config.h"
#include "xenia/emulator.h"
#include "xenia/gpu/graphics_system.h"
#include "xenia/gpu/null/null_graphics_system.h"
#include "xenia/gpu/vulkan/vulkan_graphics_system.h"
#include "xenia/hid/nop/nop_hid.h"
#include "xenia/kernel/user_module.h"
#include "xenia/kernel/util/xex2_info.h"
#include "xenia/kernel/xam/xam_module.h"
#include "xenia/vfs/devices/host_path_device.h"

#include "emulator.h"
#include "emulator_ax360e.h"

#include "xe_android_hid.h"
#include "xe_android_input_driver.h"
#include "xe_opensles_audio_system.h"
#include "xe_aaudio_audio_system.h"
#include "document_file.h"
#include "xe_multi_disc.h"

#include "ax360e_emu.h"
//#include "nlohmann/json.hpp"

#define LOG_TAG "ax360e_native"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG,__VA_ARGS__);

DEFINE_string(apu, "aaudio", "Audio system. Use: [any, nop, opensles, aaudio]", "APU");
DEFINE_string(gpu, "vulkan", "Graphics system. Use: [vulkan, null]",
              "GPU");
DEFINE_string(hid, "android", "Input system. Use: [android, nop]",
              "HID");

DEFINE_path(
        storage_root, "",
        "Root path for persistent internal data storage (config, etc.), or empty "
        "to use the path preferred for the OS, such as the documents folder, or "
        "the emulator executable directory if portable.txt is present in it.",
        "Storage");
DEFINE_path(
        content_root, "",
        "Root path for guest content storage (saves, etc.), or empty to use the "
        "content folder under the storage root.",
        "Storage");
DEFINE_path(
        cache_root, "",
        "Root path for files used to speed up certain parts of the emulator or the "
        "game. These files may be persistent, but they can be deleted without "
        "major side effects such as progress loss. If empty, the cache folder "
        "under the storage root, or, if available, the cache directory preferred "
        "for the OS, will be used.",
        "Storage");

DEFINE_bool(mount_scratch, false, "Enable scratch mount", "Storage");
DEFINE_bool(mount_cache, false, "Enable cache mount", "Storage");
DEFINE_bool(mount_memory_unit, false, "Enable memory unit (MU) mount",
            "Storage");

DECLARE_bool(force_mount_devkit);
DEFINE_transient_path(target, "",
                      "Specifies the target .xex or .iso to execute.",
                      "General");
DEFINE_transient_bool(portable, false,
                      "Specifies if Xenia should run in portable mode.",
                      "General");

DECLARE_bool(debug);

DEFINE_bool(discord, false, "Enable Discord rich presence", "General");

extern JavaVM* g_jvm;
namespace ae{
    extern std::unique_ptr<xe::ui::WindowedApp> g_windowed_app;
}
void AndroidWindowedAppContext::NotifyUILoopOfPendingFunctions() {
    std::unique_lock<std::mutex> lock(mutex);
    bool completed = false;
    events.push({EVENT_EXECUTE_PENDING_FUNCTIONS, &completed});
    cv.notify_one();
    cv.wait(lock, [&completed] { return completed; });
}

void AndroidWindowedAppContext::PlatformQuitFromUIThread() {
    std::lock_guard<std::mutex> lock(mutex);
    events.push({EVENT_QUIT, nullptr});
    cv.notify_one();
}

void AndroidWindowedAppContext::request_paint() {
    bool expected = false;
    if (!paint_pending_.compare_exchange_strong(expected, true)) {
        return;
    }
    // [ANDROID SURFACE RECOVERY v2] Detect if this paint request comes from
    // the guest output thread (GPU Commands thread) rather than the UI
    // thread. The guest output thread calls window->RequestPaint() via
    // Presenter::RequestPaintOrConnectionRecoveryViaWindow(true) ONLY when
    // the Vulkan surface is outdated (VK_ERROR_OUT_OF_DATE_KHR from
    // vkAcquireNextImageKHR). Normal frame presentation goes through
    // vkQueuePresentKHR directly, NOT through RequestPaint.
    //
    // So if the caller is not the UI thread, this is a surface recovery
    // request. We set a flag so the main_loop can validate ae::window and
    // decide whether to force a full surface recreation (UpdateSurface)
    // before painting.
    if (WindowedAppContext::ui_thread_id_ != std::thread::id{} &&
        std::this_thread::get_id() != WindowedAppContext::ui_thread_id_) {
        paint_from_guest_thread_.store(true, std::memory_order_relaxed);
    }
    std::lock_guard<std::mutex> lock(mutex);
    events.push({EVENT_PAINT, nullptr});
    cv.notify_one();
}

void AndroidWindowedAppContext::request_surface_changed() {
    std::lock_guard<std::mutex> lock(mutex);
    events.push({EVENT_SURFACE_CHANGED, nullptr});
    cv.notify_one();
}

void AndroidWindowedAppContext::setup_ui_thr_id(std::thread::id id){
    WindowedAppContext::ui_thread_id_=id;
}

void AndroidWindowedAppContext::main_loop(){
    assert(WindowedAppContext::ui_thread_id_==std::this_thread::get_id());
    while(!WindowedAppContext::HasQuitFromUIThread()){
        EventItem item;
        {
            std::unique_lock<std::mutex> lock(mutex);
            cv.wait(lock, [this] { return !events.empty() || WindowedAppContext::HasQuitFromUIThread(); });
            if(events.empty()) {
                continue;
            }
            item = events.front();
            events.pop();
        }

        if(item.type==EVENT_EXECUTE_PENDING_FUNCTIONS){
            WindowedAppContext::ExecutePendingFunctionsFromUIThread();
            {
                std::lock_guard<std::mutex> lock(mutex);
                *item.completed = true;
            }
            cv.notify_one();
        }
        else if(item.type==EVENT_PAINT){
            paint_pending_.store(false, std::memory_order_release);
            EmulatorApp* app=static_cast<EmulatorApp*>(ae::g_windowed_app.get());
            AndroidWindow* win=static_cast<AndroidWindow*>(app->emu_window->window());

            // [ANDROID SURFACE RECOVERY v2]
            //
            // If this paint was requested by the guest output thread (GPU
            // Commands thread), it means the Vulkan surface is outdated.
            //
            // Before forcing UpdateSurface(), we MUST validate that ae::window
            // is still usable. If surfaceDestroyed was called (ae::window ==
            // nullptr), calling UpdateSurface() would create a surface with a
            // null ANativeWindow, and the internal
            // AwaitAllSubmissionsCompletion() would wait for GPU fences that
            // may never be signaled — hanging the UI thread.
            //
            // With the companion fix in vulkan_submission_tracker.cc (5-second
            // fence wait timeout instead of UINT64_MAX), even if we DO proceed
            // with UpdateSurface(), the UI thread will no longer hang forever.
            // But it's still better to skip the forced recovery when the
            // window is clearly gone, and let the normal PaintFromUIThread
            // recovery path handle it gracefully.
            //
            // When surfaceCreated provides a new ANativeWindow, it calls
            // surface_changed() which pushes EVENT_SURFACE_CHANGED, and the
            // presenter reconnects cleanly via the normal surface-changed flow.
            bool from_guest = paint_from_guest_thread_.exchange(false,
                                                                std::memory_order_relaxed);
            if (from_guest) {
                bool can_recover = false;
                if (ae::window) {
                    int w = ANativeWindow_getWidth(ae::window);
                    int h = ANativeWindow_getHeight(ae::window);
                    if (w > 0 && h > 0) {
                        can_recover = true;
                    } else {
                        XELOGW("[SURFACE RECOVERY] ae::window has invalid size "
                               "({}x{}) — skipping forced UpdateSurface(), "
                               "waiting for new surface from Java", w, h);
                    }
                } else {
                    XELOGW("[SURFACE RECOVERY] ae::window is null "
                           "(surface destroyed) — skipping forced "
                           "UpdateSurface(), waiting for surfaceCreated");
                }

                if (can_recover) {
                    auto now_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
                        std::chrono::steady_clock::now().time_since_epoch()).count();
                    int64_t last_ns = last_surface_recovery_ns_.load(std::memory_order_relaxed);
                    if (now_ns - last_ns > SURFACE_RECOVERY_COOLDOWN_NS) {
                        last_surface_recovery_ns_.store(now_ns, std::memory_order_relaxed);
                        XELOGI("[SURFACE RECOVERY] Guest output thread reported "
                               "outdated surface — forcing UpdateSurface() "
                               "before paint (ae::window is valid)");
                        win->UpdateSurface();
                    }
                }
            }

            win->Paint();
        }
        else if(item.type==EVENT_SURFACE_CHANGED){
            EmulatorApp* app=static_cast<EmulatorApp*>(ae::g_windowed_app.get());
            AndroidWindow* win=static_cast<AndroidWindow*>(app->emu_window->window());
            win->UpdateSurface();
        }
        else if(item.type==EVENT_QUIT){
            WindowedAppContext::QuitFromUIThread();
            cv.notify_all();
            return;
        }
    }
}

AndroidWindowedAppContext::AndroidWindowedAppContext() {
}

AndroidWindowedAppContext::~AndroidWindowedAppContext(){
}

AndroidWindow::AndroidWindow(xe::ui::WindowedAppContext& app_context, const std::string_view title,
                             uint32_t desired_logical_width, uint32_t desired_logical_height)
                             : Window(app_context, title, desired_logical_width, desired_logical_height) {}

bool AndroidWindow::OpenImpl() {
    XELOGI("Opening Android window...");

    if (ae::window) {
        int w = ANativeWindow_getWidth(ae::window);
        int h = ANativeWindow_getHeight(ae::window);
        if (w > 0 && h > 0) {
            OnDesiredLogicalSizeUpdate(SizeToLogical(w), SizeToLogical(h));
            WindowDestructionReceiver destruction_receiver(this);
            OnActualSizeUpdate(uint32_t(w), uint32_t(h), destruction_receiver);
        } else {
            XELOGW("Android window has invalid size: {}x{}", w, h);
        }
    }

    return true;
}

void AndroidWindow::RequestCloseImpl() {
    XELOGI("Requesting Android window close...");
}

std::unique_ptr<xe::ui::Surface> AndroidWindow::CreateSurfaceImpl(xe::ui::Surface::TypeFlags allowed_types) {
    XELOGI("Creating Android surface...");
    if(allowed_types&xe::ui::Surface::kTypeFlag_AndroidNativeWindow) {
        ANativeWindow *window = ae::window;
        return std::make_unique<xe::ui::AndroidNativeWindowSurface>(window);
    }
    return nullptr;
}

void AndroidWindow::RequestPaintImpl() {
    XELOGI("Requesting Android window paint...");
    static_cast<AndroidWindowedAppContext&>(app_context()).request_paint();
}

void AndroidWindow::UpdateSurface(){
    if (ae::window) {
        int w = ANativeWindow_getWidth(ae::window);
        int h = ANativeWindow_getHeight(ae::window);
        if (w > 0 && h > 0) {
            OnDesiredLogicalSizeUpdate(SizeToLogical(w), SizeToLogical(h));
            WindowDestructionReceiver destruction_receiver(this);
            OnActualSizeUpdate(uint32_t(w), uint32_t(h), destruction_receiver);
            if (destruction_receiver.IsWindowDestroyedOrClosed()) {
                return;
            }
        }
    }
    OnSurfaceChanged(true);
}

void AndroidWindow::Paint(){
    // ax360e real-fix: skip Paint() entirely when the Android surface is gone.
    //
    // Before this guard, Paint() was called from main_loop's EVENT_PAINT
    // handler even when surfaceDestroyed() had already cleared ae::window.
    // OnPaint(false) would then route through the presenter, which would
    // attempt to vkAcquireNextImageKHR on a stale/outdated swapchain and log
    // "Presentation to the swapchain image has been dropped as the swapchain
    // or the surface has become outdated" — repeating every frame and
    // poisoning the log. It also kept the guest output thread spinning on
    // surface recovery attempts that could never succeed without a new
    // ANativeWindow from surfaceCreated.
    //
    // With this guard:
    //   - If ae::window is null (surfaceDestroyed ran), Paint() is a no-op.
    //   - The guest output thread is allowed to keep running its emulation
    //     loop (CPU keeps stepping, audio keeps playing), but presentation
    //     is suspended until surfaceCreated provides a new ANativeWindow.
    //   - When surfaceCreated arrives, EVENT_SURFACE_CHANGED is pushed,
    //     AndroidWindow::UpdateSurface() reconnects the presenter, and the
    //     next Paint() will actually present.
    //
    // This is a "pause presentation, not pause emulation" strategy. It
    // matches what the user expects when the app is briefly backgrounded
    // or when the surface is recreated during orientation change.
    if (!ae::window) {
        static int64_t last_skip_log_ns = 0;
        const auto now_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(
                                std::chrono::steady_clock::now().time_since_epoch())
                                .count();
        // Throttle the skip log to once per 5 seconds to avoid log noise.
        if (now_ns - last_skip_log_ns > 5'000'000'000LL) {
            last_skip_log_ns = now_ns;
            XELOGI("[AndroidWindow::Paint] surface is null (destroyed) — "
                   "skipping OnPaint, emulation continues but presentation "
                   "is paused until surfaceCreated");
        }
        return;
    }
    OnPaint(false);
}

std::unique_ptr<xe::ui::Window> xe::ui::Window::Create(WindowedAppContext& app_context,
                                                       const std::string_view title,
                                                       uint32_t desired_logical_width,
                                                       uint32_t desired_logical_height) {
    return std::make_unique<AndroidWindow>(
            app_context, title, desired_logical_width, desired_logical_height);
}

android_menu_item::android_menu_item(Type type, const std::string& text, const std::string& hotkey,
                                     std::function<void()> callback)
        : MenuItem(type, text, hotkey, callback) {
    LOGW("android_menu_item: %d %s %s",static_cast<int>(type),text.c_str(),hotkey.c_str());
}

std::unique_ptr<xe::ui::MenuItem> xe::ui::MenuItem::Create(Type type,
                                                   const std::string& text,
                                                   const std::string& hotkey,
                                                   std::function<void()> callback) {
    return std::make_unique<android_menu_item>(type, text, hotkey, callback);
}


std::unique_ptr<xe::ui::WindowedApp> EmulatorApp::create(xe::ui::WindowedAppContext& app_context) {
    return std::unique_ptr<xe::ui::WindowedApp>(new EmulatorApp(app_context));
}

EmulatorApp::EmulatorApp(xe::ui::WindowedAppContext& app_context)
        : WindowedApp(app_context,"ax36e") {
}

bool EmulatorApp::OnInitialize() {

    xe::Profiler::Initialize();
    xe::Profiler::ThreadEnter("Main");

    std::filesystem::path storage_root=cvars::storage_root;

    storage_root = std::filesystem::absolute(storage_root);
    XELOGI("Storage root: {}", storage_root.c_str());

    config::SetupConfig(storage_root);

#if XE_ARCH_AMD64 == 1
    xe::amd64::InitFeatureFlags();
#elif XE_ARCH_ARM64 == 1
    xe::arm64::InitFeatureFlags();
#endif

    std::filesystem::path content_root = cvars::content_root;
    if (content_root.empty()) {
        content_root = storage_root / "content";
    } else {
        // If content root isn't an absolute path, then it should be relative to the
        // storage root.
        if (!content_root.is_absolute()) {
            content_root = storage_root / content_root;
        }
    }
    content_root = std::filesystem::absolute(content_root);
    XELOGI("Content root: {}", content_root.c_str());

    std::filesystem::path cache_root = cvars::cache_root;
    if (cache_root.empty()) {
        cache_root = storage_root / "cache";
        // TODO(Triang3l): Point to the app's external storage "cache" directory on
        // Android.
    } else {
        // If content root isn't an absolute path, then it should be relative to the
        // storage root.
        if (!cache_root.is_absolute()) {
            cache_root = storage_root / cache_root;
        }
    }
    cache_root = std::filesystem::absolute(cache_root);
    XELOGI("Host cache root: {}", cache_root);

    // Create the emulator but don't initialize so we can setup the window.
    emu =
            std::make_unique<xe::Emulator>("", storage_root, content_root, cache_root);

    // Determine window size based on user setting.
    auto res = xe::gpu::GraphicsSystem::GetInternalDisplayResolution();

    // Main emulator display window.
    emu_window = xe::app::EmulatorWindow::Create(emu.get(), app_context(),
                                                 ae::window_width,ae::window_height);

    if (!emu_window) {
        XELOGE("Failed to create the main emulator window");
        return false;
    }

    emu_thr_quit_requested.store(false, std::memory_order_relaxed);
    emu_thr_event = xe::threading::Event::CreateAutoResetEvent(false);
    assert_not_null(emu_thr_event);
    emu_thr = std::thread(&EmulatorApp::emu_thr_main, this);

    return true;
}

std::unique_ptr<xe::apu::AudioSystem> EmulatorApp::create_audio_system(xe::cpu::Processor* processor) {
    Factory<xe::apu::AudioSystem, xe::cpu::Processor*> factory;
    factory.Add<xe::apu::nop::NopAudioSystem>("nop");
#if XE_PLATFORM_AX360E
    factory.Add<xe::apu::opensles::OpenSLESAudioSystem>("opensles");
    factory.Add<xe::apu::aaudio::AAudioAudioSystem>("aaudio");
#endif
    return factory.Create(cvars::apu, processor);
}

std::unique_ptr<xe::gpu::GraphicsSystem> EmulatorApp::create_graphics_system() {
    Factory<xe::gpu::GraphicsSystem> factory;
    factory.Add<xe::gpu::vulkan::VulkanGraphicsSystem>("vulkan");
    factory.Add<xe::gpu::null::NullGraphicsSystem>("null");
    return factory.Create(cvars::gpu);
}


std::vector<std::unique_ptr<xe::hid::InputDriver>> EmulatorApp::create_input_drivers(xe::ui::Window* window) {
    std::vector<std::unique_ptr<xe::hid::InputDriver>> drivers;
    if (cvars::hid.compare("nop") == 0) {
        drivers.emplace_back(xe::hid::nop::Create(window, xe::app::EmulatorWindow::kZOrderHidInput));
    }
    else {
        Factory<xe::hid::InputDriver, xe::ui::Window *, size_t> factory;
        factory.Add("android", xe::hid::android::Create);

        for (auto &driver: factory.CreateAll(cvars::hid, window,
                                             xe::app::EmulatorWindow::kZOrderHidInput)) {
            if (XSUCCEEDED(driver->Setup())) {
                drivers.emplace_back(std::move(driver));
            }
        }
    }
    if (drivers.empty()) {
        // Fallback to nop if none created.
        drivers.emplace_back(
                xe::hid::nop::Create(window, xe::app::EmulatorWindow::kZOrderHidInput));
    }
    return drivers;
}

void EmulatorApp::emu_thr_main() {
    assert_not_null(emu_thr_event);

    JNIEnv *env;
    g_jvm->AttachCurrentThread(&env, nullptr);

    xe::threading::set_name("Emulator");
    xe::Profiler::ThreadEnter("Emulator");

    // [ANDROID PERF] Raise the emulator thread's scheduling priority.
    // On Android, threads default to nice=0 (SCHED_OTHER). The emulator
    // thread is the single hottest thread in the process - it runs the
    // PPC JIT, the kernel, and drives the GPU command processor. Setting
    // nice=-8 gives it priority over background GC, binder threads, and
    // UI housekeeping without requiring SCHED_FIFO (which needs CAP_SYS_NICE
    // and is blocked by Android's SELinux policy for unprivileged apps).
    // -8 is chosen as a safe value: it's above foreground (-6) but below
    // audio threads (-16) so audio glitching is avoided if AAudio uses
    // its own high-priority thread.
    // We use setpriority directly because xenia's threading::set_priority
    // tries SCHED_FIFO first (which fails with EPERM on Android) and then
    // falls back to nice, but logs a warning each time - calling
    // setpriority directly avoids the warning noise.
    {
        int tid = static_cast<int>(gettid());
        if (setpriority(PRIO_PROCESS, tid, -8) != 0) {
            // EPERM is expected if the process has already dropped privileges.
            // Most Android apps can still lower nice to -8 within their
            // scheduling class via setpriority - the kernel allows up to
            // RLIMIT_NICE (default -10 on Android).
            XELOGW("Failed to set emulator thread priority: errno={}", errno);
        }
    }

    // [ANDROID PERF] Pin the emulator thread to the "big" cores on
    // big.LITTLE ARM64 SoCs (Snapdragon, Exynos, Dimensity). On a typical
    // 8-core SoC with 1 prime + 3 big + 4 little (e.g. Cortex-X2 + A710 +
    // A510), the default scheduler may migrate the emulator thread to
    // little cores during transient load drops, causing 5-10x slowdown
    // when the workload ramps back up. By pinning to cores 4-7 (the big +
    // prime cluster on most Android SoCs), we ensure consistent high-
    // performance execution. This is a heuristic - if the SoC has a
    // different topology (e.g. all-big), the affinity mask still works
    // because sched_setaffinity ignores non-existent CPUs in the mask.
    // The sysconf(_SC_NPROCESSORS_ONLN) check ensures we don't set bits
    // for CPUs that don't exist (which would fail with EINVAL).
    {
        int cpu_count = static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN));
        if (cpu_count > 4) {
            cpu_set_t mask;
            CPU_ZERO(&mask);
            // Pin to cores 4..cpu_count-1 (typically the big/prime cluster).
            // On 8-core SoCs this is cores 4-7. On 6-core (3+3) it's 3-5.
            for (int i = 4; i < cpu_count; ++i) {
                CPU_SET(i, &mask);
            }
            int tid = static_cast<int>(gettid());
            if (sched_setaffinity(tid, sizeof(cpu_set_t), &mask) != 0) {
                // EPERM is possible on some Android versions for unprivileged
                // apps, but most allow it within the same process.
                XELOGW("Failed to set emulator thread affinity: errno={}", errno);
            }
        }
    }

    // Setup and initialize all subsystems. If we can't do something
    // (unsupported system, memory issues, etc) this will fail early.
    xe::X_STATUS result = emu->Setup(
            emu_window->window(), emu_window->imgui_drawer(), true,
            create_audio_system, create_graphics_system, create_input_drivers);
    if (XFAILED(result)) {
        XELOGE("Failed to setup emulator: {:08X}", result);
        app_context().RequestDeferredQuit();
        return;
    }
    app_context().CallInUIThread(
            [this]() { emu_window->SetupGraphicsSystemPresenterPainting(); });
    const auto fs = emu->file_system();

    if (cvars::mount_scratch) {
        auto scratch_device = std::make_unique<xe::vfs::HostPathDevice>(
                "\\SCRATCH", emu->storage_root() / "scratch", false);
        if (!scratch_device->Initialize()) {
            XELOGE("Unable to scan scratch path");
        } else {
            if (!fs->RegisterDevice(std::move(scratch_device))) {
                XELOGE("Unable to register scratch path");
            } else {
                fs->RegisterSymbolicLink("scratch:", "\\SCRATCH");
            }
        }
    }

    if (cvars::mount_cache) {
        auto cache0_device = std::make_unique<xe::vfs::HostPathDevice>(
                "\\CACHE0", emu->storage_root() / "cache0", false);
        if (!cache0_device->Initialize()) {
            XELOGE("Unable to scan cache0 path");
        } else {
            if (!fs->RegisterDevice(std::move(cache0_device))) {
                XELOGE("Unable to register cache0 path");
            } else {
                fs->RegisterSymbolicLink("cache0:", "\\CACHE0");
            }
        }

        auto cache1_device = std::make_unique<xe::vfs::HostPathDevice>(
                "\\CACHE1", emu->storage_root() / "cache1", false);
        if (!cache1_device->Initialize()) {
            XELOGE("Unable to scan cache1 path");
        } else {
            if (!fs->RegisterDevice(std::move(cache1_device))) {
                XELOGE("Unable to register cache1 path");
            } else {
                fs->RegisterSymbolicLink("cache1:", "\\CACHE1");
            }
        }

        // Some (older?) games try accessing cache:\ too
        // NOTE: this must be registered _after_ the cache0/cache1 devices, due to
        // substring/start_with logic inside VirtualFileSystem::ResolvePath, else
        // accesses to those devices will go here instead
        auto cache_device = std::make_unique<xe::vfs::HostPathDevice>(
                "\\CACHE", emu->storage_root() / "cache", false);
        if (!cache_device->Initialize()) {
            XELOGE("Unable to scan cache path");
        } else {
            if (!fs->RegisterDevice(std::move(cache_device))) {
                XELOGE("Unable to register cache path");
            } else {
                fs->RegisterSymbolicLink("cache:", "\\CACHE");
            }
        }
    }

    // [VFS DEVICE REGISTRATION] Always register cache: and update: devices
    // regardless of cvars::mount_cache. Many games (Forza Horizon 2, etc.)
    // access cache:\ and update:\ during boot, and if these devices aren't
    // registered, the VFS logs "ResolvePath(...) failed - device not found"
    // which clutters the log and can cause game logic to take error paths.
    //
    // cache: points to {storage_root}/cache (already created by
    // Application.onCreate which makes cache, cache0, cache1 dirs).
    // update: points to {storage_root}/update (created on demand).
    // \Device is registered as a NullDevice so ResolvePath(\Device) doesn't
    // log an error (the guest uses it for XFileFsDeviceInformation queries).
    if (!cvars::mount_cache) {
        auto cache_device = std::make_unique<xe::vfs::HostPathDevice>(
                "\\CACHE", emu->storage_root() / "cache", false);
        if (cache_device->Initialize()) {
            if (fs->RegisterDevice(std::move(cache_device))) {
                fs->RegisterSymbolicLink("cache:", "\\CACHE");
            }
        }
    }

    // update: device — used by some games to check for title updates.
    // Point to {storage_root}/update (empty dir = no updates).
    {
        auto update_dir = emu->storage_root() / "update";
        std::error_code ec;
        std::filesystem::create_directories(update_dir, ec);
        auto update_device = std::make_unique<xe::vfs::HostPathDevice>(
                "\\UPDATE", update_dir, false);
        if (update_device->Initialize()) {
            if (fs->RegisterDevice(std::move(update_device))) {
                fs->RegisterSymbolicLink("update:", "\\UPDATE");
            }
        }
    }

    if (cvars::force_mount_devkit) {
        auto devkit_device =
                std::make_unique<xe::vfs::HostPathDevice>("\\DEVKIT", "devkit", false);

        if (!devkit_device->Initialize()) {
            XELOGE("Unable to scan devkit path");
        }

        if (!fs->RegisterDevice(std::move(devkit_device))) {
            XELOGE("Unable to register devkit path");
        }

        fs->RegisterSymbolicLink("DEVKIT:", "\\DEVKIT");
        fs->RegisterSymbolicLink("e:", "\\DEVKIT");
    }

    if (cvars::mount_memory_unit) {
        auto mu_device =
                std::make_unique<xe::vfs::HostPathDevice>("\\MU", "MU", false);

        if (!mu_device->Initialize()) {
            XELOGE("Unable to scan MU path");
        }

        if (!fs->RegisterDevice(std::move(mu_device))) {
            XELOGE("Unable to register MU path");
        }

        fs->RegisterSymbolicLink("MU:", "\\MU");
    }

// Set a debug handler.
// This will respond to debugging requests so we can open the debug UI.
    /*if (cvars::debug) {
        emulator_->processor()->set_debug_listener_request_handler(
                [this](xe::cpu::Processor* processor) {
                    if (debug_window_) {
                        return debug_window_.get();
                    }
                    app_context().CallInUIThreadSynchronous([this]() {
                        debug_window_ = xe::debug::ui::DebugWindow::Create(emulator_.get(),
                                                                           app_context());
                        debug_window_->window()->AddListener(
                                &debug_window_closed_listener_);
                    });
                    // If failed to enqueue the UI thread call, this will just be null.
                    return debug_window_.get();
                });
    }*/
#if 1
    emu->on_launch.AddListener([&](auto title_id, const auto& game_title) {
        XELOGI("on_launch {}",
               game_title.empty() ? "Unknown Title" : std::string(game_title));
        app_context().CallInUIThread([this]() { emu_window->UpdateTitle(); });

        // [MULTI-DISC SUPPORT]
        //
        // At this point the executable module is loaded and we can read its
        // xex2_opt_execution_info to obtain media_id and disc_number. We
        // then register the launched disc with MultiDiscManager, which
        // triggers a scan of sibling ISO files in the parent SAF directory.
        // The scan runs synchronously on this (emulator) thread — it can
        // take a few seconds for directories with many ISOs, but it does
        // not block the game thread (which is the PPC guest thread, not
        // this one).
        //
        // We use `ae::boot_game_uri` (a global set by j_setup_game_path
        // before OnInitialize runs) to re-create a DocumentFile clone.
        // The original DocumentFile was moved into LaunchDiscImage earlier
        // and is no longer accessible.
        try {
            auto exec_module = emu->kernel_state()->GetExecutableModule();
            if (exec_module) {
                xex2_opt_execution_info* info = nullptr;
                exec_module->GetOptHeader(XEX_HEADER_EXECUTION_INFO, &info);
                if (info) {
                    uint32_t media_id =
                            static_cast<uint32_t>(info->media_id);
                    uint32_t disc_number =
                            static_cast<uint32_t>(info->disc_number);
                    uint32_t disc_count =
                            static_cast<uint32_t>(info->disc_count);

                    XELOGI("MultiDisc: launched title media_id={:08X} "
                           "disc_number={} disc_count={}",
                           media_id, disc_number, disc_count);

                    // Re-create a DocumentFile clone from the URI stored
                    // in ae::boot_game_uri. If empty (e.g. booting from a
                    // path-based source rather than SAF), skip multi-disc
                    // registration — it only works for SAF-launched ISO
                    // files.
                    const std::string& boot_uri = ae::boot_game_uri;
                    if (!boot_uri.empty() && g_jvm) {
                        JNIEnv* env = nullptr;
                        if (g_jvm->GetEnv(reinterpret_cast<void**>(&env),
                                         JNI_VERSION_1_6) == JNI_EDETACHED) {
                            g_jvm->AttachCurrentThread(&env, nullptr);
                        }
                        if (env) {
                            jclass uri_class = env->FindClass("android/net/Uri");
                            jmethodID parse_method = env->GetStaticMethodID(
                                    uri_class, "parse",
                                    "(Ljava/lang/String;)Landroid/net/Uri;");
                            jstring uri_string =
                                    env->NewStringUTF(boot_uri.c_str());
                            jobject uri = env->CallStaticObjectMethod(
                                    uri_class, parse_method, uri_string);
                            std::unique_ptr<DocumentFile> game_file_clone =
                                    DocumentFile::find(g_jvm, uri);
                            env->DeleteLocalRef(uri_string);
                            env->DeleteLocalRef(uri);
                            if (game_file_clone) {
                                xe::MultiDiscManager::Instance()
                                        .RegisterLaunchedGame(
                                                std::move(game_file_clone),
                                                media_id, disc_number);
                            } else {
                                XELOGW("MultiDisc: DocumentFile::find returned "
                                       "null for launched URI '{}'",
                                       boot_uri);
                            }
                        }
                    } else {
                        XELOGW("MultiDisc: no boot URI available — "
                               "skipping sibling scan (boot_type={})",
                               ae::boot_type);
                    }
                } else {
                    XELOGW("MultiDisc: launched module has no execution info");
                }
            }
        } catch (const std::exception& e) {
            XELOGW("MultiDisc: RegisterLaunchedGame threw: {}", e.what());
        } catch (...) {
            XELOGW("MultiDisc: RegisterLaunchedGame threw unknown exception");
        }

        emu_thr_event->Set();
    });
#else
    emu->on_launch.AddListener([&](auto title_id, const auto& game_title) {
        /*nlohmann::json json;
        if(std::filesystem::exists(g_uri_info_list_file_path)){
            std::ifstream json_file(g_uri_info_list_file_path);
            json = nlohmann::json::parse(json_file);
            json_file.close();
        }
        if(!game_title.empty()){
            nlohmann::json info;
            info["name"] = game_title;

            json[cvars::target.string()]=info;
        }
        std::ofstream json_file(g_uri_info_list_file_path);
        json_file << json;
        json_file.close();

        emu_thr_event->Set();*/
    });
#endif
    emu->on_shader_storage_initialization.AddListener(
            [this](bool initializing) {
                XELOGI("Shader storage initialization: {}", initializing);
                app_context().CallInUIThread([this, initializing]() {
                    emu_window->SetInitializingShaderStorage(initializing);

                });

            });

    emu->on_patch_apply.AddListener([this]() {
        app_context().CallInUIThread([this]() { emu_window->UpdateTitle(); });
    });

    emu->on_terminate.AddListener([]() {
        XELOGI("Emulator terminated");
        // [MULTI-DISC SUPPORT] Clear the disc registry so the next game
        // starts with a clean slate. Without this, a stale disc map from
        // a previous game could be returned by FindDisc if the new game
        // happens to share a media_id (extremely unlikely, but possible).
        xe::MultiDiscManager::Instance().Clear();
    });

    // Enable emulator input now that the emulator is properly loaded.
    app_context().CallInUIThread(
            [this]() { emu_window->OnEmulatorInitialized(); });

    // Grab path from the flag or unnamed argument.
    std::string path;
    if (!cvars::target.empty()) {
        path = cvars::target;
    }

    if (!path.empty()) {
        jclass uri_class = env->FindClass("android/net/Uri");
        jmethodID parse_method = env->GetStaticMethodID(uri_class, "parse", "(Ljava/lang/String;)Landroid/net/Uri;");
        jstring uri_string = env->NewStringUTF(path.c_str());
        jobject uri = env->CallStaticObjectMethod(uri_class, parse_method, uri_string);

        std::unique_ptr<DocumentFile> file =
                DocumentFile::find(g_jvm,uri);

        std::string name = file->getName();


        // Case-insensitive extension check. Android SAF document names preserve
        // the original byte sequence, so "Far Cry 2 [RF].ISO" (uppercase) was
        // misrouted to the STFS branch and produced "Error reading STFS header:
        // -30 / Failed to open STFS container" because the XGD/ISO magic at the
        // start of the file is not a valid STFS header.
        auto ieq = [](std::string_view s, std::string_view ext) {
            return s.size() >= ext.size() &&
                   std::equal(s.end() - ext.size(), s.end(), ext.begin(),
                              ext.end(),
                              [](char a, char b) {
                                  return std::tolower(static_cast<unsigned char>(a)) ==
                                         std::tolower(static_cast<unsigned char>(b));
                              });
        };
        if(ieq(name, ".xex")){
            result = app_context().CallInUIThread(
                    [this, &file]() { return emu->LaunchXexFile(std::move(file)); });
        }
        else if(ieq(name, ".iso")){
            result = app_context().CallInUIThread(
                    [this, &file]() { return emu->LaunchDiscImage(std::move(file)); });
        }
        else if(ieq(name, ".zar")){
            result = app_context().CallInUIThread(
                    [this, &file]() { return emu->LaunchDiscArchive(std::move(file)); });
        }
        else{
            std::string data_dir = path+".data";
            jstring data_dir_str = env->NewStringUTF(data_dir.c_str());
            jobject data_dir_uri = env->CallStaticObjectMethod(uri_class, parse_method, data_dir_str);

            std::unique_ptr<DocumentFile> data_dir_file =
                    DocumentFile::find(g_jvm,data_dir_uri);
            result = app_context().CallInUIThread(
                    [this, &file,&data_dir_file]() { return emu->LaunchStfsContainer(std::move(file), std::move(data_dir_file)); });
        }

        /*result = emu->LaunchPath(abs_path);*//*app_context().CallInUIThread(
                [this, abs_path]() { return emu_window->RunTitle(abs_path); });*/

        if (XFAILED(result)) {
            xe::FatalError(fmt::format("Failed to launch target: {:08X}", result));
            app_context().RequestDeferredQuit();
            return;
        }
    }

    auto xam = emu->kernel_state()->GetKernelModule<xe::kernel::xam::XamModule>(
            "xam.xex");

    if (xam) {
        xam->LoadLoaderData();

        if (xam->loader_data().launch_data_present) {
            const std::filesystem::path host_path = xam->loader_data().host_path;
            app_context().CallInUIThread([this, host_path]() {
                return emu_window->RunTitle(host_path);
            });
        }
    }

    // Now, we're going to use this thread to drive events related to emulation.
    /*while (!emu_thr_quit_requested.load(std::memory_order_relaxed)) {
        xe::threading::Wait(emu_thr_event.get(), false);
        emu->WaitUntilExit();
    }*/
    while (!emu_thr_quit_requested.load(std::memory_order_relaxed)) {
        xe::threading::Wait(emu_thr_event.get(), false);
        emu->WaitUntilExit();
    }

    XELOGI("QUIT");
    app_context().QuitFromUIThread();
}

XE_DEFINE_WINDOWED_APP(ax36e,EmulatorApp::create);

namespace ae{

    int boot_type;

    std::string boot_game_path;
    int boot_game_fd;
    std::string boot_game_uri;

    ANativeWindow* window;
    int window_width;
    int window_height;

    std::string game_id;

     std::unique_ptr<xe::ui::WindowedApp> g_windowed_app;
     EmulatorApp* g_windowed_app_ref;

    // n->[n]
    static std::array<xe::ui::VirtualKey,24> key_maps={
            xe::ui::VirtualKey::kXInputPadDpadLeft,
            xe::ui::VirtualKey::kXInputPadDpadUp,
            xe::ui::VirtualKey::kXInputPadDpadRight,
            xe::ui::VirtualKey::kXInputPadDpadDown,
            xe::ui::VirtualKey::kXInputPadA,
            xe::ui::VirtualKey::kXInputPadB,
            xe::ui::VirtualKey::kXInputPadX,
            xe::ui::VirtualKey::kXInputPadY,
            xe::ui::VirtualKey::kXInputPadBack,
            xe::ui::VirtualKey::kXInputPadStart,

            xe::ui::VirtualKey::kXInputPadLShoulder,
            xe::ui::VirtualKey::kXInputPadRShoulder,
            xe::ui::VirtualKey::kXInputPadLThumbPress,
            xe::ui::VirtualKey::kXInputPadRThumbPress,
            xe::ui::VirtualKey::kXInputPadLTrigger,
            xe::ui::VirtualKey::kXInputPadRTrigger,

            xe::ui::VirtualKey::kXInputPadLThumbLeft,
            xe::ui::VirtualKey::kXInputPadLThumbUp,
            xe::ui::VirtualKey::kXInputPadLThumbRight,
            xe::ui::VirtualKey::kXInputPadLThumbDown,
            xe::ui::VirtualKey::kXInputPadRThumbLeft,
            xe::ui::VirtualKey::kXInputPadRThumbUp,
            xe::ui::VirtualKey::kXInputPadRThumbRight,
            xe::ui::VirtualKey::kXInputPadRThumbDown
    };

    void main_thr(){

        std::string tid=[]{
            std::stringstream ss;
            ss<<std::this_thread::get_id();
            return ss.str();
        }();
        LOGW("new thr: %s",tid.c_str());

        prctl(PR_SET_TIMERSLACK,1,0,0,0);

        AndroidWindowedAppContext wnd_ctx;
        wnd_ctx.setup_ui_thr_id(std::this_thread::get_id());
        g_windowed_app=xe::ui::GetWindowedAppCreator()(wnd_ctx);
        g_windowed_app_ref=dynamic_cast<EmulatorApp*>(g_windowed_app.get());

        std::vector<char*> args;
        args.push_back(NULL);
        for(auto& i:g_launch_args){
            args.push_back((char*)i.c_str());
        }
        static std::string boot_target=std::string("--target=")+ae::boot_game_uri;
        args.push_back((char*)boot_target.c_str());

        int argc=args.size();
        char** argv=args.data();

        cvar::ParseLaunchArguments(argc, argv, g_windowed_app->GetPositionalOptionsUsage(),
                                   g_windowed_app->GetPositionalOptions());
        xe::InitializeLogging(g_windowed_app->GetName());
        if(g_windowed_app->OnInitialize()){
            wnd_ctx.main_loop();
        }

    }

    void key_event(int key_code,bool pressed,int value){
        static const bool is_android=cvars::hid=="android";
        if(is_android){
            xe::hid::android::AndroidInputDriver* driver=reinterpret_cast<xe::hid::android::AndroidInputDriver*>(g_windowed_app_ref->emu->input_system()->drivers_[0].get());
            driver->OnKey(key_code,pressed,value);
        }
    }
    void surface_changed(){
        if(!g_windowed_app) return;
        auto* ctx=static_cast<AndroidWindowedAppContext*>(&g_windowed_app->app_context());
        ctx->request_surface_changed();
    }
    bool is_running(){
        return !g_windowed_app_ref->emu->is_paused();
    }
    bool is_paused(){
        return g_windowed_app_ref->emu->is_paused();
    }
    void pause(){
        //g_windowed_app_ref->emu->Pause();
        /*g_windowed_app_ref->app_context().CallInUIThread([]{
            g_windowed_app_ref->emu->Pause();
        });*/
    }
    void resume(){
        //g_windowed_app_ref->emu->Resume();
        /*g_windowed_app_ref->app_context().CallInUIThread([]{
            g_windowed_app_ref->emu->Resume();
        });*/
    }
    void quit(){
    }

    void init(){
    }

    // [ANDROID LOG FLUSH] Flushes the xenia-canary log file to disk.
    //
    // The FileLogSink (created in InitializeLogging when --log_file is set)
    // uses stdio FILE* buffering. On normal exit, ~Logger() calls
    // ~FileLogSink() which calls fflush+fclose. But if the process is killed
    // by System.exit(0) (as EmulatorActivity.onDestroy does), the C++ static
    // destructors may not run, and the last few KB of log data stays in the
    // FILE* buffer and is lost.
    //
    // This function is called from EmulatorActivity.onDestroy BEFORE
    // captureGameLog() and BEFORE System.exit(0), ensuring the log file has
    // all flushed data on disk when captureGameLog copies it.
    //
    // We use ShutdownLogging() rather than a partial flush because:
    //   1. It calls ~Logger() which calls ~FileLogSink() on all sinks,
    //      guaranteeing both fflush AND fclose
    //   2. After ShutdownLogging, logger_ is null, so any late XELOG* calls
    //      from other threads are safely dropped (ShouldLog returns false)
    //   3. The process is about to exit anyway, so we don't need the logger
    //      anymore
    void flush_log() {
        xe::ShutdownLogging();
    }

    // [FPS COUNTER] Returns the current presentation FPS from the Vulkan
    // presenter. The FPS is computed in Presenter::PaintAndPresent by counting
    // successfully presented frames over a ~500ms sliding window.
    //
    // This function is called from JNI (j_get_fps) which is called from the
    // Kotlin FPS overlay's polling loop. It's safe to call from any thread —
    // GetFPS() does an atomic relaxed load.
    //
    // Returns 0 if the emulator or presenter is not yet initialized, or if no
    // frames have been presented yet.
    int get_fps() {
        if (!g_windowed_app) return 0;
        auto* app = static_cast<EmulatorApp*>(g_windowed_app.get());
        if (!app || !app->emu_window) return 0;
        xe::ui::Presenter* presenter = app->emu_window->GetGraphicsSystemPresenter();
        if (!presenter) return 0;
        return static_cast<int>(presenter->GetFPS());
    }

}
