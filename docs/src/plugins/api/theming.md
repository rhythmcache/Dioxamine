# Dynamic Theming and Material 3

Dioxamine automatically extracts the user's dynamic Material You / Material 3 color scheme and injects CSS custom properties (variables) into every loaded plugin.

---

## Injected CSS Custom Properties

The following CSS variables are available in the `:root` pseudo-class:

| Variable | Description |
| :--- | :--- |
| `--dioxamine-bg` | Main background color (maps to `--dioxamine-background`). |
| `--dioxamine-fg` | Main text color (maps to `--dioxamine-on-background`). |
| `--dioxamine-card-bg` | Card and surface container background. |
| `--dioxamine-card-fg` | Text color on surface cards. |
| `--dioxamine-accent` | Primary accent color (maps to `--dioxamine-primary`). |
| `--dioxamine-danger` | Error and destructive action color. |
| `--dioxamine-outline` | Border outline color. |
| `--dioxamine-outline-variant` | Subtle divider and secondary border color. |
| `--dioxamine-surface-variant` | Secondary surface container color. |
| `--dioxamine-on-surface-variant` | Secondary muted text color. |

---

## Using Variables in CSS

```css
body {
    background-color: var(--dioxamine-bg, #121212);
    color: var(--dioxamine-fg, #ffffff);
}

.card {
    background-color: var(--dioxamine-card-bg, #1e1e1e);
    color: var(--dioxamine-card-fg, #ffffff);
    border: 1px solid var(--dioxamine-outline-variant, #333333);
    border-radius: 12px;
}

button.primary {
    background-color: var(--dioxamine-accent, #2196f3);
    color: var(--dioxamine-on-primary, #ffffff);
}

button.danger {
    background-color: var(--dioxamine-danger, #f44336);
    color: #ffffff;
}
```

---

## Dark Mode Attribute

Dioxamine sets the `data-dioxamine-theme` attribute on the root `<html>` element:

```html
<html data-dioxamine-theme="dark">
```

You can target specific themes in CSS:

```css
[data-dioxamine-theme="dark"] .custom-shadow {
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.5);
}

[data-dioxamine-theme="light"] .custom-shadow {
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
}
```

---

## JavaScript Theme APIs

### `dioxamine.getTheme()`

Queries the current theme state synchronously.

```javascript
const theme = dioxamine.getTheme();
console.log("Is dark theme active?", theme.isDark);
```

### `dioxamine.onThemeChange()`

Registers a listener called when the user changes the system or app theme.

```javascript
dioxamine.onThemeChange(() => {
    const isDark = dioxamine.getTheme().isDark;
    console.log("Theme switched to:", isDark ? "Dark" : "Light");
    // Update charts, canvas, or WebGL shaders
});
```
