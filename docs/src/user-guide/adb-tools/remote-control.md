# Remote Control

The **Remote Control** tool transforms your host phone into a wireless remote controller with directional buttons, volume/power controls, Android TV-specific features, media buttons, and text injection.

## Opening Remote Control

1. Open the **ADB** tab.
2. Under **Built-in Actions**, tap the **Remote Control** tile.

---

## Automatic Android TV Detection

When opened, Dioxamine queries the target device for Android TV characteristics (such as Leanback UI features):
- **Android TV Mode**: Automatically activates television-specific controls (TV Guide, TV Input, Channel +/-) and media playback panels.
- **Standard Android Mode**: Streamlined for secondary phones and tablets.
- **Manual Mode Switcher**: Tap the dropdown arrow in the top app bar title to manually switch between **Android TV** and **Standard Android** modes.

---

## Remote Control Layout

### 1. Power and Volume Bar (Top)
- **Power Button (Red)**: Sends power key event (`KEYCODE_POWER`) to lock, unlock, or wake the device.
- **Mute Button**: Toggles audio mute state (`KEYCODE_VOLUME_MUTE`).
- **Vol - / Vol + Buttons**: Lowers or raises system media volume (`KEYCODE_VOLUME_DOWN` / `KEYCODE_VOLUME_UP`).

### 2. Directional Pad (D-Pad)
- **Up, Down, Left, Right Arrows**: Navigates through menus, app grids, list views, and TV interfaces.
- **Center OK Button (Primary)**: Confirms selections and opens selected apps or items (`KEYCODE_DPAD_CENTER`).

### 3. Android Navigation Bar
- **Back Button**: Navigates back one screen (`KEYCODE_BACK`).
- **Home Button**: Returns immediately to the home launcher screen (`KEYCODE_HOME`).
- **Recents Button**: Opens the multitasking / recent apps view (`KEYCODE_APP_SWITCH`).
- **Menu Button**: Opens the context options menu (`KEYCODE_MENU`).

### 4. Android TV Controls (TV Mode Only)
- **TV Guide**: Opens the electronic program guide (`KEYCODE_GUIDE`).
- **TV Input**: Cycles through HDMI and auxiliary inputs (`KEYCODE_TV_INPUT`).
- **Channel Up / Channel Down**: Switches channels up or down (`KEYCODE_CHANNEL_UP` / `KEYCODE_CHANNEL_DOWN`).
- **Search**: Launches voice/text search on the TV interface (`KEYCODE_SEARCH`).

### 5. Media Playback Controls (TV Mode Only)
- **Previous**: Skips to previous audio/video track (`KEYCODE_MEDIA_PREVIOUS`).
- **Rewind**: Fast-rewinds media playback (`KEYCODE_MEDIA_REWIND`).
- **Play / Pause (Center Blue)**: Toggles media playback (`KEYCODE_MEDIA_PLAY_PAUSE`).
- **Fast Forward**: Fast-forwards media playback (`KEYCODE_MEDIA_FAST_FORWARD`).
- **Next**: Skips to next audio/video track (`KEYCODE_MEDIA_NEXT`).

### 6. Text Input Injection Bar
Typing on a TV with a directional remote can be slow. Dioxamine lets you type directly from your phone:
1. Tap the text field at the bottom of the Remote Control screen.
2. Type any text, URL, or password using your phone keyboard.
3. Tap the **Send icon** (or press Enter on your keyboard).
4. Dioxamine will instantly inject the text string into the focused input field on the target device.

### 7. Numeric Keypad Drawer
- Tap the **Dialpad icon** inside the text input bar to open a 0-9 numeric pad.
- Useful for entering PINs, channel numbers, or security codes on Android TVs.
