# Manifest Specification (plugin.json)

Every Dioxamine plugin must include a valid `plugin.json` file at the root of its archive. The manifest declares metadata, required permissions, versioning, and display preferences.

## Example Manifest

```json
{
  "schemaVersion": 1,
  "id": "com.rhythmcache.dioxamine.terminal",
  "name": "Terminal",
  "description": "Interactive PTY terminal using xterm.js",
  "version": "1.0.1",
  "versionCode": 2,
  "author": "RhythmCache",
  "entry": "index.html",
  "icon": "icon.png",
  "minAppVersionCode": 1,
  "permissions": [
    "shell"
  ],
  "fullscreen": true,
  "homepage": "https://github.com/rhythmcache/dioxamine"
}
```

## Field Reference

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `schemaVersion` | `Integer` | **Yes** | Manifest format version. Must currently be `1`. |
| `id` | `String` | **Yes** | Unique reverse-DNS identifier (lowercase letters, numbers, dots, and underscores). Must contain at least one dot (for example, `com.example.myplugin`). |
| `name` | `String` | **Yes** | Human-readable title displayed in the plugin directory and header. Maximum 50 characters. |
| `description` | `String` | No | Short summary of plugin functionality. Maximum 200 characters. Default: `""`. |
| `version` | `String` | **Yes** | Human-readable version string (for example, `"1.0.0"` or `"2.1.0-beta"`). |
| `versionCode` | `Integer` | **Yes** | Incremental integer version used for upgrade detection. Must be greater than or equal to `1`. |
| `author` | `String` | No | Author or organization name. |
| `entry` | `String` | **Yes** | Relative path to the HTML entrypoint file (for example, `"index.html"` or `"ui/main.html"`). Path traversal (`..`) is forbidden. |
| `icon` | `String` | No | Relative path to the plugin icon image (PNG, WebP, JPG, or SVG). Default: `null`. |
| `minAppVersionCode` | `Integer` | No | Minimum Dioxamine app `versionCode` required to execute this plugin. Default: `1`. |
| `permissions` | `Array<String>` | No | List of required permission identifiers. Only valid permission names are permitted. Default: `[]`. |
| `fullscreen` | `Boolean` | No | If `true`, hides the Dioxamine top bar on launch to provide an edge-to-edge full-screen display. Default: `false`. |
| `homepage` | `String` | No | Web URL pointing to the plugin repository, source code, or documentation. |

## Validation Rules

When installing or loading a plugin, Dioxamine strictly enforces the following validation checks:

1. **ID Format**:
   - Must match the regular expression `^[a-z0-9]+(\.[a-z0-9_]+)+$`.
   - Uppercase characters, spaces, and leading/trailing dots will cause installation to fail.
2. **Path Sanitization**:
   - `entry` and `icon` paths must point inside the plugin directory.
   - Any path containing `..` or leading slashes will be rejected.
3. **Permission Whitelist**:
   - Every entry in `permissions` must be one of the recognized permission strings: `shell`, `push`, `pull`, `install`, `forward`, `reverse`.
   - Unknown permissions will fail manifest validation with an explicit error.
4. **App Version Compatibility**:
   - If `minAppVersionCode` exceeds the running Dioxamine application version, installation will be blocked with a compatibility notice.
