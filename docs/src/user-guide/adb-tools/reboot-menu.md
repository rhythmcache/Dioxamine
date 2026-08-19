# Reboot Menu

The **Reboot Menu** allows you to safely reboot the target device into various operating modes or completely shut it down without using physical hardware buttons.

## Opening the Reboot Menu

1. Open the **ADB** tab.
2. Under **Built-in Actions**, tap the **Reboot** tile.

---

## Available Reboot Options

| Option | Target Mode | Description |
|---|---|---|
| **Reboot System** | Android OS | Normal reboot back into the Android operating system. |
| **Reboot to Bootloader** | Bootloader (Fastboot) | Restarts the device into hardware Bootloader/Fastboot mode for flashing. |
| **Reboot to Recovery** | Stock / Custom Recovery | Restarts into Android Recovery mode (e.g. Stock Recovery, TWRP, OrangeFox). |
| **Reboot to Fastboot (fastbootd)** | Userspace Fastbootd | Reboots directly into Android 10+ userspace `fastbootd` mode to flash dynamic logical partitions. |
| **Shutdown (Power Off)** | Power Off | Safely powers off the target device completely. |

---

## Executing a Reboot

1. Tap on any reboot card (or tap the **Play arrow** on the right side of the card).
2. Dioxamine will display a toast confirming execution (e.g. *"Executing: Reboot to Recovery"*).
3. The target device will immediately begin its reboot sequence.
