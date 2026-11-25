# Advance Sleep

Advance Sleep is a root-based utility for people who want a truly quiet phone during sleep hours — at night or during daytime naps (e.g., shift work).

**Scheduled & Configurable Sleep mode**
  - Automatically enters Sleep mode at your configured start time and exits at your configured end time.
  - Runs on selected weekdays.
  - Lets you independently toggle each sleep action: Airplane mode, Wi-Fi, Bluetooth, Force Doze, and Device Sensors (Android 10+).
  - Restores your previous settings on exit.
  
> **Root access is required**. The app uses `su` commands to toggle radios, enforce Doze, and manage sensor privacy.

## How it works

It can run on a schedule and also provides a **Quick Settings tile** for an instant, manual override (tap ON/OFF).

When Sleep mode turns **ON** (by schedule or tile), Advance Sleep:

1. Takes a **snapshot** of current states for enabled actions (Airplane / Wi‑Fi / Bluetooth).
2. Applies only the enabled Sleep mode actions (root commands):
   - Airplane Mode **ON** (`enable_airplane_mode`)
   - Wi‑Fi **OFF** (`disable_wifi`)
   - Bluetooth **OFF** (`disable_bluetooth`)
   - Disable Device Sensors via `sensor_privacy` on Android 10+ (`disable_sensors`)
   - Force **Doze** idle (`force_doze`)
3. Shows a persistent notification (optional in Settings).

When Sleep mode turns **OFF** (scheduled end, tile tap, or “Stop now”), Advance Sleep:

1. Unforces **Doze** (if enabled).
2. Re-enables Device Sensors (if enabled).
3. Restores the pre-sleep **snapshot** for enabled actions (Airplane/Wi‑Fi/Bluetooth states).
4. Removes the persistent notification.
5. Keeps the schedule enabled (so it can re-enter Sleep mode next time).

> About Doze: Android defers network/background work during Doze and periodically allows brief maintenance windows.  
> https://developer.android.com/training/monitoring-device-state/doze-standby

## Screenshots

<div align="center">
  <img src="https://github.com/aydinAGF/Advance-Sleep/blob/master/metadata/en-US/images/phoneScreenshots/01.png?raw=true" alt="App UI" width="405" />
  <img src="https://github.com/aydinAGF/Advance-Sleep/blob/master/metadata/en-US/images/phoneScreenshots/02.png?raw=true" alt="App Settings" width="405" />
</div>

## Permissions & Privacy

Advance Sleep is designed to do one job (enter/exit Sleep mode) and not collect any personal data.

### Why permissions are used

- **Root access**  
  Required to execute system-level commands like toggling radios, forcing Doze, and disabling sensors.  
- **Exact alarm scheduling**  
  Used to trigger Sleep mode start/end at precise times (schedule reliability under idle modes).
- **Run at startup**  
  Ensures your schedule can be restored after reboot.
- **Notifications**  
  Used for the persistent “Sleep mode active” notification and the “Stop now” action.
- **Ignore Battery Optimizations**  
  On first launch, the app asks to set it to "Unrestricted" so the background service keeps running while the device is idle.

### Privacy

- **No Internet permission**: the app cannot send data to servers.
- **No personal data access**: it does not read contacts, location, messages, or files.
- **Open source**: you can review exactly what it does.

## Installation & License

<a href="https://github.com/aydinAGF/Advance-Sleep/releases"><img src="images/GitHub.png" alt="Get it on GitHub" height="60"></a>

---

This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.

---
