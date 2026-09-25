# UI Controls, Dialogs and Fullscreen

The UI API allows plugins to trigger native Android toast notifications, display blocking confirmation dialogs, and toggle edge-to-edge full-screen mode.

**Required Permissions**: None (Safe native UI methods).

---

## Native Toast Notifications

### `dioxamine.showToast()`

Displays a native Android toast popup message.

```javascript
dioxamine.showToast(message: string, duration?: 'short' | 'long'): void
```

#### Parameters
- `message` (`string`): The text to display.
- `duration` (`string`, optional): Either `'short'` (2 seconds) or `'long'` (3.5 seconds). Default: `'short'`.

#### Example
```javascript
dioxamine.showToast("Settings saved successfully", "short");
```

---

## Native Material Confirmation Dialogs

### `dioxamine.showDialog()`

Displays a native Material 3 alert dialog and waits for user button selection.

```javascript
dioxamine.showDialog(options: {
    title: string;
    message: string;
    buttons?: string[];
}): Promise<{ buttonIndex: number }>
```

#### Parameters
- `title` (`string`): Dialog heading text.
- `message` (`string`): Descriptive dialog body text.
- `buttons` (`string[]`, optional): Array of button labels in order. Default: `['OK']`.

#### Returns
A `Promise` resolving to `{ buttonIndex: number }`, where `buttonIndex` corresponds to the clicked button index (0-based). If dismissed without clicking a button, returns `-1`.

#### Example
```javascript
async function confirmReboot() {
    const res = await dioxamine.showDialog({
        title: "Reboot Device",
        message: "Are you sure you want to reboot the target device into Recovery mode?",
        buttons: ["Cancel", "Reboot to Recovery"]
    });

    if (res.buttonIndex === 1) {
        await dioxamine.shellExec("reboot recovery");
        dioxamine.showToast("Rebooting...", "short");
    }
}
```

---

## Fullscreen Controls

### `dioxamine.setFullScreen()` / `dioxamine.fullScreen()`

Dynamically toggles whether Dioxamine's top bar is visible or hidden, giving the plugin the entire screen.

```javascript
dioxamine.setFullScreen(enable: boolean): void
```

#### Parameters
- `enable` (`boolean`): Pass `true` to enter full-screen mode (hide top bar) or `false` to restore the windowed top bar.

#### Note
`dioxamine.fullScreen(enable)` is also supported as an alias for `dioxamine.setFullScreen(enable)`.

#### Example
```javascript
// Toggle fullscreen based on user button click
let isFull = false;

document.getElementById('toggle-fullscreen-btn').addEventListener('click', () => {
    isFull = !isFull;
    dioxamine.setFullScreen(isFull);
});
```

---

## Exiting and Closing Plugins

### 1. System Back Gesture / Back Button
Users can exit any plugin at any time by performing the standard Android **Back gesture** (swiping from the left/right screen edge) or pressing the system **Back button**.

### 2. Programmatic Exit (`dioxamine.exitPlugin()`)
Plugins can also provide an in-app "Exit" or "Close" button in their web UI:

```javascript
dioxamine.exitPlugin(): void
```

#### Example
```javascript
document.getElementById('close-btn').addEventListener('click', () => {
    dioxamine.exitPlugin();
});
```

#### Note
`dioxamine.closePlugin()` is also supported as an alias.

---

## Hardware and System Button Interception (Back and Volume Keys)

By default, pressing the system **Back button** or performing a back gesture navigates back through internal web history or closes the plugin screen, while pressing the **Volume buttons** adjusts Android's system media volume.

Plugins can request button passing to intercept these buttons directly, preventing the default app action and handling the events in JavaScript.

**Required Permissions**: None (Safe native UI controls).

### 1. Back Button Interception

When back button interception is requested and granted, pressing the Android Back button or performing an edge-swipe gesture will not close the plugin. Instead, the app passes the back event directly to the plugin's JavaScript handler. The plugin can then decide what to do (e.g., dismiss an in-app modal, navigate internal SPA routes, or call `dioxamine.exitPlugin()` / `dioxamine.defaultBack()`).

#### `dioxamine.onBackButton(listener)`
Registers a listener callback for the back button. Setting a function automatically enables back button interception. Passing `null` or `false` disables interception.

```javascript
dioxamine.onBackButton(listener: ((event: ButtonEvent) => void) | null): void
```

#### `dioxamine.setInterceptBackButton(enable)`
Explicitly toggles whether the app intercepts back button presses and passes them to the plugin.

```javascript
dioxamine.setInterceptBackButton(enable: boolean): void
dioxamine.isInterceptingBackButton(): boolean
```

#### `dioxamine.defaultBack()`
Performs the app's default back action (navigates web history if available, or closes the plugin). Useful when the plugin decides not to handle a particular back press.

```javascript
dioxamine.defaultBack(): void
```

#### Example: In-Plugin Modal Back Handling
```javascript
let isModalOpen = false;

// Registering onBackButton automatically enables back button interception
dioxamine.onBackButton((event) => {
    console.log("Back button pressed:", event);
    if (isModalOpen) {
        // Dismiss in-plugin dialog
        closeModal();
    } else {
        // No modal open: perform default back action or exit
        dioxamine.exitPlugin();
    }
});

// To stop intercepting back button:
// dioxamine.onBackButton(null);
```

