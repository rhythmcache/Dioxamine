# Scrcpy Settings and Quality Tuning

The **Configurator** sub-tab in Scrcpy provides comprehensive controls to balance visual quality, frame rate, latency, and network bandwidth.

---

## Video Settings

### 1. Resolution (Max Size)
Caps the maximum dimension of the video stream while preserving the target device's aspect ratio:
- **1080p (Default)**: Balanced sharpness and performance.
- **720p**: Recommended for slower Wi-Fi networks or older devices.
- **480p**: Ultra low bandwidth mode for high latency connections.
- **Auto (Original)**: Uncapped original device display resolution.
- **Custom Resolution**: When enabled in App Settings, tap **Add Custom (+)** to enter any custom resolution height/width (e.g. `1440` or `1600`).

### 2. Frame Rate (Max FPS)
Limits the video encoder frame rate:
- **60 FPS (Default)**: Smooth, fluid animations and scrolling.
- **30 FPS**: Reduces CPU and Wi-Fi load by half.
- **15 FPS**: Low-power monitoring mode.
- **Custom FPS**: Enter any custom limit (e.g. `90` or `120` on supported hardware).

### 3. Video Bitrate
Sets the target compression bitrate:
- **8 Mbps (Default)**: High fidelity video output.
- **4 Mbps**: Good balance for standard 2.4GHz Wi-Fi networks.
- **2 Mbps**: Low data usage mode.
- **Custom Bitrate**: Enter custom values in Mbps (e.g. `12` or `16`).

### 4. Video Codec
Selects the hardware video encoding format on the target device:
- **H.264 (Default)**: Compatible with almost all Android devices.
- **H.265 (HEVC)**: Better compression and quality at lower bitrates, requires hardware HEVC support.
- **AV1**: Advanced open-source video compression on modern Android 14+ chipsets.

---

## Audio Settings

Dioxamine supports real-time target device audio forwarding over ADB.

### Requirements:
- Audio forwarding requires the target device to run **Android 11 (API 30)** or newer.

### Options:
- **Forward Device Audio Toggle**: Enables real-time audio playback on your host phone speakers or headphones.
- **Audio Codec**: Choose between **Opus (Default)**, **AAC**, **FLAC**, or uncompressed **RAW**.
- **Audio Bitrate**: Select audio quality: **64 Kbps**, **128 Kbps (Default)**, **192 Kbps**, **256 Kbps**, or **320 Kbps**.
- **Don't Mute (Audio Duplication)**:
  - By default, Android mutes the target phone speakers when audio is forwarded.
  - Enabling **Don't Mute** plays audio simultaneously on both the target device and host phone.
  - Requires target device to run **Android 13 (API 33)** or newer.
