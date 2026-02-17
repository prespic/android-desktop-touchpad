# Vyzkoušené přístupy – log

Zařízení: Pixel 8 Pro, Android 16 (SDK 36), Shizuku UID 2000

---

## Přístupy k pohybu kurzoru

### 1. InputManager.getInstance() + injectInputEvent (reflection)
- **Stav: NEFUNGUJE**
- `InputManager.getInstance()` bylo odstraněno v Android 16 SDK 36
- Metoda neexistuje → `NoSuchMethodException`

### 2. InputManagerGlobal.getInstance() + injectInputEvent
- **Stav: NEFUNGUJE (vrací true, ale kurzor se nepohne)**
- `Class.forName("android.hardware.input.InputManagerGlobal")` funguje
- 2-arg i 3-arg `injectInputEvent()` vrací `true` na displayId 0 i 197
- **Problém**: MotionEvent injekce doručuje eventy do oken, ale NEPOHYBUJE systémovým kurzorem. PointerController čte pouze z kernel input devices.

### 3. Instrumentation.sendPointerSync()
- **Stav: NEFUNGUJE**
- `SecurityException`: "not directed at window owned by uid 2000"

### 4. Shell `input` příkazy
- **`input -d <displayId> tap X Y`**: FUNGUJE pro tap/klik
- **`input motionevent HOVER_MOVE X Y`**: NEFUNGUJE — `IllegalArgumentException`
- **`input mouse move X Y`**: NEFUNGUJE — "Unknown command: mouse"
- **`input keyevent <code>`**: FUNGUJE — pro navigační akce (Back, Home, Recent, All Apps)
- **`cmd statusbar expand-notifications`**: NETESTOVÁNO — pro notifikační lištu

### 5. /dev/uhid – Virtual HID Mouse ✅ FUNGUJE
- **Stav: FUNGUJE (aktuální strategie)**
- UHID_CREATE2 vytvoří virtuální HID myš přes `/dev/uhid`
- HID report descriptor: 3-button relative mouse + wheel (52 bytes)
- Kurzor se zobrazí a pohybuje na externím displeji
- **Vyřešené bugy:**
  - Synchronní AIDL + flush() → app freeze → oprava: `oneway` AIDL, odebrání flush()
  - @Synchronized na AIDL metodách → binder thread starvation → oprava: @Synchronized pouze na sendMouseReport()
  - Nekonečná smyčka v uhidMove() → `toInt()` zaokrouhloval 0.5–0.99 na 0 → oprava: `round()` + break guard
  - Report counter: 3,298,750 reportů při 2 voláních moveCursor = důkaz infinite loop

### 6. /dev/uinput – Virtual Input Device
- **Stav: NETESTOVÁNO (dostupné)**
- `/dev/uinput` existuje a je writable pro shell (UID 2000)
- Vyžaduje ioctl() → potřeba JNI nebo C binary
- Odloženo — UHID funguje

### 7. sendevent shell příkaz
- **Stav: IMPLEMENTOVÁNO jako fallback**
- `sendevent /dev/input/eventN EV_REL REL_X value` etc.
- Pomalejší než UHID (shell overhead), ale funkční jako záloha

### 8. AccessibilityService
- **Stav: NEVYZKOUŠENO**
- GestureDescription API — pravděpodobně nepodporuje sekundární displej
- Nepotřebuje Shizuku, ale omezené možnosti

---

## Přístupy k AIDL komunikaci

### Synchronní AIDL
- **NEFUNGUJE** — blokuje UI thread, app zamrzne při rychlém pohybu

### Oneway AIDL ✅
- **FUNGUJE** — fire-and-forget, UI thread se neblokuje
- `diagnose()` zůstává synchronní (vrací String)

### @Synchronized na AIDL metodách
- **NEFUNGUJE** — binder thread starvation, diagnose() se nikdy nedostane ke slovu
- Oprava: @Synchronized pouze na sendMouseReport() (chrání fd write)

---

## Klíčové nálezy

1. **DisplayId není sekvenční** — ID bývá 196, 197, 203 atd.
2. **MotionEvent injekce nepohybuje kurzorem** — fundamentální omezení Androidu
3. **UHID funguje** — kernel vytvoří pointer, reporty pohybují kurzorem
4. **`/proc/bus/input/devices` vyžaduje root** na Android 16
5. **`/sys/class/input/*/device/name` nečitelné pro shell** — nutno použít `getevent -pl`
6. **Nekonečná smyčka je zákeřná** — projevuje se jako "kurzor se nepohybuje" i když reporty se posílají (miliony reportů s dx=0, dy=0)
