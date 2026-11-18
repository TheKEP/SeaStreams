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
│   │   ├── SeaStreamsDatabase.kt     # Room DB (v1)
│   │   ├── dao/                      # ProfileDao, LibraryDao, WatchProgressDao (tracks resume position + dismissedFromContinueWatching flag), AddonDao
│   │   └── entity/                   # Room @Entity definitions
│   ├── datastore/
│   │   ├── SettingsDataStore.kt      # Typed Preferences DataStore. General Settings (background color, UI scroll speed, buffer profile, parallel connections, audio, subtitles, player, inline trailers) are persisted per profile under `p{profileId}/…` keys and always reflect the active profile; the active profile id, TMDB key and Movies/TV provider rows stay global. Deleting a profile clears its keys; pre-per-profile app-wide values are copied once into every existing profile at startup by `data/setup/ProfileSettingsMigrator`.
│   │   └── ProfileSettingsCodec.kt   # Per-profile key set + typed reader/writer (scoped value → legacy global fallback → default)
│   └── webserver/
│       ├── LocalPortalServer.kt      # Embedded Ktor CIO server on port 8765
│       └── PortalHtmlTemplate.kt     # Mobile-responsive HTML/JS UI served to phone/PC browsers
├── domain/
│   ├── model/                        # MediaItem, TitleDetails + CreditPerson (director/creator + top 9 cast), Episode, StreamSource, SubtitleTrack, UserProfile, AddonConfig, PlaybackRequest
│   ├── filter/
│   │   ├── AgeRestrictionEngine.kt   # Pure Kotlin certification + genre safety evaluator (Kids profiles & Profile Gate artwork)
│   │   └── SdhSubtitleCleaner.kt     # Pure Kotlin SDH cue stripper
│   └── usecase/                      # GetHomeCatalogsUseCase, ResolveStreamsUseCase, ResolveTrailerStreamUseCase, ResolveContinueWatchingTargetUseCase (CW card -> age-checked PlaybackTarget, single-title /external_ids), ImportMdbListUseCase
├── player/
│   ├── SeaStreamsPlayerManager.kt    # ExoPlayer wrapper + LoadControl buffer profiles + Stream Fallback Timer (1s-10s) + MergingMediaSource trailers
│   ├── ParallelRangeDataSource.kt    # Multi-connection HTTP Byte-Range DataSource wrapper (1-4 concurrent chunks) + live throughput tracker
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
    ├── navigation/                   # SeaStreamsNavGraph.kt (Splash -> AppGate: Onboarding | Profile Gate)
    ├── components/
    │   ├── TopNavBar.kt              # Avatar, Clock, Search, Home, Movies, TV Shows, Library, Settings (Avatar ⇄ Settings edge wrap loop)
    │   ├── HeroCarousel.kt           # Full-width hero card with horizontal D-pad cycling
    │   ├── ExpandingCatalogRow.kt    # TvLazyRow with configurable scroll/expansion speed + 2:3 -> 16:9 transition + View More + wrap loop
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
        ├── player/                   # PlayerScreen.kt + PlayerOverlayControls.kt
        └── settings/                 # SettingsScreen.kt (Addons, Providers, Playback, Audio, Subtitles, QR Portal)