/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2020 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#ifndef XENIA_GPU_GPU_FLAGS_H_
#define XENIA_GPU_GPU_FLAGS_H_
#include "xenia/base/cvar.h"

DECLARE_path(trace_gpu_prefix);
DECLARE_bool(trace_gpu_stream);

DECLARE_path(dump_shaders);

DECLARE_bool(vsync);

DECLARE_uint64(framerate_limit);

DECLARE_bool(gpu_allow_invalid_fetch_constants);

DECLARE_bool(non_seamless_cube_map);

DECLARE_bool(half_pixel_offset);

// ax360e backport: new occlusion query system (upstream fbd620c2 + 73945c06).
DECLARE_string(occlusion_query);

DECLARE_int32(occlusion_query_fake_lower_threshold);

DECLARE_int32(occlusion_query_fake_upper_threshold);

DECLARE_int32(occlusion_query_querybatch_range);

DECLARE_double(occlusion_query_saturation);

DECLARE_int32(anisotropic_override);

DECLARE_bool(disassemble_pm4);

// ax360e backport: async shader compilation (upstream 5845f343).
DECLARE_bool(async_shader_compilation);

// ax360e backport: thread pool for shader compilation (upstream ccf8fb66).
DECLARE_int32(vulkan_pipeline_creation_threads);

#define XE_GPU_FINE_GRAINED_DRAW_SCOPES 1

#endif  // XENIA_GPU_GPU_FLAGS_H_
