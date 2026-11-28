# MILESTONES.md — SeaStreams Stepwise Execution Roadmap

## Execution Protocol
- Run **one milestone per fresh Claude Code session** (`/clear` or restart `claude` between milestones) to prevent context window degradation.
- Every milestone ends with `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` passing with zero errors.
- Reference `CLAUDE.md`, `PRD.md`, `ARCHITECTURE.md`, and `docs/ui-references/` in every session.

---

## Run this audit after every milestone in a fresh session
**Prompt for Claude Code:**
> Switch to Plan Mode. Read `CLAUDE.md`, `PRD.md`, and `ARCHITECTURE.md`. Perform a strict codebase audit. Check for:
> 1. Any Kotlin file exceeding 350 lines that should be split,
> 2. Duplicated logic,
> 3. Any network, socket, or disk I/O running on the Main thread,
> 4. Resource and lifecycle leaks: unclosed sockets/NIO channels, leaked worker threads/coroutines (including `DeviceAuthSessions` polling jobs, `PendingSourceClear` undo timers, and `InlineTrailerController` hover jobs), unreturned pooled buffers (`SegmentBufferPool`/`SegmentArena`), unclosed 7z/crypto resources (`SevenZipAesData`, `BufferedSha256`), unbounded in-memory caches (`DebridLibraryCache`, `DebridStreamRegistry`, `DebridLinkResolver`, `ReleaseHealthCache`, `AutoSyncSession`), or missing cancellation/cleanup in `NntpEngine`, `WarmConnections`, `UsenetSessionRegistry`, `ParallelRangeReader`, `SubtitleAutoSync`, `DebridTokenRefresher`, `InlineTrailerPlayerManager`, `ExoPlayer`, or Ktor server/lease lifecycles,
> 5. Secret/security hygiene (no plain-text passwords, NZB archive passwords, API keys, OAuth/refresh tokens, client secrets, or token-bearing resolved Debrid URLs in logs, exception/failure messages, persisted `last_used_streams`, standard portal state responses, or backup exports when `includeCredentials` is false — intentional credential inclusion when `includeCredentials` is true is expected; Debrid requests stay library-only per `DebridLibraryOnlyGuardTest`; all portal routes enforce trusted-host, content-type, and body-size guards) and non-null `!!` assertions, and
> 6. Unused dependencies or dead code.
>
> Output a prioritized list of fixes and wait for my approval before editing files. Once approved, execute them, update `ARCHITECTURE.md` (and `PRD.md` if behavior or settings drifted) if any files were split, moved, or changed, and verify both `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` pass with zero errors.

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
> 3. Seed the database on first launch with built-in TMDB metadata and a default enabled OpenSubtitles Stremio addon. *(Superseded: addons are now per profile, so every new profile is seeded with its own copy of these defaults, and the portal edits one selected profile's addons. See PRD §8.)*
> 4. Build `ProfileCreateScreen.kt` (triggered when 0 profiles exist, displaying a live QR code + LAN IP URL and observing Room so entering the TMDB key on a phone automatically unlocks the "Continue" button on TV) and `ProfileSelectScreen.kt` (matching `docs/ui-references/profiles.jpg`, max 5 profiles, with Kids age-restriction setup).
> 5. Write unit tests for Stremio `manifest.json` parsing, addon reordering, and portal form submission. Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.

**Acceptance Criteria:**
- App boots into `ProfileCreateScreen` when no profiles exist and blocks continuing until a valid TMDB API key is saved.
- Scanning the QR code or visiting `http://<tv-ip>:8765` on a phone loads the Web Portal and updates the TV screen reactively.

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
>    - Wire all `SettingsDataStore` preferences: Addon Manager & LAN QR Web Portal trigger, Background Theme Picker, Blur Unwatched Thumbnails (default `false`), Auto-Play Top Stream (default `true`) + Preferred Addon (default *First enabled addon*), **Auto-Play Next Episode** (toggle, **enabled/`true` by default**), Auto-Play from Continue Watching (default `true`), Stream Fallback Timeout (`10s` / `15s` / `20s`, default `10s`), External Player selection (ExoPlayer default, dynamically discovered installed video/* apps, or Other / System Chooser), **Convert Dolby Vision Profile 7 to HDR** (toggle, **enabled/`true` by default**), HDR White Dimming (default `true`), Audio Passthrough (default `true`) + Dialogue Boost (Off) + Night Mode (`false`) + Audio Delay (`0ms`), Subtitles Always On/Off (default Always Off) + Preferred Language (English) + Hide Non-Preferred (default `true`) + Clean SDH (default `true`) + Global Subtitle Styling (100%, bold, not italic, white, outline, 0% background), and Clear Library (with 5-second Undo).
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
>    - Implement the configurable **Stream Fallback Timer** (`10s` / `15s` / `20s`, default `10s` from `SettingsDataStore`): if a stream fails to reach `STATE_READY` or throws a playback/decoder error within the timeout, automatically advance to and start the next stream in the resolved list. *(Post-launch: gated by the per-profile **Automatic Stream Failover** toggle, default `ON`; when off there is no timer and a failed stream stops with the Streams overlay open.)*
>    - **Dolby Vision Profile 7 Conversion:** Read `convertDvProfile7ToHdr` from `SettingsDataStore` (default `true`). When enabled, intercept Dolby Vision Profile 7 (`dvhe.07` / `dvh1.07`) tracks in the Media3 `TrackSelector`/extractor pipeline so ExoPlayer falls back/strips to the HDR10 base layer (`hev1` / `hvc1`) instead of failing or rendering purple/green tint on unsupported displays.
>    - **Auto-Play Next Episode:** Read `autoPlayNextEpisode` from `SettingsDataStore` (default `true`). When a TV episode plays to its end (ExoPlayer `STATE_ENDED`, or `end_by == "playback_completion"` from an external player — for players without `end_by`, such as VLC, a reported position at the very end), automatically resolve the next episode (in sequence, or following the active shuffle session), fetch streams, and launch the **top stream result** (honoring the preferred auto-play addon setting, via ExoPlayer or the configured external player / system chooser). A user `Back` exit in any player, at any position (including `>= 90%`), saves progress (watched at `>= 90%`) and never advances.
    - **Playback Buffer Profile & Parallel HTTP Range Reader (`ParallelRangeDataSource.kt`):** Wire `playbackBufferProfile` (`STANDARD`, `LARGE` [default], `MAX`) and `parallelStreamConnections` (`1..4`, default `4`) in `SettingsDataStore` and `SettingsScreen.kt` under `PLAYBACK`. Configure `DefaultLoadControl` + `DefaultAllocator` in `SeaStreamsPlayerManager` (`Standard`: 50s / 64 MB; `Large`: 150s min / 150s max / 5s rebuffer / 150 MB target; `Max`: 300s min / 300s max / 8s rebuffer / 250 MB target). Build `ParallelRangeDataSource` wrapping `OkHttpDataSource` to prefetch and assemble 2–4 concurrent `Range: bytes=start-end` chunks (sliding-window read-ahead ring bounded by the buffer profile's 24/64/128 MB high-water mark, 4 MB start-up chunks then 8 MB chunks, deterministic cancellation on seek/close, and automatic single-stream fallback when the server does not return `206 Partial Content`), while tracking rolling 2-second download throughput (`Mbps`) and buffer health for the `Info` overlay.
> 2. **Audio DSP & Bluetooth Sync (`DialogueNightAudioProcessor.kt`):**
>    - Implement a custom Media3 `AudioProcessor` (active when HDMI Audio Passthrough is disabled in `SettingsDataStore`) supporting **Dialogue Boost** (`Off`, `+3 dB`, `+6 dB`, `+9 dB`, `+12 dB` vocal/center frequency gain), **Night Mode** (dynamic range compression), and **Audio Delay** (`-500ms` to `+500ms` in `25ms` steps).
> 3. **Subtitles & Clean SDH (`SdhSubtitleCleaner.kt` & `CleanSdhTextRenderer.kt`):**
>    - Implement `SdhSubtitleCleaner` (pure Kotlin regex/parser stripping bracketed/parenthetical sound cues like `[SIGHS]`, `(DOOR CREAKS)`, `♪`, and speaker labels like `JOHN:` in real time) and wire it into `CleanSdhTextRenderer` when Clean SDH is toggled on.
>    - Enforce global subtitle defaults (*Always On* / *Always Off*), sort the user's preferred subtitle & audio languages to the top of track lists, honor *Hide non-preferred subtitle languages*, and apply global subtitle styling from `SettingsDataStore`.
4. **Player UI, Remote Controls & Bottom OSD (`PlayerScreen.kt` & `PlayerOverlayControls.kt`):**
   - **Remote Key Handling:** Clicking `Enter`/`DPAD_CENTER` while media is playing pauses playback and opens the Player HUD; pressing `Up` or `Down` while playing continues playback and opens the Player HUD; pressing `Back` once inside a sub-overlay closes only that overlay and refocuses its bar button (`Play/Pause` if that button is hidden); pressing `Back` once on the main Player HUD dismisses only the HUD without stopping playback; pressing `Back` once while the HUD is hidden exits playback (media and trailers alike).
   - **Bottom OSD Action Bar & Non-Blocking Overlays:** Render the bottom control bar (alongside the seek bar and HDR White Dimming overlay) with the following items:
     - **Play/Pause:** Dynamic icon button switching between `Pause` (when playing) and `Play` (when paused).
     - **Next Episode:** Shown strictly when playing a TV episode (hidden for movies); advances to the next episode's top stream.
     - **Subtitles:** Opens an in-player overlay for track selection, *Clean SDH* toggle, and **session-only Subtitle Styling** overrides (resetting on player exit without overwriting General Settings).
     - **Subtitle Sync Offset (`-  0.0s  +`):** Rendered **only** when an external subtitle track is active (hidden when subtitles are off or an embedded track is selected). `-` slows subtitles down (`-0.1s` step) and `+` speeds subtitles up (`+0.1s` step) with `0.0s` displayed in between, bounded to `-60.0s..+60.0s` (session-only, resets on exit).
     - **Audio:** Opens an in-player overlay for audio track selection and **session-only** *Dialogue Boost*, *Night Mode*, and *Audio Delay* controls (never persisted to `SettingsDataStore`; General Settings audio preferences take priority on exit).
     - **Streams:** Opens an in-player overlay showing the current item's resolved stream list without exiting the player, allowing hot-swapping streams.
     - **Episodes:** Shown for TV episodes; opens an in-player season/episode browser overlay without exiting the player so the user can switch episodes directly.
     - **Info:** Opens an in-player technical metadata overlay showing stream result index/label, player engine (`Media3 ExoPlayer`), filename, video codec, resolution, bitrate, audio codec, channels, sample rate, audio language, subtitle name, subtitle language, subtitle source (`Embedded` vs. `External`), plus live download throughput (`Mbps`), active parallel range connection count, and live buffer health (`buffered seconds` & `buffered MB`).
> 5. **In-App Trailer Playback — Full-Screen Details & Inline 16:9 Catalog Row Previews (`ResolveTrailerStreamUseCase.kt`, `OkHttpDownloader.kt`, `InlineTrailerPlayerManager.kt`, & `InlineTrailerOverlay.kt`):**
>    - Configure JitPack and `com.github.TeamNewPipe:NewPipeExtractor` in `libs.versions.toml` and `app/build.gradle.kts` (enabling `isCoreLibraryDesugaringEnabled = true` with `desugar_jdk_libs` if `minSdk < 33`), and initialize `NewPipe.init(OkHttpDownloader(okHttpClient))` in `SeaStreamsApp.onCreate()`.
>    - Implement `ResolveTrailerStreamUseCase` to extract the highest-quality adaptive video stream (`<= 2160p`) and best M4A audio stream from a TMDB YouTube trailer key on `Dispatchers.IO`.
>    - **Full-Screen Details Trailer (`PlaybackRequest.Trailer`):** Model playback inputs via a sealed `PlaybackRequest` (`PlaybackRequest.Media` vs. `PlaybackRequest.Trailer`) and wire `DetailsScreen`'s `Trailer` button to launch `PlayerScreen` with `PlaybackRequest.Trailer`. Ignore all inline trailer settings (`autoPlayTrailers`, `muteAutoPlayTrailers`, `trailerAutoPlayDelaySeconds`), always play full-screen with audio enabled (`volume = 1f`) inside built-in ExoPlayer using `MergingMediaSource(videoSource, audioSource)`, skip all `WatchProgressDao` writes, disable Stremio fallback timers and `autoPlayNextEpisode`, show a simplified trailer OSD (title, play/pause, seek bar only—no audio/subtitle track pickers or Clean SDH toggles), and pop back to `DetailsScreen` on `STATE_ENDED` (restoring D-pad focus to the `Trailer` button). Fall back silently to launching the external YouTube `ACTION_VIEW` intent only if `ResolveTrailerStreamUseCase` fails.
>    - **Inline 16:9 Catalog Row Auto-Play (`Home`, `Movies`, `TV Shows`):** Wire `autoPlayTrailers` (default `true`), `muteAutoPlayTrailers` (default `true`, visible/enabled only when `autoPlayTrailers` is `true`), and `trailerAutoPlayDelaySeconds` (`1..7`, default `2`, visible/enabled only when `autoPlayTrailers` is `true`) in `SettingsDataStore` and `SettingsScreen.kt` under `PLAYBACK`. Build `InlineTrailerPlayerManager` (single shared lifecycle-aware `ExoPlayer` instance) and `InlineTrailerOverlay.kt` (keeping `ExpandingCatalogRow.kt` under 350 lines) so hovering D-pad focus on a catalog row card for the configured delay plays its trailer inside the 16:9 artwork frame (excluding `HeroCarousel`, `Continue Watching`, `View More`, `Search`, and `Library`). Show a small white speaker-with-slash icon (`Icons.AutoMirrored.Rounded.VolumeOff`) in the top-right corner of the 16:9 artwork when playing muted (offset cleanly if the top-right watched checkmark badge is present). Cancel pending hover jobs and stop playback immediately on D-pad navigation, held-key repeat scrolling (`repeatCount > 0`), screen pause/stop, or navigation to `DetailsScreen`. On any extraction or playback error on a catalog row, fail silently and keep showing the normal 16:9 backdrop artwork as if trailers were disabled—never fall back to the external YouTube app.
> 6. **Unit Tests:**
>    - Write comprehensive JUnit5 unit tests covering: (a) stream auto-fallback timeout and error advancement, (b) DV Profile 7 to HDR10 track fallback when enabled vs. disabled, (c) `autoPlayNextEpisode` triggering on episode completion vs. early exit for both ExoPlayer and external players, (d) `SdhSubtitleCleaner` cue stripping edge cases, (e) `DialogueNightAudioProcessor` buffer/gain/delay behavior, and (f) `ResolveTrailerStreamUseCase` stream selection, full-screen trailer playback skipping `WatchProgressDao` writes with YouTube fallback on extraction error, `SettingsDataStore` inline trailer defaults/bounds (`1s–7s`), inline hover timer cancellation, and catalog row trailer errors reverting to static artwork without triggering YouTube fallback.
> Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.

**Acceptance Criteria:**
- Unresponsive or broken streams automatically advance to the next candidate stream within the configured `10s` / `15s` / `20s` timeout.
- Dolby Vision Profile 7 streams play cleanly in HDR10 when the setting is enabled.
- Finishing a TV episode automatically resolves and plays the top stream for the next episode when *Auto-Play Next Episode* is enabled (both in ExoPlayer and external players), while exiting with `Back` (at any position) saves progress without advancing.
- Dialogue Boost, Night Mode, and Audio Delay process PCM audio cleanly when passthrough is disabled, and Clean SDH strips hearing-impaired cues in real time.
- Session subtitle style edits in the Player OSD reset upon exiting playback while global Settings persist.
- Clicking `Trailer` on `DetailsScreen` plays the trailer full-screen with audio enabled inside the built-in ExoPlayer with a simplified HUD (ignoring inline trailer settings), records zero watch progress, returns focus to the `Trailer` button on completion or `Back`, and falls back silently to YouTube only if stream extraction fails.
- When *Auto-Play Trailers* is enabled, hovering on a `Home`, `Movies`, or `TV Shows` catalog card for the configured `1s–7s` delay (`2s` default) plays the trailer inside its 16:9 artwork frame, displays a top-right white muted speaker icon when muted, and silently reverts to normal 16:9 backdrop artwork without launching YouTube if playback or extraction fails.
- During media playback, `Enter` pauses and opens the HUD, `Up`/`Down` opens the HUD without pausing, each `Back` press does exactly one thing for media and trailers alike (an open sub-overlay closes with focus on its bar button, else the main HUD is dismissed while playback continues, else playback exits with focus restored to the originating `DetailsScreen` control, falling back to `Play`/`Resume`, and the trailing `KeyUp` consumed), and the bottom bar provides dynamic Play/Pause, episode-only Next Episode & Episodes browser, Subtitles overlay, external-subtitle-only `- 0.0s +` (`0.1s` steps, `-60.0s` to `+60.0s`), session-only Audio overlay (reverting to General Settings on exit), live Streams switcher overlay, and full technical Info overlay (including live `Mbps` throughput and buffered seconds/MB).
- `Settings -> Playback` exposes `Playback Buffer Profile` (`Standard`, `Large (2 min)`, `Max (5 min)`) and `Parallel Stream Connections` (`Off (1x)` to `4x`), smoothly absorbing transient peering congestion without stalling or corrupting seeks.
- `ProfileSelectScreen` featured backdrop artwork excludes Horror (`27`), Romance (`10749`), Thriller (`53`), and Crime (`80`) genres, never uses `Continue Watching` history, and enforces `PG`/`TV-PG` (`allowUnrated = false`) when any Kids profile exists (or `PG-13`/`TV-14` with `allowUnrated = false` otherwise).

---

## Post-Launch Feature: Startup Splash Screen (`feat/startup-splash`)
**Prompt for Claude Code:**
> Read `CLAUDE.md`, `PRD.md` (§2 Startup Splash Screen), and `ARCHITECTURE.md`. Add the native Compose startup splash before the Profile Gate:
> 1. Verify `R.drawable.ic_seastreams_icon` and `res/font/fredoka_semibold.ttf`; define `FredokaFontFamily` (`Font(R.font.fredoka_semibold, FontWeight.SemiBold)`) in `ui/theme/Type.kt`.
> 2. Make the launch window `#000000` with a transparent Android 12+ splash icon (`values-v31/themes.xml`) so no system icon flashes first.
> 3. Build `ui/screens/splash/SplashScreen.kt` (`SeaStreamsSplashScreen(isStartupReady, onSplashFinished)`, under 350 lines) with the left-to-right spring-reveal wordmark, and show it in `SeaStreamsNavGraph` ahead of the `AppGate` routing, once per process.
> 4. Unit-test the wave offset math. Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.

**Acceptance Criteria:**
- Cold launch shows a black window, then the icon + `seastreams` wordmark with no system splash icon before it on Android 12+.
- Letters pop in left-to-right and bob in the water, and every letter is fully visible and completely still by ~`3.26s` (`420ms` black start + `2840ms` motion); it does not loop.
- The play icon (`96dp`, `14dp` down) brackets the lowercase `s` with its tip near the `s` waist, matching `docs/ui-references/tv_banner.png`.
- When profiles exist, `ProfileSelectScreen` opens with its backdrop already drawn (fetched and decoded during the splash).
- The splash stays for at least `3.35s` (`0.42s` black + `2.84s` motion + ~`0.09s` stillness) and until `AppGate` is no longer `Loading`, then shows `ProfileCreateScreen` (no profiles/key) or `ProfileSelectScreen`.
- `Back` during the splash does nothing; rotating/recreating the activity or returning from Home to the profile switcher does not replay the splash.

---

## Post-Launch Feature: Auto Subtitle Sync & FFmpeg Audio Fallback (`feat/auto-subtitle-sync-and-ffmpeg-audio`)
**Prompt for Claude Code:**
> Read `CLAUDE.md`, `PRD.md` (§9 Audio DSP & Sync, Subtitles & HDR, Subtitle Timing Offset; §11 Subtitles), and `ARCHITECTURE.md` (§4–§6). Implement:
> 1. **FFmpeg audio fallback:** add the approved `io.github.anilbeesetti:nextlib-media3ext` (version prefix = `media3`). In `PlayerEngineFactory` use `EXTENSION_RENDERER_MODE_ON` and append NextLib's `FfmpegAudioRenderer` after `MediaCodecAudioRenderer` (`AudioRendererPolicy`, `LinkageError`-safe). Give both sinks the PCM chain `SpeechActivityTap → SafeDownmixProcessor (stereo-only outputs) → DialogueNightAudioProcessor`, 16-bit output. Drive `dspAvailable` from `AnalyticsListener.onAudioTrackInitialized` (PCM vs. bitstream).
> 2. **Auto-sync setting:** `SubtitleSettings.autoSyncExternal` (default `true`), per-profile `ScopedKey("subs_auto_sync")`, and the *Auto-sync external subtitles* toggle as the last `SUBTITLES` row, below *Subtitle styling*.
> 3. **Auto-sync engine (`player/subtitle/sync/`):** 100 ms activity grids, ±30.0 s / 0.1 s Pearson cross-correlation with a strict confidence gate, an embedded same-language reference track (fast path), audio voice activity (fallback, two agreeing estimates). Write the result into the HUD `- 0.0s +` offset with an `Auto` caption, refresh the visible cue at once, and let a manual nudge always win.
> 4. Unit-test DataStore default/persistence, grids, correlation + gate (shifts, music, noise, periodic dialogue, ±30 s edge), the collector, the controller (virtual time), the tap's media-time stamping, the clip-safe downmix, renderer placement, the HUD/Info state and bar width. Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.

**Acceptance Criteria:**
- TrueHD, DTS-HD, DTS, AC-3 and E-AC-3 tracks play audibly with passthrough off, and with passthrough on through a sink that can't bitstream them. With passthrough on and a capable AVR they're still bitstreamed.
- Dialogue Boost, Night Mode and Audio Delay work on FFmpeg-decoded audio. A 5.1/7.1 track on a stereo-only output never clips.
- An external subtitle that is out of sync shifts into sync during playback, typically ~20 s after dialogue starts (also after a seek). The HUD shows `Syncing…` while scanning, then e.g. `+2.0s` with `Auto`, with no pause, seek or re-buffer spinner. A music/action intro, long silence or an unrelated file leaves it at `0.0s`. Info shows the live auto-sync state.
- Turning on Dialogue Boost, Night Mode or Audio Delay (General Settings or the player's Audio overlay) switches passthrough off so the effect is heard at once; passthrough can be switched back on and stays on. In the player this happens live, with only a brief audio gap.
- The Info overlay (and every right-hand overlay) ends above the bottom action bar, scrolls with `Up`/`Down`, and never covers the active subtitle.
- The background embedded reference track is never drawn and never changes the Subtitles overlay selection or the Info overlay's subtitle name, language and `External` source.
- Manual `-`/`+` always works, starting from the auto value. With the setting off the control starts at `0.0s`. Toggling the setting on one profile never affects another.
- The full episode bar with the sync control and `Auto` caption fits a 960×540 dp TV viewport without clipping or growing taller.

---


## Post-Launch Feature: Kids Trending Family Rows (`feat/kids-trending-family-rows`)
**Scope:** Two TMDB-backed rows become the only built-in Kids Home catalog rows, directly below `Continue Watching`. They replace the four MDBList Kids rows (`Trending Kids Movies`, `Trending Kids Shows`, `Latest Family Movies`, `Latest Family Shows`); the Kids hero keeps its MDBList pool (`snoak/trending-kids-movies` + `tvgeniekodi/trending-kids-tv-shows`). The rows: `Trending Family Movies` (`/discover/movie` digital releases from the last 90 days that can be streamed, rented or bought in the US, `G`/`PG`) and `Trending Family Shows` (`/discover/tv` Returning Series that aired in the last 60 days or air within the next 7, `TV-Y`…`TV-PG`). Exact parameters and genre allow/block lists are in PRD §3 and ARCHITECTURE §9.

**Acceptance Criteria:**
- Kids Home order: Hero → `Continue Watching` → `Trending Family Movies` → `Trending Family Shows` → enabled Stremio metadata addon catalogs (strictly age-filtered through `AgeRestrictionEngine`). No Kids Home row comes from MDBList. The Adult Home order is unchanged.
- A title that carries any blocked genre (even a secondary one) or none of the allowed genres never appears in either row or its View More grid.
- Only US `G`/`PG` movies and `TV-Y`/`TV-Y7`/`TV-G`/`TV-PG` shows appear, also intersected with the profile's own ceiling (a `G` profile sees no `PG`, a `TV-Y7` profile sees no `TV-G`/`TV-PG`). Unrated titles never appear, even with `Allow Unrated` on.
- Rows fetch only `page=1` and show `View More` when `total_pages > 1`. View More grows to up to 100 titles (pages 2–5) with the same filtering. No `/external_ids` call is made on load.
- `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` pass.

---


## Milestone 7: Sources Foundation — Usenet Providers, Indexers, Request Headers & Performance Profiles (`feat/usenet-sources`)
**Prompt for Claude Code:**
> Read `CLAUDE.md`, `PRD.md` (§8, §11 `Sources`, §12), and `ARCHITECTURE.md` (§1, §10). Execute **Milestone 7** (configuration only, no streaming):
> 1. **Room v7:**
>    - Add the app-wide `UsenetProviderEntity` (`usenet_providers`) and `NewznabIndexerEntity` (`newznab_indexers`) with their DAOs.
>    - Add `UsenetSourcesMigration` (6→7, Room's exported createSql verbatim) and `app/schemas/…/7.json`.
>    - Register both in `DatabaseModule`.
> 2. **Secrets:** add `core/security/SecretCipher.kt`, an Android Keystore AES-256-GCM cipher behind an interface. Passwords and API keys are stored only encrypted.
> 3. **Repository:** add `data/usenet/UsenetSourcesRepository.kt` (CRUD, enable, move, remove, validation, CR/LF rejection, blank secret keeps the stored one).
> 4. **Presets:** add `presets/UsenetProviderPresets.kt` (29 providers) and `presets/IndexerPresets.kt`.
> 5. **Headers:** add `headers/RequestHeaderPresets.kt` and `headers/HeaderVersionUpdater.kt` (GitHub `releases/latest`, ≤ once per 24 h, Update now).
> 6. **Settings model:** add `domain/usenet/UsenetPerformance.kt` (profiles, Custom on edit, clamps) and `core/datastore/UsenetSettingsStore.kt`.
> 7. **Settings UI:**
>    - Add `SettingsCategory.SOURCES` (7 tabs, still Home ⇄ Other).
>    - Add `ui/screens/settings/sources/`: rows, providers/indexers panels, performance panel, header choices, `SourcesViewModel`.
> 8. **Portal:** add `PortalUsenetRoutes` + `PortalUsenetScript` (secrets never returned).
> 9. **Startup:** call `HeaderVersionUpdater.checkIfDue()` from `SeaStreamsApp`.
> 10. **Tests:**
>     - migration (sqlite-jdbc);
>     - repository validation;
>     - cipher round-trip (fake);
>     - presets sanity;
>     - updater (24 h gate, `v` strip, bad tags ignored, failure keeps last);
>     - profile templates / Custom / clamps / JSON round-trip;
>     - Sources rows and order;
>     - tab bar width ≤ 848 dp;
>     - portal routes (secrets masked, blank keeps).
>
> Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.

**Acceptance Criteria:**
- `Settings` shows seven tabs, `Home`…`Subtitles`, `Sources`, `Other`, on one row at 960×540 dp. A fresh `Left`/`Right` wraps `Home` ⇄ `Other`.
- `Sources` rows: `Usenet providers`, `Indexers`, `Search request headers`, `Download request headers`, `Update header versions`, `Usenet performance`, `Web portal`.
- Providers and indexers added from the Web Portal (presets prefill host/port or API URL) appear on TV at once.
- On TV a provider or indexer can be enabled/disabled, moved and removed. The panels follow the Manage addons focus rules (two-way wrap, keep focus on `Remove`, `Back` → opening row).
- Passwords and API keys are stored encrypted and are never returned by `/api/usenet`.
- Picking `Conservative`/`Balanced`/`High` fills every PRD §12 value. Stepping or toggling any value switches the profile to `Custom`. Values persist across restarts and are the same on every profile.
- The header rows show `Prowlarr 2.7.0.5723` / `SABnzbd 5.1.3` until a check succeeds. `Update header versions` refreshes them, and an automatic check runs at most once per 24 h.

---

## Milestone 8: NNTP Engine Core — Connections, Pipelining, yEnc, NZB, Read-Ahead & Bounded Segment Cache
**Prompt for Claude Code:**
> Read `CLAUDE.md`, `PRD.md` §12 and `ARCHITECTURE.md` §3 and §10. Use `C:\Users\Ken\Downloads\Nabrio\pov-rajput-android-tv` (`dev/pov/tv/usenet/`) and `C:\Users\Ken\Downloads\AIOStreams\AIOStreams` (`packages/core/src/usenet/`) as references. Execute **Milestone 8** (pure JVM engine, no player wiring):
> 1. **`usenet/nntp/`:**
>    - `NntpConnection` (TLS, auth classification, pipelined BODY/STAT/DATE), `NntpReader`, `NntpErrors`, `SegmentFetch`.
>    - `ProviderWorkerPool` (lazy slots, HIGH/LOW priority share, AIMD connection-limit throttle, idle TTL), `ProviderSelector` (priority groups → backups, negative cache), `CircuitBreaker`, `ConnectionThrottle`, `WarmConnections`, `PrioritySemaphore`.
>    - `NntpEngine` (registry keyed by provider fingerprint) and `NntpConnectionTester` (the provider `Test` button on TV and in the portal).
> 2. **`usenet/yenc/`:** `YencDecoder` (single pass, pooled output, pcrc32 / single-part crc32, `wrong_part`) and `SegmentBufferPool`.
> 3. **`usenet/nzb/`:** `NzbParser` (`indexOf` scanner, message-ID guard, password, backfill, per-article merge, SHA-1 key), `NzbModels`, `SubjectNames`.
> 4. **`usenet/stream/`:** `UsenetFile` (size discovery, random access), `SegmentLocator`, `ReadAheadWindow` (profile window, heap clamp, head/tail pins), `SegmentArena`.
> 5. **`usenet/cache/`:** `SegmentDiskCache` + `SegmentCacheIndex` (byte-bounded LRU, profile cap ∩ 25% free ∩ ≥ 2 GB left), `NzbFileCache`.
> 6. **`CacheManager`:** add `ManagedCache` and add `nntp_segments`/`nntp_nzb` to `MANAGED_DIRS`.
> 7. **Tests**, using a new `testutil/FakeNntpServer` (real localhost socket, yEnc encoder, 430/481/482/502, stall and CRC-corrupt modes):
>    - auth classification; pipelining FIFO at depths 1–16;
>    - pool limits / AIMD / breaker (virtual time); failover order and negative cache;
>    - yEnc vectors; NZB parser edge cases; locator with uneven parts;
>    - read-ahead cancel on seek; interrupt → `InterruptedIOException`;
>    - arena LRU; disk cache caps and clear-while-reading; `CacheManager` integration.
>
> Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.

**Acceptance Criteria:**
- Random-access reads over an NZB file are byte-exact.
- A 430 fails over to a backup provider, and 482/502 throttles connections instead of failing the account.
- The circuit breaker rests a failing provider. Warm connections are reused.
- The segment cache never exceeds its cap or the free-space guard. `Clear cache` empties it; the orphan sweep never deletes it.
- The provider `Test` button reports success / bad login / connection limit / unreachable.

---

## Milestone 9: Indexer Search, Archives, Verification & Usenet Playback (ExoPlayer + External Players)
**Prompt for Claude Code:**
> Read `CLAUDE.md`, `PRD.md` §12 and `ARCHITECTURE.md` §10. Execute **Milestone 9**:
> 1. **`data/usenet/newznab/`:**
>    - `NewznabClient`: caps cached 14 d; `t=movie&imdbid`, `t=tvsearch&imdbid&season&ep`, `cat` 2000/5000, `extended=1`, `q=` fallback; error codes 100–104.
>    - `NewznabXmlScanner`.
>    - `NzbGrabber`: Download headers, `maxNzbSize`, must contain `<segment`.
>    - `UsenetStreamSearch`: priority tiers, backup-only indexers last, `maxConcurrentImports`.
>    - `ReleaseMatch`: year / SxxEyy.
>    - Indexer `Test` (caps) on TV and in the portal.
> 2. **`usenet/archive/`:** stored RAR4/RAR5, every volume header parsed (lazily when enabled), `.partNN`/`.rNN`/`.s00–.z99` naming, duplicate collapse, obfuscated ordering via yEnc `name=`, `.001` splits, `MediaSelector` (largest video; sample/proof < 5% excluded; par2/nfo/sfv ignored). Refuse compressed, encrypted and 7z sets.
> 3. **`usenet/verify/`:** census STAT on pooled LOW connections, `verifyBudgetMs`, content sniff, `DamagePolicy`, `HoleFiller` (≤ 4 / ≤ 64 / ≤ 2%), `MatroskaHoleRepair` (EBML Void), `ReleaseHealthCache`.
> 4. **`usenet/session/`:** `UsenetSessionRegistry` (≤ 4 sessions, stream idle reaper), `UsenetSessionOpener`, `UsenetStreamRegistry` (token → grab descriptor; no API keys in URLs).
> 5. **`player/usenet/`:** `NntpDataSource`, `PlaybackDataSources` (scheme routing, called from `SeaStreamsPlayerManager.playStream`), `UsenetLoadErrorPolicy`.
> 6. **External players:**
>    - `core/webserver/NntpProxyRoutes` + `ByteRange`: loopback-only `GET/HEAD /nntp/{token}/{name}`, 206/416.
>    - `LocalPortalServer` lease, plus a lease check in `MainActivity.onStop`.
>    - `player/external/NntpProxyService` (foreground `mediaPlayback`, manifest permissions) and `ExternalStreamUrls` (rewrite + head warm-up, called from `PlaybackViewModel.start`).
> 7. **Hooks:**
>    - `ResolveStreamsUseCase` adds the built-in `Usenet` stream addon (after Stremio addons; only when ≥ 1 provider and ≥ 1 indexer are enabled).
>    - `StremioResources` parses `nzbUrl` + `servers` streams.
> 8. **Tests:**
>    - Newznab fixtures and query building, tiers and headers;
>    - RAR4/RAR5 builders, naming, refusal, obfuscation;
>    - census strict/tolerant, hole limits, MKV Void bytes;
>    - `NntpDataSource` open/read/seek;
>    - proxy ranges / loopback-only / 404, lease across `onStop`;
>    - resolve use case shows the Usenet card only when configured;
>    - LAST USED on Usenet streams.
>
> Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.

**Acceptance Criteria:**
- A title with configured sources lists releases under a `Usenet` card, and they play in ExoPlayer: plain files, stored RAR sets (incl. obfuscated and `.sNN` rollover) and `.001` splits.
- Non-streamable releases are dropped and failover moves on. Census catches broken releases.
- Seeking inside the cache is instant.
- Just Player, Vimu and Kodi play via `127.0.0.1:8765` while SeaStreams is backgrounded. LAN clients get `403` on `/nntp/*`.
- Auto-play, failover and LAST USED work for Usenet streams.

---

## Milestone 10: Stream Filtering, Sorting & Formatter (AIOStreams-style, per profile)
**Prompt for Claude Code:**
> Read `CLAUDE.md`, `PRD.md` §13 and `ARCHITECTURE.md` §11, and inspect `docs/ui-references/aio-filters.png`, `aio-sorting.png` and `aio-formatter.png`. Use AIOStreams `packages/core/src/{parser,streams,formatters}` as the reference. Execute **Milestone 10**:
> 1. **`domain/streams/parse/`:** `ReleaseRegexTables`, `LanguageTable`, `ReleaseParser`, `ParsedStream`.
> 2. **`filter/`:** `StreamFilterEngine` (include override; required / excluded / preferred per attribute; language any/all; keywords; regex with the `n` negate flag; seeders; age; size per resolution for movies/series; bitrate; result limits), `KeywordMatcher`, `SizeBitrateRules`, `StreamDeduplicator`.
> 3. **`sort/StreamSorter`:** Global/Movies/Series/Anime lists, per-criterion direction, preferred-index keys.
> 4. **`format/`:** template lexer / parser / AST / renderer / modifiers / fields, plus `PredefinedFormatters` (Torrentio, GDrive, Light GDrive, Minimalistic GDrive, Prism, Custom).
> 5. **`ProcessStreamsUseCase`**, run inside `ResolveStreamsUseCase` before `topStream`. `StreamSource.parsed`/`display`; the raw `label`/`description` are kept for LAST USED.
> 6. **Settings storage:** per-profile ScopedKeys `stream_filters`, `stream_sort`, `stream_formatter` (JSON; defaults = no change).
> 7. **Sources rows:** `Stream filters`, `Stream sorting`, `Stream formatter` (`MultiChoicePanel`, sort order with Move up/down + direction, formatter picker + preview).
> 8. **Portal:** `PortalStreamRoutes` / `PortalStreamScript` (regex, keywords, sizes, custom templates + `/api/streams/format-preview`).
> 9. **Tests:**
>    - parser fixtures;
>    - each filter's semantics;
>    - dedupe;
>    - sorter;
>    - formatter (the `aio-formatter.png` preview as a golden test);
>    - raw label preserved;
>    - per-profile isolation.
>
> Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.

**Acceptance Criteria:**
- `Movie.Title.2023.2160p.BluRay.HEVC.DV.TrueHD.Atmos.7.1.iTA.ENG-GROUP.mkv` renders exactly like the `aio-formatter.png` preview.
- Excluded resolutions disappear and preferred ones sort first. Auto-play, failover and Addons counts follow the processed list.
- One profile's filters never affect another's. Every new panel follows the D-pad wrap / Back / KeyUp rules.

---

## Milestone 11: Sources Overhaul & Debrid Providers — Toggles, Scoped Clear, Room v8, Encrypted Sign-In, Connection Tests & Web Portal (`feat/debrid-sources`)
**Prompt for Claude Code:**
> Read `CLAUDE.md`, `PRD.md` (§8, §11 `Sources`, §12, §14) and `ARCHITECTURE.md` (§1, §10, §12). Use `C:\Users\Ken\Downloads\Nabrio\pov-rajput-android-tv` (`dev/pov/tv/data/LinkDebrids.kt` `validate`, `TorBoxApi.kt` device auth, `OffcloudApi.kt`) as the reference for account checks and device flows only. Do **not** port its hash / magnet / `checkcached` resolver. Execute **Milestone 11** (configuration, sign-in and tests only, no library scanning):
> 1. **Room v8:**
>    - Add the app-wide `DebridProviderEntity` (`debrid_providers`, unique `kind`, index on `sortOrder`) and `DebridProviderDao`.
>    - Add `DebridSourcesMigration` (7→8, Room's exported createSql verbatim) and `app/schemas/…/8.json`.
>    - Register both in `DatabaseModule`.
> 2. **Repository:** add `data/debrid/DebridSourcesRepository` (CRUD, enable, move, remove, `deleteAll`; key/token validation incl. CR/LF; a blank key keeps the stored one; every secret through `SecretCipher`; undecryptable → *Sign-in needed*) and `DebridServiceKind` (7 services, auth methods, key-page URLs, BuildConfig client-ID gates for Debrid-Link / put.io).
> 3. **Clients (account only):** `data/debrid/api/DebridHttp` (`no-store`, Bearer headers, error mapping) and one `DebridLibraryClient` per service implementing `account()` → `DebridTestResult` (premium expiry only; never username/email).
> 4. **Sign-in:** `data/debrid/auth/` `DeviceAuthSessions`, `RealDebridDeviceFlow`, `AllDebridPinFlow`, `TorBoxDeviceFlow`, `DebridTokenRefresher` (ARCH §12).
> 5. **Toggles:**
>    - `usenet/enabled` in `UsenetSettingsStore` and a new `DebridSettingsStore` (`debrid/enabled`), both default false.
>    - `data/setup/SourceTogglesMigrator`: a one-time `usenet/enabled = true` when ≥ 1 provider and ≥ 1 indexer already exist.
>    - AND the Usenet flag into `UsenetAddonState.configured`, `UsenetStreamSearch.configured()`, addon `nzbUrl` registration (own `servers` exempt) and `UsenetPreflight`.
> 6. **Scoped clear:** `PendingSourceClear` (one per source, 5 s, Undo) plus `UsenetSourcesRepository.clearAll()` + `UsenetSettingsStore.clearAll()` + `NntpEngine` reconfigure, and the Debrid equivalent (ARCH §10, §12).
> 7. **Settings UI (`ui/screens/settings/sources/`):**
>    - `USENET` / `DEBRID` / `PORTAL` sections exactly as PRD §11.
>    - `Enable Usenet` / `Enable Debrid` toggles.
>    - `Clear … settings` rows whose labels switch to `Undo clear …`.
>    - A `Debrid providers` sub-panel on the shared `SourceListPanel` (toggle + Move up/down + Test + Remove, closing `Add a provider` → Web portal, two-way wrap, keep-focus remove, Back → row).
>    - `DebridSourcesViewModel`.
> 8. **Portal:** `PortalDebridRoutes` / `PortalDebridController` / DTOs / `PortalDebridScript` (no `$`), with key paste, device-code panel (code + link + countdown + status polling), test, enable, move, remove, and the two source toggles. Secrets are never returned.
> 9. **Tests:**
>    - migration (sqlite-jdbc, v7 data survives, unique `kind`);
>    - repository validation / CR-LF / blank keeps;
>    - cipher round-trip (fake);
>    - per-service `account()` JSON fixtures → each `DebridTestResult`, no PII retained;
>    - device-flow state machines (pending / linked / expired / denied / cancelled / superseded / discarded-after-clear) on a virtual clock;
>    - refresher (pre-expiry, after `401`, single-flight, failure → *Sign-in needed*);
>    - upgrade migrator (configured → ON, empty → OFF, runs once);
>    - Usenet gating (OFF → no entry, no search, own-`servers` `nzbUrl` still listed);
>    - Clear isolation: Usenet clear leaves Debrid rows/keys intact and vice versa, Undo restores, both windows independent, nothing deleted before 5 s;
>    - Sources rows / order / sections / one-way wrap, Debrid panel edges;
>    - portal routes (masking, blank keeps, toggles, auth session lifecycle, guards).
>
> Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.

**Acceptance Criteria:**
- `Sources` shows `USENET` (`Enable Usenet` … `Usenet performance`, `Clear Usenet settings`), `DEBRID` (`Enable Debrid`, `Debrid providers`, `Clear Debrid settings`) and `PORTAL` (`Web portal`). A fresh `Down` on `Web portal` wraps to `Enable Usenet`.
- On a fresh install both toggles are OFF. An install upgraded with providers and indexers configured keeps Usenet results (toggle reads ON). Switching a toggle OFF hides that source's built-in entry and keeps every provider, indexer and setting; switching it back ON restores them.
- `Clear Usenet settings` → `Undo clear Usenet settings` for 5 s. Undo keeps everything; letting it pass empties providers, indexers and Usenet settings while every Debrid provider stays. `Clear Debrid settings` behaves the same in reverse, and both can be pending at once.
- From the Web Portal: Real-Debrid, AllDebrid and TorBox sign in with a device / PIN code, and all seven services accept a pasted key or token. The provider appears on TV at once, and `/api/debrid` never returns a key or token.
- On TV a Debrid provider can be enabled/disabled, moved, tested and removed with Manage addons focus rules. `Test` reports connected (with premium expiry), invalid key, not premium, rate limited or unreachable.

---

## Milestone 12: Debrid Library Scanner, Built-In Debrid Addon, Stream Processing & Playback (`feat/debrid-library`)
**Prompt for Claude Code:**
> Read `CLAUDE.md`, `PRD.md` (§8, §9, §13, §14) and `ARCHITECTURE.md` (§10, §11, §12). Use Nabrio `TorBoxApi.cloud` / `OffcloudApi.cloud` + `cloudFiles` and `EpisodeFilename` as references for listing and episode-file choice only. Execute **Milestone 12** (library-only: no hash, magnet or cache-check calls anywhere):
> 1. **Clients:** implement `library()`, `files()` and `resolve()` for all seven `DebridLibraryClient`s per the ARCH §12 endpoint table (pagination ≤ 2,000 items, finished items only, HTTPS links only).
> 2. **`data/debrid/library/`:**
>    - `DebridLibraryCache` (60 s, single-flight, cleared on credential change / remove / Clear).
>    - `DebridLibraryMatcher` + `EpisodeFilePicker` (ReleaseParser / ReleaseMatch, year ±1, SxxEyy / ranges / `1x02`, packs, IMDb tag, video-only, sample < 5% dropped, ≤ 8 `files()` calls per provider).
>    - `DebridLibrarySearch` (parallel, provider order, 20 s timeout, per-provider failure isolation).
> 3. **`data/debrid/stream/`:** `DebridStreamRegistry` (stable SHA-1 token) and `DebridLinkResolver` (20 min cache, refresher-wrapped).
> 4. **Addon list:**
>    - Add `BuiltInAddons.DEBRID_URL` (`debrid://builtin`), `DebridAddonState.listed`, and `BuiltInAddonGuard.keepDebridEntries` → `AddonRepository.ensureDebrid` (bottom of every profile, protected, movable / renamable / switchable).
>    - Make sure Manage addons, Preferred auto-play addon and the portal addon editor show it only while listed.
> 5. **Resolve:**
>    - Generalise `UsenetSlot` into `BuiltInSlot` in `ResolveStreamsUseCase` and add the Debrid slot.
>    - Feed results through `ProcessStreamsUseCase`.
>    - `StreamFacts` type Debrid and indexer = service name.
>    - `PredefinedFormatters.SOURCE` shows the service name.
> 6. **Playback:**
>    - Add `player/debrid/DebridPreflight` wired into `PlaybackStartGate.firstPlayable`, `PlayerStreamGate` and `ExternalStreamUrls.prepare`.
>    - Add `PlaybackDataSources` routing for `seastreams-debrid://` → resolved HTTPS on the existing HTTP / parallel-range path.
>    - External players get the HTTPS link.
>    - LAST USED stores the `seastreams-debrid://` URL.
> 7. **Tests:**
>    - each client against JSON fixtures (listing, pagination, unfinished items skipped, `files`, `resolve`, non-HTTPS refused);
>    - a guard test that no client ever requests a hash / magnet / cache-check / add endpoint;
>    - matcher fixtures (movies ± year, episodes, multi-episode, season packs, nested folders, IMDb tags, samples, non-video);
>    - cache TTL / single-flight;
>    - search ordering and timeout;
>    - Debrid card only when listed and placed at the entry's position, per-profile switch-off;
>    - filters / sort / formatter on Debrid streams;
>    - preflight skip on deleted file / revoked key with failover on and off;
>    - LAST USED match across searches;
>    - Clear empties caches and registry links.
>
> Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.

**Acceptance Criteria:**
- A movie or episode the user has in any enabled Debrid account lists under a `Debrid` card (service name shown per stream), placed where the profile's `Debrid` entry sits. Titles not in the account show no Debrid results, and no request ever sends a hash or magnet.
- Season packs play the correct episode file. Samples and non-video files never appear.
- Per-profile filters, sorting, result limits and the formatter apply to Debrid streams exactly as to Stremio and Usenet streams. One profile switching the entry off never affects another.
- Debrid streams play in ExoPlayer and in Just Player / Vimu / Kodi. A file deleted from the account is skipped silently and failover moves on (or the Streams overlay opens with failover off).
- Auto-play, `Preferred auto-play addon`, LAST USED and `Auto-play next episode` work for Debrid streams.

---

## Milestone 13: Next Up in Continue Watching (`feat/next-up-continue-watching`)
**Prompt for Claude Code:**
> Read `CLAUDE.md`, `PRD.md` (§3, §8, §11) and `ARCHITECTURE.md` (§1, §13). Execute **Milestone 13**:
> 1. **Room v10:** add `next_up_dismissals` (`NextUpDismissalEntity` / `NextUpDismissalDao`) with a manual `NextUpDismissalsMigration` whose SQL is Room's exported createSql. Also add `WatchProgressDao.observeLatestShowActivity` (each show's newest episode record plus an `inLibrary` flag).
> 2. **Domain:** add `NextUpPolicy` (pure candidates / next episode / activity time / dismissal / merge) and `GetNextUpUseCase` (HTTP-cached `/tv/{id}` via `TitleDetailsRepository.load`, age-checked, never `/external_ids`). `EpisodeNavigator.latest` breaks timestamp ties so Details and Home pick the same episode.
> 3. **Home:** `HomeViewModel` merges in-progress and Next Up cards (activity order, cap 10). Next Up cards show the badge but no progress bar. Their long-press menu = `View details`, `Remove`. `Remove` records a dismissal.
> 4. **Settings & Backup:** add a per-profile `Show next up in continue watching` toggle (`p{id}/home_next_up_continue_watching`, default on) to the codec, Settings → Playback and `GeneralSettingsBackup`. Add `ProfileBackupEntry.nextUpDismissals` to export, validation and the restore merge (the newer removal wins).
> 5. **Tests:** policy rules, use case (watched non-Library show, Library show, Kids gate, load failure, toggle, dismissal, no `/external_ids`), actions, menu and focus, the migration, and the backup round-trip and restore.
>
> Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.

**Acceptance Criteria:**
- Finishing an episode (`>= 90%`) of any show, in the Library or not, replaces its in-progress card with a `Next Up` card for the next aired episode (`S1:E3` → `S1:E4`; a finale → `S2:E1` once aired). The card shows the `S7:E10` badge and no progress bar.
- A caught-up Library show (or a recently watched one) gets a `Next Up` card when its next episode airs. An unwatched Library show never shows `S1:E1`. Shows that ended, or whose next episode hasn't aired, show nothing.
- Continue Watching stays capped at 10 cards and sorted by most recent activity (last watch, or the air day of a newly aired next episode). A show never has both kinds of card.
- Home never calls `/external_ids`. Each candidate show costs at most one HTTP-cached `/tv/{id}` lookup. Kids profiles only see shows within their current ceiling, enforced before `HomeViewModel`.
- `OK` on a `Next Up` card auto-plays that exact episode from the start (lazy `/external_ids` for that title), or opens Details when auto-play is off. Details `Play` then targets the same episode.
- Long-press `Remove` hides the card without touching watch history. It stays hidden across Home reloads until any episode of the show is watched again or a newer episode airs. Focus moves to the neighbouring card (§3 removal focus), and `View details` + `Back` returns focus to the card.
- Turning `Show next up in continue watching` off leaves only in-progress cards. The toggle is per profile and is included in profile/app backups, as are removed `Next Up` cards. Files from before M13 still restore.

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