# Plan: Fix Android surface rotation black-screen (Turnip + Adreno)

## Context for the executor

This plan is self-contained. You have not seen the audit conversation. Read this whole document before touching any file.

### Repo
- Path: `/home/z/my-project/work/ax360e_compose` (branch `fix/turnip-black-screen-v2`)
- Project: ax360e_compose — Android port of Xenia Canary (Xbox 360 emulator) using Jetpack Compose + Vulkan + adrenotools
- Build: `./gradlew assembleDebug --no-daemon --stacktrace` (CI runs `.github/workflows/build.yml`)
- Native code root: `app/src/main/cpp/xenia-canary/src/`

### Bug
Two related symptoms reported by the user on a phone with Adreno 619:
1. **With Turnip driver** (`vulkan_lib_path = .../libvulkan_freedreno.so`): the screen stays black while game audio plays (game is running, frames are being submitted to `vkQueuePresentKHR`).
2. **Without Turnip** (Adreno proprietary driver): the game image is shown in landscape but **rotated 90° relative to the phone's physical orientation** — i.e. when the phone is held horizontally (landscape), the game image appears sideways.

### Root cause (confirmed by diagnostic logs)

The Android `SurfaceFlinger` reports `VkSurfaceCapabilitiesKHR::currentTransform = VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR` (value `0x2`) for this device's window. This is normal on Android phones whose default display orientation is portrait but the app window is forced to landscape.

Per the Vulkan spec (VK_KHR_swapchain, "Surface Transformations"):
> "If preTransform does not match currentTransform at the time the swapchain was created, presentation will be suboptimal."

The previous code (commit `05b7eb6f`) correctly set `preTransform = currentTransform`, which fixed the `VK_SUBOPTIMAL_KHR` loop. But that is only half of the spec: **when preTransform is 90°/180°/270°, the application MUST rotate the rendered image itself before presenting**. The SurfaceFlinger will NOT rotate it for you when preTransform != IDENTITY.

