# TODO – Aktuální stav a další kroky

## ✅ Hotovo

### Pohyb kurzoru přes /dev/uhid
- UHID virtual HID mouse funguje na Pixel 8 Pro, Android 16
- Oneway AIDL pro neblokující IPC
- Multi-strategie: UHID → sendevent → shell input (fallback)
- Diagnostický panel s 6 testy

### Základní gesta
- 1 prst tah = pohyb kurzoru
- 1 prst tap = levý klik
- 2 prsty vertikální tah = scroll

### UI
- Tlačítka: Připojit, Diagnostika, Kopírovat, Sdílet
- Event countery pro debugging
- Detekce externího displeje přes DisplayManager

---

## 🔴 Fáze 1 – Aktuální sprint

### 1. Snížit citlivost kurzoru
- `sensitivity`: 2.5f → 1.5f (default, konfigurovatelná přes settings)

### 2. Zvýšit citlivost scrollu
- `scrollSensitivity`: 0.03f → 0.08f (default, konfigurovatelná)

### 3. Pravý klik (2-prstový tap)
- TouchpadView: detekce dvou-prstového tapu (< 200ms, žádný pohyb)
- AIDL: `oneway void rightClick(displayId, x, y)`
- InputService: `sendMouseReport(2, 0, 0)` (BTN_RIGHT = bit 1)

### 4. Tap-and-drag (1 prst drží + 2. jezdí)
- 1. prst na místě > 200ms, 2. prst přidán = drag mode
- AIDL: `oneway void startDrag(displayId)`, `oneway void endDrag(displayId)`
- InputService: `sendMouseReport(1, 0, 0)` drží tlačítko, pohyb kurzoru pokračuje

### 5. Pinch zoom (2 prsty od/k sobě)
- Detekce změny vzdálenosti mezi prsty
- Implementace: Ctrl + scroll přes shell/UHID

### 6. 3-prstová navigační gesta
- Doleva = Zpět (`input keyevent 4`)
- Doprava = Task manager (`input keyevent 187`)
- Nahoru = App drawer (`input keyevent 284`)
- Dolů = Notifikace (`cmd statusbar expand-notifications`)
- AIDL: `oneway void sendKeyEvent(displayId, keyCode)`, `oneway void sendShellCommand(displayId, command)`

### 7. Fullscreen UI
- Immersive mode (skrýt status bar + nav bar)
- Touchpad na celou obrazovku
- Malé gear icon (top-right, poloprůhledné) → otevře settings

### 8. Settings panel (BottomSheet)
- Slider citlivost kurzoru (0.5–4.0)
- Slider citlivost scrollu (0.01–0.20)
- Tlačítko Diagnostika
- Tlačítko Odpojit
- Uložení do SharedPreferences

---

## 🟡 Fáze 2 – Vylepšení

### 9. Konfigurovatelné zkratky gest
- UI pro mapování gesto → akce
- Podpora: keyevent kód, shell příkaz

### 10. Haptic feedback
- Vibrace při kliknutí, pravém kliku

### 11. Automatické spuštění
- BroadcastReceiver pro připojení monitoru

---

## 🟢 Fáze 3 – Release

### 12. Release build + signing
- Keystore, signing config, ProGuard
- GitHub Actions workflow pro release APK

### 13. Google Play Store
- Developer účet ($25 jednorázově)
- Store listing, screenshoty, popis
- Shizuku dependency vysvětlit v popisu

### 14. Monetizace (volitelné)
- Freemium model: základní funkce zdarma, premium za in-app purchase
- Nebo tip jar přes Google Play Billing
- Alternativy: Ko-fi, GitHub Sponsors (mimo Play Store)

---

## Kompatibilita

| Zařízení | Podpora |
|----------|---------|
| Pixel 8/9 Pro | ✅ Testováno (Android 16) |
| Pixel 8/9 | Mělo by fungovat (stejný Desktop Mode) |
| Samsung (DeX) | Pravděpodobně funguje (UHID je kernel-level) |
| Motorola (Ready For) | Pravděpodobně funguje |
| Jiné s USB-C DP | Závisí na Desktop Mode podpoře |

**Požadavky:** Android 14+ s Desktop Mode, Shizuku, USB-C video výstup
