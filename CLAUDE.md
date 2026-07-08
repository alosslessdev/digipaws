# Curbox

Curbox is an advanced screentime management tool for Android. It blocks apps, reels, keywords, and UI elements using a single accessibility service. Gradle modules: `:app` (the app) and `:apitester` (sample client for the Curbox API). Written in Kotlin, classic Views + Fragments + ViewBinding. **No Jetpack Compose anywhere.**

# Code style
- Prioritize readability over cleverness. Ask clarifying questions before making architectural changes.
- Comments only when absolutely necessary or when documenting behavior that the code cannot show.
- JVM target 1.8. Follow the existing style of whatever file you touch.

# Build, install, test
```
./gradlew assembleFullDebug            # also: assemblePlaystoreDebug, assembleFdroidDebug
./gradlew installAndGrantAccessibilityFullDebug   # custom task: installs, grants accessibility via adb, launches the app
./gradlew testFullDebugUnitTest        # unit tests (CryptoBoxTest, uihider ScriptLanguageTest)
```
Lint is configured to never abort the build. Debug builds use applicationId suffix `.debug` and the name "Debug Curbox".

# Hard invariants (never break these)
1. **AppBlockerService must never crash.** Every feature call inside the service is wrapped in try/catch that logs via `CrashLogger.logNonFatalError` and swallows the error. Keep that pattern for any code you add there. Only `CancellationException` is rethrown.
2. **The app runs in multiple processes.** The UI runs in the main process, `AppBlockerService` runs in `:app_blocker_service`, and `CrashLogActivity` runs in `:crash_handler`. Because of this:
   - Room must always be accessed through `AppDatabase.getInstance()` (it uses `enableMultiInstanceInvalidation()`).
   - Settings must always be accessed through `DataStoreManager` (`utils/DataStore.kt`), which uses `MultiProcessDataStoreFactory` behind a double-checked singleton. Never create another DataStore for the same file; that crashes with "multiple DataStores active for the same file".
   - Anything initialized in `Curbox` (the Application class) with `isMainProcess()` does NOT run in the service process. Sync only initializes in the main process.
