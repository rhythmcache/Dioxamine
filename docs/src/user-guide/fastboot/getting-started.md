# Fastboot Getting Started

Dioxamine includes native Fastboot protocol support over USB OTG. This allows you to flash custom partition images, test boot recovery/kernels, manage bootloader lock states, and run raw Fastboot commands directly from your phone.

---

## Fastboot Connection Requirements

1. **USB OTG Connection**: Fastboot requires a physical USB OTG cable connection between your host phone and the target device. Fastboot does not operate over Wi-Fi.
2. **Target in Fastboot / Bootloader Mode**:
   - Power off the target device.
   - Hold the device's hardware button combination (typically **Power + Volume Down** on most devices, or **Power + Volume Up** on certain models) until the Fastboot/Bootloader screen appears.
   - Alternatively, if the device is currently booted into Android with ADB connected, use Dioxamine's **Reboot Menu > Reboot to Bootloader**.

---

## Device Connection Status Strip

When you open the **Fastboot** tab in Dioxamine:
- **Connected (Green USB icon)**: The target device is detected and communicating with Dioxamine. The device serial or product name is shown in the top strip.
- **Detected, Not Connected (Gray USB icon)**: A Fastboot USB interface is present but needs a handshake. Tap the **Retry** button on the right side of the strip.
- **Disconnect Button**: Tap **Disconnect** if you wish to close the active Fastboot session.

---

## Sub-Tabs Overview

The Fastboot screen is divided into two primary sub-tabs:

1. **Actions**: Guided graphical cards for common maintenance tasks:
   - **Reboot Options**: Restart to System, Bootloader, Recovery, Fastbootd, Continue Boot, or Shutdown.
   - **Flash Image**: Flash any partition (`boot`, `recovery`, `init_boot`, `vendor_boot`, `system`, etc.) with image files.
   - **Boot Image**: Temporarily boot a kernel or recovery image without modifying device partitions.
   - **Lock State**: Unlock or lock bootloader states safely.
   - **Variables**: Query bootloader variables (e.g. current slot, battery voltage, secure boot status).
2. **Shell**: An interactive Fastboot command-line terminal with real-time log output.
