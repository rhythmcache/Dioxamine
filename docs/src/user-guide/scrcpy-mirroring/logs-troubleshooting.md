# Scrcpy Logs and Troubleshooting

If screen mirroring fails to start, displays a black screen, or lags, use the built-in Scrcpy Logs viewer to identify the exact cause.

## Accessing Scrcpy Logs

1. Open the **Scrcpy** tab.
2. Select the **Logs** sub-tab next to Configurator.
3. Dioxamine will display the live log output from the Scrcpy server running on the target device.
4. Tap the **Trash (Clear) icon** to clear the current log buffer.

---

## Common Issues and Solutions

### 1. Mirroring Starts But Screen is Black
- **Cause**: The target screen may be locked with secure flags, or a streaming DRM-protected app (e.g. Netflix, banking apps) is in the foreground.
- **Solution**: Unlock the target device and return to the home screen.

### 2. Mirroring Works But Taps Do Not Register (Xiaomi / HyperOS / MIUI)
- **Cause**: Xiaomi devices block remote touch input by default until a security toggle is enabled.
- **Solution**: On the target device, open **Settings > Developer Options** and turn ON **USB debugging (Security settings)**.

### 3. Audio Forwarding Error: "Audio forwarding requires Android 11+"
- **Cause**: Audio streaming uses internal Android 11 audio capture APIs. Older devices (Android 10 and below) only support video.
- **Solution**: Turn OFF **Forward Device Audio** in Scrcpy settings for Android 10 or older targets.

### 4. "Don't Mute" Error: "Requires at least Android 13"
- **Cause**: Duplicating audio to both the target speaker and host phone requires Android 13 (API 33).
- **Solution**: Turn OFF **Don't Mute** in Audio settings if target device is running Android 11 or 12.

### 5. High Lag or Stuttering on Wi-Fi
- **Solutions**:
  1. Switch video resolution from `1080p` to `720p` or `480p`.
  2. Reduce video bitrate from `8 Mbps` to `4 Mbps` or `2 Mbps`.
  3. Ensure both devices are connected to a 5GHz Wi-Fi network or a 5GHz mobile hotspot rather than 2.4GHz.
