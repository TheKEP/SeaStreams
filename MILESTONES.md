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
>    - Add `mdblist.com` URLs assigned to `Home`, `Movies`, or `TV Shows`, and import MDBList URLs directly into a profile's `Movies` or `TV Shows` Library.
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
>    - Caps at 30 items + `View More` (`>>`) card if >30 items exist; pressing `Right` past the end loops back to item #1.
> 5. Assemble `HomeScreen.kt` with Hero, `Continue Watching` (last 10 items, honoring auto-play toggle), Adult `Most Watched Movies`/`Shows`, or Kids rows (`Trending Kids Movies`, `Trending Kids Shows`, `Latest Family Movies`, `Latest Family Shows`), plus custom Home MDBLists.
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

## Milestone 5: Details Screen, Episodes Screen & Stremio Stream Resolver
**Prompt for Claude Code:**
> Read `CLAUDE.md`, `PRD.md`, and `ARCHITECTURE.md`, and inspect `docs/ui-references/movie-page.jpg`, `show-page.jpg`, and `episodes-page.jpg`. Execute **Milestone 5**:
> 1. Build `DetailsScreen.kt` with edge-to-edge backdrop artwork, title/logo, rating %, year, up to 2 genres, certification badge, duration, synopsis, cast, and buttons: `Play`, `Trailer`, `More episodes` (TV), `Shuffle` (TV — selects a random episode across all seasons), and `Add to library`.
> 2. Enforce lazy IMDb ID resolution: only call TMDB `/external_ids` when entering `DetailsScreen` (cached 6–12h), then query all enabled Stremio stream/subtitle addons in parallel.
> 3. Build `EpisodesScreen.kt` with left season selector pills and right episode list, blurring unwatched episode thumbnails when the setting is enabled.
> 4. Implement stream selection modal + *Auto-play top stream* logic (respecting preferred auto-play addon setting) and external player intent launcher.
> Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.

**Acceptance Criteria:**
- `/external_ids` is called only when opening `DetailsScreen`.
- TV Shows display `More episodes` and `Shuffle`; Movies do not.

---

## Automated UI & D-Pad Navigation Tests
**Prompt for Claude Code:**
> Write Compose UI Tests (createComposeRule()) that simulate D-pad key presses (Key.DirectionDown, Key.DirectionRight, Key.DirectionCenter, Key.Back) across the Home screen, Profile switcher, and Player HUD to guarantee no screen ever traps or loses D-pad focus.

---

## Milestone 6: Media3 ExoPlayer, Auto-Fallback, Audio DSP & Clean SDH Subtitles
**Prompt for Claude Code:**
> Read `CLAUDE.md`, `PRD.md`, and `ARCHITECTURE.md`. Execute **Milestone 6**:
> 1. Build `SeaStreamsPlayerManager.

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