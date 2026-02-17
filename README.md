# Pixel Touchpad

Minimální Android aplikace, která promění displej telefonu ve virtuální touchpad pro ovládání kurzoru na externím monitoru (desktop mode).

## Funkce

- **Pohyb kurzoru** – jeden prst, relativní pohyb jako na notebookovém touchpadu
- **Klik** – krátké ťuknutí jedním prstem
- **Scroll** – vertikální tah dvěma prsty

## Požadavky

- Android 9+ (Pixel 8 s Android 15/16 ideální)
- [Shizuku](https://github.com/RikkaApps/Shizuku/releases) nainstalovaný a spuštěný
- Externí displej připojený přes USB-C

## Jak to funguje

1. Aplikace se připojí k Shizuku a spustí privilegovanou službu
2. Služba používá `InputManager.injectInputEvent()` k vkládání mouse eventů na sekundární displej
3. TouchpadView na telefonu zachytává dotykové gesta a překládá je na pohyb kurzoru

## Build přes GitHub Actions

### 1. Vytvoř repozitář na GitHubu

Jdi na [github.com/new](https://github.com/new) a vytvoř nový repozitář (např. `PixelTouchpad`).

### 2. Nahraj kód

```bash
cd PixelTouchpad
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/TVUJ_USERNAME/PixelTouchpad.git
git push -u origin main
```

### 3. Počkej na build

- Jdi na záložku **Actions** v repozitáři
- Build se spustí automaticky po push
- Po dokončení (cca 3-5 minut) klikni na run → **Artifacts** → stáhni `PixelTouchpad-debug.zip`

### 4. Nainstaluj na telefon

- Rozbal ZIP – uvnitř je `.apk` soubor
- Přenes na telefon a nainstaluj (povol instalaci z neznámých zdrojů)

## Použití

1. Spusť **Shizuku** a aktivuj službu (Wireless debugging)
2. Připoj monitor přes USB-C kabel
3. Zapni **desktop mode** v Developer options
4. Otevři **Pixel Touchpad**
5. Klikni **Připojit** – appka se připojí k Shizuku a najde externí displej
6. Touchpad se aktivuje – pohybuj prstem po displeji telefonu

## Úpravy

### Citlivost touchpadu
V `TouchpadView.kt`:
```kotlin
var sensitivity = 2.5f        // rychlost kurzoru
var scrollSensitivity = 0.03f // rychlost scrollu
```

### Detekce kliknutí
V `TouchpadView.kt`:
```kotlin
private val tapMaxDuration = 200L   // max doba dotyku pro klik (ms)
private val tapMaxDistance = 30f     // max pohyb prstu pro klik (px)
```

## Známé limitace

- Kurzor se po restartu appky vrátí doprostřed displeje
- Nemá pravé tlačítko myši (lze přidat jako long-press nebo tří-prstové ťuknutí)
- Vyžaduje restart Shizuku po restartu telefonu

## Licence

MIT – dělej si s tím co chceš.
