### Adreno Tools 
A library for applying rootless Adreno GPU driver modifications/replacements. Currently supports loading custom GPU drivers such as [turnip](https://docs.mesa3d.org/android.html#building-using-the-android-ndk),  enabling BCn textures and redirecting file operations to allow accessing shader dumps and modification of the [driver config file](https://gist.github.com/bylaws/04130932e2634d1c6a2a9729e3940d60) without root.

#### Documentation
API is documented in the `include/adrenotools` headers.

#### Support
Android 9+
Arm64

Please create an issue if support for anything else is desired.

#### ax360e additions

This fork is based on upstream `bylaws/libadrenotools` (commit `8fae8ce`) and
adds the following:

- **`adrenotools_set_freedreno_env(varName, value)`** — sets a Freedreno/Turnip
  environment variable (e.g. `TU_DEBUG`, `FD_RD_DUMP`, `MESA_DEBUG`) before the
  driver is loaded. Must be called before `adrenotools_open_libvulkan()`. The
  function verifies that the env var was actually applied (some Android
  variants silently fail `setenv` when called too early in static init).
- **Bounded pattern scans in `adrenotools_patch_bcn`** — the upstream scans
  (`while (... != sig) ptr++`) are unbounded and can walk into unrelated
  driver code on newer Adreno 619 driver layouts (e.g. Qualcomm BLOB
  minor=530). The bounded version returns `false` instead of writing the
  trampoline to a coincidental match — fixing a silent failure where
  `adrenotools_patch_bcn` returned `true` but `BC1 optimalTilingFeatures`
  stayed `0x0` (manifested as black textures in Far Cry 2).
- **Structured logcat output** under the `adrenotools`, `adrenotools/bcenabler`
  and `hook_impl` tags. Every fall-back path and pattern-scan failure now
  emits an `ANDROID_LOG_WARN` / `ANDROID_LOG_ERROR` line, making custom-driver
  load failures much easier to diagnose.

### FAQ

#### Is there an example project?

There is a simple bare-bones project [AdrenoToolsTest](https://github.com/darksylinc/AdrenoToolsTest) demonstrating how to get libadrenotools working.

#### How do I use this to update the drivers on my phone? Where's the apk?

You don't. This library is **not** for installing into Android and is **not** for end users.
This library is aimed at other developers.

Each individual app must explicitly make use of libadrenotools in order to load custom drivers into an app / game.

#### How do I use this library to make \<favourite game\> use newer drivers?

See previous question. It's up to the game developer to add support & use this library.

You could contact them to so they add support for it; but that's out of our power.