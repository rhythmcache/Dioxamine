# ADB Terminal Shell

Dioxamine includes a full interactive ADB terminal shell that allows you to run Linux shell commands, inspect system logs, and control the target system through standard command-line tools.

## Opening the Terminal Shell

1. Open the **ADB** tab.
2. Select the **ADB Shell** sub-tab at the top.
3. If an active device is connected, Dioxamine will automatically launch a live shell session and display the command prompt.

---

## Terminal Interface Features

### 1. Terminal Output View
- Supports full ANSI color escape codes (color-coded text, syntax highlighting, warnings, and errors).
- Automatically scrolls with new output while allowing smooth upward touch scrolling to review previous command output.

### 2. Quick Toolbar Controls
Above the input bar is a utility toolbar:
- **Ctrl Button**: Tap to latch the `Ctrl` key active for your next keystroke. For example, latching `Ctrl` and sending `c` will send `SIGINT` (Ctrl+C) to terminate running processes (like `top` or `logcat`).
- **Tab Button**: Sends a tab character for shell path and command autocompletion.
- **Clear Button**: Clears the current terminal scroll buffer.
- **Restart Button**: Closes and restarts the active shell session.

### 3. Command Input Bar and History
- **Input Field**: Type any ADB shell command (e.g. `pm list packages`, `logcat -d`, `df -h`, `top`).
- **History Navigation (Up / Down Arrows)**: Tap the up or down arrow icons next to the input field to cycle through your previously executed command history.
- **Send (Enter)**: Submits the command to the target shell for execution.
