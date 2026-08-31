# Miscellaneous Tools

The **Miscellaneous** suite brings together essential device tweaks, display modifiers, UI inspector controls, developer toggles, and system shortcuts into an easy one-tap interface.

---

## 1. App & Process Actions

- **Kill Foreground App**:
  - Automatically queries the active window manager on the target device to find the currently focused foreground package and force-stops it instantly.
- **Clear Active App Data** (`pm clear`):
  - Prompts for confirmation and resets all application storage, cache, and database logins for the active foreground app.

---

## 2. Display & Orientation

- **Set Screen Orientation**:
  - Overrides the display orientation to **0° (Portrait)**, **90° (Landscape Right)**, **180° (Inverted Portrait)**, or **270° (Landscape Left)**.
- **Toggle Auto-Rotate**:
  - Toggles the accelerometer-based display rotation on or off.
- **Screen Density (DPI)**:
  - Inspects the current display density. Allows selecting from preset values or entering a custom DPI between `72` and `1200`. Includes a **Reset Default** button to restore factory settings (`wm density reset`).
- **Screen Resolution**:
  - Queries active screen dimensions and lets you apply custom Width × Height resolutions or reset to device defaults (`wm size reset`).
- **Keep Screen Awake (Stay Awake)**:
  - Forces the target device's display to remain awake while connected over USB.

---

## 3. System UI & Notifications

- **Expand Notifications**:
  - Pulls down the notification shade.
- **Expand Quick Settings**:
  - Pulls down the full Quick Settings tile panel.
- **Collapse Status Bar**:
  - Dismisses any open notification or Quick Settings shades.

---

## 4. Developer & UI Tweaks

- **Demo Mode (Clean Status Bar)**:
  - Puts the target device into Android Demo Mode with 100% battery indicator, a clean 12:00 clock, full Wi-Fi/mobile signal bars, and hidden notification icons. Perfect for clean screenshots and recordings.
- **Toggle Show Touches**:
  - Enables or disables the visual white touch circle indicator under finger taps (`show_touches`).
- **Toggle Pointer Location**:
  - Overlays touch coordinates, pressure rulers, and finger path crosshairs on top of the screen (`pointer_location`).
- **Window Animation Speed**:
  - Adjusts window animation scale, transition animation scale, and animator duration scale between `0.0x` (instant/disabled), `0.5x`, `1.0x` (default), `1.5x`, and `2.0x`.

---

## 5. Battery Emulation

- **Simulate Battery State**:
  - Set custom simulated battery charge percentages (0% to 100%).
  - Toggle simulated AC/USB charging or discharging states.
  - Includes a **Reset Simulation** button (`dumpsys battery reset`) to restore real hardware battery monitoring.

---

## 6. Intent & Deep Links

- **Open URL / Deep Link**:
  - Launches any HTTP/HTTPS URL, deep link URI, or custom scheme directly in the target device's default web browser or assigned handler application.
