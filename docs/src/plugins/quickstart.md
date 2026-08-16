# Quickstart: Your First Plugin

In this tutorial, you will create a simple plugin called **Device Info** that displays the connected Android device's model and kernel version using single-command shell execution.

## Step 1: Create the Plugin Manifest

Create a new directory named `device-info-plugin` and add a `plugin.json` file:

```json
{
  "schemaVersion": 1,
  "id": "com.example.deviceinfo",
  "name": "Device Info",
  "description": "Displays device model and kernel information",
  "version": "1.0.0",
  "versionCode": 1,
  "author": "Your Name",
  "entry": "index.html",
  "icon": "icon.png",
  "minAppVersionCode": 1,
  "permissions": [
    "shell"
  ],
  "fullscreen": false
}
```

## Step 2: Create the User Interface (`index.html`)

Create `index.html`:

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
    <title>Device Info</title>
    <link rel="stylesheet" href="styles.css">
</head>
<body>
    <div id="app">
        <h1>Device Information</h1>
        <p class="subtitle">Powered by Dioxamine Plugin Engine</p>

        <div class="card">
            <div class="row">
                <span class="label">Model:</span>
                <span id="device-model" class="value">Loading...</span>
            </div>
            <div class="row">
                <span class="label">Kernel:</span>
                <span id="kernel-version" class="value">Loading...</span>
            </div>
        </div>

        <button id="refresh-btn">Refresh</button>
    </div>

    <script src="app.js"></script>
</body>
</html>
```

## Step 3: Add Styles (`styles.css`)

Create `styles.css` using Dioxamine CSS theme variables:

```css
body {
    margin: 0;
    padding: 16px;
    background-color: var(--dioxamine-bg, #121212);
    color: var(--dioxamine-fg, #ffffff);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
}

#app {
    max-width: 600px;
    margin: 0 auto;
}

h1 {
    font-size: 20px;
    margin-bottom: 4px;
}

.subtitle {
    font-size: 13px;
    color: var(--dioxamine-on-surface-variant, #888888);
    margin-bottom: 20px;
}

.card {
    background-color: var(--dioxamine-card-bg, #1e1e1e);
    border: 1px solid var(--dioxamine-outline-variant, #333333);
    border-radius: 12px;
    padding: 16px;
    margin-bottom: 16px;
}

.row {
    display: flex;
    justify-content: space-between;
    padding: 8px 0;
    border-bottom: 1px solid var(--dioxamine-outline-variant, #2a2a2a);
}

.row:last-child {
    border-bottom: none;
}

.label {
    font-weight: 500;
    color: var(--dioxamine-on-surface-variant, #aaaaaa);
}

.value {
    font-family: monospace;
    color: var(--dioxamine-accent, #64b5f6);
}

button {
    background-color: var(--dioxamine-primary, #2196f3);
    color: var(--dioxamine-on-primary, #ffffff);
    border: none;
    border-radius: 8px;
    padding: 10px 18px;
    font-size: 14px;
    font-weight: 600;
    cursor: pointer;
    width: 100%;
}

button:active {
    opacity: 0.85;
}
```

## Step 4: Implement Logic (`app.js`)

Create `app.js` and use `dioxamine.shellExec()` to query Android system properties:

```javascript
async function loadDeviceInfo() {
    const modelEl = document.getElementById('device-model');
    const kernelEl = document.getElementById('kernel-version');

    modelEl.textContent = 'Fetching...';
    kernelEl.textContent = 'Fetching...';

    try {
        // Verify bridge and active connection
        const device = await dioxamine.getActiveDevice();
        if (!device) {
            modelEl.textContent = 'No connected ADB device';
            kernelEl.textContent = '-';
            return;
        }

        // Query model
        const modelResult = await dioxamine.shellExec('getprop ro.product.model');
        modelEl.textContent = modelResult.stdout.trim() || 'Unknown';

        // Query kernel
        const kernelResult = await dioxamine.shellExec('uname -r');
        kernelEl.textContent = kernelResult.stdout.trim() || 'Unknown';

        dioxamine.showToast('Device information updated', 'short');
    } catch (error) {
        modelEl.textContent = 'Error: ' + error.message;
        kernelEl.textContent = '-';
        dioxamine.log.e('DeviceInfo', error.message);
    }
}

document.getElementById('refresh-btn').addEventListener('click', loadDeviceInfo);

// Wait for Dioxamine bridge initialization
if (window.dioxamine && window.__dioxamine_bridge_ready) {
    loadDeviceInfo();
} else {
    window.addEventListener('dioxamine-bridge-ready', loadDeviceInfo, { once: true });
}
```

## Step 5: Package and Install

1. Create a 512x512 PNG image named `icon.png`.
2. Select all files inside the directory (`plugin.json`, `index.html`, `styles.css`, `app.js`, `icon.png`) and create a ZIP archive named `com.example.deviceinfo.zip`.
3. In Dioxamine, navigate to the **ADB** tab, click **Plugins**, click **Install Plugin**, and select your ZIP file.
4. Grant the requested `shell` permission when prompted, and your plugin will run.
