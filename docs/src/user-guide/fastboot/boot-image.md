# Live Booting Images (Fastboot Boot)

The **Boot Image** tool allows you to upload and boot a kernel or custom recovery image in RAM **without permanently flashing or modifying your device storage** (equivalent to running `fastboot boot <image.img>`).

## Why Use Live Booting?

- **Test Custom Recovery**: Test TWRP or OrangeFox without overwriting your stock recovery partition.
- **Rooting via Magisk / KernelSU**: Boot a patched boot image once to install Magisk directly to the device.
- **Troubleshooting**: Boot a rescue kernel to recover from bootloops.

---

## Step-by-Step Instructions

1. Ensure the device is connected in Fastboot mode over USB OTG.
2. In Dioxamine, open **Fastboot > Actions > Boot Image**.
3. Tap **Choose Image File and Boot**.
4. Select your `.img` file from phone storage.
5. Dioxamine will stream the image into the target device RAM and issue the boot execution command.
6. The target device screen will immediately reboot and launch the selected image in memory.

> [!NOTE]
> Live booting requires an unlocked bootloader. Certain newer devices with dynamic vendor boot partitions may require flashing rather than tethered booting depending on manufacturer bootloader implementation.
