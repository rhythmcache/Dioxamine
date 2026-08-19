# Fastboot Variables Inspector

The **Variables** tool queries and displays hardware variables and configuration parameters from the bootloader (equivalent to running `fastboot getvar all`).

## Viewing Variables

1. Connect the target device in Fastboot mode via USB OTG.
2. In Dioxamine, navigate to **Fastboot > Actions > Variables**.
3. Dioxamine will send the `getvar all` command and present a list of all exposed variables.

---

## Common Fastboot Variables

| Variable | Description |
|---|---|
| `product` | Hardware codename of the target device (e.g. `husky`, `taro`, `marlin`). |
| `current-slot` | Active A/B boot partition slot (`a` or `b`). |
| `slot-count` | Number of partition slots supported by the device (`2` for A/B devices, `1` for legacy A-only). |
| `unlocked` | Current bootloader state (`yes` = unlocked, `no` = locked). |
| `secure` | Indicates if Secure Boot is active. |
| `battery-voltage` | Current battery voltage in millivolts (useful if device battery is depleted). |
| `max-download-size` | Maximum packet size the bootloader accepts during image flashing in bytes. |
| `version-bootloader` | Internal bootloader firmware version string. |
| `version-baseband` | Cellular modem / baseband firmware version string. |

---

## UI Controls

- **Refresh (Top Right)**: Tap the refresh button to re-query the variables from the target device.
- **Copy**: Long press on any variable to copy its key and value to your clipboard.
