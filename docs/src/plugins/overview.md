# Plugin Architecture and Overview

Dioxamine provides a modern, sandboxed Web Plugin System. Developers can create rich tools, dashboards, terminals, and utilities using standard web technologies (HTML, CSS, JavaScript, WebAssembly) that interact directly with connected ADB devices through a secure native bridge.

## Core Concepts

A Dioxamine plugin is a directory or ZIP package containing:
- `plugin.json`: The manifest file describing identity, version, entrypoint, required permissions, and display settings.
- `index.html` (or custom entry): The main web page for the plugin interface.
- Web assets: CSS styles, JavaScript logic, icons, fonts, WebAssembly binaries, or static assets.

```
my-plugin/
├── plugin.json
├── index.html
├── styles.css
├── app.js
└── icon.png
```

## Security and Sandboxing

Plugins operate under strict security boundaries:

1. **Origin Isolation via WebViewAssetLoader**:
   Plugins are loaded over a virtual secure origin (`https://appassets.androidplatform.net/plugin/`). Direct `file://` access is disabled in the Android WebView, preventing cross-origin leaks and unauthorized local filesystem access.
2. **Permission Gating**:
   Dangerous ADB capabilities (such as shell execution, file push/pull, package installation, and port forwarding) are restricted behind explicit permissions. When a plugin attempts an action, Dioxamine prompts the user for consent and enforces policy rules.
3. **No External Network Leaks**:
   Requests to external domains are blocked by the native `WebViewClient` unless explicitly configured.
4. **Path Traversal Protection**:
   The native asset loader enforces canonical path checking, preventing plugins from escaping their allocated storage directory.

## Communication Bridge

When Dioxamine loads a plugin, it automatically injects a JavaScript bridge script before any page scripts execute:

```html
<script src="https://appassets.androidplatform.net/assets/plugin_runtime/dioxamine-bridge.js"></script>
```

This exposes the global object `window.dioxamine` (and `window.Dioxamine`), which provides asynchronous Promise-based APIs for device interactions, interactive shell streams, storage access, native dialogs, and dynamic theming.
