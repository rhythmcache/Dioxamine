# Miscellaneous and Keep Alive

The **Miscellaneous** card in Settings provides tools to keep Dioxamine connections alive and active in the background when the app is minimized.

---

## Keep Alive Background Service

By default, modern Android versions (Android 11+) apply aggressive background limits, including the **Cached Apps Freezer** and **Doze Mode**. If an app is minimized without a Foreground Service, the operating system pauses CPU execution, freezing active ADB TCP/USB sockets and fastboot operations.

The **Keep Alive** feature resolves this by running a dedicated Android Foreground Service with a WakeLock.

---

## Enabling Keep Alive

1. Open the **Settings** tab.
2. Tap the **Miscellaneous** expandable card.
3. Toggle on **Keep Alive**.
4. Grant the requested system permissions:
   - **Notification Permission** (`POST_NOTIFICATIONS` on Android 13+ / API 33+): Required to show the persistent foreground service notification.
   - **Battery Optimization Exemption** (Android 6.0+ / API 23+): Prompts you to exempt Dioxamine from battery optimizations to prevent Doze mode from sleeping background network/USB transfers.

---

## Live Status Notification

While Keep Alive is running, Dioxamine displays an ongoing notification in your notification shade showing real-time connected device metrics:

- **Both ADB and Fastboot connected**: e.g., `1 ADB device connected, 1 Fastboot device connected`
- **ADB Only**: e.g., `1 ADB device connected` or `2 ADB devices connected`
- **Fastboot Only**: e.g., `1 Fastboot device connected`
- **No devices connected**: `No devices connected • Active in background`

### Notification Actions
- **Tap Notification**: Instantly brings Dioxamine back to the foreground.
- **Stop Action**: Tap the **Stop** button directly on the notification to terminate the background service and release the WakeLock without needing to navigate to the Settings tab.

---

## About Links

Under **Settings > About**, you can find direct links to external resources:
- **Documentation**: Direct hyperlink to the official [Dioxamine Documentation Book](https://rhythmcache.github.io/Dioxamine/book/).
- **Source Code**: Direct link to the [GitHub Repository](https://github.com/rhythmcache/Dioxamine).
- **Social & Community**: Quick buttons to open the GitHub profile and Telegram group.
