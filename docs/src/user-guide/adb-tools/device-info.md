# Device Information

The **Device Information** tool provides an instant diagnostic summary of the target device's hardware, software version, and real-time operating metrics.

## Accessing Device Information

1. Open the **ADB** tab in Dioxamine.
2. Select **Built-in Actions** sub-tab.
3. Tap on the **Device Information** tile.

## Displayed Metrics

When opened, Dioxamine queries the target device sequentially and displays the following information cards:

| Card | Description | Command Executed |
|---|---|---|
| **Device Model** | Manufacturer marketing name and hardware model number | `getprop ro.product.model` |
| **Android Version** | OS release version (for example: Android 13, Android 14) | `getprop ro.build.version.release` |
| **Battery Info** | Real-time battery charge level, status, temperature, and health | `dumpsys battery` |
| **Serial Number** | Target device unique serial identifier | `getprop ro.serialno` |
| **CPU Info** | Processor architecture, core count, and hardware chipset | `cat /proc/cpuinfo` |
| **Uptime** | How long the device has been powered on since the last reboot | `cat /proc/uptime` |
| **Screen Resolution** | Current display resolution and physical display dimensions | `wm size` |
| **Installed Packages** | Total count of packages currently installed on the target | `pm list packages \| wc -l` |

## UI Controls

- **Refresh Button (Top Right)**: Tap the refresh icon in the top app bar to re-query all metrics sequentially from the device.
- **Copying Text**: You can long-press and select text within any output card to copy details to your clipboard.
