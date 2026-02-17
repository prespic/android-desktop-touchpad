# CLAUDE.md – Instrukce pro Claude Code

## O projektu

**PixelTouchpad** je Android aplikace, která promění displej telefonu ve virtuální touchpad pro ovládání kurzoru na externím monitoru v Android Desktop Mode. Primárně cílí na Pixel 8+ s Android 15 QPR2+ / Android 16.

## Architektura

Aplikace má tři hlavní vrstvy:

### 1. TouchpadView (`TouchpadView.kt`)
- Custom `View` zachytávající dotykové gesta na displeji telefonu
- Překládá gesta na relativní pohyb kurzoru (jako notebook touchpad)
- Gesta: jeden prst = pohyb, tap = klik, dva prsty vertikálně = scroll
- Sleduje absolutní pozici kurzoru na externím displeji (cursorX, cursorY)
- Volá callbacky `onCursorMove`, `onClick`, `onScroll`

### 2. InputService (`InputService.kt`)
- Shizuku UserService – běží v separátním procesu s ADB/shell oprávněními
- Komunikuje s hlavní appkou přes AIDL rozhraní (`IInputService.aidl`)
- Injektuje mouse MotionEventy na cílový displej přes `InputManager.injectInputEvent()` (reflection)
- Má shell fallback: pokud InputManager API selže, použije `input` shell příkazy
- Obsahuje `diagnose()` metodu pro debugging

### 3. MainActivity (`MainActivity.kt`)
- Řídí setup flow: Shizuku permission → bind UserService → detekce displeje → aktivace touchpadu
- Detekuje externí displeje přes `DisplayManager`
- Propojuje TouchpadView callbacky s InputService voláními
- Zobrazuje diagnostické informace

## Známé problémy (aktuální stav)

### KRITICKÝ: Kurzor se nepohybuje
Aplikace se úspěšně nainstaluje, připojí k Shizuku, detekuje externí displej, ale pohyb prstem po touchpadu neovládá kurzor na monitoru. Pravděpodobné příčiny:

1. **InputManager.injectInputEvent() vrací false nebo hází výjimku**
   - Na Android 16 mohla Google změnit přístup k této metodě
   - Reflection na skrytá API může být blokována
   - Oprávnění shell procesu nemusí stačit pro injekci na sekundární displej

2. **setDisplayId() nefunguje správně**
   - Event se může injektovat na hlavní displej místo externího
   - DisplayId nemusí odpovídat tomu, co vrací DisplayManager

3. **Shell fallback může být příliš pomalý nebo taky nefunkční**
   - `input -d <displayId>` flag nemusí být na Android 16 podporován
   - `input motionevent HOVER_MOVE` nemusí existovat

### CO UDĚLAT JAKO PRVNÍ
1. Opravit layout XML (build.yml push byl přerušen uprostřed zápisu `activity_main.xml`)
2. Spustit diagnostiku (tlačítko "Diagnostika" v UI) a analyzovat výstup
3. Na základě diagnostiky opravit injekci eventů

## Layout XML – nedokončená úprava
Soubor `app/src/main/res/layout/activity_main.xml` potřebuje aktualizovat – přidat tlačítko `btnDiagnose`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="#121212">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp"
        android:gravity="center">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Pixel Touchpad"
            android:textSize="24sp"
            android:textColor="#e94560"
            android:textStyle="bold"
            android:layout_marginBottom="8dp" />

        <TextView
            android:id="@+id/statusText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Inicializace..."
            android:textSize="14sp"
            android:textColor="#aaaaaa"
            android:gravity="center"
            android:lineSpacingMultiplier="1.3" />

        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:layout_marginTop="12dp">

            <Button
                android:id="@+id/btnConnect"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Připojit"
                android:backgroundTint="#0f3460" />

            <Button
                android:id="@+id/btnDiagnose"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Diagnostika"
                android:layout_marginStart="8dp"
                android:backgroundTint="#e94560"
                android:visibility="gone" />
        </LinearLayout>
    </LinearLayout>

    <com.pixeltouchpad.app.TouchpadView
        android:id="@+id/touchpadView"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:visibility="gone" />

