# File Manager

Dioxamine includes a full-featured remote **File Manager** that allows you to browse internal storage, upload files, download files to your local phone, create folders, rename, and delete items on the target device.

## Opening the File Manager

1. Open the **ADB** tab.
2. Under **Built-in Actions**, tap the **File Manager** tile.
3. The File Manager will initialize and open the primary storage directory (`/sdcard`).

---

## Navigating Folders

- **Breadcrumbs Bar (Top)**: Below the top app bar, a horizontal breadcrumb path shows your current location (e.g. `/ > sdcard > Download`). Tap any folder name in the breadcrumb path to jump directly to it.
- **Entering Folders**: Tap on any folder in the list to open it.
- **Parent Directory**: Tap the top item `..` to navigate one level up.
- **Back Gesture**: Pressing the Android back button returns to the previous parent directory.

---

## File Manager Actions

### 1. Uploading Files to Target (Push)
1. Navigate to the target folder where you want to save the files (e.g. `/sdcard/Download`).
2. Tap the **Add (+)** icon in the top right app bar.
3. Your host phone's system file picker will open.
4. Select one or multiple files (APKs, images, documents, zips).
5. Dioxamine will immediately start uploading the files and display live progress bars for each item.

### 2. Downloading Files to Host Phone (Pull)
1. Locate the file you want to download on the target device.
2. Tap the three-dots menu icon on the right side of the file row.
3. Select **Download**.
4. A system save dialog will open on your host phone. Choose where to save the file and tap **Save**.
5. Dioxamine will stream the file directly to your phone storage.

### 3. Creating a New Folder
1. Navigate to the directory where the new folder should be created.
2. Tap the **New Folder icon** in the top app bar.
3. Enter the desired folder name.
4. Tap **Create**.

### 4. Renaming a File or Folder
1. Tap the three-dots menu icon next to the target item.
2. Select **Rename**.
3. Edit the full path or name in the text field.
4. Tap **Rename** to apply.

### 5. Deleting a File or Folder
1. Tap the three-dots menu icon next to the item.
2. Select **Delete**.
3. Confirm the deletion in the warning dialog.

### 6. Viewing File Properties
1. Tap on any file, or select **Properties** from the three-dots menu.
2. The dialog displays:
   - **Name and Full Path**
   - **Type** (File, Directory, or Symlink target)
   - **File Size** (formatted in KB/MB/GB and exact bytes)
   - **Unix Permissions / Mode** (e.g. `drwxrwxr-x`)

### 7. Searching Files
- Tap the **Search (Magnifying Glass)** icon in the top app bar.
- Type any keywords to filter files and folders in real time within the active directory.
