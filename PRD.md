# PRD.md — SeaStreams Product Requirements Document

## 1. Visual Design System (Derived from `docs/ui-references/`)
- **Theme:** Deep obsidian/charcoal dark theme (`#0B0D12` background, `#161A23` surface cards, crisp white `#FFFFFF` primary text, `#A0A8B8` secondary text, `#E5E9F0` high-contrast pill focus states).
- **Top Navigation Bar (`home-hero.jpg`, `home-catalogs.jpg`):**
  - **Top-Left:** Active profile circular avatar + live system clock in 12-hour `h:mm a` format (e.g. `12:15 AM`, `Locale.US`), never 24-hour time. All user-facing clock times across the app use this 12-hour format.
  - **Top-Right:** Rounded translucent pill container holding: Search (magnifying glass icon), `Home`, `Movies`, `TV Shows`, `Library`, and Settings (gear icon). *(Note: When an age-restricted Kids profile is active, `Movies` and `TV Shows` tabs are hidden).*
  - **Back Button Navigation:** Pressing Remote `Back` while browsing any Home screen content row moves D-pad focus directly to the Top Navigation Bar, defaulting focus onto the `Home` pill.
- **Global Edge-Stop-Then-Wrap Rule (applies to every screen, including all Milestone 4+ screens):** Focus never wraps automatically while a direction key is held down.
  - **Held key (`KeyEvent.nativeKeyEvent.repeatCount > 0`):** Focus stops and stays on the last item of a horizontal row (`Right`) or the bottom-most row/element of a page (`Down`). Auto-repeat events at the edge are consumed so focus neither wraps nor escapes elsewhere.
  - **Fresh press at the edge (`repeatCount == 0`):** Only when focus is *already* on the last item of a row (or the bottom-most row of a page) and the user presses that same direction again as a new key press does focus wrap: `Right` → item #1 of the row; `Down` → the top of the page.
  - Implementation: shared `Modifier.onDpadEdgeWrap(key, wrap)` in `ui/theme/FocusModifiers.kt`; new rows/grids/pages must use it rather than hand-rolled wrap handlers.

## 2. Onboarding & Profiles (Max 5 Profiles)
- **Profile Gate (`profiles.jpg`):** On startup, if no profile exists, launch directly into Profile Creation. If profiles exist, display the vertical "Choose a Profile" selector on the left with full-bleed featured backdrop art on the right, plus an edit pencil icon and `+` button (up to 5 profiles).
- **Mandatory TMDB Key & Web Portal Input:** During first profile creation, the user must enter a valid TMDB API key (validated via a test call to `/configuration`) before proceeding. Users can type via on-screen keyboard OR scan a QR code / visit the local LAN IP (`http://<tv-ip>:8765`) to type the API key and profile name from a phone/PC browser.
- **Profile Age Restrictions:**
  - Profiles can be standard (Adult) or Kids (Age-Restricted with max certification ceiling, e.g., `G`, `PG`, `PG-13` / `TV-Y`, `TV-Y7`, `TV-G`, `TV-PG`, plus a toggle to `Allow/Disallow Unrated Items`).
  - **Hard Filtering:** On Kids profiles, any movie/show outside the allowed rating—or unrated when disallowed—is stripped at the data layer even if returned by an external Stremio addon or MDBList.

## 3. Home Screen Architecture
- **Adult Profile Home Layout:**
  1. **Hero Carousel (`home-hero.jpg`):** Large rounded-corner featured card spanning screen width. Pools the first 15 items of `https://mdblist.com/lists/official/movies/justwatch-streaming-charts` and the first 15 of `https://mdblist.com/lists/official/shows/justwatch-streaming-charts` (30 total, de-duplicated), shuffles them, and caps the carousel at 10 items per Home screen load (only titles with backdrop art qualify). Horizontal D-pad scrolling cycles items; displays logo/title, match/rating %, year, genres, certification, runtime, truncated synopsis, and a focused `View Details` pill button.
     - **Focus Ring:** While the hero holds D-pad focus, the card shows the same crisp `3dp` `#E5E9F0` focus border used by focused catalog cards (shared `FocusBorderWidth` / `Modifier.focusRing` in `ui/theme/FocusModifiers.kt`), drawn on the hero's own rounded shape.
     - **Continuous 5-Second Auto-Advance:** The hero advances to the next item every 5 seconds, wrapping from item #10 back to #1, for as long as Home is on screen — including while focus is on the Top Navigation Bar or the catalog rows below. Only direct interaction with the hero (Left/Right on the hero card, or pressing its button) restarts the 5-second countdown. The timer pauses when Home leaves the foreground (`ON_PAUSE`/`ON_STOP`) or the user navigates to another screen.
  2. **Continue Watching (`home-genres-continue.jpg`):** Horizontal row of the user's last 10 in-progress movies/episodes with progress bars and season/episode badges (`S7:E10`). Clicking an item either starts playback immediately (if *Auto-play from Continue Watching* is ON) or opens its Details page (if OFF).
  3. **Most Watched Movies:** Horizontal row pulling from `https://mdblist.com/lists/official/movies/most-watched-week`.
  4. **Most Watched Shows:** Horizontal row pulling from `https://mdblist.com/lists/official/shows/most-watched-week`.
  5. **Custom MDBList Home Catalogs:** Any additional MDBLists added via the Web Portal assigned to `Home`.
