# Android Auto Custom UI: aa-torque vs Official Templates (Research)

> **Note:** Android Auto support was removed from openRS_ in v2.0.1. This document is preserved as research for potential future AA integration. See the [Roadmap](../README.md#roadmap) for current plans.

This document summarizes how **aa-torque** ([agronick/aa-torque](https://github.com/agronick/aa-torque)) achieves a “fully customizable” Android Auto UI (themes, multiple dashboards, custom gauges, expressions) and how that differs from the **official AndroidX Car App Library** approach used by openRS.

---

## 1. Two Different Android Auto Integration Paths

| Aspect | **Official (openRS current)** | **Unofficial (aa-torque)** |
|--------|-------------------------------|-----------------------------|
| **SDK** | AndroidX Car App Library | **aauto-sdk** (martoreto/cupral) via `aauto.aar` |
| **Entry point** | `CarAppService` → `Session` → Screens | `CarActivityService` → **CarActivity** |
| **UI model** | Template-based (Pane, List, etc.) | **Full Android Activity**: `setContentView()`, Fragments, custom Views |
| **Manifest categories** | `androidx.car.app.CarAppService` + `IOT` | `CATEGORY_PROJECTION` + **CATEGORY_PROJECTION_OEM** |
| **automotive_app_desc** | `<uses name="template" />` | `<uses name="service" />` + `<uses name="projection" />` |
| **Distribution** | Play Store / normal install | Requires AA **Developer options → Unknown sources** (or tools like KingInstaller) |

So: **aa-torque’s “custom” UI is not “the same app scaled”—it uses a different, unofficial SDK that lets a real Activity (with normal layouts and views) run on the car display.** openRS currently uses the official template API, which does not allow arbitrary layouts.

---

## 2. How aa-torque Achieves Custom UI

### 2.1 Unofficial SDK: aauto-sdk

- **Origin:** [martoreto/aauto-sdk](https://github.com/martoreto/aauto-sdk) (archived; Google requested takedown). Community forks/updates exist (e.g. [cupral/aauto-sdk](https://github.com/cupral/aauto-sdk)).
- **In aa-torque:** Dependency is a **local AAR**: `implementation files('../lib/aauto.aar')`. Comment in their `build.gradle`: *“Replaced with updated SDK from https://github.com/cupral/aauto-sdk.git”*.
- **What it provides:**  
  - `CarActivityService` — service that Android Auto starts; returns the `CarActivity` class to run.  
  - `CarActivity` — base class for the **Activity** that is shown on the car screen.  
  - `CarUiController` (e.g. `StatusBarController`, `MenuController`) for status bar and menu.  
  - The car screen is literally a normal Android Activity with standard Views and Fragments.

### 2.2 Manifest and automotive_app_desc

**Service declaration (aa-torque):**

```xml
<service
    android:name="com.aatorque.stats.CarService"
    android:exported="true"
    android:label="@string/app_car_service_name">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="com.google.android.gms.car.category.CATEGORY_PROJECTION" />
        <category android:name="com.google.android.gms.car.category.CATEGORY_PROJECTION_OEM" />
    </intent-filter>
</service>
```

**automotive_app_desc** (from cupral/aauto-sdk README):

```xml
<automotiveApp xmlns:tools="http://schemas.android.com/tools">
    <uses name="service" tools:ignore="InvalidUsesTagAttribute" />
    <uses name="projection" tools:ignore="InvalidUsesTagAttribute" />
    <uses name="notification" />
</automotiveApp>
```

**Permissions:**  
- `com.google.android.gms.permission.CAR_VENDOR_EXTENSION` is used (e.g. for vendor channel).  
- No AndroidX Car App manifest entries; no `androidx.car.app.CarAppService`.

### 2.3 Car service and Activity

**CarService** (extends SDK’s `CarActivityService`):

```java
public class CarService extends CarActivityService {
    public Class getCarActivity() {
        return MainCarActivity.class;
    }
}
```

**MainCarActivity** (extends SDK’s `CarActivity`):

- `onCreate()`: reads theme from DataStore, calls `setContentView(R.layout.activity_car_main)`.
- Uses **FragmentManager**: adds `DashboardFragment`, `CreditsFragment`, etc., and switches between them.
- **Theming:** `mapTheme(this, theme)` + `setTheme()`; on theme change can recreate or re-add `DashboardFragment`.
- **Status bar:** `carUiController.statusBarController` (e.g. `setTitle()`, `hideTitle()`).
- Standard Android: `supportFragmentManager`, `runBlocking`/DataStore, key events, rotary input.

So the “car UI” is a normal Activity + Fragments, not template screens.

### 2.4 Custom content: dashboards, gauges, themes

- **Layout:** `activity_car_main` has a `fragment_container`. **DashboardFragment** uses ViewBinding (`FragmentDashboardBinding`) and a ConstraintLayout; it hosts child fragments for gauges and displays.
- **Gauges / displays:** Custom views and fragments (e.g. **TorqueGauge**, **TorqueDisplay**, **TorqueChart**) using:
  - **SpeedView** (submodule) for gauge graphics.
  - **GraphView** for charts.
  - Data from Torque Pro via **TorqueServiceWrapper** and **TorqueRefresher**; **EvalEx** for custom expressions.
- **Multiple dashboards:** User preferences (e.g. DataStore) hold `screensList`, `currentScreen`, `screensCount`. Fragment observes these and updates which gauges/displays are shown; swipe or rotary changes `currentScreen`.
- **Themes:** Stored in DataStore (`selectedTheme`). Activity applies theme with `setTheme(mapTheme(...))` and can recreate the dashboard fragment so all views use the new theme (fonts, colors, backgrounds).
- **Other:** Album art background, blur, fonts (e.g. digital, VAG-style), rotary dial, key events — all implemented with normal Android APIs inside this Activity/Fragment tree.

### 2.5 Data flow (for reference)

- **Torque Pro** (required) exposes OBD data; aa-torque talks to it via **TorqueServiceWrapper** and receives PID values.
- **TorqueRefresher** builds “queries” for each gauge/display from user config and pushes values into **TorqueGauge** / **TorqueDisplay**.
- **EvalEx** is used for custom formulas over PIDs. Config (dashboards, themes, fonts, etc.) is persisted with DataStore (and backup/restore).

---

## 3. openRS vs aa-torque (Summary)

| Topic | openRS (current) | aa-torque |
|-------|------------------|-----------|
| **AA API** | AndroidX Car App (templates) | aauto-sdk (CarActivity) |
| **UI** | PaneTemplate, ListTemplate, etc. | Activity + Fragments + custom Views |
| **Themes / dashboards** | Only what templates allow (e.g. row text/icons) | Full themes, multiple screens, custom layouts |
| **Custom expressions** | Not in UI; could be in app logic only | EvalEx in UI/config |
| **Install** | Normal / Play Store path | “Unknown sources” or KingInstaller |
| **Data source** | WiCAN (MeatPi) + baked-in PIDs | Torque Pro app |

So the “fully customizable” experience in aa-torque comes from **using the unofficial aauto-sdk and a full Activity**, not from the official template API.

---

## 4. Options for openRS

1. **Keep AndroidX Car App (current)**  
   - Pros: Official, no “Unknown sources”, Play Store–friendly, stable.  
   - Cons: UI remains template-only (lists, panes, rows); no custom gauges/layouts in the official sense.

2. **Adopt aauto-sdk (aa-torque–style)**  
   - Pros: Full custom UI (gauges, themes, multiple dashboards, EvalEx-style expressions).  
   - Cons: Unofficial; users must enable AA “Unknown sources”; possible breakage with Android/AA updates; need to host or build `aauto.aar` (martoreto archived, cupral or others); licensing (aa-torque is GPL-3.0; aauto-sdk licensing must be checked if we depend on it).

3. **Hybrid**  
   - Keep official Car App as primary (store, default install).  
   - Offer a separate build or “expert” flavor that uses aauto-sdk for power users who accept sideload/unknown sources.  
   - Shared: same WiCAN/telemetry and PID catalog; different AA UI stack.

---

## 5. References

- **aa-torque:** https://github.com/agronick/aa-torque  
- **aauto-sdk (cupral):** https://github.com/cupral/aauto-sdk  
- **aauto-sdk (martoreto, archived):** https://github.com/martoreto/aauto-sdk  
- **aauto-sdk-demo (martoreto):** https://github.com/martoreto/aauto-sdk-demo — example `MainCarActivity` and `CarActivityService` usage  
- **XDA – Unofficial Android Auto SDK:** https://forum.xda-developers.com/t/unofficial-android-auto-sdk-custom-apps.3693708/  
- **EvalEx (expressions):** https://ezylang.github.io/EvalEx/

---

*Document generated from research on aa-torque and aauto-sdk for the openRS project. Last updated: 2025-03.*
