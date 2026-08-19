# Fastboot Command Shell

The **Fastboot Shell** provides an interactive command terminal to execute arbitrary Fastboot commands directly on the connected device.

## Opening the Fastboot Shell

1. Open the **Fastboot** tab in Dioxamine.
2. Select the **Shell** sub-tab at the top.
3. The terminal window will open with a command prompt.

---

## Executing Commands

Type any Fastboot subcommand into the bottom input bar (without typing the word `fastboot`) and tap **Send** (or press Enter on your keyboard):

### Example Commands:
- `getvar product`: Check the device codename.
- `oem device-info`: Check OEM-specific bootloader lock details on older devices.
- `set_active a` / `set_active b`: Switch active A/B boot slots.
- `erase userdata`: Wipe the user data partition.
- `reboot`: Reboot into the Android system.
- `reboot-bootloader`: Restart back into Fastboot mode.
- `reboot-fastboot`: Reboot into userspace `fastbootd` mode.

---

## Log Output and Status Colors

Each response line in the log window is color-coded:
- **`$ [command]` (Primary Color)**: Command sent to device.
- **`  [output]` (White/Gray)**: Text output or info packets returned by the bootloader.
- **`! [error]` (Red)**: Fastboot errors (e.g. `FAIL: remote: Partition not found`).
- **`# [system]` (Teal)**: Connection lifecycle notices (e.g. USB attached, session opened).

---

## Toolbar Controls

- **Clear Log**: Tap the **Delete Sweep icon** in the top right to clear the terminal output history.
- **Auto Scroll**: The terminal automatically scrolls to the newest line upon receiving new data.
