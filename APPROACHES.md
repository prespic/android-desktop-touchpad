# Vyzkoušené přístupy – log

Zařízení: Pixel 8 Pro, Android 16 (SDK 36), Shizuku UID 2000

## Přístupy k pohybu kurzoru

### 1. InputManager.getInstance() + injectInputEvent (reflection)
- **Stav: NEFUNGUJE**
- `InputManager.getInstance()` bylo odstraněno v Android 16 SDK 36
- Metoda neexistuje → `NoSuchMethodException`

### 2. InputManagerGlobal.getInstance() + injectInputEvent
- **Stav: NEFUNGUJE (vrací true, ale kurzor se nepohne)**
- `Class.forName("android.hardware.input.InputManagerGlobal")` funguje
- 2-arg `injectInputEvent(event, mode)`: vrací `true` na displayId 0 i 197
- 3-arg `injectInputEvent(event, mode, targetUid)`: vrací `true` na displayId 197
- Režim INJECT_INPUT_EVENT_MODE_ASYNC (0) i WAIT_FOR_RESULT (1) vrací true
- **Problém**: MotionEvent injekce doručuje eventy do oken, ale NEPOHYBUJE systémovým kurzorem (PointerController). PointerController čte pouze z kernel input devices.

### 3. Instrumentation.sendPointerSync()
- **Stav: NEFUNGUJE**
- Hází `SecurityException`: "not directed at window owned by uid 2000"
- Shell proces (UID 2000) nemá okno, takže Instrumentation odmítne event

### 4. Shell `input` příkazy
- **`input -d <displayId> tap X Y`**: FUNGUJE pro tap/klik
- **`input motionevent HOVER_MOVE X Y`**: NEFUNGUJE — `IllegalArgumentException` na Android 16
- **`input mouse move X Y`**: NEFUNGUJE — "Unknown command: mouse"
- **`input -d <displayId> mouse move X Y`**: NEFUNGUJE — "Unknown command: mouse"
- **`input touchscreen swipe X1 Y1 X2 Y2 duration`**: netestováno pro kurzor
- **Problém**: shell `input` příkazy pro pohyb myši neexistují na Android 16

### 5. /dev/uhid – Virtual HID Mouse (AKTUÁLNÍ STRATEGIE)
- **Stav: ČÁSTEČNĚ FUNGUJE**
- Vytvoření UHID device: FUNGUJE (UHID_CREATE2 přes FileOutputStream)
- Kurzor se na externím displeji ZOBRAZÍ (kernel vytvoří pointer pro virtuální myš)
- HID report descriptor: 3-button relative mouse + wheel (52 bytes)
- Zápis UHID_INPUT2 reportů: zápis nehodí výjimku
- **Problém s pohybem**: kurzor se zobrazí, ale NEPOHYBUJE se prstem
  - `prevX`/`prevY` se aktualizují → `moveCursor()` se volá
  - `reportSentCount` je potřeba ověřit v diagnostice
  - Možné příčiny:
    - Short write (10 bytes místo 4102) — kernel by měl zero-padovat, ale testujeme full buffer
    - UHID device se neregistruje jako input device (v /proc/bus/input/devices nenalezen, ale Permission denied)
    - Reporty se zapisují ale kernel je nepropaguje do input subsystému
- **Dřívější úspěch**: V jednom buildu se kurzor pohyboval! Ale app zamrzla a crashla (synchronní AIDL + flush blokoval UI thread)
- Po přidání `oneway` AIDL + odebrání flush() kurzor přestal reagovat
- **BUG NALEZEN (2026-02-17)**: Nekonečná smyčka v `uhidMove()`!
  - Když delta je 0.5–0.99, `toInt()` zaokrouhlí na 0, odečte se 0, smyčka běží donekonečna
  - Diagnostika ukázala: `moveCursor calls: 2` ale `reports sent: 3,298,750`
  - Všechny binder thready zůstaly zaseknuté v nekonečné smyčce
  - Oprava: `kotlin.math.round()` místo `toInt()` + guard `if (sx==0 && sy==0) break`

### 6. /dev/uinput – Virtual Input Device
- **Stav: NETESTOVÁNO (detekováno jako dostupné)**
- `/dev/uinput` existuje a je writable pro shell (UID 2000)
- Vyžaduje ioctl() volání pro setup → Java FileOutputStream nestačí, potřeba JNI nebo C binary
- Referenční implementace: https://gist.github.com/Xtr126/c5de3932490758f2cbac44f8a6c3206e

### 7. sendevent shell příkaz
- **Stav: TESTUJE SE**
- Pokud UHID device vytvoří `/dev/input/eventN`, můžeme posílat eventy přes shell `sendevent`
- `sendevent /dev/input/eventN EV_REL REL_X value` etc.
- Přidáno jako fallback strategie v aktuálním kódu

### 8. AccessibilityService
- **Stav: NEVYZKOUŠENO**
- GestureDescription API by mohlo fungovat bez Shizuku
- Pravděpodobně nepodporuje sekundární displej

## Přístupy k AIDL komunikaci

### Synchronní AIDL (původní)
- **Stav: FUNGUJE ale BLOKUJE UI**
- UI thread čeká na odpověď z InputService
- Při rychlém pohybu prstem → desítky synchronních volání → UI zamrzne → crash

### Oneway AIDL (aktuální)
- **Stav: FUNGUJE**
- `oneway void moveCursor/click/scroll/destroy` — fire-and-forget
- UI thread se neblokuje
- Binder thread pool doručuje volání do service
- `diagnose()` zůstává synchronní (vrací String)

### @Synchronized na AIDL metodách
- **Stav: NEFUNGUJE — způsobuje binder thread starvation**
- Všechny binder thready čekají na zámek → `diagnose()` se nikdy nedostane ke slovu
- Odstraněno — `@Synchronized` pouze na `sendMouseReport()` (chrání fd write)

## Klíčové nálezy

1. **DisplayId není sekvenční** — externí displej má ID 196, 197, 203 atd., NE 1 nebo 2
2. **MotionEvent injekce nepohybuje kurzorem** — fundamentální omezení Androidu, PointerController čte jen z kernel input devices
3. **UHID device se vytváří a kurzor se zobrazí** — to potvrzuje, že kernel device funguje
4. **V jednom buildu kurzor reagoval** — takže UHID princip je správný, jen je něco špatně v timing/buffer/delivery
5. **`/proc/bus/input/devices` vyžaduje root** — na Android 16 shell nemá read permission
6. **flush() na FileOutputStream pro /dev/uhid** — je no-op (Java FileOutputStream je unbuffered), ale jeho přítomnost korelovala s fungujícím kurzorem (mohla to být jen náhoda)
