# Touchpad and PC Keyboard

The **Touchpad & PC Keyboard** tool turns your host phone into a full-precision laptop trackpad and a complete PC hardware keyboard. It uses virtual HID device drivers over ADB to send native mouse movements, clicks, multi-finger scroll gestures, keyboard shortcuts, and real-time typing.

## Opening Touchpad and Keyboard

1. Open the **ADB** tab.
2. Under **Built-in Actions**, tap the **Touchpad** tile.
3. At the top of the screen, choose between two sub-tabs:
   - **TouchPad**: Full trackpad interface with discrete buttons and quick soft typing.
   - **PC Keyboard**: Complete desktop keyboard layout with modifier keys, function keys, navigation keys, and shortcuts.

---

## Tab 1: Touchpad Interface

### Trackpad Canvas Gestures:
- **Move Pointer**: Slide one finger across the trackpad area to move the mouse cursor on the target device.
- **Left Click**: Single tap with one finger.
- **Right Click**: Long-press on the trackpad, or tap with two fingers.
- **Scroll (Wheel)**: Drag up or down with two fingers simultaneously to scroll web pages, lists, and documents.
- **Drag and Drop**: Double tap, hold your finger down on the second tap, and slide to drag windows or select text.

### Discrete Physical Buttons:
Below the trackpad canvas are 3 physical buttons:
- **Left Button**: Tap to left click, or press and hold while moving your finger on the canvas to highlight text and drag items.
- **Middle Button**: Sends a middle mouse click (`KEYCODE_BUTTON_MIDDLE`).
- **Right Button**: Tap or hold for context menus.

### Sensitivity Tuning:
- Tap the **Tune (Sliders)** icon in the top app bar to open the sensitivity slider.
- Adjust cursor tracking speed from **0.5x** (fine precision) to **3.0x** (fast velocity).

### Quick Action Keys:
- Quick access buttons for **Esc**, **Tab**, **Enter**, and **Backspace (Del)**.
- **Type Button**: Toggles a real-time soft keyboard input bar right above the trackpad.

---

## Tab 2: PC Hardware Keyboard Layout

The PC Keyboard tab provides a full desktop keyboard experience organized into clean sections:

### 1. Real-Time Soft Keyboard Interceptor
At the top of the PC Keyboard tab is a live typing input box:
- Tap the input box to open your phone's on-screen keyboard.
- Every character you type, paste, or delete is streamed to the target device **in real time**.
- There is no need to press a "Send" button.

### 2. Modifier Keys (With Latch State)
Tap any modifier key once to latch it active for your next keystroke:
- **Ctrl** (Control)
- **Alt** (Alternate)
- **Shift**
- **Win** (Windows / Command / Meta key)
- **Esc** and **Tab**

### 3. Navigation and Editing Block
- **Del** (Forward Delete)
- **Ins** (Insert)
- **Home** and **End**
- **PgUp** (Page Up) and **PgDn** (Page Down)
- **Enter** and **Backspace**
- **Arrow Keys** (Left, Up, Down, Right)

### 4. Function Keys (F1 - F12)
Dedicated grid for function keys **F1 through F12**, useful for BIOS menus, terminal shortcuts, and PC desktop applications.

### 5. Common Desktop Shortcuts
One-tap chips for standard desktop keyboard shortcuts:
- **Ctrl+C** (Copy)
- **Ctrl+V** (Paste)
- **Ctrl+Z** (Undo)
- **Ctrl+A** (Select All)
- **Alt+Tab** (Switch Windows / Apps)
