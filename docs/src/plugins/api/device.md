# Device Management API

The Device Management API allows plugins to verify the current ADB connection status and query device identifiers.

## `dioxamine.getActiveDevice()`

Retrieves information about the currently active ADB device.

### Signature
```javascript
dioxamine.getActiveDevice(): Promise<DeviceInfo | null>
```

### Parameters
None.

### Returns
A `Promise` resolving to a `DeviceInfo` object, or `null` if no device is connected.

### `DeviceInfo` Object Structure

```typescript
interface DeviceInfo {
    serial: string;      // Device serial or IP:Port (e.g. "192.168.1.50:5555" or "RFCW10ABCDE")
    model: string;       // Device marketing name or model (e.g. "Pixel 8 Pro")
    state: string;       // Connection state: "device", "offline", "unauthorized", etc.
}
```

### Example

```javascript
async function checkDevice() {
    const dev = await dioxamine.getActiveDevice();
    if (!dev) {
        console.warn("No active ADB device connected in Dioxamine");
        return;
    }
    console.log(`Connected to ${dev.model} (${dev.serial}) [${dev.state}]`);
}
```
