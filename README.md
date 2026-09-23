# Dioxamine

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_512.png" width="128" height="128" alt="Dioxamine" align="right" />

Dioxamine lets you run ADB, Scrcpy, and Fastboot directly from your Android phone or tablet. Connect another device via [USB OTG](https://en.wikipedia.org/wiki/USB_On-The-Go) or [Wireless ADB (Wi-Fi)](https://rhythmcache.github.io/Dioxamine/book/user-guide/connecting-devices.html) to mirror screens with full touch control, flash partitions, sideload, unlock bootloaders, manage files and apps, or run custom plugins. No PC or root required.

<table align="center">
  <tr>
    <th align="center">ADB Tools</th>
    <th align="center">Process Telemetry</th>
    <th align="center">Screen Mirroring</th>
    <th align="center">Fastboot Flasher</th>
    <th align="center">App Settings</th>
  </tr>
  <tr>
    <td align="center"><img src="assets/screenshots/adb_management.jpg" width="180" alt="ADB Management" /></td>
    <td align="center"><img src="assets/screenshots/process_manager.jpg" width="180" alt="Process Telemetry" /></td>
    <td align="center"><img src="assets/screenshots/scrcpy_mirroring.jpg" width="180" alt="Screen Mirroring" /></td>
    <td align="center"><img src="assets/screenshots/fastboot_flashing.jpg" width="180" alt="Fastboot Flashing" /></td>
    <td align="center"><img src="assets/screenshots/settings.jpg" width="180" alt="App Settings" /></td>
  </tr>
</table>

<p align="center"><a href="SCREENSHOTS.md">View full screenshot gallery (all 12 features)</a></p>

It focuses on:

- **PC-free control** — full ADB, Fastboot, and Scrcpy client running phone-to-phone
- **Flashing & recovery** — flash images, live-boot recoveries/kernels, and unlock bootloaders over USB OTG
- **Non-intrusiveness** — no root or client app needed on the connected device
- **Extensibility** — build and install custom tools with the HTML/JS plugin engine

## Features

### Scrcpy screen mirroring and audio

> Device compatibility notice: Scrcpy video streaming depends on the capabilities of both the host and slave device. Screen mirroring was tested successfully on the developer's device but may not work on every Android device. Devices without compatible hardware video decoding, or unsupported decoder configurations, may fail to start streaming or produce decoding errors. If mirroring fails, try changing the codec, resolution, FPS, or other streaming settings.

- [Real-time display mirroring](https://rhythmcache.github.io/Dioxamine/book/user-guide/scrcpy-mirroring/screen-mirroring.html) with full multi-touch and hardware key control
- [Audio forwarding](https://rhythmcache.github.io/Dioxamine/book/user-guide/scrcpy-mirroring/settings-tuning.html) (Android 11+)
- [Camera streaming](https://rhythmcache.github.io/Dioxamine/book/user-guide/scrcpy-mirroring/camera-streaming.html) (Android 12+ — front and rear cameras, flashlight/torch toggle, high-FPS modes)
- [Mirroring with target screen off](https://rhythmcache.github.io/Dioxamine/book/user-guide/scrcpy-mirroring/screen-mirroring.html) to save battery and reduce heat
- [Touchpad & PC keyboard mode](https://rhythmcache.github.io/Dioxamine/book/user-guide/adb-tools/touchpad-keyboard.html) via UHID simulation
- Configurable codecs (H.264, H.265/HEVC, AV1, VP8/VP9), bitrate, resolution, and FPS

### Fastboot flasher and bootloader tools (USB OTG)

- [Flash partition images](https://rhythmcache.github.io/Dioxamine/book/user-guide/fastboot/flashing-images.html) (`boot`, `recovery`, `vendor_boot`, `init_boot`, etc.)
- [Live boot images](https://rhythmcache.github.io/Dioxamine/book/user-guide/fastboot/boot-image.html) (`fastboot boot <image>`) to test custom kernels or recoveries without flashing
- [Unlock & lock bootloader](https://rhythmcache.github.io/Dioxamine/book/user-guide/fastboot/lock-bootloader.html) state directly from your phone
- [Variable inspector](https://rhythmcache.github.io/Dioxamine/book/user-guide/fastboot/variables.html) (`getvar all`, check current slot A/B)
- [Interactive Fastboot shell](https://rhythmcache.github.io/Dioxamine/book/user-guide/fastboot/fastboot-shell.html) for raw commands

### ADB management and diagnostics

- [Process manager](https://rhythmcache.github.io/Dioxamine/book/user-guide/adb-tools/process-manager.html) — live CPU & RAM telemetry, process inspector, search filter, Force Stop, and PID termination
- [Miscellaneous tools](https://rhythmcache.github.io/Dioxamine/book/user-guide/adb-tools/miscellaneous.html) — DPI & resolution changer, screen orientation, stay awake, demo mode, touch visualization, window animation scale, battery emulation, deep link launcher
- [File manager](https://rhythmcache.github.io/Dioxamine/book/user-guide/adb-tools/file-manager.html) — browse target filesystem, upload, download, and manage storage
- [Package manager](https://rhythmcache.github.io/Dioxamine/book/user-guide/adb-tools/package-manager.html) — install split APKs, debloat/disable system apps, extract and pull APKs
- [Reboot menu](https://rhythmcache.github.io/Dioxamine/book/user-guide/adb-tools/reboot-menu.html) — one-tap reboot to System, Recovery, Bootloader, FastbootD, EDL, or Power Off
- [Sideload & rescue](https://rhythmcache.github.io/Dioxamine/book/user-guide/adb-tools/sideload-rescue.html) — flash OTA packages (`.zip`) via `adb sideload` or restore bricked devices
- [Screenshot capture](https://rhythmcache.github.io/Dioxamine/book/user-guide/adb-tools/screenshots.html) — high-res screenshots pulled directly from the target frame buffer
- [Interactive ADB shell](https://rhythmcache.github.io/Dioxamine/book/user-guide/adb-tools/terminal-shell.html)

### Custom plugin engine

- Run custom modular tools built with HTML5, CSS, and JavaScript inside a sandboxed WebView
- Direct JavaScript Bridge API for shell commands, file push/pull, port forwarding, and Material 3 theming
- Install third-party `.zip` plugins or build your own
- See the [Terminal Plugin](https://github.com/rhythmcache/Terminal) or the [Plugin Development Guide](https://rhythmcache.github.io/Dioxamine/book/plugins/overview.html)

## Prerequisites

- **Host device:** Android 7.0+ (API 24+)
- **Target device:** Android 5.0+ (API 21+)
  - Audio forwarding requires Android 11+ (API 30+)
  - Camera streaming requires Android 12+ (API 31+)
  - Wireless ADB QR pairing requires Android 11+ (API 30+)

Make sure [USB debugging](https://developer.android.com/studio/debug/dev-options#enable) is enabled on the target device.

On Xiaomi/HyperOS/MIUI devices, also enable **USB debugging (Security Settings)** in Developer Options to allow touch control and input injection.

## Documentation

Full documentation, guides, and API specs live in the [Dioxamine Book](https://rhythmcache.github.io/Dioxamine/book/).

**User guide**
- [Connecting devices (USB OTG, Wireless ADB, QR pairing)](https://rhythmcache.github.io/Dioxamine/book/user-guide/connecting-devices.html)
- [OEM setup & troubleshooting](https://rhythmcache.github.io/Dioxamine/book/user-guide/oem-setup.html)
- [ADB built-in tools](https://rhythmcache.github.io/Dioxamine/book/user-guide/adb-tools/overview.html)
- [Scrcpy screen mirroring & audio](https://rhythmcache.github.io/Dioxamine/book/user-guide/scrcpy-mirroring/screen-mirroring.html)
- [Fastboot tools](https://rhythmcache.github.io/Dioxamine/book/user-guide/fastboot/getting-started.html)

**Plugin development**
- [Plugin overview & architecture](https://rhythmcache.github.io/Dioxamine/book/plugins/overview.html)
- [Quickstart guide](https://rhythmcache.github.io/Dioxamine/book/plugins/quickstart.html)
- [Manifest specification (`plugin.json`)](https://rhythmcache.github.io/Dioxamine/book/plugins/manifest.html)
- [JavaScript Bridge API reference](https://rhythmcache.github.io/Dioxamine/book/plugins/api-reference.html)

## Build instructions

Clone the repository recursively (to include the embedded `scrcpy` submodule):

```bash
git clone --depth 1 --recurse-submodules https://github.com/rhythmcache/Dioxamine.git
cd Dioxamine
export JAVA_HOME=/path/to/java
export ANDROID_NDK_ROOT=/path/to/android/ndk
./gradlew assembleDebug
```

Requirements:
- JDK 17+
- Android SDK (API 37)
- Android NDK (`ANDROID_NDK_HOME` set)

## Contributing

Contributions are welcome. If you'd like to contribute code, report bugs, or help translate Dioxamine into your language, open an issue or pull request.

### Translations

We want Dioxamine to be accessible in as many languages as possible.

- [x] English (`en`) — default
- [x] Simplified Chinese (`zh-CN`) — [@riyousa](https://github.com/riyousa)
- [x] Hindi (`hi`) — [@rhythmcache](https://github.com/rhythmcache)
- [x] Russian (`ru`) — [@LorianL98](https://github.com/LorianL98)
- [x] German (`de`) — [@ctrl-mietze](https://github.com/ctrl-mietze)
- [x] Persian (`fa`) — [@MrMR-711](https://github.com/MrMR-711)

![Translation Coverage](badges/coverage.svg)

<!-- Contributors: add your language checkmark below when submitting a translation -->

### How to add a language translation

**1. Fork & clone**

```bash
git clone https://github.com/<your-username>/Dioxamine.git
cd Dioxamine
git checkout -b translate-<language>
```

**2. Create the translation file**

1. Create a new folder under `app/src/main/res/`:
   ```bash
   app/src/main/res/values-<locale>/
   ```
   (e.g. `values-es` for Spanish, `values-ru` for Russian, `values-zh-rCN` for Simplified Chinese, `values-pt-rBR` for Brazilian Portuguese)
2. Copy the base strings from [`app/src/main/res/values/strings.xml`](app/src/main/res/values/strings.xml) into `app/src/main/res/values-<locale>/strings.xml`.
3. Translate the strings inside `<string name="...">Your Translation</string>`.

   Keep placeholders like `%s`, `%d`, `%1$s`, HTML tags, and XML entities (`&amp;`, `\'`) unchanged.

   AI/LLM translations are fine as a starting point, but review and refine the output manually — LLMs often produce overly formal or literal phrasing. Adapt it so it reads naturally for native speakers.

**3. Register your language in code**

1. In [`app/src/main/res/values/strings.xml`](app/src/main/res/values/strings.xml), add a string resource for your language's native name (endonym), marked `translatable="false"`:
   ```xml
   <string name="settings_language_spanish" translatable="false">Español</string>
   ```
2. In [`app/src/main/kotlin/io/github/rhythmcache/dioxamine/settings/SupportedLanguages.kt`](app/src/main/kotlin/io/github/rhythmcache/dioxamine/settings/SupportedLanguages.kt), add an entry to `supportedLanguages`:
   ```kotlin
   LanguageOption(R.string.settings_language_spanish, "es"),
   ```

**4. Add a checkmark and open a PR**

1. Under **Translations** above, add your language:
   ```markdown
   - [x] Spanish (`es`) - [@your-github-username](https://github.com/your-github-username)
   ```
2. Commit, push, and open a pull request:
   ```bash
   git add .
   git commit -m "i18n: add Spanish translation"
   git push origin translate-spanish
   ```

## Backends used by this project

- [rhythmcache/adb-kt](https://github.com/rhythmcache/adb-kt)
- [rhythmcache/fastboot-kt](https://github.com/rhythmcache/fastboot-kt)
- [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy)

## Community

- Telegram channel: [t.me/tr1ple_fault](https://t.me/tr1ple_fault)
- Issues & suggestions: [GitHub Issues](https://github.com/rhythmcache/Dioxamine/issues)

## Support

I'm [rhythmcache](https://github.com/rhythmcache), the developer of [Dioxamine](https://github.com/rhythmcache/Dioxamine). If you like this project, consider sponsoring it or buying me a coffee — it helps me keep working on it.

- [Buy me a coffee](https://www.buymeacoffee.com/triple_fault)
- [Sponsor on GitHub](https://github.com/sponsors/rhythmcache)

## License

    Copyright (C) 2026 rhythmcache

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
