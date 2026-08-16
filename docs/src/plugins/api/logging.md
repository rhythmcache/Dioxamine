# Native Logging and Debugging

Dioxamine provides integrated logging channels that bridge JavaScript console messages and explicit log calls directly into the native Android `Logcat` stream.

---

## Direct Native Logging (`dioxamine.log`)

The `dioxamine.log` namespace sends structured log entries to Android Logcat under the tag `Plugin:<plugin-id>/<tag>`.

### Methods

```javascript
dioxamine.log.v(tag: string, message: string): void  // Verbose
dioxamine.log.d(tag: string, message: string): void  // Debug
dioxamine.log.i(tag: string, message: string): void  // Info
dioxamine.log.w(tag: string, message: string): void  // Warn
dioxamine.log.e(tag: string, message: string): void  // Error
```

### Example
```javascript
dioxamine.log.i("Network", "Connecting to device socket at 127.0.0.1:8080");
dioxamine.log.e("Sync", "File transfer aborted unexpectedly");
```

---

## Automatic Console Hooking

Dioxamine automatically hooks standard JavaScript `console` methods:
- `console.log(...)` -> Forwarded to `dioxamine.log.d()`
- `console.info(...)` -> Forwarded to `dioxamine.log.i()`
- `console.warn(...)` -> Forwarded to `dioxamine.log.w()`
- `console.error(...)` -> Forwarded to `dioxamine.log.e()`

Objects, errors, and arrays passed to `console.log()` are automatically serialized to JSON strings.

---

## Inspecting Logs via ADB

You can monitor plugin runtime messages in real time using the ADB CLI from your PC:

```bash
adb logcat -s "Plugin:com.example.myplugin" "PluginJS:com.example.myplugin"
```

---

## Chrome DevTools Remote Debugging

To inspect elements, view network requests, or set breakpoints using Chrome DevTools on your computer:

1. Open Dioxamine on your phone.
2. Navigate to **Settings** -> **Plugins**.
3. Toggle **Enable WebView Debugging** on.
4. On your PC, open Google Chrome and navigate to `chrome://inspect`.
5. Under **Remote Target**, find your plugin WebView and click **inspect**.
