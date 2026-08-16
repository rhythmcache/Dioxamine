# Layout Patterns and CSS Guide

Because Dioxamine renders plugins within an Android WebView inside Jetpack Compose, understanding how CSS containing blocks resolve height is essential for creating responsive interfaces.

## Pattern A: Full-Screen App and Terminal Layout

Use this pattern for interactive terminals, real-time log viewers, code editors, or canvas games where the page should occupy the entire screen, lock document scrolling, and manage an internal scrolling panel.

```css
/* Lock root document to exact physical viewport boundaries */
html, body {
    width: 100%;
    height: 100%;
    margin: 0;
    padding: 0;
    overflow: hidden;
    background-color: var(--dioxamine-bg, #121212);
    color: var(--dioxamine-fg, #ffffff);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
}

/* Root application flex container */
#app {
    display: flex;
    flex-direction: column;
    width: 100%;
    height: 100%;
    overflow: hidden;
}

/* Fixed toolbar or header */
header {
    flex-shrink: 0;
    padding: 10px 14px;
    background-color: var(--dioxamine-card-bg, #1e1e1e);
    border-bottom: 1px solid var(--dioxamine-outline-variant, #333333);
}

/* Main scrollable viewport (terminal / log container) */
.scroll-content {
    flex: 1 1 0;
    min-height: 0; /* CRITICAL: Allows flex child to shrink below intrinsic content size */
    overflow-y: auto;
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
    padding: 12px;
}
```

```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
    <link rel="stylesheet" href="styles.css">
</head>
<body>
    <div id="app">
        <header>
            <h1>Terminal Console</h1>
        </header>
        <div id="terminal-container" class="scroll-content"></div>
    </div>
</body>
</html>
```

## Pattern B: Standard Document and Page Layout

Use this pattern for information dashboards, settings pages, documentation readers, or card-based views that scroll naturally.

```css
body {
    margin: 0;
    padding: 16px;
    background-color: var(--dioxamine-bg, #121212);
    color: var(--dioxamine-fg, #ffffff);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    -webkit-text-size-adjust: 100%;
}

.container {
    max-width: 720px;
    margin: 0 auto;
}

.card {
    background-color: var(--dioxamine-card-bg, #1e1e1e);
    color: var(--dioxamine-card-fg, #ffffff);
    border: 1px solid var(--dioxamine-outline-variant, #333333);
    border-radius: 12px;
    padding: 16px;
    margin-bottom: 16px;
}
```

## High-Frequency DOM Updates Best Practice

When processing high-frequency streams (such as `logcat` or serial metrics emitting hundreds of lines per second), appending single DOM elements synchronously blocks the UI thread.

Always batch DOM insertions using `DocumentFragment` and a throttled render loop:

```javascript
let pendingBatch = [];
let renderTimer = null;
const MAX_LINES = 1000;

function queueLine(text) {
    pendingBatch.push(text);
    if (!renderTimer) {
        renderTimer = setTimeout(flushBatch, 40); // 25fps batch flush
    }
}

function flushBatch() {
    renderTimer = null;
    if (pendingBatch.length === 0) return;

    const container = document.getElementById('log-container');
    const fragment = document.createDocumentFragment();
    const batch = pendingBatch.splice(0, pendingBatch.length);

    for (let i = 0; i < batch.length; i++) {
        const line = document.createElement('div');
        line.className = 'log-line';
        line.textContent = batch[i];
        fragment.appendChild(line);
    }

    container.appendChild(fragment);

    // Prune excess lines to prevent memory bloat
    while (container.childNodes.length > MAX_LINES) {
        container.removeChild(container.firstChild);
    }

    // Smooth auto-scroll
    container.scrollTop = container.scrollHeight;
}
```
