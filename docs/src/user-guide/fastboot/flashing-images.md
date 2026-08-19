# Flashing Partition Images

The **Flash Image** tool in Fastboot allows you to write raw `.img` image files to any partition on the target device (equivalent to running `fastboot flash <partition> <file.img>`).

## How to Flash an Image

1. Connect the target device in Fastboot mode via USB OTG.
2. Tap the **Fastboot** tab in Dioxamine.
3. Select the **Actions** sub-tab.
4. Tap the **Flash Image** tile.
5. Tap **Choose Image File**.
6. Your host phone's file picker will open. Select your `.img` file (e.g. `boot.img`, `recovery.img`, `magisk_patched.img`, `init_boot.img`).
7. Dioxamine will inspect the filename and auto-populate the partition name in a confirmation dialog (e.g. selecting `boot.img` auto-fills `boot`).
8. Review or edit the target partition name:
   - For kernels on Android 13+: `init_boot` or `boot`
   - For recovery: `recovery` or `vendor_boot`
   - For system images: `system`
9. Tap **Flash**.

---

## Live Progress Tracking

While flashing, Dioxamine displays:
- **Circular progress indicator** showing the active flashing operation.
- **Percentage bar** (0% to 100%).
- **Byte counter** (e.g. `48 MB / 64 MB`).
- **Completion Card**: Displays a green checkmark upon success, or an error message if the partition is write-protected or invalid.

> [!WARNING]
> Flashing incorrect images to critical partitions can cause your device to fail to boot. Always verify that the image matches your exact device model and processor before flashing.
