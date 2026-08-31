# Camera Streaming Mode

In addition to mirroring the display, Scrcpy allows you to stream live video directly from the target device's cameras without installing any camera application on the target phone.

> [!IMPORTANT]
> **Requirement**: Camera streaming requires the target device to run **Android 12 (API 31)** or newer. If a target device running Android 11 or older is selected, Dioxamine will display an API badge (e.g. `Requires Android 12+ (API 31). Device is API 30.`) and keep the Camera source chip disabled.

## Switching to Camera Mode

1. Open the **Scrcpy** tab in Dioxamine.
2. In the **Video Source** section, tap the **Camera** chip (available when connected to Android 12+).
3. Dioxamine will automatically query the target device's camera hardware (Camera2 API) and list all available sensors.

---

## Camera Configuration Options

### 1. Camera Selector
- Pick specific camera sensors on the target device:
  - **Back / Main Camera** (e.g. `Cam 0 (back)`)
  - **Front / Selfie Camera** (e.g. `Cam 1 (front)`)
  - **Ultra-wide / Telephoto / Macro Sensors** (if exposed by vendor)
  - **External USB Webcams** (e.g. `external`)

### 2. Camera Resolution
- Select from supported hardware resolutions reported by the camera sensor (e.g. `1920x1080`, `1280x720`, `640x480`).

### 3. Frame Rate and High-Speed 120+ FPS Mode
- Choose your desired capture frame rate:
  - Standard rates: **60 FPS**, **30 FPS**, **15 FPS**.
  - High-Speed slow motion sensors: **120 FPS**, **240 FPS** (on supported camera hardware).
  - When high-speed rates (>=120 FPS) are chosen, Dioxamine automatically configures high-speed H.264 camera profiles.

### 4. Remote Torch / Flashlight Toggle
- While streaming camera video, tap the **Flashlight (Torch) icon** in the video player overlay to toggle the target phone's LED camera flash ON or OFF remotely.

---

## Audio in Camera Mode

When switching Video Source to **Camera**, Dioxamine automatically defaults the audio source to the target device's **Microphone** (`mic`). This allows using the target phone as a remote webcam and microphone pair for monitoring, streaming, or video calls.
