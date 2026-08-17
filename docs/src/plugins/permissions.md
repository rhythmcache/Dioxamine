# Permissions and Security Model

Dioxamine enforces a strict permission model to protect connected devices and the host Android system from unauthorized operations.

## Declared Permissions

Plugins must explicitly declare every required capability in `plugin.json`. Attempting to call an API without declaring the corresponding permission results in an immediate Promise rejection with `SecurityException: Permission not declared in manifest`.

| Permission | Identifier | Description | Protected APIs |
| :--- | :--- | :--- | :--- |
| **Shell Execution** | `"shell"` | Allows executing non-interactive shell commands and opening interactive PTY/sh sessions. | `dioxamine.shellExec()`, `dioxamine.openInteractiveShell()` |
| **File Push** | `"push"` | Allows writing and pushing files or streams onto the target device filesystem. | `dioxamine.pushFile()`, `dioxamine.pushStream()` |
| **File Pull** | `"pull"` | Allows reading and pulling files or directories from the target device filesystem. | `dioxamine.pullFile()`, `dioxamine.pullStream()` |
| **Package Install** | `"install"` | Allows streaming APK files to the device package manager (`pm install`). | `dioxamine.installApk()` |
| **Port Forward** | `"forward"` | Allows binding local host ports and forwarding traffic to target device sockets. | `dioxamine.forwardPort()`, `dioxamine.forwardList()`, `dioxamine.forwardRemove()` |
| **Port Reverse** | `"reverse"` | Allows binding target device ports and reversing traffic back to the host system. | `dioxamine.reversePort()`, `dioxamine.reverseList()`, `dioxamine.reverseRemove()` |

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
- `dioxamine.getActiveDevice()`
- `dioxamine.showToast()`
- `dioxamine.showDialog()`
- `dioxamine.setFullScreen()` / `dioxamine.fullScreen()`
- `dioxamine.pickFile()` / `dioxamine.saveFile()` (delegates to Android Storage Access Framework with user file picker)
- `dioxamine.log.*` and `console.*` forwarding
- `dioxamine.getTheme()` and `dioxamine.onThemeChange()`
- Base64 / UTF-8 conversion helpers
