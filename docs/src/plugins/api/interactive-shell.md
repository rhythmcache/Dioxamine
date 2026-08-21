# Interactive Shell Sessions (openInteractiveShell)

The Interactive Shell API opens a persistent bi-directional ADB streaming session. This is designed for terminal emulators (such as `xterm.js`), interactive command prompts (`sh`), and continuous background log streamers (`logcat -v time -T 25`).

**Required Permission**: `"shell"`

## `dioxamine.openInteractiveShell()`

Opens a live interactive PTY shell stream to the target device.

### Signature
```javascript
dioxamine.openInteractiveShell(): Promise<InteractiveShellSession>
```

### Parameters
None.

### Returns
A `Promise` resolving to an `InteractiveShellSession` controller object.

---

## `InteractiveShellSession` Interface

```typescript
interface InteractiveShellSession {
    sessionId: string;
    
    // Register incoming binary data callback (data is base64 encoded)
    onData(callback: (base64Chunk: string) => void): void;
    
    // Register stream termination callback
    onClose(callback: (error?: string) => void): void;
    
    // Send binary data into the shell stream (data must be base64 encoded)
    write(base64Data: string): Promise<void>;
    
    // Dynamically resize target device PTY dimensions (triggers SIGWINCH on device)
    resize(cols: number, rows: number): Promise<void>;
    
    // Terminate the shell session and close native socket
    close(): Promise<void>;
}
```

---

## Dynamic Window Sizing (`session.resize`)

Interactive full-screen CLI apps (like `nano`, `htop`, `vim`, and `top`) rely on the Linux kernel PTY dimensions (`struct winsize`) to layout their interface correctly.

When running inside a responsive WebView, whenever the terminal size changes (such as on screen rotation, split-screen, or when the virtual keyboard appears), call `session.resize(cols, rows)`.

- **`cols`**: Number of columns (positive integer, 1..65535).
- **`rows`**: Number of rows (positive integer, 1..65535).

This sends an official ADB Shell v2 `WINDOW_SIZE_CHANGE` packet to the Android daemon, which executes `ioctl(pty_fd, TIOCSWINSZ)` and broadcasts `SIGWINCH` to the running command.

---

## Encoding and Decoding Data

Data transmitted over `onData` and `write` is encoded in Base64 to support arbitrary binary streams, ANSI escape codes, and UTF-8 multibyte characters safely.

You can use the built-in helper functions `dioxamine.utf8ToBase64()` and `dioxamine.base64ToUtf8()` or standard Web APIs (`TextEncoder`/`TextDecoder`):

```javascript
// Converting String -> Base64 for session.write()
function stringToBase64(str) {
    const bytes = new TextEncoder().encode(str);
    let bin = '';
    for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
    return btoa(bin);
}

// Converting Base64 -> String from session.onData()
function base64ToString(b64) {
    const bin = atob(b64);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    return new TextDecoder('utf-8', { fatal: false }).decode(bytes);
}
```

---

## Example 1: Integrating with xterm.js & FitAddon

```javascript
const term = new Terminal({
    cursorBlink: true,
    fontFamily: 'monospace',
    theme: { background: '#121212', foreground: '#ffffff' }
});

const fitAddon = new FitAddon.FitAddon();
term.loadAddon(fitAddon);

term.open(document.getElementById('terminal-container'));
fitAddon.fit();

async function startTerminal() {
    const session = await dioxamine.openInteractiveShell();

    // Send initial dimensions
    session.resize(term.cols, term.rows);

    // Device stdout -> xterm.js
    session.onData((b64) => {
        const text = dioxamine.base64ToUtf8(b64);
        term.write(text);
    });

    // Keyboard input -> Device stdin
    term.onData((data) => {
        session.write(dioxamine.utf8ToBase64(data));
    });

    // Dynamic resizing (device rotation / soft keyboard toggling)
    term.onResize(({ cols, rows }) => {
        session.resize(cols, rows);
    });

    window.addEventListener('resize', () => {
        fitAddon.fit();
    });

    session.onClose((err) => {
        term.writeln("\r\n[Session Terminated: " + (err || "OK") + "]");
    });
}
```

---

## Example 2: Continuous Logcat Stream

```javascript
async function startLiveLogcat(onLineReceived) {
    const session = await dioxamine.openInteractiveShell();
    let buffer = '';

    session.onData((b64) => {
        buffer += dioxamine.base64ToUtf8(b64);
        const lines = buffer.split('\n');
        buffer = lines.pop(); // keep trailing partial line

        for (let i = 0; i < lines.length; i++) {
            const line = lines[i].trimEnd();
            if (line) onLineReceived(line);
        }
    });

    // Send command to follow the last 25 lines continuously (-T)
    const cmd = "logcat -v time -T 25\n";
    await session.write(dioxamine.utf8ToBase64(cmd));
    return session;
}
```