- **Kids Profile Home Layout:**
  1. **Kids Hero Carousel:** Pools the first 15 entries of `https://mdblist.com/lists/snoak/trending-kids-movies` and the first 15 of `https://mdblist.com/lists/tvgeniekodi/trending-kids-tv-shows` (30 total), strictly filtered through `AgeRestrictionEngine` against the profile's ceiling, shuffles them, and caps the carousel at 10 items per Home screen load. Same focus ring and continuous 5-second auto-advance as the Adult hero.
  2. **Continue Watching:** Last 10 watched items for that Kids profile.
  3. **Fixed Kids Rows:** `Trending Kids Movies`, `Trending Kids Shows`, `Latest Family Movies` (`kep084/latest-family-movies`), and `Latest Family Shows` (`kep084/latest-family-shows`), filtered strictly by the profile's age ceiling.

## 4. Expanding Catalog Rows & "View More" Grid
- **Expanding Card Behavior (`catalog1.jpg`, `home-catalogs.jpg`):** On `Home`, `Movies`, and `TV Shows` tabs, the currently D-pad focused item in a horizontal row smoothly animates from a vertical 2:3 poster into a wide 16:9 backdrop card with title logo, rating %, year, genre, runtime, and synopsis below it.
- **Row Cap & Looping (`catalog2.jpg`):** Each horizontal row displays up to 30 items. If the source catalog has >30 items, a `View More` (`>>`) card appears at position 31. Pressing `Right` past the end of the row (past `View More`, or past the last item if ≤30 items) wraps focus smoothly back to item #1, following the §1 Edge-Stop-Then-Wrap Rule (holding `Right` stops at the end; a fresh `Right` press there wraps).
- **View More Screen (`view-more.jpg`):** Clicking `View More` opens a vertical 5-column poster grid displaying up to 100 items from that catalog (standard 2:3 posters only, no 16:9 expansion).

## 5. Movies & TV Shows Tabs (TMDB Discover)
- **Movies Tab:** `Popular Movies` plus toggleable streaming rows (`Netflix` ID 8, `Amazon Prime` ID 9, `Disney+` ID 337, `HBO Max` ID 1899, `Hulu` ID 15, `Apple TV+` ID 350, `Paramount+` ID 531, `Peacock` ID 386) using:
  `GET https://api.themoviedb.org/3/discover/movie?with_watch_providers={ID}&watch_region=US&with_watch_monetization_types=flatrate&sort_by=popularity.desc&include_adult=false&language=en-US&page=1`
- **TV Shows Tab:** `Popular Shows` plus the same toggleable streaming providers using `/discover/tv`.
- **Caching & Rate Limits:** Cache `/discover` and `/external_ids` responses locally for 6–12 hours. Retry HTTP `429` responses with exponential backoff. Never batch-fetch `/external_ids` on catalog load.

## 6. Item Details & Episodes Screens
- **Details Screen (`movie-page.jpg`, `show-page.jpg`):**
  - Full-bleed edge-to-edge backdrop artwork with left vignette gradient.
  - Left column displays: Title/Logo, rating %, release year, up to 2 genres, age certification badge, duration, 3-line synopsis, and cast list.
  - Vertical action button stack: `Play` (high-contrast white pill), `Trailer`, `More episodes` (TV shows only), `Shuffle` (TV shows only — plays a random episode across available seasons), and `Add to library` / `In library`.
  - Opening this screen triggers the lazy fetch of `/external_ids` (IMDb ID) so Stremio stream addons can be queried.
