# DeskPad

Turn your Android phone into a low-latency wireless **trackpad and keyboard** for your Windows PC.

## Features

- **Precision Multi-Touch Trackpad**: 1-finger pointer acceleration with sub-pixel remainder tracking, tap-to-click, double-tap-and-drag, 2-finger tap (right click), 2-finger vertical/horizontal scroll (with Natural Scrolling toggle), pinch-to-zoom, 3-finger middle click, and press-and-hold mouse buttons.
- **Dual-Mode Keyboard**: Built-in tactical PC QWERTY / Fn / Arrow deck (with sticky `Ctrl`, `Alt`, `Win`, `Shift`) plus **Phone IME** bridge for native swipe & voice typing.
- **Themes**: Switch live between **Tactile Mono** (default) and **Cyber Deck**.
- **Foldable Ready**: Preserves active connections across fold/unfold transitions and switches to a side-by-side trackpad + keyboard split workspace when unfolded (`>= 600dp`).
- **Dual Transport**:
  - **Wi-Fi LAN**: Auto-discovery (`UDP 48910`), low-latency pointer stream (`UDP 48911`), and reliable command/ping channel (`TCP 48912`).
  - **Bluetooth HID**: Direct composite Mouse + Keyboard + Consumer Media HID profile requiring no PC software.

## Repository Layout

- `app/` — Kotlin + Jetpack Compose Android application (`com.deskpad.app`).
- `pc-companion/DeskPadPC.cs` — Native Windows receiver (`SendInput`) with system tray and Tactile Mono / Cyber Deck UI.
- `pc-companion/DeskPadSetup.cs` — Windows per-user installer (`DeskPadPCSetup.exe`) with shortcuts, startup toggle, and Add/Remove Programs uninstaller.
- `pc-companion/deskpad_server.py` — Zero-dependency Python receiver (Windows `ctypes` `SendInput` / Linux `xdotool` fallback).

## Quick Start

1. Download **`DeskPad.apk`** and **`DeskPadPCSetup.exe`** (or portable **`DeskPadPC.exe`**) from the [Releases](../../releases) page.
2. Run `DeskPadPCSetup.exe` on Windows (click **Allow Firewall** in DeskPad PC if prompted for ports `48910–48912`).
3. Open **DeskPad** on Android, tap **Link**, and select your PC.
