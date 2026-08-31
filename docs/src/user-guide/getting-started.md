# Getting Started

This guide introduces Dioxamine, its navigation layout, and how to start managing secondary Android devices.

## App Layout and Navigation

Dioxamine is organized into four main tabs along the bottom navigation bar:

1. **ADB**: The central hub for ADB device management. Contains device connection controls, built-in device tools (File Manager, Package Manager, Remote Control, Touchpad, Screenshots, Reboot Menu, Sideload), an interactive ADB terminal, and custom Web plugins.
2. **Scrcpy**: Real-time screen mirroring, remote touch interaction, audio forwarding, and target camera video streaming.
3. **Fastboot**: Bootloader flashing utilities, kernel booting, lock state management, variable inspection, and raw Fastboot command execution.
4. **Settings**: ADB RSA key management (generation, export, import), Material 3 Monet theming, language configuration, diagnostic log export, and plugin security permissions.

## Requirements

To use Dioxamine effectively, ensure your setup meets the following requirements:

### Host Phone (Running Dioxamine)
- Android 7.0 (Nougat, API 24) or newer.
- USB OTG support if connecting via cable.
- Dynamic Material 3 colors (Monet) require Android 12 or newer.

### Target Device (Device Being Controlled)
- **Developer Options** enabled.
- **USB Debugging** enabled (for USB OTG or TCP connections).
- **Wireless Debugging** enabled (for Android 11+ Wi-Fi connections).
- For Scrcpy audio forwarding: target device must run Android 11 (API 30) or newer.
- For Scrcpy camera streaming: target device must run Android 12 (API 31) or newer.
- For Scrcpy audio duplication without muting the target: target device must run Android 13 (API 33) or newer.

## Enabling Developer Options on Target Device

Before connecting any target device:

1. Open **Settings** on the target device.
2. Navigate to **About Phone** (or **About Device**).
3. Tap **Build Number** repeatedly (usually 7 times) until a message appears saying "You are now a developer!".
4. Go back to **Settings > System > Developer Options** (location varies slightly by manufacturer).
5. Toggle **USB Debugging** to ON.
6. If connecting wirelessly on Android 11+, also toggle **Wireless Debugging** to ON.
