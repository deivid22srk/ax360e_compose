package aenu.ax360e.ui.screens

import aenu.ax360e.R
import android.content.Context

sealed class SettingsEntry {
    abstract val key: String
    abstract val title: String

    data class Section(
        override val key: String,
        override val title: String,
        val children: List<SettingsEntry>
    ) : SettingsEntry()

    data class Bool(
        override val key: String,
        override val title: String
    ) : SettingsEntry()

    data class Int(
        override val key: String,
        override val title: String,
        val min: kotlin.Int,
        val max: kotlin.Int
    ) : SettingsEntry()

    data class StrArr(
        override val key: String,
        override val title: String,
        val entries: List<String>,
        val values: List<String>
    ) : SettingsEntry()

    data class StrLeaf(
        override val key: String,
        override val title: String
    ) : SettingsEntry()
}

object SettingsTree {
    fun buildRoot(context: Context): List<SettingsEntry> {
        return listOf(
            SettingsEntry.Section(
                key = "APU",
                title = context.getString(R.string.es_apu),
                children = listOf(
                    SettingsEntry.StrArr(key = "APU|apu", title = context.getString(R.string.es_apu_apu), entries = listOf("nop", "aaudio", "opensles"), values = listOf("nop", "aaudio", "opensles")),
                    SettingsEntry.Int(key = "APU|apu_max_queued_frames", title = context.getString(R.string.es_apu_apu_max_queued_frames), min = 4, max = 64),
                    SettingsEntry.Bool(key = "APU|enable_xmp", title = context.getString(R.string.es_apu_enable_xmp)),
                    SettingsEntry.Bool(key = "APU|ffmpeg_verbose", title = context.getString(R.string.es_apu_ffmpeg_verbose)),
                    SettingsEntry.Bool(key = "APU|mute", title = context.getString(R.string.es_apu_mute)),
                    SettingsEntry.Bool(key = "APU|use_dedicated_xma_thread", title = context.getString(R.string.es_apu_use_dedicated_xma_thread)),
                    SettingsEntry.StrArr(key = "APU|xma_decoder", title = context.getString(R.string.es_apu_xma_decoder), entries = listOf("fake", "master", "old", "new"), values = listOf("fake", "master", "old", "new")),
                    SettingsEntry.Int(key = "APU|xmp_default_volume", title = context.getString(R.string.es_apu_xmp_default_volume), min = 0, max = 100),
                )
            ),
            SettingsEntry.Section(
                key = "CPU",
                title = context.getString(R.string.es_cpu),
                children = listOf(
                    SettingsEntry.Bool(key = "CPU|break_condition_truncate", title = context.getString(R.string.es_cpu_break_condition_truncate)),
                    SettingsEntry.Bool(key = "CPU|break_on_debugbreak", title = context.getString(R.string.es_cpu_break_on_debugbreak)),
                    SettingsEntry.Bool(key = "CPU|break_on_start", title = context.getString(R.string.es_cpu_break_on_start)),
                    SettingsEntry.Bool(key = "CPU|break_on_unimplemented_instructions", title = context.getString(R.string.es_cpu_break_on_unimplemented_instructions)),
                    SettingsEntry.Bool(key = "CPU|clock_no_scaling", title = context.getString(R.string.es_cpu_clock_no_scaling)),
                    SettingsEntry.Bool(key = "CPU|clock_source_raw", title = context.getString(R.string.es_cpu_clock_source_raw)),
                    SettingsEntry.StrArr(key = "CPU|cpu", title = context.getString(R.string.es_cpu_cpu), entries = listOf("any", "a64"), values = listOf("any", "a64")),
                    SettingsEntry.Bool(key = "CPU|disable_context_promotion", title = context.getString(R.string.es_cpu_disable_context_promotion)),
                    SettingsEntry.Bool(key = "CPU|disable_instruction_infocache", title = context.getString(R.string.es_cpu_disable_instruction_infocache)),
                    SettingsEntry.Bool(key = "CPU|disable_prefetch_and_cachecontrol", title = context.getString(R.string.es_cpu_disable_prefetch_and_cachecontrol)),
                    SettingsEntry.Bool(key = "CPU|disassemble_functions", title = context.getString(R.string.es_cpu_disassemble_functions)),
                    SettingsEntry.Bool(key = "CPU|dump_translated_hir_functions", title = context.getString(R.string.es_cpu_dump_translated_hir_functions)),
                    SettingsEntry.Bool(key = "CPU|emit_inline_mmio_checks", title = context.getString(R.string.es_cpu_emit_inline_mmio_checks)),
                    SettingsEntry.Bool(key = "CPU|emit_mmio_aware_stores_for_recorded_exception_addresses", title = context.getString(R.string.es_cpu_emit_mmio_aware_stores_for_recorded_exception_addresses)),
                    SettingsEntry.Bool(key = "CPU|enable_early_precompilation", title = context.getString(R.string.es_cpu_enable_early_precompilation)),
                    SettingsEntry.Bool(key = "CPU|full_optimization_even_with_debug", title = context.getString(R.string.es_cpu_full_optimization_even_with_debug)),
                    SettingsEntry.Bool(key = "CPU|ignore_trap_instructions", title = context.getString(R.string.es_cpu_ignore_trap_instructions)),
                    SettingsEntry.Bool(key = "CPU|inline_mmio_access", title = context.getString(R.string.es_cpu_inline_mmio_access)),
                    SettingsEntry.Bool(key = "CPU|no_reserved_ops", title = context.getString(R.string.es_cpu_no_reserved_ops)),
                    SettingsEntry.Bool(key = "CPU|permit_float_constant_evaluation", title = context.getString(R.string.es_cpu_permit_float_constant_evaluation)),
                    SettingsEntry.Bool(key = "CPU|record_mmio_access_exceptions", title = context.getString(R.string.es_cpu_record_mmio_access_exceptions)),
                    SettingsEntry.Bool(key = "CPU|store_all_context_values", title = context.getString(R.string.es_cpu_store_all_context_values)),
                    SettingsEntry.Bool(key = "CPU|trace_function_coverage", title = context.getString(R.string.es_cpu_trace_function_coverage)),
                    SettingsEntry.Bool(key = "CPU|trace_function_references", title = context.getString(R.string.es_cpu_trace_function_references)),
                    SettingsEntry.Bool(key = "CPU|trace_functions", title = context.getString(R.string.es_cpu_trace_functions)),
                    SettingsEntry.Bool(key = "CPU|validate_hir", title = context.getString(R.string.es_cpu_validate_hir)),
                    SettingsEntry.Bool(key = "CPU|writable_code_segments", title = context.getString(R.string.es_cpu_writable_code_segments)),
                )
            ),
            SettingsEntry.Section(
                key = "Content",
                title = context.getString(R.string.es_content),
                children = listOf(
                    SettingsEntry.StrArr(key = "Content|license_mask", title = context.getString(R.string.es_content_license_mask), entries = listOf("disable", "first", "all"), values = listOf("0", "1", "-1")),
                )
            ),
            SettingsEntry.Section(
                key = "Display",
                title = context.getString(R.string.es_display),
                children = listOf(
                    SettingsEntry.Bool(key = "Display|fullscreen", title = context.getString(R.string.es_display_fullscreen)),
                    SettingsEntry.Bool(key = "Display|host_present_from_non_ui_thread", title = context.getString(R.string.es_display_host_present_from_non_ui_thread)),
                    SettingsEntry.StrArr(key = "Display|postprocess_antialiasing", title = context.getString(R.string.es_display_postprocess_antialiasing), entries = listOf("none", "fxaa", "fxaa_extreme"), values = listOf("none", "fxaa", "fxaa_extreme")),
                    SettingsEntry.Bool(key = "Display|postprocess_dither", title = context.getString(R.string.es_display_postprocess_dither)),
                    SettingsEntry.StrArr(key = "Display|postprocess_scaling_and_sharpening", title = context.getString(R.string.es_display_postprocess_scaling_and_sharpening), entries = listOf("bilinear", "cas", "fsr"), values = listOf("bilinear", "cas", "fsr")),
                    SettingsEntry.Bool(key = "Display|present_letterbox", title = context.getString(R.string.es_display_present_letterbox)),
                    SettingsEntry.Bool(key = "Display|present_render_pass_clear", title = context.getString(R.string.es_display_present_render_pass_clear)),
                )
            ),
            SettingsEntry.Section(
                key = "GPU",
                title = context.getString(R.string.es_gpu),
                children = listOf(
                    SettingsEntry.Bool(key = "GPU|clear_memory_page_state", title = context.getString(R.string.es_gpu_clear_memory_page_state)),
                    SettingsEntry.Bool(key = "GPU|depth_float24_convert_in_pixel_shader", title = context.getString(R.string.es_gpu_depth_float24_convert_in_pixel_shader)),
                    SettingsEntry.Bool(key = "GPU|depth_float24_round", title = context.getString(R.string.es_gpu_depth_float24_round)),
                    SettingsEntry.Bool(key = "GPU|depth_transfer_not_equal_test", title = context.getString(R.string.es_gpu_depth_transfer_not_equal_test)),
                    SettingsEntry.Bool(key = "GPU|disassemble_pm4", title = context.getString(R.string.es_gpu_disassemble_pm4)),
                    SettingsEntry.Bool(key = "GPU|draw_resolution_scaled_texture_offsets", title = context.getString(R.string.es_gpu_draw_resolution_scaled_texture_offsets)),
                    SettingsEntry.Bool(key = "GPU|execute_unclipped_draw_vs_on_cpu", title = context.getString(R.string.es_gpu_execute_unclipped_draw_vs_on_cpu)),
                    SettingsEntry.Bool(key = "GPU|execute_unclipped_draw_vs_on_cpu_for_psi_render_backend", title = context.getString(R.string.es_gpu_execute_unclipped_draw_vs_on_cpu_for_psi_render_backend)),
                    SettingsEntry.Bool(key = "GPU|execute_unclipped_draw_vs_on_cpu_with_scissor", title = context.getString(R.string.es_gpu_execute_unclipped_draw_vs_on_cpu_with_scissor)),
                    SettingsEntry.Bool(key = "GPU|force_convert_line_loops_to_strips", title = context.getString(R.string.es_gpu_force_convert_line_loops_to_strips)),
                    SettingsEntry.Bool(key = "GPU|force_convert_quad_lists_to_triangle_lists", title = context.getString(R.string.es_gpu_force_convert_quad_lists_to_triangle_lists)),
                    SettingsEntry.Bool(key = "GPU|force_convert_triangle_fans_to_lists", title = context.getString(R.string.es_gpu_force_convert_triangle_fans_to_lists)),
                    SettingsEntry.Bool(key = "GPU|gamma_render_target_as_srgb", title = context.getString(R.string.es_gpu_gamma_render_target_as_srgb)),
                    SettingsEntry.StrArr(key = "GPU|gpu", title = context.getString(R.string.es_gpu_gpu), entries = listOf("vulkan", "null"), values = listOf("vulkan", "null")),
                    SettingsEntry.Bool(key = "GPU|gpu_allow_invalid_fetch_constants", title = context.getString(R.string.es_gpu_gpu_allow_invalid_fetch_constants)),
                    SettingsEntry.Bool(key = "GPU|gpu_allow_invalid_upload_range", title = context.getString(R.string.es_gpu_gpu_allow_invalid_upload_range)),
                    SettingsEntry.Bool(key = "GPU|half_pixel_offset", title = context.getString(R.string.es_gpu_half_pixel_offset)),
                    SettingsEntry.Bool(key = "GPU|ignore_32bit_vertex_index_support", title = context.getString(R.string.es_gpu_ignore_32bit_vertex_index_support)),
                    SettingsEntry.Bool(key = "GPU|log_guest_driven_gpu_register_written_values", title = context.getString(R.string.es_gpu_log_guest_driven_gpu_register_written_values)),
                    SettingsEntry.Bool(key = "GPU|log_ringbuffer_kickoff_initiator_bts", title = context.getString(R.string.es_gpu_log_ringbuffer_kickoff_initiator_bts)),
                    SettingsEntry.Bool(key = "GPU|mrt_edram_used_range_clamp_to_min", title = context.getString(R.string.es_gpu_mrt_edram_used_range_clamp_to_min)),
                    SettingsEntry.Bool(key = "GPU|native_2x_msaa", title = context.getString(R.string.es_gpu_native_2x_msaa)),
                    SettingsEntry.Bool(key = "GPU|native_stencil_value_output", title = context.getString(R.string.es_gpu_native_stencil_value_output)),
                    SettingsEntry.Bool(key = "GPU|non_seamless_cube_map", title = context.getString(R.string.es_gpu_non_seamless_cube_map)),
                    SettingsEntry.Bool(key = "GPU|readback_memexport", title = context.getString(R.string.es_gpu_readback_memexport)),
                    SettingsEntry.StrArr(key = "GPU|readback_resolve", title = context.getString(R.string.es_gpu_readback_resolve), entries = listOf("fast", "full", "none"), values = listOf("fast", "full", "none")),
                    SettingsEntry.StrArr(key = "GPU|render_target_path_vulkan", title = context.getString(R.string.es_gpu_render_target_path_vulkan), entries = listOf("any", "fbo", "fsi"), values = listOf("any", "fbo", "fsi")),
                    SettingsEntry.Bool(key = "GPU|resolve_resolution_scale_fill_half_pixel_offset", title = context.getString(R.string.es_gpu_resolve_resolution_scale_fill_half_pixel_offset)),
                    SettingsEntry.Bool(key = "GPU|snorm16_render_target_full_range", title = context.getString(R.string.es_gpu_snorm16_render_target_full_range)),
                    SettingsEntry.Bool(key = "GPU|store_shaders", title = context.getString(R.string.es_gpu_store_shaders)),
                    SettingsEntry.StrLeaf(key = "GPU|target_trace_file", title = context.getString(R.string.es_gpu_target_trace_file)),
                    SettingsEntry.Int(key = "GPU|texture_cache_memory_limit_hard", title = context.getString(R.string.es_gpu_texture_cache_memory_limit_hard), min = 512, max = 4096),
                    SettingsEntry.Int(key = "GPU|texture_cache_memory_limit_soft", title = context.getString(R.string.es_gpu_texture_cache_memory_limit_soft), min = 512, max = 4096),
                    SettingsEntry.StrLeaf(key = "GPU|trace_dump_path", title = context.getString(R.string.es_gpu_trace_dump_path)),
                    SettingsEntry.Bool(key = "GPU|trace_gpu_stream", title = context.getString(R.string.es_gpu_trace_gpu_stream)),
                    SettingsEntry.Bool(key = "GPU|vsync", title = context.getString(R.string.es_gpu_vsync)),
                )
            ),
            SettingsEntry.Section(
                key = "General",
                title = context.getString(R.string.es_general),
                children = listOf(
                    SettingsEntry.Bool(key = "General|allow_game_relative_writes", title = context.getString(R.string.es_general_allow_game_relative_writes)),
                    SettingsEntry.Bool(key = "General|allow_plugins", title = context.getString(R.string.es_general_allow_plugins)),
                    SettingsEntry.Bool(key = "General|apply_patches", title = context.getString(R.string.es_general_apply_patches)),
                    SettingsEntry.Bool(key = "General|controller_hotkeys", title = context.getString(R.string.es_general_controller_hotkeys)),
                    SettingsEntry.Bool(key = "General|debug", title = context.getString(R.string.es_general_debug)),
                    SettingsEntry.Bool(key = "General|disable_doubleclick_fullscreen", title = context.getString(R.string.es_general_disable_doubleclick_fullscreen)),
                    SettingsEntry.Bool(key = "General|discord", title = context.getString(R.string.es_general_discord)),
                    SettingsEntry.Int(key = "General|time_scalar", title = context.getString(R.string.es_general_time_scalar), min = 1, max = 8),
                )
            ),
            SettingsEntry.Section(
                key = "HID",
                title = context.getString(R.string.es_hid),
                children = listOf(
                    SettingsEntry.Bool(key = "HID|guide_button", title = context.getString(R.string.es_hid_guide_button)),
                    SettingsEntry.StrArr(key = "HID|hid", title = context.getString(R.string.es_hid_hid), entries = listOf("android", "nop"), values = listOf("android", "nop")),
                )
            ),
            SettingsEntry.Section(
                key = "Kernel",
                title = context.getString(R.string.es_kernel),
                children = listOf(
                    SettingsEntry.Bool(key = "Kernel|allow_avatar_initialization", title = context.getString(R.string.es_kernel_allow_avatar_initialization)),
                    SettingsEntry.Bool(key = "Kernel|allow_incompatible_title_update", title = context.getString(R.string.es_kernel_allow_incompatible_title_update)),
                    SettingsEntry.Bool(key = "Kernel|allow_nui_initialization", title = context.getString(R.string.es_kernel_allow_nui_initialization)),
                    SettingsEntry.Bool(key = "Kernel|apply_title_update", title = context.getString(R.string.es_kernel_apply_title_update)),
                    SettingsEntry.Bool(key = "Kernel|ignore_thread_affinities", title = context.getString(R.string.es_kernel_ignore_thread_affinities)),
                    SettingsEntry.Bool(key = "Kernel|ignore_thread_priorities", title = context.getString(R.string.es_kernel_ignore_thread_priorities)),
                    SettingsEntry.Bool(key = "Kernel|pin_guest_threads_to_performance_cores", title = context.getString(R.string.es_kernel_pin_guest_threads_to_performance_cores)),
                    SettingsEntry.Bool(key = "Kernel|kernel_cert_monitor", title = context.getString(R.string.es_kernel_kernel_cert_monitor)),
                    SettingsEntry.Bool(key = "Kernel|kernel_debug_monitor", title = context.getString(R.string.es_kernel_kernel_debug_monitor)),
                    SettingsEntry.StrArr(key = "Kernel|kernel_display_gamma_type", title = context.getString(R.string.es_kernel_kernel_display_gamma_type), entries = listOf("linear", "sRGB(CRT)", "BT.709(HDTV)"), values = listOf("0", "1", "2")),
                    SettingsEntry.Bool(key = "Kernel|kernel_pix", title = context.getString(R.string.es_kernel_kernel_pix)),
                    SettingsEntry.Bool(key = "Kernel|log_high_frequency_kernel_calls", title = context.getString(R.string.es_kernel_log_high_frequency_kernel_calls)),
                    SettingsEntry.Bool(key = "Kernel|staging_mode", title = context.getString(R.string.es_kernel_staging_mode)),
                )
            ),
            SettingsEntry.Section(
                key = "Logging",
                title = context.getString(R.string.es_logging),
                children = listOf(
                    SettingsEntry.Bool(key = "Logging|flush_log", title = context.getString(R.string.es_logging_flush_log)),
                    // Matches xe::LogLevel: Error=0, Warning=1, Info=2, Debug=3, Trace=4.
                    // Official cvar docs stop at debug; trace is valid in ShouldLog and
                    // surfaces the most verbose XELOG*/CPU tracer output in xe.log.
                    SettingsEntry.StrArr(
                        key = "Logging|log_level",
                        title = context.getString(R.string.es_logging_log_level),
                        entries = listOf("error", "warning", "info", "debug", "trace"),
                        values = listOf("0", "1", "2", "3", "4")
                    ),
                    SettingsEntry.Int(
                        key = "Logging|log_mask",
                        title = context.getString(R.string.es_logging_log_mask),
                        min = 0,
                        max = 15
                    ),
                    SettingsEntry.Bool(key = "Logging|log_string_format_kernel_calls", title = context.getString(R.string.es_logging_log_string_format_kernel_calls)),
                    SettingsEntry.Bool(key = "Logging|log_to_debugprint", title = context.getString(R.string.es_logging_log_to_debugprint)),
                    SettingsEntry.Bool(key = "Logging|log_to_stdout", title = context.getString(R.string.es_logging_log_to_stdout)),
                )
            ),
            SettingsEntry.Section(
                key = "Memory",
                title = context.getString(R.string.es_memory),
                children = listOf(
                    SettingsEntry.Bool(key = "Memory|ignore_offset_for_ranged_allocations", title = context.getString(R.string.es_memory_ignore_offset_for_ranged_allocations)),
                    SettingsEntry.Int(key = "Memory|mmap_address_high", title = context.getString(R.string.es_memory_mmap_address_high), min = 2, max = 63),
                    SettingsEntry.Bool(key = "Memory|protect_on_release", title = context.getString(R.string.es_memory_protect_on_release)),
                    SettingsEntry.Bool(key = "Memory|protect_zero", title = context.getString(R.string.es_memory_protect_zero)),
                    SettingsEntry.Bool(key = "Memory|scribble_heap", title = context.getString(R.string.es_memory_scribble_heap)),
                    SettingsEntry.Bool(key = "Memory|writable_executable_memory", title = context.getString(R.string.es_memory_writable_executable_memory)),
                )
            ),
            SettingsEntry.Section(
                key = "Storage",
                title = context.getString(R.string.es_storage),
                children = listOf(
                    SettingsEntry.Bool(key = "Storage|force_mount_devkit", title = context.getString(R.string.es_storage_force_mount_devkit)),
                    SettingsEntry.Bool(key = "Storage|mount_cache", title = context.getString(R.string.es_storage_mount_cache)),
                    SettingsEntry.Bool(key = "Storage|mount_memory_unit", title = context.getString(R.string.es_storage_mount_memory_unit)),
                    SettingsEntry.Bool(key = "Storage|mount_scratch", title = context.getString(R.string.es_storage_mount_scratch)),
                )
            ),
            SettingsEntry.Section(
                key = "UI",
                title = context.getString(R.string.es_ui),
                children = listOf(
                    SettingsEntry.StrLeaf(key = "UI|custom_font_path", title = context.getString(R.string.es_ui_custom_font_path)),
                    SettingsEntry.Bool(key = "UI|headless", title = context.getString(R.string.es_ui_headless)),
                    SettingsEntry.Bool(key = "UI|profiler_dpi_scaling", title = context.getString(R.string.es_ui_profiler_dpi_scaling)),
                    SettingsEntry.Bool(key = "UI|show_achievement_notification", title = context.getString(R.string.es_ui_show_achievement_notification)),
                    SettingsEntry.Bool(key = "UI|show_profiler", title = context.getString(R.string.es_ui_show_profiler)),
                    SettingsEntry.Bool(key = "UI|storage_selection_dialog", title = context.getString(R.string.es_ui_storage_selection_dialog)),
                )
            ),
            SettingsEntry.Section(
                key = "Video",
                title = context.getString(R.string.es_video),
                children = listOf(
                    SettingsEntry.StrArr(key = "Video|avpack", title = context.getString(R.string.es_video_avpack), entries = listOf("PAL-60 Component (SD)", "Unused", "PAL-60 SCART", "480p Component (HD)", "HDMI+A", "PAL-60 Composite/S-Video", "VGA", "TV PAL-60", "HDMI"), values = listOf("0", "1", "2", "3", "4", "5", "6", "7", "8")),
                    SettingsEntry.Bool(key = "Video|enable_3d_mode", title = context.getString(R.string.es_video_enable_3d_mode)),
                    SettingsEntry.Bool(key = "Video|interlaced", title = context.getString(R.string.es_video_interlaced)),
                    SettingsEntry.StrArr(key = "Video|internal_display_resolution", title = context.getString(R.string.es_video_internal_display_resolution), entries = listOf("640x480", "640x576", "720x480", "720x576", "800x600", "848x480", "1024x768", "1152x864", "1280x720", "1280x768", "1280x960", "1280x1024", "1360x768", "1440x900", "1680x1050", "1920x540", "1920x1080"), values = listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16")),
                    SettingsEntry.Bool(key = "Video|use_50Hz_mode", title = context.getString(R.string.es_video_use_50Hz_mode)),
                    SettingsEntry.StrArr(key = "Video|video_standard", title = context.getString(R.string.es_video_video_standard), entries = listOf("NTSC", "NTSC-J", "PAL-60"), values = listOf("1", "2", "3")),
                    SettingsEntry.Bool(key = "Video|widescreen", title = context.getString(R.string.es_video_widescreen)),
                )
            ),
            SettingsEntry.Section(
                key = "Vulkan",
                title = context.getString(R.string.es_vulkan),
                children = listOf(
                    SettingsEntry.Bool(key = "Vulkan|adrenotools_force_max_clocks", title = context.getString(R.string.es_vulkan_adrenotools_force_max_clocks)),
                    SettingsEntry.Bool(key = "Vulkan|vulkan_allow_present_mode_fifo_relaxed", title = context.getString(R.string.es_vulkan_vulkan_allow_present_mode_fifo_relaxed)),
                    SettingsEntry.Bool(key = "Vulkan|vulkan_allow_present_mode_immediate", title = context.getString(R.string.es_vulkan_vulkan_allow_present_mode_immediate)),
                    SettingsEntry.Bool(key = "Vulkan|vulkan_allow_present_mode_mailbox", title = context.getString(R.string.es_vulkan_vulkan_allow_present_mode_mailbox)),
                    SettingsEntry.StrLeaf(key = "Vulkan|vulkan_lib_path", title = context.getString(R.string.es_vulkan_vulkan_lib_path)),
                    SettingsEntry.Bool(key = "Vulkan|vulkan_log_debug_messages", title = context.getString(R.string.es_vulkan_vulkan_log_debug_messages)),
                    SettingsEntry.Bool(key = "Vulkan|vulkan_sparse_shared_memory", title = context.getString(R.string.es_vulkan_vulkan_sparse_shared_memory)),
                    SettingsEntry.Bool(key = "Vulkan|vulkan_validation", title = context.getString(R.string.es_vulkan_vulkan_validation)),
                )
            ),
            SettingsEntry.Section(
                key = "XConfig",
                title = context.getString(R.string.es_xconfig),
                children = listOf(
                    SettingsEntry.StrArr(key = "XConfig|user_country", title = context.getString(R.string.es_xconfig_user_country), entries = listOf("AE", "AL", "AM", "AR", "AT", "AU", "AZ", "BE", "BG", "BH", "BN", "BO", "BR", "BY", "BZ", "CA", "CH", "CL", "CN", "CO", "CR", "CZ", "DE", "DK", "DO", "DZ", "EC", "EE", "EG", "ES", "FI", "FO", "FR", "GB", "GE", "GR", "GT", "HK", "HN", "HR", "HU", "ID", "IE", "IL", "IN", "IQ", "IR", "IS", "IT", "JM", "JO", "JP", "KE", "KG", "KR", "KW", "KZ", "LB", "LI", "LT", "LU", "LV", "LY", "MA", "MC", "MK", "MN", "MO", "MV", "MX", "MY", "NI", "NL", "NO", "NZ", "OM", "PA", "PE", "PH", "PK", "PL", "PR", "PT", "PY", "QA", "RO", "RU", "SA", "SE", "SG", "SI", "SK", "SV", "SY", "TH", "TN", "TR", "TT", "TW", "UA", "US", "UY", "UZ", "VE", "VN", "YE", "ZA"), values = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31", "32", "33", "34", "35", "36", "37", "38", "39", "40", "41", "42", "43", "44", "45", "46", "47", "48", "49", "50", "51", "52", "53", "54", "55", "56", "57", "58", "59", "60", "61", "62", "63", "64", "65", "66", "67", "68", "69", "70", "71", "72", "73", "74", "75", "76", "77", "78", "79", "80", "81", "82", "83", "84", "85", "86", "87", "88", "89", "90", "91", "92", "93", "95", "96", "97", "98", "99", "100", "101", "102", "103", "104", "105", "106", "107", "108", "109")),
                    SettingsEntry.StrArr(key = "XConfig|user_language", title = context.getString(R.string.es_xconfig_user_language), entries = listOf("en", "ja", "de", "fr", "es", "it", "ko", "zh", "pt", "pl", "ru", "sv", "tr", "nb", "nl", "zh"), values = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "11", "12", "13", "14", "15", "16", "17")),
                )
            ),
        )
    }

    fun getEntries(path: List<String>): List<SettingsEntry> {
        // For simplicity we cache the root statically on first access.
        val ctx = cachedContext ?: return emptyList()
        val root = cachedRoot ?: buildRoot(ctx).also { cachedRoot = it }
        var current: List<SettingsEntry> = root
        for (segment in path) {
            val section = current.firstOrNull { it is SettingsEntry.Section && it.title == segment } as? SettingsEntry.Section ?: return emptyList()
            current = section.children
        }
        return current
    }

    @Volatile private var cachedRoot: List<SettingsEntry>? = null
    @Volatile private var cachedContext: Context? = null

    fun init(context: Context) {
        if (cachedContext == null) {
            cachedContext = context.applicationContext
        }
    }
}
