# CLAUDE.md – Instrukce pro Claude Code

## O projektu

**PixelTouchpad** je Android aplikace, která promění displej telefonu ve virtuální touchpad pro ovládání kurzoru na externím monitoru v Android Desktop Mode. Primárně cílí na Pixel 8 Pro s Android 16 (SDK 36).

## Architektura

```
TouchpadView (UI, dotykové eventy)
    ↓ callbacky (onCursorMove, onClick, onScroll)
MainActivity (propojení, Shizuku setup, detekce displeje)
    ↓ oneway AIDL IPC
InputService (Shizuku UserService, UID 2000 shell)
    ↓ write()
/dev/uhid (kernel UHID → virtual HID mouse)
    ↓ kernel input subsystem
PointerController (systémový kurzor na displeji)
```

### 1. TouchpadView (`TouchpadView.kt`)
- Custom `View` zachytávající dotykové gesta
- Jeden prst = pohyb, tap = klik, dva prsty vertikálně = scroll
- Sensitivity multiplier 2.5f
- Sleduje absolutní pozici kurzoru (cursorX, cursorY) na externím displeji
- Volá callbacky `onCursorMove(x, y)`, `onClick(x, y)`, `onScroll(x, y, vScroll)`

### 2. InputService (`InputService.kt`)
- Shizuku UserService – běží v separátním procesu s shell oprávněními (UID 2000)
- Komunikuje s appkou přes AIDL rozhraní (`IInputService.aidl`)
- AIDL metody: `oneway void moveCursor/click/scroll/destroy`, `String diagnose`
- Aktuální strategie: /dev/uhid virtual HID mouse
- Fallback: sendevent shell příkazy

### 3. MainActivity (`MainActivity.kt`)
- Setup flow: Shizuku permission → bind UserService → detekce displeje → touchpad
- DisplayManager pro externí displej (displayId může být libovolné číslo, např. 196, 203)
- Event countery (moveCount, clickCount, scrollCount) pro debugging
- Diagnostika běží na background Thread, výstup zobrazí v UI
- Tlačítka: Připojit, Diagnostika, Kopírovat, Sdílet

## Závislost na Shizuku

Ano, celé řešení závisí na Shizuku. Důvod:
- `/dev/uhid` vyžaduje UID ve skupině `uhid` — shell (UID 2000) to má
- `/dev/input/eventN` vyžaduje skupinu `input` — shell to má
- `sendevent` shell příkaz vyžaduje shell oprávnění
- Normální Android appka (UID 10xxx) nemá přístup k žádnému z těchto

Alternativy bez Shizuku:
- AccessibilityService (ale nepodporuje sekundární displej)
- Root (příliš restriktivní požadavek)
- ADB wireless (uživatel musí manuálně spustit adb)

## Build

- Gradle 8.10, AGP 8.5.2, Kotlin 2.0.0
- compileSdk 35, minSdk 28, targetSdk 35
- JDK 17
- Shizuku: `dev.rikka.shizuku:api:13.1.5` a `provider:13.1.5`

## Struktura souborů

```
├── CLAUDE.md                       # Tento soubor
├── TODO.md                         # Prioritizované úkoly
├── APPROACHES.md                   # Log všech vyzkoušených přístupů
├── app/src/main/
│   ├── aidl/.../IInputService.aidl # AIDL rozhraní (oneway)
│   ├── java/.../InputService.kt    # Shizuku UserService
│   ├── java/.../MainActivity.kt    # UI + setup
│   ├── java/.../TouchpadView.kt    # Dotykové gesta
│   └── res/layout/activity_main.xml
```
