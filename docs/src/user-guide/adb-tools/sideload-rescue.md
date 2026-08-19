# Sideload and Rescue Modes

When a target device is connected while in Recovery Sideload mode or Rescue mode, Dioxamine automatically detects the state and presents a dedicated maintenance interface on the main ADB screen.

---

## 1. Sideload Mode (Flashing OTA ZIPs)

Recovery Sideload mode is used to flash official OTA update packages, custom ROM zips, Magisk, or custom kernels directly from stock or custom recovery.

### When Sideload Mode Appears:
When the target device is in Recovery mode and you select **"Apply update from ADB"** (or **"Advanced > ADB Sideload"** in TWRP/OrangeFox), Dioxamine displays a **[Sideload]** badge on the top device chip and opens the **Sideload Flash Screen**.

### Step-by-Step Instructions:

1. Connect the target device in Sideload mode via USB OTG cable.
2. In Dioxamine, tap **Choose File**.
3. Select your `.zip` firmware package (OTA update, ROM, kernel, or Magisk zip) from your phone storage.
4. Tap **Sideload**.
5. Dioxamine will stream the update package to the recovery installer and display:
   - Live percentage progress bar (0% - 100%).
   - Total megabytes transferred (e.g. `1450MB / 2200MB`).
6. When complete, a **Complete** confirmation will appear. You can tap **Done** and reboot the target device.

---

## 2. Rescue Mode (Wiping Userdata)

Rescue mode is available on certain modern Android devices (such as Google Pixel devices) when booted into Android Rescue Mode.

### When Rescue Mode Appears:
When a device is connected in Rescue mode, Dioxamine displays a **[Rescue]** badge and presents:
- **Sideload OTA Flasher**: Allows pushing recovery firmware zips.
- **Wipe Userdata Button**: Erases user data partitions to recover from bootloops.

### Wiping Userdata in Rescue Mode:
1. Tap the **Wipe Userdata** button (outlined in red).
2. A confirmation dialog will warn you that this action erases all user data on the target device.
3. Tap **Wipe** to confirm.
4. Dioxamine will send the wipe command and output the completion status on screen.