3. **Never traverse UI nodes in `onAccessibilityEvent`.** Cheap checks (app blocking, grayscale, focus mode, trackers) run inline. Heavy features that walk the node tree (ReelBlocker, UiHider, KeywordBlocker's browser check) consume events from a `Channel.CONFLATED` in a background worker. Events are copied with `AccessibilityEvent.obtain(event)` and must always be `recycle()`d, including on send failure.
4. **`Settings` is serialized with Gson.** Every field in `data/models/Settings.kt` must have a default value so old JSON on disk deserializes cleanly. Renaming a field silently loses the stored value.
5. **Room uses `fallbackToDestructiveMigration()`.** Changing any entity requires bumping the version in `AppDatabase.kt`, and it wipes user data. Mention this tradeoff before doing it.
6. **Gate optional features with BuildConfig flags** (see Build variants below) before surfacing them in UI, services, or the Curbox API.

# Architecture
One accessibility service, `services/AppBlockerService.kt` (extends `services/BaseBlockingService.kt`), hosts every blocker and tracker. `BaseBlockingService` provides the foreground notification, a service protection heartbeat, and the global actions `pressHome()` / `pressBack()` plus `isDelayOver()`.

Each feature is a compartmental plain class with this lifecycle, all wired in `AppBlockerService`:
- Instantiated as a field of the service.
- `setupX(service)` called in `onServiceConnected()` (loads config, gets DAOs).
- `setupReceivers()` called right after (registers its broadcast receivers).
- `doXCheck(event)` / `onEvent(event)` called per accessibility event.
- `removeReceivers()` / `onDestroy()` called in the service's `onDestroy()`.

**UI process → service process communication is via broadcasts.** Each feature declares its intent actions as companion constants, e.g. `AppBlocker.INTENT_ACTION_REFRESH_APP_BLOCKER` = `neth.iecal.curbox.refresh.appblocker`. A settings screen writes config through `DataStoreManager`, then sends the feature's refresh broadcast (some features instead collect the `dataStoreManager.settings` flow; follow whichever pattern the feature already uses).

## Blockers (`blockers/`)
- `AppBlocker.kt`: blocks apps by schedule or usage limit. Keeps `ConcurrentHashMap`s of blocked/cooldown packages; a package can be in several groups and blocks if any group demands it.
- `ReelBlocker.kt`: blocks Instagram reels, YouTube shorts, Facebook reels (heavy, runs in worker).
- `KeywordBlocker.kt`: blocks websites/regex/keywords. It does NOT scan the screen. It observes the website stats Room table (invalidation tracker) that `WebsiteUsageTracker` writes, and decides whether to block.
- `FocusModeBlocker.kt`: temporarily blocks apps and keywords for a set duration.
- `BrowserBlocker.kt`, `AntiUninstallBlocker.kt` (device admin via `receivers/AdminReceiver`, shared logic in `utils/AntiUninstallManager.kt`).
- `uihider/`: hides specific UI elements with an overlay. Contains a full custom scripting language (Lexer, Parser, Interpreter, Budget) under `uihider/script/`, covered by `ScriptLanguageTest`. Node picking runs through `services/NodePickerService`.
- Blocking decisions that need a user-facing screen go through `ui/activity/WarningActivity` with `Constants.WARNING_SCREEN_MODE_*`.

## Trackers (`trackers/`, run inside AppBlockerService)
- `AppUsageTracker.kt`: app usage analytics into Room.
- `WebsiteUsageTracker.kt`: reads browser URL bars (IDs from `hardcoded/BrowserUrlBarIds.kt`), writes website analytics into Room, feeds KeywordBlocker. Has a 15s heartbeat because some browsers never fire URL change events.
- `ReelsCountTracker.kt`: counts short-form videos scrolled.

## Anti stimulants (`anti_stimulants/`)
`AutoDnd`, `GrayScaleFilter`, `MindfulMessage`. Same compartment pattern, also hosted in AppBlockerService. DND state is combined via `AppBlockerService.syncDndState()`.

## Hardcoded (`hardcoded/`)
All hardcoded view IDs and per-app configs live here, never inline in features: `BrowserUrlBarIds.kt` (`URL_BAR_ID_LIST`), `ReelAppConfig.kt` (reel apps and mods), `UiHiderSamples.kt`, `OemAutostartIntents.kt`.

## Service protection
`ServiceWatchdogJob`, `utils/ServiceProtectionManager`, `receivers/BootReceiver`, and the heartbeat in `BaseBlockingService` keep the service alive and detect it being killed. Anti uninstall UI is under `ui/fragments/main/reducers/advanced/`.

## Curbox API (`api/`)
An exported AIDL bound service (`CurboxApiService`, contract in `app/src/main/aidl/`) that other apps bind to, Shizuku style, with per-app user approval (`ApiPermissionActivity`, `ApiAuthStore`). Integration guide: `CURBOX_API.md`. Working client: the `:apitester` module. API responses must respect the BuildConfig feature flags.

# Data
- `data/models/`: plain data classes, including `Settings.kt`, the single Gson-serialized DataStore object. To add a setting: add a defaulted field to `Settings`, add an `updateX()` helper in `DataStoreManager`, then wire UI + feature refresh.
- **Settings change delay:** the blocker/anti-stimulant `updateX()` methods in `DataStoreManager` go through `updateGated()`. When the user enables the delay (`SettingsChangeDelayConfig`), a write that weakens a restriction (per `utils/RestrictionComparator`, conservative: unprovable = weaker) is NOT applied; it is parked as a `PendingSettingsChange` and applied later by `applyDuePendingChanges()` (called from the service heartbeat, app start, and the delay screen). Stricter writes apply instantly and drop that field's pending change. New gated fields need: a `GatedSettingsField` entry, a `withFieldValue` branch, a `RestrictionComparator` case, and a label in `SettingsChangeDelayFragment`. UI is `reducers/advanced/SettingsChangeDelayFragment`.
- `data/db/`: all Room objects (entities, DAOs, `AppDatabase`). Room stores large or growing data (usage stats, reel stats, focus stats, intent logs); DataStore stores configuration.
- `utils/UsageStatsCleaner.kt` purges old local usage rows; `supabase/migrations/` holds backend SQL (pg_cron purge job) applied with the Supabase MCP/CLI, not part of the app build.

# Build variants
Three product flavors in the "version" dimension (`app/build.gradle.kts`):
- **full**: every feature including cross device sync.
- **playstore**: sync, but no UI hider and no anti uninstall. Its manifest (`app/src/playstore/AndroidManifest.xml`) strips AdminReceiver (device admin) and NodePickerService with `tools:node="remove"`. Sync is free here, same as full; `SyncEntitlement` is a free stub in `src/sync` (billing was removed temporarily, July 2026), so no build compiles Play Billing.
- **fdroid**: every feature except sync. No INTERNET permission, no Firebase, no Supabase code compiled in. CI release workflows build this flavor.

BuildConfig flags: `SUPPORTS_UI_HIDER` and `SUPPORTS_ANTI_UNINSTALL` (true by default, false in playstore), `FDROID_VARIANT` (gates sync UI), `SYNC_USE_FCM` (staging switch for the FCM push migration in the sync flavors).

Source sets: `app/src/sync/java` is added as an extra source dir to full and playstore only. `app/src/fdroid/java` holds the offline stub `SyncProviderFactory` that returns `NoopSyncProvider`. If you add a class under `src/sync`, the fdroid build must still compile without it.

# Sync (`src/sync` + `data/sync` in main)
- The interface `SyncProvider` and `SyncGateway` live in main so all flavors compile; the real `PlaystoreSyncProvider` lives in `src/sync`. `SyncGateway.init()` is called from the `Curbox` Application class, main process only.
- Data is end-to-end encrypted with `CryptoBox` (unit tested). `SupabaseRest` is a hand-rolled REST client over OkHttp (no Supabase SDK). `RealtimeClient` (websocket), `FcmPush` + `CurboxMessagingService` (Firebase initialized manually from `FcmConfig`, so no google-services.json or plugin), `SyncWorker` (WorkManager), `SecureKeyStore`.
- Remote usage from other devices shows up in usage UI via `remoteAppUsage()` / `remoteWebsiteUsage()` and the sentinel package `SYNCED_WEB_PACKAGE`.

# UI
- Classic Views: `FragmentActivity` hosts fragments with manual `supportFragmentManager` transactions and a bottom nav. ViewBinding everywhere. No Compose, no Navigation component.
- Feature settings screens live under `ui/fragments/main/reducers/<feature>/`, usually Fragment + ViewModel pairs. Shared blocker settings UIs are in `reducers/blockertools/shared/`.
- Android dynamic colors (`DynamicColors.applyToActivitiesIfAvailable`). Use default Material values for text, button colors etc. Mix of ascii art, typography, minimalist material design.
- Fonts: Coolvetica for extreme typographical hooky screens (onboarding), Inter for most of the app.
- All user-facing text goes in `res/values/strings.xml` (translations exist, e.g. `values-it`).

## UX
- Doesn't overwhelm users; calming and peaceful; smooth animations for views resizing, appearing, disappearing.

## Writing user displayed text
- Never use dashes(-) anywhere
- Keep simple language at a 6th grader level
- Speak in first person
- Be crisp and concise
- Explain with real world examples if too complex
- The reader is not tech savvy

# Recipes
**Add a new blocker or tracker:**
1. Create the class in `blockers/` or `trackers/` following the compartment lifecycle above; declare its `INTENT_ACTION_*` constants in a companion object.
2. Instantiate it in `AppBlockerService`; call setup + `setupReceivers()` in `onServiceConnected()`, cleanup in `onDestroy()`, and its check in `onAccessibilityEvent` (cheap) or the background worker loop (node traversal). Wrap in the existing try/catch blocks.
3. Add its config to `Settings.kt` + `DataStoreManager`, and its UI under `ui/fragments/main/reducers/`.
4. If the feature is variant-restricted, gate every entry point with the BuildConfig flag.

**Add support for a new browser or app mod:** edit `hardcoded/BrowserUrlBarIds.kt` or `hardcoded/ReelAppConfig.kt` (copy the closest existing entry, change the package name). Details in CONTRIBUTING.md.

**Change the database:** edit or add the entity + DAO in `data/db/`, register it in `AppDatabase`, bump the version. Remember migration is destructive.

# Other docs
`CONTRIBUTING.md` (contributor workflows), `CURBOX_API.md` (API integration guide), `Readme.md`.
