# OEM-Specific Setup and Troubleshooting

Certain Android manufacturers include custom security layers, battery optimizers, or modified permission frameworks that require specific adjustments for ADB, Touch Control, and Screen Mirroring to function properly.

---

## Xiaomi / Redmi / POCO (HyperOS and MIUI)

Xiaomi devices impose strict limitations on remote ADB input and background automation by default.

### Required Settings:

1. Open **Settings > Additional Settings > Developer Options**.
2. Enable **USB debugging**.
3. Enable **Install via USB** (allows Dioxamine to install APKs and push helper tools).
4. Enable **USB debugging (Security settings)**:
   - This setting allows Dioxamine to send touch events, simulate keypresses, and control the screen via Scrcpy or Touchpad.
   - Xiaomi requires a working SIM card and a signed-in Mi Account to enable this toggle. Follow the on-screen countdown prompts.
5. If using Wireless Debugging, also enable **Wireless Debugging**.

> [!WARNING]
> If **USB debugging (Security settings)** is disabled, screen mirroring will work, but you will not be able to tap, swipe, or send keystrokes to the target device.

---

## Samsung (One UI)

Samsung devices generally work smoothly with ADB, but consider the following:

1. Go to **Settings > Developer Options**.
2. Enable **USB Debugging**.
3. If connecting via Wireless Debugging on One UI 3.0+ (Android 11+), ensure **Wireless Debugging** is toggled on.
4. Disable **Auto Blocker** (One UI 6.0+ / Android 14+):
   - Navigate to **Settings > Security and Privacy > Auto Blocker**.
   - If Auto Blocker is enabled, it blocks all USB command execution and package installations via ADB. Turn Auto Blocker OFF or customize its restrictions to allow ADB commands.
5. In **Developer Options**, turn off **Verify apps over USB** if package installations hang or prompt repeatedly.

---

## OnePlus / OPPO / Realme (ColorOS / OxygenOS / Realme UI)

1. Open **Settings > Additional Settings (or System Settings) > Developer Options**.
2. Toggle on **USB Debugging**.
3. Enable **Disable Permission Monitoring**:
   - This prevents ColorOS from popping up a confirmation prompt on the target device every time an automated touch event or shell command is executed.
4. If installing applications fails, ensure **Install via USB** or **Verify ADB installs** is properly configured.

---

## Huawei / Honor (EMUI / MagicOS)

1. Go to **Settings > System & updates > Developer options**.
2. Enable **USB debugging**.
3. Enable **"Allow ADB debugging in charge only mode"**:
   - Huawei devices often switch USB connections to Charge Only mode when connected via OTG. Enabling this setting ensures the ADB daemon remains active.

---

## Android TV / Google TV Boxes

1. Open TV **Settings > Device Preferences > About**.
2. Tap **Android TV OS Build** 7 times to enable Developer Options.
3. Return to **Device Preferences > Developer Options**.
4. Enable **USB Debugging** and **Network Debugging** (if available).
5. When connecting Dioxamine, keep the TV screen on so you can click **"Always allow from this computer"** using your TV remote control.
