# Device Management API

The Device Management API allows plugins to verify the active ADB connection status.

> **Multi-Device Behavior**: In Dioxamine, plugins run in the scope of the device currently selected in the UI chip (the "active" device). All ADB operations (`shellExec`, `pull`, `push`, `forwardAdd`, `openInteractiveShell`, etc.) automatically target this selected device.

## `dioxamine.adb.getActiveDevice()`

Checks whether an active ADB device is currently connected and selected.

### Signature
```javascript
dioxamine.adb.getActiveDevice(): Promise<ActiveDeviceStatus | null>
```

### Parameters
None.

### Returns
A `Promise` resolving to an `ActiveDeviceStatus` object (`{ connected: true }`) if an active device is connected, or `null` if no device is connected.

### `ActiveDeviceStatus` Object Structure

```typescript
interface ActiveDeviceStatus {
    connected: boolean;          // Always true when an active device is connected
    id?: string;                 // Device connection ID (e.g. "192.168.1.100:5555" or USB serial)
    label?: string;              // Display name or model
    model?: string;              // Device model name (e.g. "Pixel 7 Pro", "SM-S918B")
    androidVersion?: string;     // Android OS version (e.g. "14", "13")
    apiLevel?: number;           // Android SDK API level (e.g. 34, 33)
    uniqueId?: string;           // Permanent hardware serial or android_id for persistent device identification
    isRoot?: boolean;            // Whether ADB shell execution runs as root (uid=0)
    transport?: "USB" | "TCP";   // Connection transport type
    mode?: string;               // ADB device mode ("DEVICE", "RECOVERY", "SIDELOAD", "RESCUE", etc.)
    supportsShellV2?: boolean;   // Whether the device supports the shell v2 protocol
}
```

### Retrieving Device Details

`dioxamine.adb.getActiveDevice()` directly returns cached device metadata without requiring `shell` permissions or executing `getprop`:

```javascript
async function getDeviceInfo() {
    const device = await dioxamine.adb.getActiveDevice();
    if (!device) {
        console.warn("No active ADB device connected in Dioxamine");
        return null;
    }

    return {
        id: device.id,
        model: device.model || "Unknown",
        androidVersion: device.androidVersion || "Unknown",
        apiLevel: device.apiLevel,
        uniqueId: device.uniqueId,
        isRoot: device.isRoot,
        transport: device.transport
    };
}
```

### Example

```javascript
async function checkDevice() {
    const dev = await dioxamine.adb.getActiveDevice();
    if (!dev || !dev.connected) {
        console.warn("No active ADB device connected in Dioxamine");
        return;
    }
    console.log("Device is connected and ready");
}
```
