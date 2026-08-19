# Screen Mirroring and Remote Control

Dioxamine integrates the Scrcpy 4.1 engine to provide real-time, low-latency screen mirroring and interactive touch control over Wi-Fi or USB OTG.

## Starting Screen Mirroring

1. Ensure a target device is connected and selected on the **ADB** tab.
2. Tap the **Scrcpy** tab in the bottom navigation bar.
3. On the **Configurator** sub-tab, review or adjust your video settings.
4. Tap the **Start Mirroring** button at the bottom of the configurator card.
5. Dioxamine will initialize the mirror session and begin rendering the live video stream in the video player window above.

---

## Interactive Touch and Navigation

While mirroring is active, you can interact directly with the video feed:

- **Touch and Drag**: Tapping or dragging on the video player sends real-time multi-touch events directly to the target device screen.
- **Full Screen Mode**: Tap the **Full Screen icon** on the video player overlay to expand the mirror feed to cover your entire phone display. Swipe from the edge or press Back to exit full screen.
- **Floating Navigation Bar**: Enable the **Floating Navigation Bar** in Display settings to overlay quick buttons for:
  - **Back**: Navigates back one screen.
  - **Home**: Returns to the home launcher screen.
  - **Recents**: Opens recent / multitasking apps.
- **Volume Buttons**: When the **Bind Volume Keys** option is enabled, pressing the physical volume up/down buttons on your host phone sends volume adjustments directly to the target device.
- **Turn Screen Off**: When enabled, the target device display will remain dark while mirroring is active, saving battery on the target device while allowing you to control it remotely.

---

## Stopping Mirroring

- Tap the **Stop Mirroring** button in the configurator, or tap the stop icon in the player controls overlay to terminate the session.
