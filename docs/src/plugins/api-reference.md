# JavaScript Bridge API Reference

The global `dioxamine` object provides asynchronous methods for interacting with connected devices, native dialogs, file systems, and the Android host.

## Bridge Initialization

The native bridge is loaded automatically before any plugin scripts execute. To guarantee readiness across all initialization states, listen for the `dioxamine-bridge-ready` event:

```javascript
function initPlugin() {
    console.log("Dioxamine Bridge is ready:", window.dioxamine);
}

if (window.dioxamine && window.__dioxamine_bridge_ready) {
    initPlugin();
} else {
    window.addEventListener('dioxamine-bridge-ready', initPlugin, { once: true });
}
```

## API Modules

The API is organized into the following specialized modules:

1. **[Device Management](api/device.md)**: Query the active ADB connection and metadata.
2. **[Single Command Execution (shellExec)](api/shell.md)**: Run non-interactive commands and receive exit code, stdout, and stderr.
3. **[Interactive Shell Sessions (openInteractiveShell)](api/interactive-shell.md)**: Open persistent bi-directional PTY streams for terminals and live logcat.
4. **[File Operations](api/files.md)**: Push and pull files, stream raw data, install APK packages, and launch Android SAF file pickers.
5. **[Port Forwarding and Reverse](api/port-forwarding.md)**: Manage TCP socket forwarding and reversing.
6. **[UI Controls, Dialogs and Fullscreen](api/ui.md)**: Show native Material toasts, blocking dialogs, and toggle edge-to-edge full-screen mode.
7. **[Native Logging and Debugging](api/logging.md)**: Forward logs and console output directly to Android Logcat.
8. **[Dynamic Theming and Material 3](api/theming.md)**: Integrate with Dioxamine's dynamic color schemes and listen for theme changes.
