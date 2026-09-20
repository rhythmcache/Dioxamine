# Device Management API

The Device Management API allows plugins to verify the active ADB connection status.

> **Multi-Device Behavior**: In Dioxamine, plugins run in the scope of the device currently selected in the UI chip (the "active" device). All ADB operations (`shellExec`, `pull`, `push`, `forwardAdd`, `openInteractiveShell`, etc.) automatically target this selected device.

## `dioxamine.getActiveDevice()`

Checks whether an active ADB device is currently connected and selected.

### Signature
```javascript
dioxamine.getActiveDevice(): Promise<ActiveDeviceStatus | null>
```

### Parameters
None.

### Returns
A `Promise` resolving to an `ActiveDeviceStatus` object (`{ connected: true }`) if an active device is connected, or `null` if no device is connected.

### `ActiveDeviceStatus` Object Structure

```typescript
interface ActiveDeviceStatus {
    connected: boolean;  // Always true when an active device is connected
}
```

### Retrieving Device Details

If your plugin needs specific device identifiers or properties (such as device model, Android release version, or serial number), query them using `dioxamine.shellExec()`:

```javascript
async function getDeviceInfo() {
    const device = await dioxamine.getActiveDevice();
    if (!device) {
        console.warn("No active ADB device connected in Dioxamine");
        return null;
    }

    const [modelRes, versionRes, serialRes] = await Promise.all([
        dioxamine.shellExec("getprop ro.product.model"),
        dioxamine.shellExec("getprop ro.build.version.release"),
        dioxamine.shellExec("getprop ro.serialno")
    ]);

    return {
        model: modelRes.stdout.trim() || "Unknown",
        androidVersion: versionRes.stdout.trim() || "Unknown",
        serial: serialRes.stdout.trim() || "Unknown"
    };
}
```

### Example

```javascript
async function checkDevice() {
    const dev = await dioxamine.getActiveDevice();
    if (!dev || !dev.connected) {
        console.warn("No active ADB device connected in Dioxamine");
        return;
    }
    console.log("Device is connected and ready");
}
```
