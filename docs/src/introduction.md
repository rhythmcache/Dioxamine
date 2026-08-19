# Introduction

Welcome to the official documentation for Dioxamine.

Dioxamine is a fast, modern Android application designed for Android enthusiasts, developers, and technicians. It allows you to control secondary Android devices, send ADB commands, mirror and stream displays or cameras with Scrcpy, flash Fastboot firmware, manage files and apps, and run custom Web plugins directly from your phone.

## What You Can Do with Dioxamine

1. **Connect Devices Anywhere**: Connect target devices via USB OTG cable adapters, Wireless ADB auto-discovery, Android 11+ QR code scanning, pairing code PINs, or direct TCP/IP.
2. **Built-in ADB Tools**:
   - Inspect full hardware specifications, CPU telemetry, battery status, and uptime.
   - Browse, upload, download, rename, and delete files on target storage.
   - Install APKs, split APK bundles (.apks, .xapk), pull installed apps, and enable or disable system packages.
   - Control Android TV and phones using a D-pad remote control or a full trackpad with virtual PC hardware keyboard.
   - Capture screenshots and execute interactive ADB shell commands.
   - Sideload recovery updates, wipe userdata in Rescue mode, and reboot into System, Recovery, Bootloader, or Fastbootd.
3. **Scrcpy Screen Mirroring and Camera Streaming**:
   - Low-latency screen mirroring with touch control and audio forwarding.
   - Stream high-speed target camera video with lens selection, frame rate controls, and remote flashlight toggle.
4. **Fastboot Tools**:
   - Flash partition images (boot, recovery, system, vendor_boot, init_boot) with live transfer progress.
   - Live boot custom kernels or recovery images without overwriting partitions.
   - Lock and unlock bootloader states.
5. **Web Plugin System**:
   - Build and install modular HTML5/JavaScript plugins with full bridge access to ADB, shell, and file transfers.

## Documentation Overview

This manual is organized into two main parts:

- **App User Guide**: Complete instructions on connecting devices, using each built-in tool, tuning Scrcpy mirroring, flashing in Fastboot mode, and configuring app settings.
- **Plugin Development Guide**: Complete technical specification and JavaScript bridge reference for creating custom Dioxamine plugins.
