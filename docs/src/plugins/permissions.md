# Permissions and Security Model

Dioxamine enforces a strict permission model to protect connected devices and the host Android system from unauthorized operations.

## Declaring Permissions in `plugin.json`

Permissions must be grouped under their respective subkeys:
- `"adb"`: Capabilities targeting the connected ADB device.
- `"fastboot"`: Capabilities targeting connected Fastboot devices in bootloader mode.
- `"common"`: Host capabilities applicable across modes (such as network access).

```json
{
  "permissions": {
    "adb": [
      "shell",
      "push"
    ],
    "fastboot": [
      "fastboot"
    ],
    "common": [
      "network"
    ]
  }
}
```

## Permission Reference

| Subkey | Permission | Identifier | Description | Protected APIs |
| :--- | :--- | :--- | :--- | :--- |
| `adb` | **Shell Execution** | `"shell"` | Allows executing non-interactive shell commands and opening interactive PTY/sh sessions. | `dioxamine.adb.shellExec()`, `dioxamine.adb.openInteractiveShell()` |
| `adb` | **File Push** | `"push"` | Allows pushing files from host SAF into target device filesystem. | `dioxamine.adb.push()` |
| `adb` | **File Pull** | `"pull"` | Allows pulling files from target device filesystem to host SAF. | `dioxamine.adb.pull()` |
| `adb` | **Package Install** | `"install"` | Allows streaming APK files to the device package manager (`pm install`). | Package install APIs |
| `adb` | **Port Forward** | `"forward"` | Allows binding local host ports and forwarding traffic to target device sockets. | `dioxamine.adb.forwardAdd()`, `dioxamine.adb.forwardRemove()` |
| `adb` | **Port Reverse** | `"reverse"` | Allows binding target device ports and reversing traffic back to the host system. | `dioxamine.adb.reverseAdd()`, `dioxamine.adb.reverseRemove()` |
| `fastboot` | **Fastboot Access** | `"fastboot"` | Full access to USB Fastboot bootloader interface (variables, raw commands, flash, boot, reboot). | `dioxamine.fastboot.*` |
| `common` | **Network Access** | `"network"` | Allows sending HTTP/HTTPS requests to the internet, local networks, and localhost services. | `dioxamine.http.fetch()` |

## Permission Policies

When a plugin is executed, Dioxamine evaluates permission requests using a configurable policy:

1. **PROMPT (Default)**:
   - On the first call to a privileged API, Dioxamine pauses execution and presents a native consent dialog to the user.
   - The user can choose:
     - **Allow Once**: Grants permission for the current session only.
     - **Always Allow**: Persists granted permission in the secure app store.
     - **Deny**: Rejects the immediate call and records denial.
2. **ALWAYS_ALLOW**:
   - The user has permanently granted the permission for this specific plugin ID.
   - API calls execute immediately without prompts.
3. **ALWAYS_DENY**:
   - The user has permanently blocked the permission for this plugin.
   - API calls fail immediately with `Permission denied by user policy`.

## Managing and Revoking Permissions in App Settings

Users can view, grant, or revoke permissions at any time:
1. Open Dioxamine and go to the **Settings** tab.
2. Expand the **Plugins** card.
3. Tap **Manage** next to **Plugin Permissions**.
4. Adjust policies individually for each installed plugin (`Ask Every Time`, `Always Allow`, or `Always Deny`), or tap **Reset All** to restore default prompt behavior.

## Safe Native APIs (No Permission Required)

The following bridge methods are safe UI/context utilities and do not require declared permissions:
- `dioxamine.adb.getActiveDevice()`
- `dioxamine.showToast()`
- `dioxamine.showDialog()`
- `dioxamine.setFullScreen()` / `dioxamine.fullScreen()`
- `dioxamine.exitPlugin()` / `dioxamine.closePlugin()`
- `dioxamine.onBackButton()` / `dioxamine.setInterceptBackButton()` / `dioxamine.defaultBack()`
- `dioxamine.onVolumeButton()` / `dioxamine.setInterceptVolumeButtons()`
- `dioxamine.buttons.*` button management
- `dioxamine.openBrowser()` / `dioxamine.openUrl()` (prompts user with a native confirmation dialog showing the target URL before launching)
- `dioxamine.requestFilePicker()` (delegates to Android Storage Access Framework with user file picker)
- `dioxamine.log.*` and `console.*` forwarding
- `dioxamine.getTheme()` and `dioxamine.onThemeChange()`
- Base64 / UTF-8 conversion helpers
