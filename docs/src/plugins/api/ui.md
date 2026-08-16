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
