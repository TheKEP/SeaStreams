# ARCHITECTURE.md — SeaStreams Technical Blueprint

## 1. Module & Package Layout (`com.seastreams.tv`)
```text
app/src/main/java/com/seastreams/tv/
├── SeaStreamsApp.kt                  # Hilt Application class
├── MainActivity.kt                   # Single-activity Compose TV host + Back handler
├── core/
│   ├── network/
│   │   ├── NetworkModule.kt          # OkHttp (100MB disk cache) + Ktor HttpClient + 429 Retry Interceptor
│   │   ├── OkHttpDownloader.kt       # NewPipeExtractor Downloader backed by shared OkHttpClient
│   │   ├── TmdbApiService.kt         # /discover, /search, /movie/{id}, /tv/{id}, /external_ids
│   │   ├── MdbListApiService.kt      # Fetches built-in Home chart JSONs & one-time Library import lists
│   │   └── StremioAddonClient.kt     # Fetches manifest.json, /catalog (Home only), /stream, /subtitles
│   ├── database/
│   │   ├── SeaStreamsDatabase.kt     # Room DB (v6; schemas exported to app/schemas/)
│   │   ├── ProfileAddonsMigration.kt # Manual v4→v5 migration: rebuilds `addons` per profile and copies every legacy app-wide addon row into every existing profile in one SQL step (inside Room's migration transaction, before any read). Its SQL is unit-tested against real SQLite via test-only `org.xerial:sqlite-jdbc`.
│   │   ├── LastUsedStreamMigration.kt # Manual v5→v6 migration: creates `last_used_streams` (+ `profileId` index). Its SQL is Room's exported createSql verbatim and is sqlite-jdbc-tested (schema match, per-profile rows, cascade on profile delete).
│   │   ├── dao/                      # ProfileDao, LibraryDao, WatchProgressDao (tracks resume position + dismissedFromContinueWatching flag), AddonDao (every query/mutation takes `profileId`; `deleteAllForProfile`, `profileIdsMissing` for built-in repair), LastUsedStreamDao (`get` / `@Upsert` of one movie's or episode's LAST USED stream)
│   │   └── entity/                   # Room @Entity definitions. `AddonEntity` is per profile: PK (`profileId`, `manifestUrl`), FK → `profiles.id` ON DELETE CASCADE, `sortOrder` dense within each profile (index on `profileId, sortOrder`). `LastUsedStreamEntity` (`last_used_streams`): PK (`profileId`, `tmdbId`, `mediaType`, `seasonNumber`, `episodeNumber`; movies use `0/0`), columns `addonUrl`, `streamUrl`, `label`, `description`, `updatedAtMillis`, FK → `profiles.id` ON DELETE CASCADE. Deliberately separate from `watch_progress`, whose rows are rebuilt on every write and deleted by Clear progress / Mark unwatched
│   ├── datastore/
│   │   ├── SettingsDataStore.kt      # Typed Preferences DataStore. General Settings (background color, buffer profile, parallel connections, audio, subtitles, player, inline trailers) are persisted per profile under `p{profileId}/…` keys and always reflect the active profile; the active profile id, TMDB key and Movies/TV provider rows stay global. Deleting a profile clears its keys; pre-per-profile app-wide values are copied once into every existing profile at startup by `data/setup/ProfileSettingsMigrator`, which also purges values of removed settings (`RETIRED_SETTING_NAMES` in `ProfileSettingsCodec.kt`, e.g. the former catalog row scroll speed).
│   │   └── ProfileSettingsCodec.kt   # Per-profile key set + typed reader/writer (scoped value → legacy global fallback → default)
│   └── webserver/
│       ├── LocalPortalServer.kt      # Embedded Ktor CIO server on port 8765
│       └── PortalHtmlTemplate.kt     # Mobile-responsive HTML/JS UI served to phone/PC browsers. The addon editor targets one selected profile (defaults to the TV's active profile); every addon request carries `profileId`, and `/api/state` returns `profileAddons` per profile.
├── data/
│   ├── addon/AddonRepository.kt      # Per-profile addon install/remove/rename/enable/reorder (all take `profileId`; unknown profiles rejected); BuiltInAddons = per-profile defaults (TMDB protected + OpenSubtitles)
│   ├── profile/ProfileRepository.kt  # create() seeds the new profile's own default addons via AddonDao; delete() removes its addons (FK cascades too)
│   └── setup/                        # ProfileSettingsMigrator (per-profile settings), BuiltInAddonGuard (startup: re-adds protected TMDB to any profile missing it), DebugProfileSeeder
├── domain/
│   ├── model/                        # MediaItem, TitleDetails + CreditPerson (director/creator + top 9 cast), Episode, StreamSource + StreamAddon (url + display name of one stream-capable addon), SubtitleTrack, UserProfile, AddonConfig, PlaybackRequest (`Media.artwork: PlaybackArtwork` = backdrop/logo/title for the loading screen, derived from `target.details.media` so every entry point and in-player switch carries it; `Media.shuffle: ShuffleMode` = the session's next-episode mode, derived from `target.shuffle`; `Media.streamAddons` = the resolution's stream addons for the Streams overlay's addon cards)
│   ├── playback/                     # PlaybackTarget (movie/episode to play + `shuffle: ShuffleSession`), PlaybackStreamResolver, EpisodeNavigator, AutoNextPolicy, PlaybackProgressRecorder. LastUsedStreams.kt: `LastUsedStreamRecorder` (record / lastUsed / indexIn for a PlaybackTarget, per profile) + pure `lastUsedIndex(streams, saved)` tiered matcher (exact URL → addon+label+description → unique addon+label). Recorded by `PlayerSwitching.onStreamReady` (built-in `PlayerEvent.Ready`, once per target+stream) and `PlaybackViewModel.start` (external hand-off); read into `StreamPickerState.lastUsedIndex` (same update as the streams) and `PlayerUiState.lastUsedIndex` (`PlayerSwitching.loadLastUsed` on every `startMedia`). `ShuffleSession(mode: ShuffleMode = None | Show | Season(n), played)` is set only by Details `Shuffle` (Show) and Episodes `Shuffle Season` (Season); every other entry point and any in-player `Episodes` overlay pick (`PlaybackTarget.forEpisode`) is sequential. `PlaybackStreamResolver.next()` → `EpisodeNavigator.next(current, seasons, lastAired, session)` is the single next-episode choice for the HUD `Next Episode` button and built-in / external Auto-Play Next Episode: sequential order, or a random aired episode from the show / that season (never the one just played, avoiding `played` until the pool is used up), with the session carried onto the next target
│   ├── filter/
│   │   ├── AgeRestrictionEngine.kt   # Pure Kotlin certification + genre safety evaluator (Kids profiles & Profile Gate artwork)
│   │   └── SdhSubtitleCleaner.kt     # Pure Kotlin SDH cue stripper
│   └── usecase/                      # GetHomeCatalogsUseCase, ResolveStreamsUseCase (`StreamResolution.streams` = one flat list in addon `sortOrder` — auto-play `topStream` and fallback use it unchanged; `streamAddons` = every enabled addon that can serve `stream` for the title's type + id, in `sortOrder`, kept even with 0 results or a failure), ResolveTrailerStreamUseCase, ResolveContinueWatchingTargetUseCase (CW card -> age-checked PlaybackTarget, single-title /external_ids), ImportMdbListUseCase
├── player/
│   ├── SeaStreamsPlayerManager.kt    # ExoPlayer wrapper + LoadControl buffer profiles (BufferProfiles.kt: byte target wins, min = max duration) + Stream Fallback Timer (1s-10s) + MergingMediaSource trailers
│   ├── ParallelRangeDataSource.kt    # Multi-connection HTTP Byte-Range DataSource wrapper (1-4 concurrent connections, default 4) + live throughput tracker; 206 probe with single-stream fallback
│   ├── ParallelRangeReader.kt        # Sliding-window ring: N workers pipeline sequential ranges ahead of ExoPlayer up to the high-water mark; deterministic cancel on seek/close
│   ├── RangeReadAhead.kt             # Ring window per buffer profile: 24/64/128 MB high-water (heap-clamped), 4 MB chunks for the first 16 MB, then 8 MB
│   ├── InlineTrailerController.kt    # Hover delay/cancellation + preview state, keyed by InlineTrailerSlot(rowKey, index, itemKey) — never by title alone; playsIn(slot, cardFocused) gates surface attachment
│   ├── InlineTrailerPlayerManager.kt # Single shared lifecycle-aware ExoPlayer for 16:9 catalog row hover previews (card-agnostic; ownership lives in InlineTrailerController's slot)
│   ├── audio/
│   │   └── DialogueNightAudioProcessor.kt # Custom Media3 AudioProcessor (Center boost + DRC Night Mode)
│   └── subtitle/
│       └── CleanSdhTextRenderer.kt   # Intercepts cues when Clean SDH is active + applies session/global styles
└── ui/
    ├── theme/                        # Color.kt, Type.kt (FredokaFontFamily from res/font/fredoka_semibold.ttf), FocusModifiers.kt,
    │                                 # SelectLongPress.kt (shared OK long-press, reset on focus change; never pass onLongClick
    │                                 # to a tv Surface: its internal long-click flag survives focus loss and eats the next click)
    │                                 # FocusModifiers.kt: onDpadEdgeWrap (bidirectional edge-stop-then-wrap: Left/Up on the first
    │                                 # item → last, Right/Down on the last → first; held repeats stop), D-pad repeat throttles,
    │                                 # ignoreInitialKeyUp
    │                                 # ListEdgeWrap.kt: verticalEdgeKeys (pure: Up on first, Down on last, both on a single item),
    │                                 # Modifier.verticalListEdgeWrap + rememberListEdgeWrap (scroll + focus first/last) for the
    │                                 # two-way stream panel columns (Addons + Choose a stream) / Episodes seasons + episodes lists
    ├── navigation/                   # SeaStreamsNavGraph.kt (Splash -> AppGate: Onboarding | Profile Gate)
    ├── components/
    │   ├── TopNavBar.kt              # Avatar, Clock, Search, Home, Movies, TV Shows, Library, Settings (Avatar ⇄ Settings edge wrap loop; no focusRestorer — navBarEntry sends D-pad Up/Down entry to the active pill and never redirects programmatic requests, so Back → active pill and Settings-close → gear land exactly)
    │   ├── HeroCarousel.kt           # Full-width hero card with horizontal D-pad cycling
    │   ├── ExpandingCatalogRow.kt    # TvLazyRow with fixed slow glide/expansion timing (`CATALOG_ROW_GLIDE_MS` / `CARD_EXPAND_MS` in ExpandingCard.kt) + 2:3 -> 16:9 transition + View More + wrap loop + `holdInfo` (keeps the info panel while the row's long-press menu holds focus)
    │   ├── InlineTrailerOverlay.kt   # 16:9 card PlayerView surface + top-right white muted speaker badge; attaches only when playsIn(slot, cardFocused) — the one focused card owning the playing slot, so a duplicate title on an unfocused row keeps its backdrop
    │   └── PosterGrid.kt             # 5-column TvLazyVerticalGrid
    └── screens/
        ├── splash/                   # SplashScreen.kt (black hold screen, icon + Fredoka "seastreams" 420ms black start, then one-shot 2.84s left-to-right pop-in + bobbing; >= 3.35s AND AppGate loaded; preloads Profile Gate backdrop)
        ├── profiles/                 # ProfileSelectScreen.kt, ProfileCreateScreen.kt (with QR code)
        ├── home/                     # HomeScreen.kt + HomeViewModel.kt
        ├── catalog/                  # MoviesScreen.kt, TvShowsScreen.kt, ViewMoreScreen.kt
        ├── details/                  # DetailsScreen.kt, DetailsCastGrid.kt (translucent rounded card, 2x5 Director + Cast portraits, non-focusable), EpisodesScreen.kt
        ├── search/                   # SearchScreen.kt (Voice mic + hover genre sidebar)
        ├── library/                  # LibraryScreen.kt (All/Movies/Shows + Sort + A-Z jump bar)
        ├── playback/                 # PlaybackHost.kt + PlaybackViewModel.kt; StreamPickerModal.kt (stream selection modal: BackHandler + StreamListPanel); StreamListPanel.kt (shared stream modal container — 760dp panel over a dimmed scrim, a `Row` of the AddonColumn (hidden while loading or with no stream addons), a 1dp divider and the `Choose a stream` column: header, then a two-way wrapping LazyColumn of the selected addon's StreamResultCards or its "returned no results" message; cards carry global stream indices so rank/playing check/`lastUsedIndex`/`onChoose` match the flat list; initial addon + both scrolls + focus via pure `initialFocus()`; Back left to the caller; used by StreamPickerModal and the player Streams overlay); AddonStreamGroups.kt (pure: `addonGroups(addons, streams)` → per-addon global indices in addon order, `initialFocus(groups, lastUsedIndex)`, `columnSwitch(column, key, addonHasStreams)` Left/Right decisions between the two columns); AddonColumn.kt (220dp `Addons` column: header + two-way wrapping LazyColumn of AddonFilterCards — focus selects, `basicMarquee` names, focused / selected / idle styles, ignoreInitialKeyUp); StreamResultCard.kt (shared full-width stream card: soft-wrapping text, no maxLines, height follows content; `lastUsed` adds the gold `LAST USED` pill top-right on the addon row, addon name `weight(1f)` so it wraps beside it; pure streamCardText() keeps every description line; also used by the player Streams overlay)
        ├── player/                   # PlayerScreen.kt + PlayerOverlayControls.kt; PlayerPanels.kt (Subtitles/Audio/Episodes/Info right-hand panels; the Streams overlay renders playback/StreamListPanel.kt); PlayerTransport.kt (play/pause/seek); PlayerLoading.kt (pure LoadingVisual state machine: `PlayerUiState.started` is reset by every new stream and set by `PlayerEvent.FirstFrame` [`onRenderedFirstFrame`]. Not started → BACKDROP_AND_LOGO; started + buffering → LOGO_ONLY; trailers → NONE); PlayerLoadingOverlay.kt (full-screen backdrop + pulsing logo/title, also drawn by playback/PlaybackHost.kt while `PlaybackUiState.loading` covers auto-play's stream lookup)
        └── settings/                 # SettingsScreen.kt (Addons, Providers, Playback, Audio, Subtitles, QR Portal)
```

