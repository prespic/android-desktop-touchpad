# TODO – Další kroky

## P0 – Musí fungovat

### 1. Opravit pohyb kurzoru přes UHID
UHID virtual mouse vytváří kurzor na externím displeji, ale pohyb nefunguje stabilně.
Diagnostika v aktuální verzi testuje 6 metod najednou:
1. UHID direct write (short 10-byte buffer)
2. UHID direct write (full 4102-byte buffer)
3. sendevent shell příkazy
4. getevent read-back verification
5. shell `input mouse` / `input touchscreen`
6. Permissions a SELinux kontext

Potřeba: spustit diagnostiku, zjistit který test skutečně pohne kurzorem, a ten použít.

### 2. Zvážit /dev/uinput jako alternativu
- Dostupný a writable pro shell
- Vyžaduje ioctl() → JNI wrapper nebo C helper binary
- Stabilnější API než UHID pro virtuální myš

## P1 – Důležité vylepšení

### 3. Pravé tlačítko myši
- Long press = pravý klik, nebo tří-prstový tap

### 4. Haptic feedback
- Krátká vibrace při kliknutí

### 5. Citlivost kurzoru
- Slider v UI pro sensitivity multiplier

## P2 – Nice to have

### 6. Automatické spuštění při připojení monitoru
### 7. Podpora gest (Alt+Tab, zoom)
### 8. Release build + signing
