# File Operations and Storage API

Dioxamine provides methods to push and pull files between the host and connected ADB device using Android's Storage Access Framework (SAF). This allows streaming large files (such as backups, ROMs, or APKs) directly without loading entire payloads into JavaScript memory.

## Storage Access Framework (SAF) File Pickers

Because plugins run in a sandboxed WebView, they cannot directly access arbitrary paths on the host filesystem. Instead, Dioxamine bridges Android's native system file picker dialogs.

**No special manifest permission is required for SAF pickers.**

### `dioxamine.requestFilePicker()`

Prompts the user to pick a file to open or select a destination to save/create.

```javascript
dioxamine.requestFilePicker(mode: "open" | "create"): Promise<{ requestId: string }>
```

**Modes:**
- `"open"`: Opens the Android document picker for the user to choose an existing file on the host. Returns a `requestId` to use with `dioxamine.adb.push()`.
- `"create"`: Opens the Android document creator for the user to choose where to save a file. Returns a `requestId` to use with `dioxamine.adb.pull()`.

---

## File Pull (`adb pull`)

Downloads a file from the connected ADB device and writes it directly into the selected SAF destination.

**Required Permission**: `"pull"`

### `dioxamine.adb.pull()`

```javascript
dioxamine.adb.pull(remotePath: string, safRequestId: string): Promise<{ bytesTransferred: number }>
```

### Parameters:
- `remotePath` (`string`): Source file path on the connected device (e.g. `"/sdcard/Download/screencap.png"`).
- `safRequestId` (`string`): The request ID obtained from `dioxamine.requestFilePicker("create")`.

### Example:
```javascript
async function downloadFileFromDevice(remotePath) {
    // 1. Prompt user to select save location on Android host
    const picker = await dioxamine.requestFilePicker("create");
    
    // 2. Stream directly from device to chosen SAF destination
    const result = await dioxamine.adb.pull(remotePath, picker.requestId);
    console.log(`Transferred ${result.bytesTransferred} bytes`);
}
```

---

## File Push (`adb push`)

Uploads a file chosen via SAF from the host directly to the target path on the connected ADB device.

**Required Permission**: `"push"`

### `dioxamine.adb.push()`

```javascript
dioxamine.adb.push(localSafRequestId: string, remotePath: string): Promise<{ bytesTransferred: number }>
```

### Parameters:
- `localSafRequestId` (`string`): The request ID obtained from `dioxamine.requestFilePicker("open")`.
- `remotePath` (`string`): Destination path on the target device (e.g. `"/sdcard/Download/archive.zip"`).

### Example:
```javascript
async function uploadFileToDevice(remotePath) {
    // 1. Prompt user to pick file from Android host
    const picker = await dioxamine.requestFilePicker("open");
    
    // 2. Stream directly from SAF file to target device
    const result = await dioxamine.adb.push(picker.requestId, remotePath);
    console.log(`Uploaded ${result.bytesTransferred} bytes`);
}
```

---

## Session-Scoped Temporary Files (`dioxamine.tempFile`)

Dioxamine provides a session-scoped temporary file API that allows plugins to stream large payloads directly to disk on the host device without buffering them into JavaScript RAM.

### Security and Lifecycle
- **Interactive Consent**: Creating a temporary file prompts the user with a native confirmation dialog. No manifest permission entry is required.
- **Session Scoped**: All temporary files and in-memory handles are automatically wiped when the plugin runner session exits or when the app is restarted.
- **Path Isolation**: Real filesystem paths on the Android host are never exposed to JavaScript. Operations reference an opaque token wrapped in closure methods.
- **Append-Only Streaming**: Writes are append-only and serialized with a per-handle mutex to prevent race conditions.
- **Lazy File Creation**: Files on disk are only created upon the first write operation.

### `dioxamine.tempFile.create()`

Requests permission to create a temporary file handle. Returns a `Promise` resolving to a file handle object.

```javascript
dioxamine.tempFile.create(): Promise<TempFileHandle>
```

#### `TempFileHandle` Interface

| Method | Return Type | Description |
|---|---|---|
| `write(base64Chunk)` | `Promise<{ bytesWritten: number }>` | Appends a Base64-encoded chunk to the temporary file. |
| `read(offset, length)` | `Promise<string>` | Reads up to `length` bytes (clamped to max 16MB per chunk) starting from byte `offset`. Returns Base64 string. Returns `""` if unwritten or at EOF. |
| `size()` | `Promise<number>` | Returns the current file size on disk in bytes (`0` if not yet written). |
| `delete()` | `Promise<{ success: boolean }>` | Deletes the file immediately and invalidates the handle. |
| `seek(position)` | `void` | Moves the local cursor to `position` bytes (clamped to `>= 0`). Local operation (no native round-trip). |
| `tell()` | `number` | Returns the current local cursor position. Local operation (no native round-trip). |
| `readNext(length)` | `Promise<string>` | Reads up to `length` bytes from the current cursor, advances the cursor by the decoded byte count (`atob(b64).length`), and returns the Base64 chunk. |

### Streaming Example

```javascript
async function streamPayloadToDisk() {
    // 1. Request a temporary file handle (prompts user consent dialog)
    const file = await dioxamine.tempFile.create();

    try {
        // 2. Stream chunks to disk
        const chunk1 = dioxamine.utf8ToBase64("Hello, ");
        const chunk2 = dioxamine.utf8ToBase64("Dioxamine Streaming!");
        await file.write(chunk1);
        await file.write(chunk2);

        console.log("Current file size in bytes:", await file.size());

        // 3. Read back using cursor-based streaming
        file.seek(0);
        const totalSize = await file.size();
        while (file.tell() < totalSize) {
            const b64Chunk = await file.readNext(1024);
            if (!b64Chunk) break;
            const textChunk = dioxamine.base64ToUtf8(b64Chunk);
            console.log("Read chunk:", textChunk);
        }
    } finally {
        // 4. Clean up file immediately when done (or let session teardown clean it up)
        await file.delete();
    }
}
```
