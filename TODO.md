# TODO – Další kroky

## 🔴 P0 – Musí fungovat (opravit hned)

### 1. Opravit injekci mouse eventů
Kurzor na externím displeji nereaguje na pohyb. Postup:
1. Spustit appku → kliknout **Diagnostika** → zkopírovat výstup
2. Analyzovat:
   - Pokud `InputManager.getInstance()` vrátí ✗ → API je na Android 16 blokované
   - Pokud `injectInputEvent` vrátí false → oprávnění nestačí nebo špatný event formát
   - Pokud shell fallback taky nefunguje → `input -d` příkaz neexistuje na Android 16
3. Na základě diagnostiky zvolit opravu (viz alternativy v CLAUDE.md)

### 2. Ověřit správný displayId
- Diagnostika vypíše seznam nalezených displejů
- displayId externího monitoru nemusí být 1 – může být libovolné číslo
- Ověřit, že `Display.DEFAULT_DISPLAY` (0) je opravdu ten na telefonu

## 🟡 P1 – Důležité vylepšení

### 3. Implementovat /dev/uinput jako alternativu
Pokud InputManager API nefunguje na Android 16, /dev/uinput je nejspolehlivější cesta:
- Vytvořit virtuální mouse device
- Posílat EV_REL eventy (relativní pohyb) místo absolutních souřadnic
- Referenční kód: https://gist.github.com/Xtr126/c5de3932490758f2cbac44f8a6c3206e
- Bude potřeba JNI nebo spouštění C binárek přes shell

### 4. Přidat pravé tlačítko myši
- Long press (>500ms) = pravý klik
- Nebo: tří-prstové ťuknutí = pravý klik
- V InputService přidat `rightClick()` metodu s `BUTTON_SECONDARY`

### 5. Přidat vizuální feedback
- Vibrace při kliknutí (krátký haptic feedback)
- Animace na touchpadu při detekci tagu/scrollu
- Indikátor připojení (zelená/červená tečka)

## 🟢 P2 – Nice to have

### 6. Nastavení v UI
- Slider pro citlivost kurzoru
- Slider pro rychlost scrollu
- Toggle pro haptic feedback
- Uložení preferencí do SharedPreferences

### 7. Notifikace s quick controls
- Persistent notification když je touchpad aktivní
- Quick action pro reset pozice kurzoru
- Quick action pro odpojení

### 8. Automatické spuštění
- Detekce připojení externího displeje → automaticky zobrazit touchpad
- BroadcastReceiver pro display connected/disconnected

### 9. Podpora gest
- Tři prsty doleva/doprava = Alt+Tab (přepínání oken)
- Tři prsty nahoru = zobrazit všechna okna
- Pinch = zoom

### 10. Release build
- Nastavit signing config v build.gradle.kts
- Vytvořit keystore pro release podpis
- GitHub Actions workflow pro release APK
- Automatické release přes GitHub tags
