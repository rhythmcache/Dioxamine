# Application Logs and Bug Reporting

Dioxamine maintains an internal diagnostic log system to assist with troubleshooting connection errors, Scrcpy streaming issues, Fastboot flashing failures, and plugin execution bugs.

## Accessing Log Settings

1. Open the **Settings** tab.
2. Tap the **Logs** expandable card.

---

## Log Settings Options

### 1. Enable Logging Toggle
- **Toggle Switch**: Enables or disables background diagnostics logging.
- Enabled by default to record session events and error traces.

### 2. Export All Logs (ZIP Archive)
1. Tap **Export All Logs**.
2. Your phone's system document picker will open with a suggested ZIP filename (e.g. `dioxamine_logs_1710000000000.zip`).
3. Select your save directory and tap **Save**.
4. The generated ZIP file contains complete timestamps and error backtraces. You can share this ZIP file on the official Telegram support channel or attach it to a GitHub issue.

### 3. Clear All Logs
- Tap **Clear Logs** (outlined in red) to permanently erase all locally cached log records and reclaim storage space.