</LinearLayout>
```

## Technické detaily

### Shizuku integrace
- Verze: `dev.rikka.shizuku:api:13.1.5` a `dev.rikka.shizuku:provider:13.1.5`
- UserService se spouští přes `Shizuku.bindUserService()` s `ComponentName` na `InputService`
- Provider v manifestu: `rikka.shizuku.ShizukuProvider` s authorities `${applicationId}.shizuku`
- UserService běží v procesu s příponou "input" (`processNameSuffix("input")`)

### InputManager API (reflection)
```kotlin
// Získání instance
InputManager::class.java.getDeclaredMethod("getInstance").invoke(null)

// Injekce eventu
InputManager::class.java.getDeclaredMethod(
    "injectInputEvent",
    InputEvent::class.java,
    Int::class.javaPrimitiveType
)
// Mode 2 = INJECT_INPUT_EVENT_MODE_ASYNC

// Nastavení cílového displeje
InputEvent::class.java.getDeclaredMethod("setDisplayId", Int::class.javaPrimitiveType)
```

### Shell fallback příkazy
```bash
input -d <displayId> motionevent HOVER_MOVE <x> <y>
input -d <displayId> tap <x> <y>
```

### Build
- Gradle 8.10, AGP 8.5.2, Kotlin 2.0.0
- compileSdk 35, minSdk 28, targetSdk 35
- JDK 17
- GitHub Actions workflow v `.github/workflows/build.yml`

## Možné alternativní přístupy k injekci (pokud InputManager nefunguje)

### A. Přímý přístup k /dev/uinput
Vytvořit virtuální vstupní zařízení přímo v kernelu:
```kotlin
// Otevřít /dev/uinput, zaregistrovat jako mouse device
// Posílat EV_REL eventy pro pohyb, EV_KEY pro kliky
// Výhoda: nejnižší latence, nezávisí na Android API
// Nevýhoda: potřebuje write přístup k /dev/uinput (shell by měl stačit)
```
Referenční implementace: https://gist.github.com/Xtr126/c5de3932490758f2cbac44f8a6c3206e

### B. Instrumentation API
```kotlin
// android.app.Instrumentation#sendPointerSync(MotionEvent)
// Jednodušší, ale nemusí podporovat sekundární displej
```

### C. AccessibilityService
```kotlin
// GestureDescription API
// Nemusí vyžadovat Shizuku vůbec, ale omezené možnosti
// A nemusí fungovat na sekundárním displeji
```

### D. Virtuální DisplayInput přes hidden API
```kotlin
// android.hardware.input.InputManagerGlobal
// android.view.WindowManagerGlobal
// Novější Android verze mohou mít jiné API
```

## Uživatelský kontext
- Uživatel: Martin, český vývojář her (Godot, HTML hry pro děti)
- Zařízení: Pixel 8, Android 16
- Shizuku: nainstalovaný a funkční (z GitHub releases, ne Play Store)
- Cíl: ovládat desktop mode na externím monitoru bez fyzické myši
- Jazyk UI: čeština

## Struktura souborů
```
PixelTouchpad/
├── .github/workflows/build.yml    # GitHub Actions CI
├── .gitignore
├── README.md
├── CLAUDE.md                      # Tento soubor
├── build.gradle.kts               # Root build config
├── settings.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts           # App build config + Shizuku deps
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── aidl/com/pixeltouchpad/app/
        │   └── IInputService.aidl     # AIDL rozhraní pro Shizuku service
        ├── java/com/pixeltouchpad/app/
        │   ├── InputService.kt        # Shizuku UserService – injekce eventů
        │   ├── MainActivity.kt        # UI + Shizuku setup + display detection
        │   └── TouchpadView.kt        # Custom View – touchpad gesta
        └── res/
            ├── layout/activity_main.xml
            ├── drawable/ic_launcher_foreground.xml
            ├── drawable/ic_launcher_background.xml
            ├── mipmap-anydpi-v26/ic_launcher.xml
            └── values/
                ├── colors.xml
                ├── strings.xml
                └── themes.xml
```
