# CLAUDE.md – Instrukce pro Claude Code

## O projektu

**PixelTouchpad** je Android aplikace, která promění displej telefonu ve virtuální touchpad pro ovládání kurzoru na externím monitoru v Android Desktop Mode. Primárně cílí na Pixel 8 Pro s Android 16 (SDK 36).

## Architektura

```
TouchpadView (UI, dotykové eventy)
    ↓ callbacky (onCursorMove, onClick, onRightClick, onScroll, onDragStart/End, onThreeFingerSwipe, onPinchZoom)
MainActivity (propojení, Shizuku setup, detekce displeje, settings)
    ↓ oneway AIDL IPC
InputService (Shizuku UserService, UID 2000 shell)
    ↓ write() / shell exec
/dev/uhid (kernel UHID → virtual HID mouse)  |  shell commands (keyevent, statusbar)
    ↓ kernel input subsystem
PointerController (systémový kurzor na displeji)
```

### 1. TouchpadView (`TouchpadView.kt`)
- Custom `View` zachytávající dotykové gesta
- Gesta (aktuální + plánovaná):
  - 1 prst tah = pohyb kurzoru
  - 1 prst tap = levý klik
  - 2 prsty tap = pravý klik
  - 2 prsty stejný směr = scroll
  - 2 prsty pinch = zoom
  - 1 prst drží + 2. jezdí = drag
  - 3 prsty L/R/U/D = zpět/recent/app drawer/notifikace
- Konfigurovatelné: `sensitivity` (default 1.5), `scrollSensitivity` (default 0.08)
- Sleduje absolutní pozici kurzoru (cursorX, cursorY) na externím displeji

### 2. InputService (`InputService.kt`)
- Shizuku UserService – běží v separátním procesu s shell oprávněními (UID 2000)
- AIDL metody (oneway): moveCursor, click, rightClick, scroll, startDrag, endDrag, sendKeyEvent, sendShellCommand, destroy
- AIDL metody (synchronní): diagnose
- Strategie: UHID > sendevent > shell input (fallback)
- UHID report: buttons(1) + X(1) + Y(1) + wheel(1) = 4 bytes

### 3. MainActivity (`MainActivity.kt`)
- Setup flow: Shizuku permission → bind UserService → detekce displeje → touchpad
- Fullscreen immersive mode po připojení
- Settings BottomSheet: citlivost kurzoru/scrollu, diagnostika, odpojení
- SharedPreferences pro persistenci nastavení

## Závislost na Shizuku

**Ano**, celé řešení závisí na Shizuku:
- `/dev/uhid` vyžaduje skupinu `uhid` — shell (UID 2000) to má
- `sendevent`, `input keyevent` vyžadují shell oprávnění
- `cmd statusbar` vyžaduje shell oprávnění
- Normální appka (UID 10xxx) nemá přístup k žádnému z těchto

## Mapa gest

| Gesto | Akce | AIDL metoda | Implementace |
|-------|------|-------------|-------------|
| 1 prst tah | pohyb kurzoru | moveCursor | UHID REL_X/Y |
| 1 prst tap | levý klik | click | UHID BTN_LEFT |
| 2 prsty tap | pravý klik | rightClick | UHID BTN_RIGHT (bit 1 = 0x02) |
| 2 prsty vertikálně | scroll | scroll | UHID REL_WHEEL |
| 2 prsty pinch | zoom | — | Ctrl+scroll (TBD) |
| 1 drží + 2. jezdí | drag | startDrag/endDrag | UHID button=1 hold |
| 3 prsty doleva | Zpět | sendKeyEvent(4) | `input keyevent 4` |
| 3 prsty doprava | Task manager | sendKeyEvent(187) | `input keyevent 187` |
| 3 prsty nahoru | App drawer | sendKeyEvent(284) | `input keyevent 284` |
| 3 prsty dolů | Notifikace | sendShellCommand | `cmd statusbar expand-notifications` |

## Build

- Gradle 8.10, AGP 8.5.2, Kotlin 2.0.0
- compileSdk 35, minSdk 28, targetSdk 35
- JDK 17
- Shizuku: `dev.rikka.shizuku:api:13.1.5` a `provider:13.1.5`

## Struktura souborů

```
├── CLAUDE.md                       # Tento soubor
├── TODO.md                         # Prioritizované úkoly a stav
├── APPROACHES.md                   # Log všech vyzkoušených přístupů
├── app/src/main/
│   ├── aidl/.../IInputService.aidl # AIDL rozhraní (oneway)
│   ├── java/.../InputService.kt    # Shizuku UserService (UHID + shell)
│   ├── java/.../MainActivity.kt    # UI + setup + settings
│   ├── java/.../TouchpadView.kt    # Dotykové gesta
│   ├── java/.../SettingsBottomSheet.kt  # Settings panel
│   └── res/
│       ├── layout/activity_main.xml          # Fullscreen layout
│       ├── layout/bottom_sheet_settings.xml  # Settings bottom sheet
│       └── values/themes.xml
```

## Uživatelský kontext
- Martin, český vývojář her (Godot, HTML hry pro děti)
- Zařízení: Pixel 8 Pro, Android 16
- Jazyk UI: čeština
- Cíl: ovládat desktop mode na externím monitoru bez fyzické myši
