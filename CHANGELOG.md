# Changelog

All notable changes to Dioxamine are documented in this file.

## [0.0.5] (Version Code: 10005)

### Scrcpy & Display Mirroring
- Added full multi-touch gesture support with multi-pointer tracking (`ACTION_POINTER_DOWN`/`UP`, `ACTION_CANCEL`, pinch-to-zoom)
- Added virtual display creation with customizable resolution and DPI presets
- Added draggable floating overlay controls with edge-adaptive snapping and sticky handle
- Upgraded control message queue to an unbounded channel for stutter-free, guaranteed event dispatch
- Hardened discovery pipeline with per-device synchronization, explicit state machine, and robust error handling

### Plugins & Developer Ecosystem
- Integrated in-app official plugin registry with live browsing, search, and one-tap installation
- Added in-app plugin update checker (`updateJson`) with one-tap updates and changelog viewing
- Added native tactile haptic feedback & vibration API (`dioxamine.haptics`, `dioxamine.vibrate`)
- Enriched `getActiveDevice` API with Shell v2 status, API level, unique hardware ID, and root detection
- Added session-scoped chunked temporary file streaming API (`dioxamine.tempFile`) for large data transfers
- Added hardware Back and Volume button interception APIs with 3-tap emergency escape hatch
- Added host app language, localization, and dynamic RTL attributes bridge API (`dioxamine.getLanguage`)
- Redesigned Plugins UI with modern tab bar, rounded-square selector, and enriched details dialog
- Added granular plugin permission manager in App Settings

### Internationalization & Documentation
- Added complete German (`de`) and Persian (`fa`) translations
- Updated and refined Hindi, Russian, and Simplified Chinese localizations
- Modernized project homepage and documentation manual with new APIs and architecture
- General performance improvements, security hardening, and bug fixes

---

## [0.0.4] (Version Code: 10004)
- Added live streaming Logcat tile with real-time log reading, debounced search, and multi-level filtering
- Scrcpy: collapsible floating overlay controls, auto-fit aspect ratio, survive screen rotation, and direct device rotate control
- Scrcpy: added VP8/VP9 codec options and per-device settings persistence
- Plugins: added Fastboot API support (flash, boot, reboot, variables, stage data) and Fastboot permission gating
- Plugins: added native HTTP client (`dioxamine.http.fetch`) and `openBrowser` API with secure user confirmation dialog
- Plugins: namespaced ADB APIs under `dioxamine.adb` and grouped permissions by subkeys (`adb`, `fastboot`, `common`)
- Added address history and real-time autocomplete suggestions for TCP and Wireless Debugging
- Added in-app GitHub Release Update Checker in Settings > About
- Export ADB keys in standard PEM format
- Added Russian translation and updated Hindi and Simplified Chinese strings
- General UI refinements, performance improvements, and bug fixes

---

## [0.0.3] (Version Code: 10003)
- Added Scrcpy Session Recording to record screen mirroring video and audio directly to MP4
- Added dedicated Recordings manager to preview, export (SAF), and delete recorded clips
- Added Process Manager tool in ADB to monitor CPU/memory usage and manage running processes
- Added Multi-device handling in Fastboot with interactive device chips and seamless session switching
- Added Keep Alive background service with WakeLock to maintain connections when minimized
- Added dynamic status notification displaying real-time connected ADB and Fastboot device counts
- Added Hindi and Simplified Chinese localization with in-app language picker
- Added Miscellaneous ADB tools card (battery metrics, density, timeout, display controls)
- Added direct Documentation links in Settings > About
- Camera mirroring is now properly gated on Android versions below 12
- UI improvements, string fixes, and general performance enhancements

---

## [0.0.2-stable] (Version Code: 10002)
- First stable release of Dioxamine (v0.0.2-stable)
- Added Custom Plugin Engine to install and run modular web-based plugins (.zip)
- Added Plugin Permission Gate and settings to manage plugin permissions
- Added Audio-only streaming mode in Scrcpy
- Added built-in action shortcuts (Home, Back, Recents, Power, Volume) to the Touchpad remote control
- Added interactive PTY support and improved terminal stability for ADB shell
- Reorganized Scrcpy video settings with source and camera controls placed under Enable Video
- Added proper back and exit navigation handling across screens and plugin views
- Added in-app documentation links and guidance for ADB Shell and Plugins
- General UI refinements and bug fixes

---

## [0.0.2-alpha] (Version Code: 10001)
- Fixed Scrcpy first-attempt connection issues by increasing connection timeout and retry delays
- Redesigned File Manager backend (dxls) to execute once as a persistent socket daemon, eliminating repeated execution overhead on directory navigation
- Enhanced security & storage hygiene: helper binary automatically deletes itself from disk upon execution while staying active in memory
- Improved Android compatibility and updated adbKt dependency to v1.0.5

---

## [0.0.1-alpha] (Version Code: 10000)
- Initial 0.0.1-alpha release of Dioxamine
- USB OTG and Wireless ADB device connectivity with auto mDNS discovery & QR pairing
- Comprehensive ADB device management (file manager, package manager, interactive shell, reboot options)
- Low-latency Scrcpy screen mirroring, touch remote control, audio streaming & display tuning
- Fastboot image flashing, bootloader locking/unlocking & Fastboot terminal
