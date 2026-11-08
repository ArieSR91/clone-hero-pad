# clone-hero-pad
Unofficial Clone Hero Overlay control for Android.

## Features

- **Keyboard keys**: A S J K L H, arrows, SPACE, ENTER, TAB, ESC, and more via *Add key*.
- **Virtual Xbox 360 gamepad** (UHID): A/B/X/Y, LB/LT/RB/RT, D-pad, SELECT/START/GUIDE, L3/R3, and two analog sticks with tuned sensitivity.
- **Slide ASJKL**: slide a finger between A/S/J/K/L; only the key under the finger is pressed.
- **Per-key size and opacity**: select a key to get a box with *Size* and *Opacity* sliders. Global *Opacity* and *Opacity ASJKL* sliders apply to keys without their own value.
- **Profiles**: multiple control layouts, a built-in *Gamepad* profile, copy / empty / rename / delete.
- **Text input**: send a string to the game (uppercase supported, optional Enter at the end).
- **Gamepad mapping**: map a real connected gamepad to overlay controls, or map controls to keyboard keys.
- **Hide/show controls**: long-press the gear icon.
- **Clone Hero colours** for ASJKL (toggle).
- **Wireless-debugging pairing**: start the input server without a PC or root.

## How it works

Android does not let normal apps inject input into other apps. Clone Hero Pad starts a small **Server** process (`app_process`) with shell (adb) or root privileges. The overlay app talks to it over a local socket, and the server injects events through `IInputManager.injectInputEvent` (keyboard) or a UHID virtual gamepad (`/dev/uhid`, Xbox 360 vid `045e` pid `028e`).

```
Overlay (app)  ──socket──▶  Server (shell/root)  ──▶  InputManager / UHID  ──▶  game
```

Transport: abstract socket `chpad2` (root), with a TCP `127.0.0.1:47831` + token fallback for the shell domain (SELinux blocks apps from shell-domain abstract sockets).

## Requirements

- Android 9+ (minSdk 28)
- Permission to draw over other apps
- One of: root, a PC with adb, or Wireless debugging (Android 11+) for on-device pairing

## Build

Requires JDK 17 and the Android SDK (compileSdk/targetSdk 34).

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

The project uses AGP 8.5.2 and Kotlin 1.9.24. Pairing depends on `libadb-android-bc` from JitPack and BouncyCastle (already configured in `settings.gradle.kts` / `app/build.gradle.kts`).

## Usage

1. Install the APK and open **Clone Hero Pad**.
2. **1.** Allow display over other apps.
3. Start the server, choose one:
   - **2a.** Rooted phone: tap *Enable Injector*.
   - **2b.** adb: copy the shown command and run it in `adb shell` from a PC.
   - **2c.** Pairing: enable *Developer options → Wireless debugging*, choose *Pair device with pairing code*, then enter the 6-digit code in the notification that appears.
4. Check that the status reads **Injector: ACTIVE ✓**.
5. Tap **Start overlay**, then switch to your game.

Tap the gear for settings. *Stop server* shuts down the injector process.

### Gamepad mapping

*Gamepad mapping* lets each overlay control be set to **Default**, **Input Gamepad** (press a button or move a stick on a real pad to learn it; needs evdev access), or a keyboard key.

## Project layout

```
app/src/main/java/com/clonehero/pad/
  MainActivity.kt     launcher screen, status, gamepad mapping, test area
  OverlayService.kt   overlay windows, settings panel, profiles, size/opacity bar
  Views.kt            KeyButton drawing (rect, circle, D-pad, stick)
  Keys.kt             key catalog and Prefs (per-profile SharedPreferences)
  Injector.kt         app-side client for the server
  Server.kt           shell/root process: injection, UHID gamepad, evdev remap
  AdbPair.kt          wireless-debugging pairing and server start
  PairingService.kt   foreground service + notification code entry
  ClonePadApp.kt      crash log capture
```

## Troubleshooting

- **Injector INACTIVE**: restart the server (2a/2b/2c). *Stop server* then start again if an old version is still running.
- **Overlay closes by itself**: the server died; the overlay stops with it.
- **Pairing fails**: the code expires quickly; request a new code and retry.
- **App crashed earlier**: the crash trace is shown on the main screen; use *Copy* to share it.

## Disclaimer

This tool injects synthetic input for personal use. Use it only in games and apps where this is allowed; some online games prohibit input automation or virtual controllers.

## License

[LICENSE](LICENSE)
