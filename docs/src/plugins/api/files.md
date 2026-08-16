# File Operations and Storage API

Dioxamine provides methods to push and pull files between the host and connected device, stream APKs to the Android package manager, and access local device storage via Android Storage Access Framework (SAF).

## `dioxamine.pushFile()`

Uploads a base64-encoded file directly to a remote path on the connected device.

**Required Permission**: `"push"`

### Signature
```javascript
dioxamine.pushFile(remotePath: string, base64Data: string, mode?: number): Promise<void>
```

### Parameters
- `remotePath` (`string`): Target path on the connected device (for example, `"/sdcard/Download/script.sh"`).
- `base64Data` (`string`): File contents encoded in base64.
- `mode` (`number`, optional): POSIX file permission mode (for example, `0o755` for executables). Default: `0o644`.

---

## `dioxamine.pullFile()`

Downloads a file from the connected device and returns its contents as base64.

**Required Permission**: `"pull"`

### Signature
```javascript
dioxamine.pullFile(remotePath: string): Promise<string>
```

### Parameters
- `remotePath` (`string`): Source file path on the connected device (for example, `"/sdcard/Download/screencap.png"`).

### Returns
A `Promise` resolving to the base64-encoded string of the file contents.

---

## `dioxamine.installApk()`

Streams an APK package to the target device and invokes `pm install`.

**Required Permission**: `"install"`

### Signature
```javascript
dioxamine.installApk(base64ApkData: string, flags?: string[]): Promise<string>
```

### Parameters
- `base64ApkData` (`string`): Binary APK file encoded in base64.
- `flags` (`string[]`, optional): Array of installation arguments (for example, `["-r", "-d", "-g"]`).

### Returns
A `Promise` resolving to the package manager installation output (for example, `"Success"`).

---

## Storage Access Framework (SAF) File Pickers

Because plugins run in a sandboxed WebView, they cannot directly read the host phone filesystem. Dioxamine bridges Android's native system file picker dialogs.

**No special manifest permission is required for SAF pickers.**

### `dioxamine.pickFile()`

Opens the system document picker for the user to select a file from host phone storage.

```javascript
dioxamine.pickFile(options?: { mimeType?: string }): Promise<SelectedFile | null>
```

```typescript
interface SelectedFile {
    name: string;        // File name (e.g. "update.zip")
    size: number;        // Size in bytes
    mimeType: string;    // MIME type
    base64Data: string;  // File content in base64
}
```

### `dioxamine.saveFile()`

Opens the system save-file dialog for the user to export a file to host phone storage.

```javascript
dioxamine.saveFile(options: {
    fileName: string;
    mimeType?: string;
    base64Data: string;
}): Promise<boolean>
```
