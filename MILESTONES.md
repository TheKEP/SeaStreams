# MILESTONES.md — SeaStreams Stepwise Execution Roadmap

## Execution Protocol
- Run **one milestone per fresh Claude Code session** (`/clear` or restart `claude` between milestones) to prevent context window degradation.
- Every milestone ends with `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` passing with zero errors.
- Reference `CLAUDE.md`, `PRD.md`, `ARCHITECTURE.md`, and `docs/ui-references/` in every session.

---

## Milestone 1: Foundation, Database, Caching & Age Engine
**Prompt for Claude Code:**
> Read `CLAUDE.md`, `PRD.md`, and `ARCHITECTURE.md`. Execute **Milestone 1**:
> 1. Configure `libs.versions.toml` and `app/build.gradle.kts` with Jetpack Compose for TV (`tv-material`, `tv-foundation`), Hilt, Room, DataStore, Ktor Client + CIO Embedded Server, Media3 ExoPlayer, Coil 3, ZXing (for QR code generation), and JUnit5/MockK/Turbine.
> 2. Implement the Room database (`SeaStreamsDatabase`), all 6 DAOs/Entities, and `SettingsDataStore`.
> 3. Implement `NetworkModule` with OkHttp disk caching (6–12h TTL) and an HTTP `429` exponential backoff interceptor.
> 4. Implement `TmdbApiService`, `MdbListApiService` (appending `/json` to MDBList URLs), and `AgeRestrictionEngine` (supporting rating ceilings + allow/disallow unrated toggle).
> 5. Write comprehensive JUnit5 unit tests covering: (a) 6–12h cache hit/miss behavior, (b) HTTP 429 retry logic, and (c) `AgeRestrictionEngine` blocking PG-13/R/TV-MA and unrated content on Kids profiles.
> Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` until both pass with zero errors.

**Acceptance Criteria:**
- Project compiles cleanly and all unit tests pass.
- `/external_ids` is strictly single-item lookup only (no batch endpoints).
- Kids age filter blocks out-of-range ratings and respects `allowUnrated`.

---

## Milestone 2: Embedded LAN Web Portal & Onboarding / Profile Gate
**Prompt for Claude Code:**
> Read `CLAUDE.md`, `PRD.md`, and `ARCHITECTURE.md`. Execute **Milestone 2**:
> 1. Build `LocalPortalServer` (Ktor CIO on port `8765`) and `PortalHtmlTemplate.kt` serving a clean mobile-friendly web UI accessible from phones/PCs on the local network.
> 2. Support the following Web Portal endpoints and UI forms:
>    - Set/validate TMDB API key and create/edit user profiles.
>    - Add Stremio addons via `manifest.json` URL, plus remove, rename, enable/disable, and reorder existing addons.
>    - Import `mdblist.com` URLs directly into a profile's `Movies` or `TV Shows` Library as a one-time batch import (no custom MDBList catalog rows on `Home`, `Movies`, or `TV Shows`, and no ongoing list change tracking or syncing).
> 3. Seed the database on first launch with built-in TMDB metadata and a default enabled OpenSubtitles Stremio addon.
> 4. Build `ProfileCreateScreen.kt` (triggered when 0 profiles exist, displaying a live QR code + LAN IP URL and observing Room so entering the TMDB key on a phone automatically unlocks the "Continue" button on TV) and `ProfileSelectScreen.kt` (matching `docs/ui-references/profiles.jpg`, max 5 profiles, with Kids age-restriction setup).
> 5. Write unit tests for Stremio `manifest.json` parsing, addon reordering, and portal form submission. Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.

**Acceptance Criteria:**
- App boots into `ProfileCreateScreen` when no profiles exist and blocks continuing until a valid TMDB API key is saved.
- Scanning the QR code or visiting `http://<tv-ip>:8765` on a phone loads the Web Portal and updates the TV screen reactively.

---

