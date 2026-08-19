# Package Manager

The **Package Manager** allows you to inspect all installed applications on the target device, install new APKs or split APK bundles, pull installed apps back to your phone, disable bloatware, force stop processes, and uninstall apps.

## Opening the Package Manager

1. Open the **ADB** tab.
2. Under **Built-in Actions**, tap the **Package Manager** tile.
3. Dioxamine will stream all installed application packages along with their app icons, labels, package names, and version badges.

---

## Filtering and Searching Apps

- **Search Bar**: Tap the **Search (Magnifying Glass)** icon in the top app bar to filter apps by app title or package name (e.g. `chrome` or `com.android.settings`).
- **Filter Dropdown**: Tap the **Filter List** icon in the top app bar to filter apps by category:
  - **All**: Shows all packages installed on the device.
  - **User**: Shows only user-installed applications.
  - **System**: Shows pre-installed system apps and framework services.
  - **Disabled**: Shows apps that are currently disabled or frozen.

---

## App Card Information

Each application card in the list displays:
- **App Icon**: Decoded high-resolution application icon.
- **App Name**: Human-readable label (e.g. "YouTube").
- **Package Name**: Unique identifier (e.g. `com.google.android.youtube`).
- **Badges**:
  - `v1.2.3`: Version name.
  - `System`: Marks pre-installed system applications.
  - `Disabled`: Highlights frozen or disabled apps.
  - `Splits (N)`: Indicates the app uses Android App Bundle split APKs.

---

## Package Manager Actions

Tap the three-dots menu on any app tile to perform actions:

### 1. Enable / Disable (Freeze) App
- Toggle the package between Enabled and Disabled states (`pm disable-user` / `pm enable`).
- Useful for freezing manufacturer bloatware without root access. Disabled apps cannot run in the background or consume battery.

### 2. Force Stop App
- Instantly terminates all running background and foreground processes of the selected application (`am force-stop`).

### 3. Pull App (Extract APK to Local Phone)
- Tap **Pull APK** to export the installed application from the target device to your host phone storage.
- **Single APK Apps**: Extracted directly as a standalone `.apk` file.
- **Split APK Bundle Apps**: Dioxamine automatically pulls the master APK and all architecture/language/density split APKs, assembling them into a standard `.apks` bundle archive with a generated `toc.json` manifest.

### 4. Uninstall App
- Opens a confirmation dialog and completely removes the application package from the target device (`pm uninstall`).

### 5. App Info
- Opens a detailed dialog showing package paths, version code, target SDK level, and provides a button to launch the App Details page in the target device's Settings menu.

---

## Installing New Apps (APK, APKS, XAPK, ZIP)

Dioxamine supports installing standalone APKs as well as modern split-APK formats:

1. In the top app bar of Package Manager, tap the **Add (+)** icon.
2. Select your application files from your host phone. You can pick:
   - Standalone `.apk` files.
   - Multiple split `.apk` files at once.
   - Bundled `.apks`, `.xapk`, or `.zip` split archive packages.
3. Dioxamine will automatically extract any archives, establish an atomic install session (`pm install-create`), stream the splits to the target, and commit the installation.
4. A toast notification will confirm when the installation is successful.
