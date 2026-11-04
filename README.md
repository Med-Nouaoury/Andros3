Andros3
=======

A PlayStation 3 emulator for Android, built on top of upstream [RPCS3](https://github.com/RPCS3/rpcs3).

Andros3 is a fork of [PS3Native](https://github.com/maxjivi05/PS3Native), which tracks the real RPCS3 tree rather than a snapshot. It is developed by [Mohamed Nouaoury](https://github.com/Med-Nouaoury) as an open-source project and as his graduation project at Institut Grasset (Montréal).

> **No games, firmware or BIOS files are provided, and none will be.** Dump the games you own from your own PS3, and install the official PS3 system software from Sony's website. Do not ask for links.

## Andros3 goals

The emulator core stays unmodified upstream RPCS3. Andros3's own work lives in the Android front end (`android/`):

* its own package name (`io.github.mednouaoury.andros3`), so it installs alongside other ports
* interface and settings work aimed at Snapdragon devices
* clear, honest crediting of every project it builds on

## Upstream: PS3Native

The rest of this section describes the PS3Native base that Andros3 inherits. The archived [RPCS3-Android](https://github.com/RPCS3/rpcs3-android) port is folded in at [`android/`](android), rebased onto current upstream, and carried forward with additional fixes and interface work contributed by the [WinNative](https://github.com/WinNative-Emu) developers.

### What this is

Upstream RPCS3 targets desktop platforms. The Android port was published separately and then went stale against a moving upstream. The goal here is a single tree where:

* the emulator core is unmodified upstream RPCS3, so improvements land by merging upstream rather than by re-porting;
* the Android front end lives beside it in `android/` and builds an APK from that same source;
* Android-specific defects — threading, shutdown, configuration persistence, on-screen controls — are fixed in place;
* the interface is a Compose Material 3 front end sharing the design language of WinNative.

Everything below `android/` is additive. The upstream history is preserved intact.

### State

Working:

* boots and runs commercial titles at full speed on current Snapdragon hardware
* Vulkan renderer, PPU/SPU recompilers via a cross-compiled LLVM
* per-game configuration, backed by RPCS3's own custom-config mechanism
* generated PS2-style on-screen controls with sticky presses and reserved touch zones
* in-game menu with pause/resume, settings and shutdown
* game library with a built-in compatibility browser

Not finished:

* netplay has never been verified end to end; RPCN has no sign-in interface on Android
* an intermittent crash in the render queue is still being tracked
* audio backend selection is limited

## Building

The Android build needs LLVM and FFmpeg cross-compiled for `arm64-v8a` first. Both scripts fetch their own sources and write into `android/prebuilt/`:

```bash
cd android
./build-ffmpeg-android.sh
./build-llvm-android.sh
./gradlew assembleStandardDebug
```

Requirements: JDK 17, Android NDK 29, CMake 3.31, and a checkout with submodules (`git submodule update --init --recursive`).

The dependency scripts are the slow part and only need running when they change. `android/prebuilt/` is not tracked.

To build in CI, push to `android-refresh` or run the **Android APK** workflow manually from the Actions tab. It caches the cross-compiled dependencies against the build-script hashes, and shares a ccache across both the dependency and application builds, so a run that only touches app code reuses everything else.

## Package name

Andros3 builds a single APK under its own package name, `io.github.mednouaoury.andros3` (Gradle task `assembleStandardDebug`). The upstream fork also published copies under other apps' package names (benchmark and game IDs) to get scheduled more aggressively by some vendors; Andros3 does not ship those.

## Frame generation

The Vulkan presenter can interpolate extra frames between the ones the RSX actually renders, using the Lossless Scaling interpolation shaders.

**The shaders are not redistributed and nothing ships with the APK.** You point the app at your own copy of `Lossless.dll`; its PE resource tree is walked for the shader blobs, which are translated to SPIR-V once and cached in app storage. The DLL is parsed as data and never executed. Import it from **Frame Gen** in the library menu or the in-game menu's Frame Gen tab.

Interpolation costs one extra frame of latency — holding the newer frame back is what makes interpolating between two of them possible. It buys smoothness, not response.

## Credits

* the [PS3Native](https://github.com/maxjivi05/PS3Native) project by maxjivi05, which Andros3 is forked from
* the [RPCS3](https://github.com/RPCS3/rpcs3) team, for the emulator
* the [RPCS3-Android](https://github.com/RPCS3/rpcs3-android) port this build started from
* the [WinNative](https://github.com/WinNative-Emu) developers, for the interface work and Android fixes

### Frame generation

The interpolation chain in [`rpcs3/Emu/RSX/VK/lsfg/`](rpcs3/Emu/RSX/VK/lsfg) is not original work. It reaches this tree through:

* **[lsfg-vk](https://github.com/PancakeTAS/lsfg-vk)** by PancakeTAS and contributors (MIT) — the original Vulkan reimplementation of the Lossless Scaling frame generation chain, and the source of its structure: the mipmap pyramid, the alpha/beta/gamma/delta flow passes and the generation pass.
* **Camille LaVey**, in the **[Eden Emulator Project](https://git.eden-emu.dev/eden-emu/eden)** (GPL-3.0-or-later) — the port of that chain into an emulator's Vulkan renderer, landed as eden PR #4263, *[vulkan, android] Initial implementation of LSFG-VK*. This is the work that made the feature possible here: getting the Lossless Scaling compute chain running correctly on Vulkan on mobile GPUs is the hard part, and it was already solved. Every `lsfg_*.cpp` and `lsfg_*.hpp` in this tree descends from `src/video_core/renderer_vulkan/present/` in that port and keeps its SPDX headers. That includes the pacer: `lsfg_pacer` began as `frame_gen_pacer` and has since been rewritten around the panel refresh rate and the guest present rate, but the design is theirs. What is written for this project is the RPCS3 side — `VKFrameGeneration`, the presenter wiring in `VKPresent`, and the settings and menu surface around them.
* **[DXVK](https://github.com/doitsujin/dxvk)** — copyright Philip Rebohle, Joshua Ashton, Robin Kertels and Jeffrey Ellison, zlib/libpng licence. Its DXBC-to-SPIR-V shader translator is vendored at [`3rdparty/dxbc`](3rdparty/dxbc), taken from a standalone repackaging of those files rather than from the DXVK tree itself. Steam builds of Lossless Scaling ship the shaders as DXBC rather than SPIR-V, so they are translated once on import. This entry is the acknowledgment the zlib licence asks for.
* **[Lossless Scaling](https://store.steampowered.com/app/993090/Lossless_Scaling/)** by THS — the shaders themselves. `Lossless.dll` is proprietary and remains the property of THS; it is read from the user's own installed copy at runtime, and neither it nor any shader extracted from it is redistributed here.
* **[LSFG-Android](https://github.com/FrankBarretta/LSFG-Android)** by FrankBarretta — the first project to run the lsfg-vk pipeline on Android, and a reference while this port was written. It takes a different route, compositing over a `MediaProjection` capture in a system overlay rather than inside a renderer, so no code is shared with it. Its repository is not under a single licence: the root is MIT, `lsfg-vk-android/` is MIT inherited from lsfg-vk, and the `LSFG-Android/` application subtree is under a custom licence that forbids app-store publication and commercial use.

lsfg-vk is MIT, which is compatible with both GPLv2 and GPLv3. The files under `rpcs3/Emu/RSX/VK/lsfg/` descend from Camille LaVey's Eden port rather than from lsfg-vk directly, so they carry its GPL-3.0-or-later terms and those SPDX headers have to survive. The two files there without such a header — `lsfg_dll.*`, which walks the PE resource tree, and `lsfg_dxbc.*`, which bridges to DXVK's translator — were written for the Android side.

## License

Most files are licensed under GNU GPL-2.0-only; see [LICENSE](LICENSE). Some files are licensed differently — check the individual file headers. Andros3 is not affiliated with or endorsed by Sony Interactive Entertainment, the RPCS3 team, or PS3Native. "PlayStation" is a registered trademark of Sony Interactive Entertainment Inc.