## Run audits after every 2 milestones
**Prompt for Claude Code:**
> Perform a strict codebase audit. Check for: (1) any file exceeding 300 lines that should be split, (2) duplicated logic, (3) any network/disk calls on the Main thread, (4) memory leaks in ExoPlayer or Ktor server lifecycles, and (5) unused dependencies or dead code. Output a prioritized list of fixes, then execute them and verify ./gradlew testDebugUnitTest passes.

---

## Milestone 3: Top Navigation Bar, Home Screen & Expanding Catalog Rows
**Prompt for Claude Code:**
> Read `CLAUDE.md`, `PRD.md`, and `ARCHITECTURE.md`, and inspect `docs/ui-references/home-hero.jpg`, `home-genres-continue.jpg`, `home-catalogs.jpg`, `catalog1.jpg`, and `catalog2.jpg`. Execute **Milestone 3**:
> 1. Build `TopNavBar.kt` (top-left profile avatar + live clock; top-right pill bar with Search, `Home`, `Movies`, `TV Shows`, `Library`, Settings; hide `Movies` & `TV Shows` on Kids profiles).
> 2. Implement global D-pad navigation rules:
>    - Pressing `Back` anywhere on Home moves focus to `TopNavBar` defaulting to `Home`.
>    - Pressing `Down` at the bottom-most row of any page wraps focus back to the top.
> 3. Build `HeroCarousel.kt`:
>    - Adult: first 15 of the official JustWatch Movies chart + first 15 of the JustWatch Shows chart (30), shuffled, capped at 10 items per Home load.
>    - Kids: first 15 of `snoak/trending-kids-movies` + first 15 of `tvgeniekodi/trending-kids-tv-shows` (30), strictly filtered by `AgeRestrictionEngine`, shuffled, capped at 10.
>    - Crisp `3dp` `#E5E9F0` focus ring around the hero card while it holds focus, matching the catalog cards (shared `ui/theme/FocusModifiers.kt` style).
>    - Lifecycle-aware continuous 5-second auto-advance (wraps #10 → #1), running even while focus is on the nav bar or rows; only Left/Right or a press on the hero resets the countdown; pauses on `ON_PAUSE`/`ON_STOP` or when leaving Home.
> 4. Build `ExpandingCatalogRow.kt`:
>    - Focused card smoothly transitions from 2:3 poster to 16:9 wide backdrop with title, rating %, year, genre, runtime, and synopsis underneath.
>    - Caps at 20 items + `View More` (`>>`) card at position 21 if >20 items exist; pressing `Right` past the end loops back to item #1.
> 5. Assemble `HomeScreen.kt` with Hero, `Continue Watching` (last 10 items, honoring auto-play toggle), Adult `Most Watched Movies`/`Shows`, or Kids rows (`Trending Kids Movies`, `Trending Kids Shows`, `Latest Family Movies`, `Latest Family Shows`), followed beneath those built-in rows by any catalogs provided by enabled Stremio metadata addons (and remove any custom MDBList catalog row UI/storage).
>    - Add a `Continue Watching` long-press (`onLongClick`) action menu with `Remove` (dismisses from row while preserving `WatchProgressDao` state so replaying brings it back) and `Clear progress` (removes from row and clears `WatchProgressDao` progress for that movie or single episode), restoring focus to the same card on `Back` and advancing focus to the item on the right (or left if at the end) after removal.
> Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.

**Acceptance Criteria:**
- Smooth 60fps D-pad horizontal scrolling with instant 2:3 -> 16:9 card expansion on focused items.
- Kids profiles never render `Movies` or `TV Shows` top nav tabs and only display age-approved Hero/Catalog rows.
- The Hero shows at most 10 shuffled items (from the top 15 + top 15 source pools), auto-advances every 5 seconds while Home is in the foreground regardless of where focus is, and shows the catalog-card focus ring when focused.

---

## Milestone 4: Movies, TV Shows, View More, Search & Library Screens
**Prompt for Claude Code:**
> Read `CLAUDE.md`, `PRD.md`, and `ARCHITECTURE.md`, and inspect `docs/ui-references/view-more.jpg`, `search-screen.jpg`, and `search-results.jpg`. Execute **Milestone 4**:
> 1. Build `MoviesScreen.kt` and `TvShowsScreen.kt` fetching `Popular` + toggleable streaming providers (`Netflix`, `Prime`, `Disney+`, `HBO Max`, `Hulu`, `Apple TV+`, `Paramount+`, `Peacock`) via TMDB `/discover` with 6–12h caching and `ExpandingCatalogRow`.
> 2. Build `ViewMoreScreen.kt` (5-column vertical grid displaying up to 100 standard 2:3 posters).
> 3. Build `SearchScreen.kt`:
>    - Top-left Microphone button (focused on screen entry, using in-app Android `SpeechRecognizer` only) + wide search bar + recent search chips with `X` clear-all button.
>    - Left hover-activated category sidebar (`Trending Movies` default, `Trending Shows`, and all TMDB movie genres) updating the 5-column poster grid on focus change.
> 4. Build `LibraryScreen.kt`:
>    - Left hover-activated filter (`All` [default], `Movies`, `TV Shows`), top Sort dropdown (`A-Z`, `Z-A`, `Last Added`, `Last Watched`, `Not Watched`, persisted per profile) + Genre filter, 5-column poster grid, and vertical A–Z quick-jump letter bar on the right.
> Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.

**Acceptance Criteria:**
- `View More`, `Search`, and `Library` grids use standard 2:3 posters only (no 16:9 card expansion).
- Search opens with D-pad focus on the Microphone icon and `Trending Movies` displayed on the right.
- Library opens with `All` selected and restores the profile's last-used sort order.

---

## Run audits after every 2 milestones
**Prompt for Claude Code:**
> Perform a strict codebase audit. Check for: (1) any file exceeding 300 lines that should be split, (2) duplicated logic, (3) any network/disk calls on the Main thread, (4) memory leaks in ExoPlayer or Ktor server lifecycles, and (5) unused dependencies or dead code. Output a prioritized list of fixes, then execute them and verify ./gradlew testDebugUnitTest passes.

---

## Milestone 5: Details Screen, Episodes Screen, Stream Resolver & Settings Screen
**Prompt for Claude Code:**
> Read `CLAUDE.md`, `PRD.md`, and `ARCHITECTURE.md`, and inspect `docs/ui-references/movie-page.jpg`, `show-page.jpg`, `episodes-page.jpg`, and `settings-example.jpg`. Execute **Milestone 5**:
> 1. **Details Screen (`DetailsScreen.kt`):**
>    - Build with edge-to-edge backdrop artwork, title/logo, rating %, year, up to 2 genres, certification badge, duration, synopsis, cast, and action buttons: `Play`, `Trailer`, `More episodes` (TV only), `Shuffle` (TV — picks a random episode across all seasons), and `Add to library` / `Remove from library`.
>    - **Library State & Duplicate Protection:** Dynamically display `Remove from library` when the item is already saved in the active profile's library (clicking removes it); display `Add to library` otherwise. Guard the repository/DAO against duplicate entries (`OnConflictStrategy.IGNORE` / existence check) so attempting to add an already-saved item never creates duplicates or UI glitches and shows a brief `"Already in library"` toast/snackbar.
>    - **Exact Focus Restoration:** Ensure pressing `Back` from `DetailsScreen` returns D-pad focus to the exact originating card/position on the previous screen (`Home` hero/row, `Movies`, `TV Shows`, `View More`, `Search`, or `Library`).
>    - **Long-Press Play Override:** When *Auto-play top stream* is enabled, a standard click on `Play` auto-plays the top stream, while a long-press (`onLongClick`) on `Play` opens the stream selection modal instead.
> 2. **Lazy IMDb ID Resolution, Stream Modal & External Player Launcher:**
>    - Call TMDB `/external_ids` only when entering `DetailsScreen` (cached 6–12h), then query all enabled Stremio stream/subtitle addons in parallel.
>    - Implement the stream selection modal, *Auto-play top stream* logic (respecting preferred auto-play addon setting), and `ExternalPlayerLauncher` using `ActivityResultContracts.StartActivityForResult`. Support targeting any selected package (populated dynamically via `PackageManager.queryIntentActivities` for `ACTION_VIEW` + `video/*`, covering Just Player, MX Player, Vimu, VLC, Kodi, and any other installed video player) OR launching `Intent.createChooser` when **Other (System Chooser)** is selected. Pass `position`/`startfrom` resume extras and `return_result = true`, parse returned `position`/`duration`/`end_by` to update `WatchProgressDao` accurately (with a safe elapsed-time fallback when players omit extras), and trigger **Auto-play next episode** on completion.
> 3. **Episodes Screen (`EpisodesScreen.kt`):**
>    - Left season selector pills + right episode list, blurring unwatched episode thumbnails when *Blur unwatched episode thumbnails* is enabled.
> 4. **General Settings Screen (`SettingsScreen.kt`):**
>    - Build the full Settings screen matching `docs/ui-references/settings-example.jpg` (left pane with centered gear icon + `Settings` label; right scrollable pane with categorized pill rows, subtext values, chevron indicators, toggle switches, and high-contrast light pill focus states).
>    - Wire all `SettingsDataStore` preferences: Addon Manager & LAN QR Web Portal trigger, Background Theme Picker, Blur Unwatched Thumbnails, Auto-Play Top Stream + Preferred Addon, **Auto-Play Next Episode** (toggle, **enabled/`true` by default**), Auto-Play from Continue Watching, Stream Fallback Timeout (`1s–10s`, default `5s`), External Player selection (ExoPlayer default, dynamically discovered installed video/* apps, or Other / System Chooser), **Convert Dolby Vision Profile 7 to HDR** (toggle, **enabled/`true` by default**), HDR White Dimming, Audio Passthrough + Dialogue Boost + Night Mode + Audio Delay, Subtitles Always On/Off + Preferred Language + Hide Non-Preferred + Clean SDH + Global Subtitle Styling, and Clear Library (with 5-second Undo).
>    - **Interactive Visual Background Color Picker:** Default app background to `#000000` (pure black). Provide visual color swatches (no hex text) for `#000000`, `#171535`, `#351529`, `#351515`, `#153535`, and `#151F35`. Focusing/hovering over a swatch live-previews the background color immediately; pressing `Enter`/`DPAD_CENTER` confirms and saves it (pressing `Back` cancels and restores the saved color).
>    - **Live Subtitle Styling Preview:** Clicking `Subtitle Styling` opens a dedicated sub-screen/panel with a pinned 16:9 widescreen preview stage at the top (displaying a cinematic high-contrast scene frame and a two-line sample subtitle rendered with the exact player subtitle style) and scrollable styling controls below (font size, bold, italic, visual text color swatches, outline/drop-shadow, and background opacity) that update the preview live as values change.
> 5. **Unit Tests:**
>    - Write unit tests for duplicate library protection, library button state toggling, DV Profile 7 and Auto-Play Next Episode default settings (`true`), background color persistence, and external player `ActivityResult` progress parsing (`position / duration` calculation, early exit vs. completion, and missing extras fallback).
> Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.

**Acceptance Criteria:**
- `/external_ids` is called only when opening `DetailsScreen`.
- Pressing `Back` from `DetailsScreen` restores D-pad focus to the exact item that opened it.
- Items already in the library display `Remove from library` on `DetailsScreen`, and duplicate add attempts display `"Already in library"` without side effects.
- Long-pressing `Play` opens the stream list even when auto-play is enabled.
- External players preserve accurate watch progress on exit and trigger Auto-Play Next Episode upon completion.
- `SettingsScreen.kt` matches `docs/ui-references/settings-example.jpg`, defaults background to `#000000`, previews swatch colors on D-pad focus and saves on `Enter`, provides a live 16:9 subtitle style preview stage, and defaults both *Convert Dolby Vision Profile 7 to HDR* and *Auto-Play Next Episode* to `true`.

---

## Automated UI & D-Pad Navigation Tests
**Prompt for Claude Code:**
> Write Compose UI Tests (createComposeRule()) that simulate D-pad key presses (Key.DirectionDown, Key.DirectionRight, Key.DirectionCenter, Key.Back) across the Home screen, Profile switcher, and Player HUD to guarantee no screen ever traps or loses D-pad focus.

---

## Milestone 6: Media3 ExoPlayer, Auto-Fallback, Audio DSP & Clean SDH Subtitles
**Prompt for Claude Code:**
> Read `CLAUDE.md`, `PRD.md`, and `ARCHITECTURE.md`. Execute **Milestone 6**:
> 1. **ExoPlayer Engine, Stream Auto-Fallback & Auto-Next Episode (`SeaStreamsPlayerManager.kt`):**
>    - Build `SeaStreamsPlayerManager` wrapping Media3 `ExoPlayer` with watch progress persistence (`WatchProgressDao`, resuming from saved position and marking watched at ≥90%).
>    - Implement the configurable **Stream Fallback Timer** (`1s–10s`, default `5s` from `SettingsDataStore`): if a stream fails to reach `STATE_READY` or throws a playback/decoder error within the timeout, automatically advance to and start the next stream in the resolved list.
>    - **Dolby Vision Profile 7 Conversion:** Read `convertDvProfile7ToHdr` from `SettingsDataStore` (default `true`). When enabled, intercept Dolby Vision Profile 7 (`dvhe.07` / `dvh1.07`) tracks in the Media3 `TrackSelector`/extractor pipeline so ExoPlayer falls back/strips to the HDR10 base layer (`hev1` / `hvc1`) instead of failing or rendering purple/green tint on unsupported displays.
>    - **Auto-Play Next Episode:** Read `autoPlayNextEpisode` from `SettingsDataStore` (default `true`). When a TV episode finishes (`>= 90%` watched in ExoPlayer, or `end_by == "playback_completion"` / `>= 90%` / elapsed-runtime completion returned from any external player), automatically resolve the next episode in sequence, fetch streams, and launch the **top stream result** (honoring the preferred auto-play addon setting, via ExoPlayer or the configured external player / system chooser). Early user exit (`< 90%`) saves resume progress without advancing.
> 2. **Audio DSP & Bluetooth Sync (`DialogueNightAudioProcessor.kt`):**
>    - Implement a custom Media3 `AudioProcessor` (active when HDMI Audio Passthrough is disabled in `SettingsDataStore`) supporting **Dialogue Boost** (`Off`, `+3 dB`, `+6 dB`, `+9 dB`, `+12 dB` vocal/center frequency gain), **Night Mode** (dynamic range compression), and **Audio Delay** (`-500ms` to `+500ms` in `25ms` steps).
> 3. **Subtitles & Clean SDH (`SdhSubtitleCleaner.kt` & `CleanSdhTextRenderer.kt`):**
>    - Implement `SdhSubtitleCleaner` (pure Kotlin regex/parser stripping bracketed/parenthetical sound cues like `[SIGHS]`, `(DOOR CREAKS)`, `♪`, and speaker labels like `JOHN:` in real time) and wire it into `CleanSdhTextRenderer` when Clean SDH is toggled on.
>    - Enforce global subtitle defaults (*Always On* / *Always Off*), sort the user's preferred subtitle & audio languages to the top of track lists, honor *Hide non-preferred subtitle languages*, and apply global subtitle styling from `SettingsDataStore`.
4. **Player UI, Remote Controls & Bottom OSD (`PlayerScreen.kt` & `PlayerOverlayControls.kt`):**
   - **Remote Key Handling:** Clicking `Enter`/`DPAD_CENTER` while media is playing pauses playback and opens the Player HUD; pressing `Up` or `Down` while playing continues playback and opens the Player HUD; pressing `Back` while the Player HUD or any sub-overlay is visible dismisses only that overlay/HUD without stopping playback; pressing `Back` while the Player HUD is hidden exits playback.
   - **Bottom OSD Action Bar & Non-Blocking Overlays:** Render the bottom control bar (alongside the seek bar and HDR White Dimming overlay) with the following items:
     - **Play/Pause:** Dynamic icon button switching between `Pause` (when playing) and `Play` (when paused).
     - **Next Episode:** Shown strictly when playing a TV episode (hidden for movies); advances to the next episode's top stream.
     - **Subtitles:** Opens an in-player overlay for track selection, *Clean SDH* toggle, and **session-only Subtitle Styling** overrides (resetting on player exit without overwriting General Settings).
     - **Subtitle Sync Offset (`-  0.0s  +`):** Rendered **only** when an external subtitle track is active (hidden when subtitles are off or an embedded track is selected). `-` slows subtitles down (`-0.1s` step) and `+` speeds subtitles up (`+0.1s` step) with `0.0s` displayed in between, bounded to `-60.0s..+60.0s` (session-only, resets on exit).
     - **Audio:** Opens an in-player overlay for audio track selection and **session-only** *Dialogue Boost*, *Night Mode*, and *Audio Delay* controls (never persisted to `SettingsDataStore`; General Settings audio preferences take priority on exit).
     - **Streams:** Opens an in-player overlay showing the current item's resolved stream list without exiting the player, allowing hot-swapping streams.
     - **Episodes:** Shown for TV episodes; opens an in-player season/episode browser overlay without exiting the player so the user can switch episodes directly.
     - **Info:** Opens an in-player technical metadata overlay showing stream result index/label, player engine (`Media3 ExoPlayer`), filename, video codec, resolution, bitrate, audio codec, channels, sample rate, audio language, subtitle name, subtitle language, and subtitle source (`Embedded` vs. `External`).
> 5. **In-App Trailer Playback — Full-Screen Details & Inline 16:9 Catalog Row Previews (`ResolveTrailerStreamUseCase.kt`, `OkHttpDownloader.kt`, `InlineTrailerPlayerManager.kt`, & `InlineTrailerOverlay.kt`):**
>    - Configure JitPack and `com.github.TeamNewPipe:NewPipeExtractor` in `libs.versions.toml` and `app/build.gradle.kts` (enabling `isCoreLibraryDesugaringEnabled = true` with `desugar_jdk_libs` if `minSdk < 33`), and initialize `NewPipe.init(OkHttpDownloader(okHttpClient))` in `SeaStreamsApp.onCreate()`.
>    - Implement `ResolveTrailerStreamUseCase` to extract the highest-quality adaptive video stream (`<= 2160p`) and best M4A audio stream from a TMDB YouTube trailer key on `Dispatchers.IO`.
>    - **Full-Screen Details Trailer (`PlaybackRequest.Trailer`):** Model playback inputs via a sealed `PlaybackRequest` (`PlaybackRequest.Media` vs. `PlaybackRequest.Trailer`) and wire `DetailsScreen`'s `Trailer` button to launch `PlayerScreen` with `PlaybackRequest.Trailer`. Ignore all inline trailer settings (`autoPlayTrailers`, `muteAutoPlayTrailers`, `trailerAutoPlayDelaySeconds`), always play full-screen with audio enabled (`volume = 1f`) inside built-in ExoPlayer using `MergingMediaSource(videoSource, audioSource)`, skip all `WatchProgressDao` writes, disable Stremio fallback timers and `autoPlayNextEpisode`, show a simplified trailer OSD (title, play/pause, seek bar only—no audio/subtitle track pickers or Clean SDH toggles), and pop back to `DetailsScreen` on `STATE_ENDED` (restoring D-pad focus to the `Trailer` button). Fall back silently to launching the external YouTube `ACTION_VIEW` intent only if `ResolveTrailerStreamUseCase` fails.
>    - **Inline 16:9 Catalog Row Auto-Play (`Home`, `Movies`, `TV Shows`):** Wire `autoPlayTrailers` (default `true`), `muteAutoPlayTrailers` (default `true`, visible/enabled only when `autoPlayTrailers` is `true`), and `trailerAutoPlayDelaySeconds` (`1..7`, default `2`, visible/enabled only when `autoPlayTrailers` is `true`) in `SettingsDataStore` and `SettingsScreen.kt` under `PLAYBACK`. Build `InlineTrailerPlayerManager` (single shared lifecycle-aware `ExoPlayer` instance) and `InlineTrailerOverlay.kt` (keeping `ExpandingCatalogRow.kt` under 350 lines) so hovering D-pad focus on a catalog row card for the configured delay plays its trailer inside the 16:9 artwork frame (excluding `HeroCarousel`, `Continue Watching`, `View More`, `Search`, and `Library`). Show a small white speaker-with-slash icon (`Icons.AutoMirrored.Rounded.VolumeOff`) in the top-right corner of the 16:9 artwork when playing muted (offset cleanly if the top-right watched checkmark badge is present). Cancel pending hover jobs and stop playback immediately on D-pad navigation, held-key repeat scrolling (`repeatCount > 0`), screen pause/stop, or navigation to `DetailsScreen`. On any extraction or playback error on a catalog row, fail silently and keep showing the normal 16:9 backdrop artwork as if trailers were disabled—never fall back to the external YouTube app.
> 6. **Unit Tests:**
>    - Write comprehensive JUnit5 unit tests covering: (a) stream auto-fallback timeout and error advancement, (b) DV Profile 7 to HDR10 track fallback when enabled vs. disabled, (c) `autoPlayNextEpisode` triggering on episode completion vs. early exit for both ExoPlayer and external players, (d) `SdhSubtitleCleaner` cue stripping edge cases, (e) `DialogueNightAudioProcessor` buffer/gain/delay behavior, and (f) `ResolveTrailerStreamUseCase` stream selection, full-screen trailer playback skipping `WatchProgressDao` writes with YouTube fallback on extraction error, `SettingsDataStore` inline trailer defaults/bounds (`1s–7s`), inline hover timer cancellation, and catalog row trailer errors reverting to static artwork without triggering YouTube fallback.
> Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.

**Acceptance Criteria:**
- Unresponsive or broken streams automatically advance to the next candidate stream within the configured `1s–10s` timeout.
- Dolby Vision Profile 7 streams play cleanly in HDR10 when the setting is enabled.
- Finishing a TV episode automatically resolves and plays the top stream for the next episode when *Auto-Play Next Episode* is enabled (both in ExoPlayer and external players), while exiting early saves resume progress without advancing.
- Dialogue Boost, Night Mode, and Audio Delay process PCM audio cleanly when passthrough is disabled, and Clean SDH strips hearing-impaired cues in real time.
- Session subtitle style edits in the Player OSD reset upon exiting playback while global Settings persist.
- Clicking `Trailer` on `DetailsScreen` plays the trailer full-screen with audio enabled inside the built-in ExoPlayer with a simplified HUD (ignoring inline trailer settings), records zero watch progress, returns focus to the `Trailer` button on completion or `Back`, and falls back silently to YouTube only if stream extraction fails.
- When *Auto-Play Trailers* is enabled, hovering on a `Home`, `Movies`, or `TV Shows` catalog card for the configured `1s–7s` delay (`2s` default) plays the trailer inside its 16:9 artwork frame, displays a top-right white muted speaker icon when muted, and silently reverts to normal 16:9 backdrop artwork without launching YouTube if playback or extraction fails.
- During media playback, `Enter` pauses and opens the HUD, `Up`/`Down` opens the HUD without pausing, `Back` dismisses open overlays/HUD first before exiting playback on a second `Back` press, and the bottom bar provides dynamic Play/Pause, episode-only Next Episode & Episodes browser, Subtitles overlay, external-subtitle-only `- 0.0s +` (`0.1s` steps, `-60.0s` to `+60.0s`), session-only Audio overlay (reverting to General Settings on exit), live Streams switcher overlay, and full technical Info overlay.
- `ProfileSelectScreen` featured backdrop artwork excludes Horror (`27`), Romance (`10749`), Thriller (`53`), and Crime (`80`) genres, never uses `Continue Watching` history, and enforces `PG`/`TV-PG` (`allowUnrated = false`) when any Kids profile exists (or `PG-13`/`TV-14` with `allowUnrated = false` otherwise).

---

## Run audits after every 2 milestones
**Prompt for Claude Code:**
> Perform a strict codebase audit. Check for: (1) any file exceeding 300 lines that should be split, (2) duplicated logic, (3) any network/disk calls on the Main thread, (4) memory leaks in ExoPlayer or Ktor server lifecycles, and (5) unused dependencies or dead code. Output a prioritized list of fixes, then execute them and verify ./gradlew testDebugUnitTest passes.

---

## Post-Launch Workflow (Every new feature or change)
1. Create a Git Branch First: Before making any changes to a working app, tell Claude Code:

**Prompt for Claude Code:**
> Create and switch to a new git branch called feat/theme-options (or fix/age-filter).

## If a change breaks your UI or D-pad focus, you can delete the branch and be right back at your rock-solid v1 build in two seconds.

2. Start a Fresh Session (/clear) + Use Plan Mode (Shift+Tab): Never pile new features onto an old session. Always let Claude inspect the existing files and propose a plan before modifying code.

3. Update Your Docs First: Tell Claude Code to update docs/PRD.md (and docs/DESIGN_SYSTEM.md if it's a visual change) before writing code so your documentation never drifts out of sync with reality.

4. Enforce Regression Testing: Require Claude to run all existing unit tests plus new tests for your change before merging back to main.

## Copy-Paste Prompt Templates for Post-Launch Changes

## Template A: Visual / UI Tweaks (e.g., Color Theme Options or Layout Changes)
> Switch to Plan Mode. Read CLAUDE.md, docs/DESIGN_SYSTEM.md, and the current theme/UI files in ui/theme/. I want to add [describe exact change, e.g., '3 selectable color themes: Midnight Blue, OLED Black, and Crimson Dark, persisted in DataStore and selectable in Settings']. Update docs/DESIGN_SYSTEM.md first, ensure D-pad focus rings remain high-contrast across all themes, and preserve 60fps scrolling performance. Show me your plan before editing files.

## Template B: Logic / Rule Changes (e.g., Modifying Age Restrictions)
> Switch to Plan Mode. Read CLAUDE.md, docs/ARCHITECTURE.md, and AgeFilterEngine along with its existing unit tests. I want to change how age restrictions work: [describe exact rule, e.g., 'allow PG-13 movies on a Kids profile only if they are in the Animation or Family genre, while still blocking all other PG-13/R/Unrated items']. Update the existing unit tests and add new test cases for every edge case of this rule. Show me your plan before editing files.

## Audits for small Tweaks
> Run ./gradlew testDebugUnitTest and ./gradlew lintDebug. Verify that no files exceed 300 lines, no new warnings were introduced, and summarize every file you modified before committing to git.

## After 3–5 post-launch features (or any big architectural addition like adding Trakt sync or a new player engine): Run the full Periodic Code & Bloat Audit in a fresh session. Repeated small edits are the #1 cause of duplicated helper functions, leftover unused UI components, and bloated ViewModel files over time.