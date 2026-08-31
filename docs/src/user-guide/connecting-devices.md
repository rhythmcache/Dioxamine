# Connecting Devices

Dioxamine provides multiple connection methods to interface with secondary Android devices over physical cables or local Wi-Fi networks.

## Connection Methods Overview

| Method | Best Used For | Requirements |
|---|---|---|
| **USB OTG Cable** | Maximum reliability, initial wireless setup, Fastboot flashing | USB OTG adapter/cable, USB Debugging enabled |
| **Wireless Auto-Discovery** | Quick Wi-Fi connection without typing IP addresses | Both devices on the same Wi-Fi network, mDNS enabled |
| **QR Code Pairing** | Fastest wireless setup on Android 11+ | Android 11+ target, camera permission on host |
| **Pairing Code / PIN** | Android 11+ pairing when QR scanning is not available | Android 11+ target, 6-digit PIN and pairing port |
| **Direct TCP/IP** | Legacy wireless connection or static IP environments | Port 5555 enabled on target, shared network |

---

## 1. USB OTG Cable Connection

Connecting over USB OTG provides the fastest and most stable link between devices.

### Step-by-Step Instructions:

1. Plug a USB OTG adapter into your host phone (the phone running Dioxamine).
2. Connect a USB cable from the adapter to the target device.
3. On your host phone, Android will display a popup: *"Allow Dioxamine to access this USB device?"*. Tap **OK**.
4. On the target phone screen, look for the prompt: *"Allow USB debugging?"*.
5. Check the box **"Always allow from this computer"** and tap **Allow**.
6. Dioxamine will automatically detect the device and display its model name in the top device chip bar.

> [!NOTE]
> If the target device does not show the debugging prompt, check whether the USB cable supports data transfer (some cables are charge-only). Also verify that USB Debugging is toggled on in Developer Options.

---

## 2. Wireless ADB Auto-Discovery

If both devices are connected to the same Wi-Fi network or if one device is connected to the other's portable hotspot, Dioxamine can automatically discover the target device using mDNS.

### Step-by-Step Instructions:

1. On the ADB tab in Dioxamine, tap the **Add Device (+)** button in the top bar.
2. The **Discovered Devices** dialog will appear and begin scanning your local network.
3. Look for your target device in the list:
   - Devices marked with **TLS** are already paired and ready to connect. Tap the device to connect immediately.
   - Devices marked with **Pairing** require pairing first. Tap the device to open the pairing code input screen.
   - Devices marked with **TCP** are listening on standard ADB ports. Tap to connect directly.

---

## 3. Wireless ADB QR Code Pairing (Android 11+)

Android 11 and newer support native Wireless Debugging with QR code pairing.

### Step-by-Step Instructions:

1. Connect both devices to the same Wi-Fi network or mobile hotspot.
2. In Dioxamine on your **host phone**, tap the **QR Pairing** icon in the top connector bar. Dioxamine starts a local pairing server and presents a styled pairing QR code on screen.
3. On the **target device**, navigate to **Settings > Developer Options > Wireless Debugging**.
4. Tap **Pair device with QR code** to open the target device's built-in QR scanner.
5. Point the target device's camera at the QR code displayed on your host phone screen.
6. Once scanned, the target authenticates over TLS, and Dioxamine automatically connects to the discovered wireless debugging port.

---

## 4. Manual Pairing Code (Android 11+)

If camera scanning is not possible, you can pair manually using the 6-digit Wi-Fi pairing code.

### Step-by-Step Instructions:

1. On the **target device**, go to **Settings > Developer Options > Wireless Debugging**.
2. Tap **Pair device with pairing code**.
3. The target screen will show:
   - **Wi-Fi pairing code** (a 6-digit number, for example: `123456`).
   - **IP address & Port** (for example: `192.168.1.50:37123`).
4. In Dioxamine, tap **Add Device (+)** > **Manual Entry** > **Wireless Debugging (TLS)** > **Pair with Code**.
5. Enter the IP address, the **Pairing Port** shown in the popup (e.g., `37123`), and the 6-digit pairing code.
6. Tap **Pair**.
7. Once paired successfully, Dioxamine will transition to the Connect screen. Enter the main connection port shown on the target's main Wireless Debugging page and tap **Connect**.

---

## 5. Direct TCP/IP Connection (Port 5555)

Used for devices running Android 10 or older, Android TV boxes with fixed IP addresses, or devices configured with ADB over TCP.

### Connecting Directly:
1. Tap **Add Device (+)** > **Manual Entry** > **Plain TCP (IP:Port)**.
2. Enter the target IP address and port (default is `5555`).
3. Tap **Connect**.

### Switching a USB Device to TCP/IP:
If you currently have a device plugged in via USB OTG and want to disconnect the cable and continue wirelessly:
1. Ensure both devices are connected to the same Wi-Fi.
2. In the top device connector card, tap the three-dots menu next to the USB device.
3. Tap **Switch to TCP/IP**.
4. Set the port (default `5555`) and confirm.
5. You can now unplug the USB cable and connect to the target's IP address over Wi-Fi.
