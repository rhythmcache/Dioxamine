# ADB Key Management

ADB requires an RSA cryptographic keypair to authenticate connections with target Android devices. Dioxamine generates and manages its own internal ADB keypair securely within the app.

## Accessing ADB Key Settings

1. Tap the **Settings** tab in the bottom navigation bar.
2. Tap on the **ADB Key** expandable card.

---

## Key Management Options

### 1. Viewing Key Fingerprint
- Dioxamine displays the SHA-256 fingerprint of your active public RSA key (e.g. `SHA256:abcd1234...`).
- When connecting to a target device for the first time, this fingerprint matches the prompt shown on the target phone screen.

### 2. Regenerate Key Pair
- Tap **Regenerate Key** to delete the current keypair and create a fresh 2048-bit RSA key.
- **When to use**: If an unauthorized device has whitelisted your key, or if you wish to reset trust on all previously paired devices.
- After regenerating, all target devices will prompt to re-authorize the connection when plugged in.

### 3. Load Custom Key (Import)
- Tap **Load Custom Key** to import an existing `adbkey` private key file from your phone storage (for example, copied from your PC's `~/.android/adbkey`).
- **Why use this**: Allows Dioxamine to connect to devices that already trust your PC without showing the authorization prompt again.

### 4. Export Key (Backup)
- Tap **Export Key** to save your active private `adbkey` file to your phone's storage.
- Useful for creating backups or migrating your authorization key to another phone.
