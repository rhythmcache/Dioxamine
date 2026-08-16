# Complete Plugin Examples

This chapter provides complete source code references for three typical Dioxamine plugin types:
1. **Windowed Dashboard**: Single-shot shell commands and Material 3 cards.
2. **Terminal Console**: Interactive PTY shell with xterm.js in full-screen mode.
3. **Live Streamer**: Real-time continuous logcat stream with custom controls.

---

## 1. Device Dashboard Plugin

A standard windowed page plugin demonstrating non-interactive `shellExec()`, Material 3 card styling, and native UI dialogs.

### `plugin.json`
```json
{
  "schemaVersion": 1,
  "id": "com.example.dashboard",
  "name": "Device Dashboard",
  "description": "System specifications and quick diagnostics demo",
  "version": "1.0.0",
  "versionCode": 1,
  "author": "Dioxamine Community",
  "entry": "index.html",
  "icon": "icon.png",
  "minAppVersionCode": 1,
  "permissions": [
    "shell"
  ],
  "fullscreen": false
}
```

### `app.js`
```javascript
async function refreshSpecs() {
    try {
        const model = await dioxamine.shellExec("getprop ro.product.model");
        const androidVer = await dioxamine.shellExec("getprop ro.build.version.release");
        const kernel = await dioxamine.shellExec("uname -r");

        document.getElementById('val-model').textContent = model.stdout.trim() || '-';
        document.getElementById('val-version').textContent = androidVer.stdout.trim() || '-';
        document.getElementById('val-kernel').textContent = kernel.stdout.trim() || '-';
    } catch (err) {
        dioxamine.showToast("Failed to query device specs: " + err.message, "long");
    }
}

document.getElementById('refresh-btn').addEventListener('click', refreshSpecs);
```

---

## 2. Interactive Terminal (`xterm.js`)

A full-screen interactive PTY terminal connecting `xterm.js` to `openInteractiveShell()`.

### `plugin.json`
```json
{
  "schemaVersion": 1,
  "id": "com.example.terminal",
  "name": "Terminal",
  "description": "Interactive PTY terminal using xterm.js",
  "version": "1.0.0",
  "versionCode": 1,
  "author": "Dioxamine Community",
  "entry": "index.html",
  "icon": "icon.png",
  "minAppVersionCode": 1,
  "permissions": [
    "shell"
  ],
  "fullscreen": true
}
```

### `app.js`
```javascript
const term = new Terminal({
    cursorBlink: true,
    fontFamily: 'monospace',
    theme: { background: '#121212', foreground: '#ffffff' }
});

term.open(document.getElementById('terminal-container'));

async function connectTerminal() {
    try {
        const session = await dioxamine.openInteractiveShell();

        // Target device stdout -> xterm.js
        session.onData((b64Chunk) => {
            const text = dioxamine.base64ToUtf8(b64Chunk);
            term.write(text);
        });

        // User input -> Target device stdin
        term.onData((data) => {
            session.write(dioxamine.utf8ToBase64(data));
        });

        session.onClose((err) => {
            term.writeln("\r\n[Stream Closed: " + (err || "OK") + "]");
        });
    } catch (err) {
        term.writeln("\r\nFailed to start shell: " + err.message);
    }
}

connectTerminal();
```

---

## 3. Live Logcat Streamer

A full-screen streaming log viewer featuring tag filtering, log level color coding, and throttled batch rendering.

### `plugin.json`
```json
{
  "schemaVersion": 1,
  "id": "com.example.logcat",
  "name": "Live Logcat",
  "description": "Real-time streaming Logcat viewer with color coding and search",
  "version": "1.0.0",
  "versionCode": 1,
  "author": "Dioxamine Community",
  "entry": "index.html",
  "icon": "icon.png",
  "minAppVersionCode": 1,
  "permissions": [
    "shell"
  ],
  "fullscreen": true
}
```

### `app.js`
```javascript
let session = null;
let logBuffer = '';

async function startLogcat() {
    session = await dioxamine.openInteractiveShell();

    session.onData((b64Chunk) => {
        const text = dioxamine.base64ToUtf8(b64Chunk);
        logBuffer += text;

        const lines = logBuffer.split('\n');
        logBuffer = lines.pop(); // Retain partial trailing line

        for (let i = 0; i < lines.length; i++) {
            const line = lines[i].trimEnd();
            if (line) appendLogLine(line);
        }
    });

    // Continuously follow the last 25 lines (-T 25)
    const cmd = "logcat -v time -T 25\n";
    await session.write(dioxamine.utf8ToBase64(cmd));
}

function appendLogLine(line) {
    const container = document.getElementById('log-container');
    const div = document.createElement('div');
    div.className = 'log-line';
    div.textContent = line;
    container.appendChild(div);
    container.scrollTop = container.scrollHeight;
}
```
