# Bootloader Lock and Unlock State

The **Lock State** tool in Fastboot allows you to unlock or lock your device bootloader, modify critical partition access, and check unlock abilities.

## Accessing Lock State Options

1. Connect the target device in Fastboot mode via USB OTG.
2. In Dioxamine, open **Fastboot > Actions > Lock State**.

---

## Available Lock Actions

| Action | Command Executed | Description |
|---|---|---|
| **Unlock Bootloader** | `fastboot flashing unlock` | Requests unlocking the device bootloader to allow flashing custom firmware. |
| **Lock Bootloader** | `fastboot flashing lock` | Re-locks the bootloader to restore verified boot and factory security state. |
| **Unlock Critical Partitions** | `fastboot flashing unlock_critical` | Unlocks critical low-level partitions (bootloader, radio, abl, xbl) on supported devices. |
| **Lock Critical Partitions** | `fastboot flashing lock_critical` | Re-locks critical low-level partitions. |
| **Check Unlock Ability** | `fastboot flashing get_unlock_ability` | Queries whether OEM Unlocking is enabled in Developer Options (`1` = allowed, `0` = disabled). |

---

## Important Warnings

> [!WARNING]
> Unlocking or re-locking the bootloader will trigger a factory reset and erase all user data on the target device due to Android security requirements. Always back up your data before proceeding.

### Confirming on Target Device:
When you trigger **Unlock Bootloader** or **Lock Bootloader**, the target phone will display an on-screen confirmation prompt (e.g. *"Do not unlock bootloader / Unlock the bootloader"*). You must use the physical Volume buttons and Power button on the target phone to highlight and confirm the selection.
