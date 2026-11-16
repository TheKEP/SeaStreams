# ARCHITECTURE.md — SeaStreams Technical Blueprint

## 1. Module & Package Layout (`com.seastreams.tv`)
```text
app/src/main/java/com/seastreams/tv/
├── SeaStreamsApp.kt                  # Hilt Application class
├── MainActivity.kt                   # Single-activity Compose TV host + Back handler
├── core/
│   ├── network/
│   │   ├── NetworkModule.kt          # OkHttp (100MB disk cache) + Ktor HttpClient + 429 Retry Interceptor
│   │   ├── TmdbApiService.kt         # /discover, /search, /movie/{id}, /tv/{id}, /external_ids
│   │   ├── MdbListApiService.kt      # Fetches MDBList JSON endpoints from list URLs
│   │   └── StremioAddonClient.kt     # Fetches manifest.json, /stream, /subtitles
│   ├── database/
│   │   ├── SeaStreamsDatabase.kt     # Room DB (v1)
│   │   ├── dao/                      # ProfileDao, LibraryDao, WatchProgressDao, CacheDao, AddonDao
│   │   └── entity/                   # Room @Entity definitions
│   ├── datastore/
│   │   └── SettingsDataStore.kt      # Typed Preferences DataStore (audio, subtitles, player, active profile)
│   └── webserver/
│       ├── LocalPortalServer.kt      # Embedded Ktor CIO server on port 8765
│       └── PortalHtmlTemplate.kt     # Mobile-responsive HTML/JS UI served to phone/PC browsers
├── domain/
│   ├── model/                        # MediaItem, Episode, StreamSource, SubtitleTrack, UserProfile, AddonConfig
│   ├── filter/
│   │   ├── AgeRestrictionEngine.kt   # Pure Kotlin certification evaluator (Adult vs Kids + Unrated toggle)
│   │   └── SdhSubtitleCleaner.kt     # Pure Kotlin SDH cue stripper
│   └── usecase/                      # GetHomeCatalogsUseCase, ResolveStreamsUseCase, ImportMdbListUseCase
├── player/
│   ├── SeaStreamsPlayerManager.kt    # ExoPlayer wrapper + Stream Fallback Timer (1s-10s)
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
    │   ├── ExpandingCatalogRow.kt    # TvLazyRow with 2:3 -> 16:9 focused card transition + View More + wrap loop
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