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

## Device Connector Card

At the top of the **Fastboot** tab:
- **No Device Connected**: Displays `USB Detector Active • No Fastboot Device Connected` while the background USB listener is waiting for a device to be connected.
- **Multiple Device Chips**: When one or more devices in Fastboot or Fastbootd mode are plugged in (including multiple devices via a USB-C OTG hub), each device appears as an interactive chip showing its label.
- **Switching Devices**: Tap any device chip to immediately switch the active Fastboot session to that target device. The selected device chip is highlighted.
- **Expandable Device List**: Tap the expand arrow on the right to view all detected devices, their connection states (`Connected` or `Detected — not connected`), and individual **Connect** / **Disconnect** buttons.

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
