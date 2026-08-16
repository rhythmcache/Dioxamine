# Packaging and Distribution

Dioxamine plugins are distributed as standard ZIP archives containing the manifest and web assets.

---

## Package Directory Structure

Before packaging, verify that all necessary files reside at the top level of your plugin folder:

```
com.example.myplugin/
├── plugin.json       (Required: Manifest file)
├── index.html        (Required: Entrypoint file)
├── styles.css        (Optional: Stylesheets)
├── app.js            (Optional: JavaScript scripts)
└── icon.png          (Optional: 512x512 PNG icon)
```

---

## Creating the ZIP Package

### Using Command Line (Linux / macOS)
```bash
cd com.example.myplugin
zip -r ../com.example.myplugin.zip *
```

### Using PowerShell (Windows)
```powershell
Compress-Archive -Path "C:\path\to\com.example.myplugin\*" -DestinationPath "C:\path\to\com.example.myplugin.zip" -Force
```

### Important Packaging Rules
1. **No Root Wrapper Directory**:
   Do not zip the outer folder itself. Zip the **contents** of the folder so that `plugin.json` is at the root of the ZIP archive.
2. **Naming Convention**:
   Name the archive `<plugin-id>.zip` (for example, `com.example.deviceinfo.zip`).
3. **Asset References**:
   Use relative paths in HTML (`<link rel="stylesheet" href="styles.css">`, `<script src="app.js"></script>`). Do not use absolute filesystem paths.

---

## Installing Plugins in Dioxamine

1. Transfer your `.zip` package to your Android device (or download it directly).
2. Open Dioxamine and go to the **ADB** tab.
3. Switch to the **Plugins** sub-tab and tap **Install Plugin**.
4. Select your `.zip` archive using the system document picker.
5. Dioxamine will validate the manifest, extract the files into its secure sandbox, and add the plugin to your installed list immediately.
