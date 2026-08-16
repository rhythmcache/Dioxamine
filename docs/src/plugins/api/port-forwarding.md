# Port Forwarding and Reverse API

The Port Forwarding and Reverse API allows plugins to route TCP network traffic between the host Android device and the target ADB device.

## Port Forwarding (`adb forward`)

Forwarding redirects connections made to a socket on the host to a socket on the connected device.

**Required Permission**: `"forward"`

### `dioxamine.forwardPort()`
Binds a local host socket and routes incoming connections to a remote target socket.

```javascript
dioxamine.forwardPort(local: string, remote: string): Promise<void>
```

**Parameters:**
- `local` (`string`): Local host specification (for example, `"tcp:8080"`).
- `remote` (`string`): Remote device specification (for example, `"tcp:8080"` or `"localabstract:scrcpy"`).

### `dioxamine.forwardList()`
Lists all active port forward rules created by the session.

```javascript
dioxamine.forwardList(): Promise<Array<{ local: string, remote: string }>>
```

### `dioxamine.forwardRemove()`
Removes an active port forward binding.

```javascript
dioxamine.forwardRemove(local: string): Promise<void>
```

---

## Port Reverse (`adb reverse`)

Reversing redirects connections made to a socket on the connected device back to a socket on the host device.

**Required Permission**: `"reverse"`

### `dioxamine.reversePort()`
Binds a remote device socket and routes connections back to a local host socket.

```javascript
dioxamine.reversePort(remote: string, local: string): Promise<void>
```

**Parameters:**
- `remote` (`string`): Remote device specification (for example, `"tcp:3000"`).
- `local` (`string`): Local host specification (for example, `"tcp:3000"`).

### `dioxamine.reverseList()`
Lists all active reverse rules.

```javascript
dioxamine.reverseList(): Promise<Array<{ remote: string, local: string }>>
```

### `dioxamine.reverseRemove()`
Removes an active reverse socket binding.

```javascript
dioxamine.reverseRemove(remote: string): Promise<void>
```

---

## Automatic Session Cleanup

All port forward and reverse mappings created during a plugin session are automatically closed and cleaned up by the native runtime when the plugin is closed or disposed.