Concretely:
- `currentExtent = 1600x720` (landscape, in the surface's native orientation)
- `preTransform = ROTATE_90` (we promised to rotate the image 90° CCW before presenting)
- We currently render the guest output (1280x720) **without any rotation** into the 1600x720 swapchain image
- Result: SurfaceFlinger receives a 1600x720 image that has been "pre-rotated 90°" but actually wasn't rotated at all. The compositor then displays it as if it were rotated, producing either a sideways image (Adreno proprietary — it tolerates the mismatch and the user sees a rotated frame) or a black screen (Turnip — it presents the unrotated image into a window whose physical orientation differs, and most pixels fall outside the visible viewport).

### Files involved

- `app/src/main/cpp/xenia-canary/src/xenia/ui/vulkan/vulkan_presenter.cc` — swapchain creation + `PaintAndPresentImpl` (the function that draws the guest output into the swapchain image)
- `app/src/main/cpp/xenia-canary/src/xenia/ui/presenter.cc` — `GetGuestOutputPaintFlow` (computes viewport/scissor/effect rectangles from surface dimensions)
- `app/src/main/cpp/xenia-canary/src/xenia/ui/presenter.h` — `SurfacePaintConnectionState`, `GuestOutputPaintFlow`, member variables `surface_width_in_paint_connection_`, `surface_height_in_paint_connection_`

### Verification gate

After implementing, request the user to:
1. Run Far Cry 2 (RF) with **Turnip** driver and capture a log. Expected: still `kPresented` (no regression of v2 fix), AND the game image is now visible (no black screen).
2. Run the same game **without Turnip** (Adreno proprietary). Expected: the game image is now upright when the phone is held horizontally (landscape), not sideways.

The diagnostic logs to grep for in the new log:
- `VulkanPresenter: Surface capabilities: ... currentTransform=0x2 ...` (unchanged)
- `VulkanPresenter: swapchain preTransform = 0x2 (currentTransform=0x2)` (unchanged)
- `VulkanPresenter: Created WxH swapchain ...` — the WxH should now be **720x1600** (swapped) when currentTransform is 90°/270°
- `VulkanPresenter: applying rotation=N° to guest output viewport` (new log from this plan)
- `[PaintAndPresent#N] result=kPresented` (heartbeat continues, no suboptimal)
- No `kNotPresented` or `kGpuLost` entries

If the image is still black with Turnip after this fix, the next investigation point is the `vkCmdBlitImage` / `vkCmdDraw` path in `PaintAndPresentImpl` — confirm the guest_output_image is actually being read in the fragment shader (sample the texture, write to `oC0`).

---

## Implementation steps

### Step 1: Swap the swapchain extent when currentTransform is 90° or 270°

In `vulkan_presenter.cc`, function `PaintContext::CreateSwapchainForVulkanSurface` (around line 926), after computing `image_extent` from `width`/`height` (line 963-971), swap width/height when the surface transform is 90° or 270°:

```cpp
// [ANDROID ROTATION FIX] When the surface's currentTransform is 90° or 270°,
// the swapchain images must have their dimensions swapped relative to the
// window's reported extent. The application is then responsible for rendering
// the guest output rotated into these swapped-dimension images, so that when
// the compositor applies the inverse transform the image appears upright.
VkSurfaceTransformFlagBitsKHR surface_transform =
    surface_capabilities.currentTransform;
const bool surface_swap_dims =
    (surface_transform & (VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR |
                          VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR)) != 0;
if (surface_swap_dims) {
  std::swap(image_extent.width, image_extent.height);
  // Re-clamp to surface caps after swap (the swapped values must still fit).
  image_extent.width =
      std::min(std::max(image_extent.width,
                        surface_capabilities.minImageExtent.width),
               surface_capabilities.maxImageExtent.width);
  image_extent.height =
      std::min(std::max(image_extent.height,
                        surface_capabilities.minImageExtent.height),
               surface_capabilities.maxImageExtent.height);
  XELOGI(
      "VulkanPresenter: surface transform=0x{:X} requires swapping "
      "swapchain dims — final swapchain extent={}x{}",
      uint32_t(surface_transform), image_extent.width, image_extent.height);
}
```

Insert this block immediately after the `if (!image_extent.width || !image_extent.height ...)` check at line 972-976.

### Step 2: Store the surface transform on the PaintContext for later use

In the same file, add a member to `PaintContext` (find the class declaration, search for `VkExtent2D swapchain_extent;`):

```cpp
// [ANDROID ROTATION FIX] The surface's currentTransform at swapchain
// creation time. The guest output must be rotated by this angle before
// presenting, because preTransform == currentTransform means we promised
// the compositor the image is already in the rotated orientation.
VkSurfaceTransformFlagBitsKHR swapchain_surface_transform =
    VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
```

In `CreateSwapchainForVulkanSurface`, after computing `chosen_pre_transform` (around line 1236-1255 in the current code — look for the block titled `[TURNIP BLACK-SCREEN FIX]`), set `swapchain_surface_transform = surface_capabilities.currentTransform;` so it persists on the `PaintContext` for later rendering.

Actually, since `chosen_pre_transform` is local to `CreateSwapchainForVulkanSurface` and the function returns the swapchain handle, we need to write the transform into the `PaintContext` via an out-parameter or extend `image_extent_out`. The cleanest path: add a new out-parameter `VkSurfaceTransformFlagBitsKHR& surface_transform_out` to `CreateSwapchainForVulkanSurface` and assign it in the caller to `paint_context_.swapchain_surface_transform`.

Function signature change:
```cpp
static VkSwapchainKHR CreateSwapchainForVulkanSurface(
    const VulkanDevice* vulkan_device, VkSurfaceKHR surface,
    uint32_t width, uint32_t height, VkSwapchainKHR old_swapchain,
    uint32_t present_queue_family, VkFormat& format_out,
    VkExtent2D& image_extent_out,
    VkSurfaceTransformFlagBitsKHR& surface_transform_out,  // NEW
    bool& is_fifo_out, bool& surface_unusable_out);
```

Update both call sites (lines 575 and 666) to pass `paint_context_.swapchain_surface_transform` as the new out-param.

### Step 3: Rotate the guest output viewport/scissor in PaintAndPresentImpl

In `vulkan_presenter.cc`, function `PaintAndPresentImpl` (around line 1379), the guest output is drawn into the swapchain image using a viewport and scissor computed from `paint_context_.swapchain_extent`. When `swapchain_surface_transform` is 90°/270°, the swapchain image is in the rotated orientation (e.g. 720x1600), and we need to render the guest output (1280x720 landscape) rotated to fill it.

Find the block around line 1815-1825 that sets `guest_output_viewport` and `guest_output_scissor`. Currently:
```cpp
VkViewport guest_output_viewport;
guest_output_viewport.x = 0.0f;
guest_output_viewport.y = 0.0f;
guest_output_viewport.width = float(frontbuffer_width_scaled);
guest_output_viewport.height = float(frontbuffer_height_scaled);
guest_output_viewport.minDepth = 0.0f;
guest_output_viewport.maxDepth = 1.0f;
VkRect2D guest_output_scissor;
guest_output_scissor.offset.x = 0;
guest_output_scissor.offset.y = 0;
guest_output_scissor.extent.width = frontbuffer_width_scaled;
guest_output_scissor.extent.height = frontbuffer_height_scaled;
```

This viewport is in the **guest output image** coordinate space (frontbuffer), not the swapchain coordinate space. The actual rotation is applied later by the `guest_output_paint_pipeline`'s fragment shader via the `GuestOutputPaintFlow` geometry. So the rotation needs to be communicated through the `GuestOutputPaintFlow`.

The cleaner approach is to swap the **surface dimensions** passed to `GetGuestOutputPaintFlow` when rotation is 90°/270°. Look at line 1570-1572:
```cpp
GuestOutputPaintFlow guest_output_flow = GetGuestOutputPaintFlow(
    guest_output_properties, paint_context_.swapchain_extent.width,
    paint_context_.swapchain_extent.height, max_framebuffer_extent.width,
    max_framebuffer_extent.height, guest_output_paint_config);
```

`GetGuestOutputPaintFlow` (in `presenter.cc` line 691) uses `host_rt_width`/`host_rt_height` (the swapchain extent) and `surface_width_in_paint_connection_`/`surface_height_in_paint_connection_` (the logical surface size) to compute the output rectangle, letterbox, etc. When we swap the swapchain dims in step 1, `swapchain_extent.width/height` are already swapped — so the guest output flow will naturally compute the output rectangle in the rotated space.

**The missing piece is the actual rotation of the sampled texture coordinates in the fragment shader.** The `guest_output_paint_pipeline` shaders (`apply_gamma_table.ps.xesl` and friends) sample the guest output image with `texelFetch2D(xe_apply_gamma_source, pixel_index, 0)` where `pixel_index = xesl_uint2(xesl_FragCoord.xy)`. When the swapchain is 720x1600 (rotated), `FragCoord.xy` goes 0..720 in x and 0..1600 in y, but the source guest output image is 1280x720. So sampling at `pixel_index = FragCoord.xy` reads out of bounds (returns black).

### Step 4: Add a rotation push-constant to the guest output paint shaders

This is the most invasive step. The fragment shader needs to know:
1. The source guest output image dimensions (1280x720)
2. The rotation angle (0°, 90°, 180°, 270°)

So it can compute the correct source texel coordinate from `FragCoord`.

Add a new push-constant block to `apply_gamma_table.xesli` and `apply_gamma_pwl.xesli`:
```xesl
xesl_pushConstants_begin(b0, space0)
  xesl_uint2 xe_apply_gamma_size;
  xesl_uint xe_apply_gamma_rotation;  // 0, 90, 180, 270 (degrees CW)
xesl_pushConstants_end
```

In the shader code, replace:
```xesl
xesl_uint2 pixel_index = xesl_uint2(xesl_FragCoord.xy);
```
with:
```xesl
xesl_uint2 frag_coord = xesl_uint2(xesl_FragCoord.xy);
xesl_uint2 pixel_index;
if (xe_apply_gamma_rotation == 0u) {
  pixel_index = frag_coord;
} else if (xe_apply_gamma_rotation == 90u) {
  // 90° CW: source.x = frag.y, source.y = source_height - 1 - frag.x
  pixel_index = xesl_uint2(frag_coord.y,
                           int(xe_apply_gamma_size.y) - 1 - int(frag_coord.x));
} else if (xe_apply_gamma_rotation == 180u) {
  pixel_index = xesl_uint2(int(xe_apply_gamma_size.x) - 1 - int(frag_coord.x),
                           int(xe_apply_gamma_size.y) - 1 - int(frag_coord.y));
} else { // 270
  pixel_index = xesl_uint2(int(xe_apply_gamma_size.x) - 1 - int(frag_coord.y),
                           frag_coord.x);
}
```

Then in `vulkan_presenter.cc` `PaintAndPresentImpl`, when writing the push-constants for the guest output paint pipeline (look for `vkCmdPushConstants` calls around line 1907), include the rotation value derived from `paint_context_.swapchain_surface_transform`:
- `VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR` → 0
- `VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR` → 90
- `VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR` → 180
- `VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR` → 270

Also pass `xe_apply_gamma_size` = the **source** guest output dimensions (frontbuffer_width_scaled, frontbuffer_height_scaled) — i.e. the unrotated size.

### Step 5: Verify the guest output image dimensions are NOT swapped

The `GuestOutputImage` (allocated in `RefreshGuestOutputImpl` line 879-887) is created with `frontbuffer_width` x `frontbuffer_height` (1280x720). This is correct — the source image is in the guest's native orientation (landscape, unrotated). The rotation happens in the paint shader, not in the source image.

### Step 6: Rebuild and request a new log from the user

After implementing steps 1-4:
1. Commit to a new branch `fix/turnip-black-screen-v3` (or push to existing `fix/turnip-black-screen-v2`).
2. Push and let CI build.
3. Ask the user to test with both Turnip and Adreno proprietary drivers, capturing logs.
4. Grep the new log for `applying rotation` and `Created .*x.* swapchain` to confirm the rotation is being applied.

---

## Risk assessment

- **High risk**: Step 4 (shader modification) touches the SPIR-V shader pipeline. A bug here could break **all** games, not just the rotation case. Mitigation: gate the rotation logic behind `if (rotation != 0)` so the 0° path is byte-identical to the current shader. Test on a non-rotated surface (e.g. Android tablet in native landscape) to confirm no regression.
- **Medium risk**: Step 1 (swapchain dim swap) could cause `vkCreateSwapchainKHR` to fail if `minImageExtent`/`maxImageExtent` are tighter than expected. Mitigation: re-clamp after swap (already in the snippet).
- **Low risk**: Steps 2, 3, 5, 6 are pure plumbing / logging / verification.

## Out of scope (do NOT attempt in this plan)

- Modifying the `presenter.cc` `GetGuestOutputPaintFlow` letterbox calculations — the `swapchain_extent` swap in step 1 should make the existing math produce the right letterbox rectangles for the rotated space. Only revisit if testing shows letterbox artifacts.
- Adding new CVars for rotation control — this should be automatic, not user-configurable.
- Pre-rotating the frontbuffer in the GPU command processor (`vulkan_command_processor.cc::IssueSwap`) — that would require changes to the EDRAM resolve path. The paint shader is the correct single point of rotation.

## Estimated effort

- Step 1: S (15 min)
- Step 2: S (15 min)
- Step 3: S (10 min — mostly investigation, the swap in step 1 does the heavy lifting)
- Step 4: M (60-90 min — shader modifications, push-constant plumbing, SPIR-V recompilation)
- Step 5: S (5 min — verification only)
- Step 6: M (waiting on CI + user log capture)

Total: ~2-3 hours of implementation + verification cycle.
