# Single Command Execution (shellExec)

The `shellExec` API runs a non-interactive shell command on the connected ADB device, captures the full output stream, and returns the exit code, standard output, and standard error.

**Required Permission**: `"shell"`

## `dioxamine.shellExec()`

Executes a command string synchronously on the target device shell.

### Signature
```javascript
dioxamine.shellExec(command: string): Promise<ShellExecResult>
```

### Parameters
- `command` (`string`): The shell command line to execute (for example, `"getprop ro.build.version.release"` or `"pm list packages -3"`).

### Returns
A `Promise` resolving to a `ShellExecResult` object:

```typescript
interface ShellExecResult {
    stdout: string;      // Standard output content
    stderr: string;      // Standard error content
    exitCode: number;    // Process exit code (0 indicates success)
}
```

### Errors
- Rejects if the `"shell"` permission was not declared in `plugin.json`.
- Rejects if the user denies the permission prompt.
- Rejects if no device is connected.

### Example

```javascript
async function getBatteryLevel() {
    try {
        const result = await dioxamine.shellExec("dumpsys battery | grep level");
        if (result.exitCode === 0) {
            const match = result.stdout.match(/level:\s*(\d+)/);
            if (match) {
                console.log("Battery level: " + match[1] + "%");
            }
        } else {
            console.error("Command failed with stderr:", result.stderr);
        }
    } catch (err) {
        console.error("Shell error:", err.message);
    }
}
```
