# Plugin Security and Permissions

Dioxamine provides a secure sandbox environment for third-party Web plugins with granular permission gates and developer debugging tools.

## Accessing Plugin Settings

1. Open the **Settings** tab.
2. Tap the **Plugin Settings** expandable card.

---

## Plugin Security Options

### 1. WebView Debugging Toggle
- **Toggle Switch**: Enables Chrome Remote DevTools inspection for installed plugins.
- **For Plugin Developers**: When enabled, you can connect your host phone to a PC, open `chrome://inspect` in Google Chrome on your computer, and inspect plugin DOM, console logs, network calls, and JavaScript breakpoints in real time.

### 2. Manage Plugin Permissions
Dioxamine requires user authorization before any plugin can execute shell commands, read device info, push files, or forward ports.

1. In the Plugin Settings card, tap **Manage Permissions**.
2. A list of all installed plugins will be displayed along with their requested permissions (e.g. `device.read`, `shell.exec`, `files.manage`, `saf.pick`).
3. You can review permission statuses:
   - **Allowed Always**: Granted permanent permission.
   - **Ask Every Time**: Prompts every time the plugin triggers an action.
   - **Denied**: Blocks the plugin from accessing that capability.
4. Tap **Revoke** or change permissions individually for any plugin at any time.