#### User Safety Escape Hatch
To ensure users are never permanently trapped by a frozen or buggy plugin:
- **Windowed Mode**: Tapping the native TopAppBar back arrow icon always closes the plugin immediately.
- **Fullscreen / Gesture Navigation**: Pressing the back button or swiping the back gesture 3 times in rapid succession (within 2 seconds) triggers an emergency escape hatch that prompts the user and force-exits the plugin.

---

### 2. Volume Buttons Interception

When volume button interception is enabled, pressing the physical **Volume Up**, **Volume Down**, or **Volume Mute** buttons on the device will not trigger the Android volume slider. The event is intercepted and forwarded directly to the plugin.

#### `dioxamine.onVolumeButton(listener)`
Registers a listener callback for volume button events (`down` and `up`). Passing a function automatically enables volume key interception. Passing `null` or `false` disables it.

```javascript
dioxamine.onVolumeButton(listener: ((event: VolumeButtonEvent) => void) | null): void
```

#### `dioxamine.setInterceptVolumeButtons(enable)`
Explicitly toggles whether the app intercepts hardware volume buttons.

```javascript
dioxamine.setInterceptVolumeButtons(enable: boolean): void
dioxamine.isInterceptingVolumeButtons(): boolean
```

#### Event Object Structure
Volume button events contain the following properties:
- `button` (`string`): `'volume_up'`, `'volume_down'`, or `'volume_mute'`.
- `direction` (`string`): `'up'`, `'down'`, or `'mute'`.
- `action` (`string`): `'down'` (key press) or `'up'` (key release).
- `key` (`string`): `'VolumeUp'`, `'VolumeDown'`, or `'VolumeMute'`.
- `keyCode` (`number`): Android keycode (`24` for Volume Up, `25` for Volume Down, `164` for Volume Mute).
- `repeatCount` (`number`): Repeat counter when button is held down (`0` for initial press).

#### Example: Remote Control Volume
```javascript
// Intercept volume buttons and forward to remote ADB device
dioxamine.onVolumeButton(async (event) => {
    if (event.action !== 'down') return;

    if (event.direction === 'up') {
        console.log("Volume Up pressed - sending ADB keyevent 24");
        await dioxamine.adb.shellExec("input keyevent 24");
    } else if (event.direction === 'down') {
        console.log("Volume Down pressed - sending ADB keyevent 25");
        await dioxamine.adb.shellExec("input keyevent 25");
    }
});

// To restore normal system volume behavior:
// dioxamine.setInterceptVolumeButtons(false);
```

---

### 3. Unified Button API (`dioxamine.buttons.*`)

All button methods are also available under the `dioxamine.buttons` namespace:

```javascript
// Configure both button interceptors at once
dioxamine.buttons.setIntercept({ back: true, volume: true });

// Check current status
const isBackIntercepted = dioxamine.buttons.isInterceptingBackButton();
const isVolumeIntercepted = dioxamine.buttons.isInterceptingVolumeButtons();

// Asynchronous status check
const status = await dioxamine.buttons.getStatusAsync();
// status => { back: true, volume: true }

// Unified button listener for all buttons (back and volume)
dioxamine.buttons.onButton((event) => {
    console.log("Button event:", event.button, event.action);
});
```

### 4. DOM Custom Events

Button presses also dispatch DOM Custom Events on the `window` object:
- `'dioxamine-back-button'`: Dispatched when the back button is pressed.
- `'dioxamine-volume-button'`: Dispatched on volume button down and up.
- `'dioxamine-button'`: Dispatched for both back and volume buttons.

```javascript
window.addEventListener('dioxamine-back-button', (e) => {
    console.log("DOM Back Event:", e.detail);
});

window.addEventListener('dioxamine-volume-button', (e) => {
    console.log("DOM Volume Event:", e.detail);
});
```

---

## Opening External Links

### `dioxamine.openBrowser()` / `dioxamine.openUrl()`

Opens an external web URL directly in the default web browser of the host Android device running Dioxamine (not on the connected ADB device).

To protect user safety and prevent unauthorized redirects or phishing attacks, Dioxamine presents a native confirmation dialog to the user displaying the target URL and origin plugin name before launching the browser.

```javascript
dioxamine.openBrowser(url: string): Promise<{ success: boolean }>
```

#### Parameters
- `url` (`string`): The web address to open. Must begin with `http://` or `https://` and have a valid host (maximum length: 2048 characters).

#### Returns
A `Promise` resolving to `{ success: true }` when the user approves the prompt and the browser is launched. If the user dismisses or clicks **Cancel**, the promise rejects with `"User cancelled opening external link"`. If no browser application is available or the URL format is invalid, it rejects with a descriptive error.

#### Example
```javascript
// Open external documentation or repository in host device browser
document.getElementById('docs-link').addEventListener('click', () => {
    dioxamine.openBrowser("https://example.com/docs")
        .then(() => {
            console.log("Browser opened successfully");
        })
        .catch(err => {
            if (err.message.includes("cancelled")) {
                console.log("User cancelled browser launch");
            } else {
                dioxamine.showToast("Failed to open browser: " + err.message, "short");
            }
        });
});
```

#### Note
`dioxamine.openUrl(url)` is also supported as an alias.