- **Episodes Screen (`episodes-page.jpg`):**
  - Left pane: Show title, metadata, and vertical Season selector pills (`Season 1`, `Season 2`, etc.).
  - Right pane: Vertical list of episodes showing episode number badge, 16:9 still thumbnail (blurred if unwatched and *Blur Unwatched Thumbnails* is enabled), episode title, air date, runtime, and synopsis.

## 7. Search & Library Pages
- **Search Page (`search-screen.jpg`, `search-results.jpg`):**
  - Top bar: Microphone button on left (triggers in-app Android `SpeechRecognizer` only, never system-wide Google Assistant) + wide text input field. Initial D-pad focus lands on the Microphone button.
  - Below search bar: Horizontal chips of recent searches with a clear-all `X` button.
  - Left sidebar: Hover-activated category list starting with `Trending Movies` (default selected), `Trending Shows`, followed by all TMDB movie genres (`Action Movies`, `Adventure Movies`, etc.). Moving D-pad focus onto a category immediately loads its 5-column poster grid on the right.
- **Library Page:**
  - Left sidebar: Hover-activated filter buttons (`All` [default focused], `Movies`, `TV Shows`).
  - Top controls: Sort selector (`A-Z`, `Z-A`, `Last Added`, `Last Watched`, `Not Watched` — persisted across sessions) + Genre filter dropdown.
  - Center: 5-column poster grid (standard 2:3 posters only).
  - Far right edge: Vertical A–Z quick-jump letter strip.
  - Settings includes "Clear Library" with a 5-second Undo snackbar/toast before permanent deletion.

## 8. Stremio Addons & LAN Web Portal
- **Stremio Protocol Support:** Parse `manifest.json`, fetch `/stream/{type}/{id}.json` and `/subtitles/{type}/{id}.json`. Ship with TMDB metadata built-in and OpenSubtitles (or default Stremio subtitle addon) pre-installed and enabled.
- **In-App Addon Manager:** Enable/disable and reorder installed addons.
- **Local Web Portal (QR Code + LAN IP):** Single-page web app served by Ktor on the TV's local IP allowing the user to:
  1. Enter/update TMDB API key & profile fields.
  2. Install Stremio addons via manifest URL, remove, rename, disable, or reorder addons.
  3. Add `mdblist.com` URLs and assign them to `Home`, `Movies`, or `TV Shows` tabs, or import an MDBList directly into the user's `Movies` or `TV Shows` Library.

## 9. Playback Engine (Media3 ExoPlayer) & Audio/Subtitle Controls
- **Default & External Players:** ExoPlayer is default; option in Settings to launch streams via external Android intents (VLC, Kodi, MX Player, Just Player).
- **Auto-Play & Stream Fallback:**
  - Option to auto-play top stream result, plus a setting to select *which* preferred addon supplies the top stream.
  - Automatic Fallback: If a stream fails to buffer/start or throws a playback error within a configurable timeout (default `5s`, adjustable `1s–10s`), automatically advance to and play the next stream in the list.
- **Audio DSP & Sync (Requires Audio Passthrough Toggle):**
  - Toggle to disable HDMI audio passthrough so PCM audio can be processed on-device.
  - **Dialogue Boost:** Off, `+3 dB`, `+6 dB`, `+9 dB`, `+12 dB` center-channel/vocal frequency gain.
  - **Night Mode:** Dynamic range compression evening out loud explosions and quiet speech.
  - **Audio Delay:** Adjustable `-500ms` to `+500ms` (in `25ms` steps) to sync Bluetooth/wireless headphones.
- **Subtitles & HDR:**
  - Global toggle: *Always On* (if available) or *Always Off*.
  - Preferred Subtitle & Audio Language settings: Preferred subtitle language always sorts to the top of the player track list; optional toggle to *Hide non-preferred subtitle languages*.
  - **Clean SDH Toggle:** Regex/parser filter stripping bracketed/parenthetical sound cues (`[SIGHS]`, `(DOOR CREAKS)`, `♪`) and speaker labels (`JOHN:`) from `.srt`/`.vtt` cues in real time.
  - **Subtitle Styling:** Font size, bold, italic, text color, outline/drop-shadow, and background opacity configurable in General Settings (persistent default) AND inside the Player OSD (session-only override that resets when exiting playback).
  - **HDR White Dimming:** Optional overlay/tone-map luminance cap to dim bright specular whites during HDR10/Dolby Vision playback.