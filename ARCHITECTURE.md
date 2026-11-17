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
│   │   └── SettingsDataStore.kt      # Typed Preferences DataStore (UI scroll speed, audio, subtitles, player, inline trailers, active profile)
│   └── webserver/
│       ├── LocalPortalServer.kt      # Embedded Ktor CIO server on port 8765
│       └── PortalHtmlTemplate.kt     # Mobile-responsive HTML/JS UI served to phone/PC browsers
├── domain/
│   ├── model/                        # MediaItem, Episode, StreamSource, SubtitleTrack, UserProfile, AddonConfig, PlaybackRequest
│   ├── filter/
│   │   ├── AgeRestrictionEngine.kt   # Pure Kotlin certification + genre safety evaluator (Kids profiles & Profile Gate artwork)
│   │   └── SdhSubtitleCleaner.kt     # Pure Kotlin SDH cue stripper
│   └── usecase/                      # GetHomeCatalogsUseCase, ResolveStreamsUseCase, ResolveTrailerStreamUseCase, ImportMdbListUseCase
├── player/
│   ├── SeaStreamsPlayerManager.kt    # ExoPlayer wrapper + Stream Fallback Timer (1s-10s) + MergingMediaSource trailers
│   ├── InlineTrailerPlayerManager.kt # Single shared lifecycle-aware ExoPlayer for 16:9 catalog row hover previews
│   ├── audio/
│   │   └── DialogueNightAudioProcessor.kt # Custom Media3 AudioProcessor (Center boost + DRC Night Mode)
│   └── subtitle/
│       └── CleanSdhTextRenderer.kt   # Intercepts cues when Clean SDH is active + applies session/global styles
└── ui/
    ├── theme/                        # Color.kt, Type.kt, FocusModifiers.kt
    ├── navigation/                   # SeaStreamsNavGraph.kt
    ├── components/
    │   ├── TopNavBar.kt              # Avatar, Clock, Search, Home, Movies, TV Shows, Library, Settings
    │   ├── HeroCarousel.kt           # Full-width hero card with horizontal D-pad cycling
    │   ├── ExpandingCatalogRow.kt    # TvLazyRow with configurable scroll/expansion speed + 2:3 -> 16:9 transition + View More + wrap loop
    │   ├── InlineTrailerOverlay.kt   # 16:9 card PlayerView surface + top-right white muted speaker badge
    │   └── PosterGrid.kt             # 5-column TvLazyVerticalGrid
    └── screens/
        ├── profiles/                 # ProfileSelectScreen.kt, ProfileCreateScreen.kt (with QR code)
        ├── home/                     # HomeScreen.kt + HomeViewModel.kt
        ├── catalog/                  # MoviesScreen.kt, TvShowsScreen.kt, ViewMoreScreen.kt
        ├── details/                  # DetailsScreen.kt, EpisodesScreen.kt
        ├── search/                   # SearchScreen.kt (Voice mic + hover genre sidebar)
        ├── library/                  # LibraryScreen.kt (All/Movies/Shows + Sort + A-Z jump bar)
        ├── player/                   # PlayerScreen.kt + PlayerOverlayControls.kt
        └── settings/                 # SettingsScreen.kt (Addons, Providers, Playback, Audio, Subtitles, QR Portal)