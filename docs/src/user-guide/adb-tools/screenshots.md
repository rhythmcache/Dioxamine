# Screenshot Tool

The **Screenshot** tool allows you to capture pixel-perfect PNG images of the target device screen over ADB and save them directly to your phone's storage.

## Opening the Screenshot Tool

1. Open the **ADB** tab.
2. Under **Built-in Actions**, tap the **Screenshot** tile.
3. Dioxamine will automatically request a screenshot from the target device and render the preview image.

---

## Tool Controls

### 1. Taking a Screenshot (Refresh)
- Tap the **Refresh icon** in the top app bar to capture a new screenshot at any time.
- Dioxamine streams raw PNG framebuffer data from the target device and displays the updated image on screen.

### 2. Saving the Screenshot
1. Once a screenshot is captured, a **Save (Disk) icon** appears in the top app bar.
2. Tap the **Save** icon.
3. Your phone's system document picker will open with a suggested filename (e.g. `screenshot_1710000000000.png`).
4. Select your destination folder (e.g. Pictures or Downloads) and tap **Save**.
5. A confirmation message will appear confirming the file was saved.