## 2. Launcher Resources (App Icon & TV Banner)
Generated from `docs/ui-references/tv_icon.png` (512x512) and `docs/ui-references/tv_banner.png` (1280x720) by `tools/launcher-assets/Generate-LauncherAssets.ps1` (PowerShell + built-in .NET `System.Drawing`, no Gradle dependency). Re-run it whenever either reference changes; never hand-edit the generated PNGs.
```text
app/src/main/res/
├── mipmap-anydpi/ic_launcher.xml        # Adaptive icon: @color/ic_launcher_background + @mipmap/ic_launcher_foreground
├── mipmap-anydpi/ic_launcher_round.xml  # Same layers (minSdk 26, so no -v26 qualifier); no <monochrome> layer (TV launchers don't theme icons)
├── mipmap-{m,h,xh,xxh,xxxh}dpi/
│   ├── ic_launcher_foreground.png       # 108dp opaque #000000 layer; every non-black pixel within a 31dp radius (66dp safe zone)
│   ├── ic_launcher.png                  # 48dp legacy square fallback
│   └── ic_launcher_round.png            # 48dp legacy circle-clipped fallback
├── drawable-{m,h,xh,xxh,xxxh}dpi/
│   └── tv_banner.png                    # 160x90dp Leanback banner (320x180 px at xhdpi; 160x90 .. 640x360 px), lockup at ~80% width on #000000
├── values/ic_launcher_background.xml    # <color name="ic_launcher_background">#000000</color>
└── drawable/ic_seastreams_icon.png      # Splash-only (SplashScreen.kt); unrelated to the launcher icon
```
- `AndroidManifest.xml`: `<application>` sets `android:icon="@mipmap/ic_launcher"`, `android:roundIcon="@mipmap/ic_launcher_round"`, `android:banner="@drawable/tv_banner"`; the `LEANBACK_LAUNCHER` `MainActivity` repeats `android:banner` (launchers read either `ActivityInfo.banner` or `ApplicationInfo.banner`) and inherits the icon.
- The Android 12+ system splash never shows the launcher icon: `values-v31/themes.xml` pins `windowSplashScreenAnimatedIcon` to `@android:color/transparent`.