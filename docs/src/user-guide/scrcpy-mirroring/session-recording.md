# Session Recording and Management

Dioxamine allows you to record live Scrcpy mirroring sessions (both video and audio) directly onto your host device in MP4 format.

---

## Starting a Recording

There are two ways to record a screen mirroring session:

### 1. Manual Recording
1. Start a mirroring session from the **Scrcpy** tab.
2. Tap on the video player overlay to reveal player controls.
3. Tap the **Record icon** (circular button).
4. The recording starts immediately, and a red **REC timer badge** (`REC 00:15`) appears in the top corner indicating elapsed recording duration.
5. Tap the **Stop Record icon** (or stop mirroring) to finish and save the clip.

### 2. Auto-Record Sessions
1. Navigate to the **Settings** tab.
2. Expand the **scrcpy** settings card.
3. Toggle on **Auto-record sessions** (`scrcpy_auto_record`).
4. Whenever a screen mirroring session begins, Dioxamine will automatically initiate recording without requiring manual interaction.

---

## Managing and Exporting Recordings

All captured sessions are stored locally and accessible through the **Recordings** sub-tab in the **Scrcpy** screen:

1. Tap the **Recordings** sub-tab in the top bar of the Scrcpy screen.
2. View your recorded video items with:
   - File name and timestamp.
   - Total recorded duration.
   - File size.
3. **Exporting (Saving) Clips**:
   - Tap the **Export (Download)** button on any clip.
   - Select a destination folder using Android's Storage Access Framework (SAF) to save the `.mp4` video to your phone's Gallery, Downloads, or SD card.
4. **Deleting Clips**:
   - Tap the **Delete (Trash)** button.
   - Confirm deletion in the prompt to remove the recording and free up storage.

---

## Technical Notes

- **Container Format**: Video and forwarded audio are muxed into standard `.mp4` containers.
- **Codec Support**: Recording uses the active video codec selected in the Configurator (**H.264** or **H.265/HEVC**). If an unsupported codec configuration is used, a warning banner will inform you.
- **Audio Synchronization**: Forwarded device audio (Android 11+) is recorded in sync with the video track.
