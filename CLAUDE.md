# CLAUDE.md — SeaStreams Engineering Constitution

## Project Identity
SeaStreams is a high-performance, native Kotlin Android TV streaming client compatible with Stremio addons (streams, subtitles, and Home catalogs), TMDB metadata, and one-time MDBList library imports, targeting the Onn 4K Pro and Google TV Streamer 4K.

## Core Architecture & Stack
- **Language:** Kotlin 2.0+ (Strict mode, no `!!` non-null assertions).
- **UI:** Jetpack Compose for TV (`androidx.tv.material3.*`, `TvLazyRow`, `TvLazyVerticalGrid`). Never use standard mobile Compose buttons/cards where `androidx.tv` equivalents exist.
- **Architecture:** Clean MVVM + Unidirectional Data Flow (`UI -> ViewModel -> UseCase/Repository -> Room/Ktor`).
- **DI:** Hilt (`@HiltViewModel`, `@Singleton`).
- **Playback:** AndroidX Media3 (`ExoPlayer`) + `NewPipeExtractor` (JitPack `com.github.TeamNewPipe:NewPipeExtractor`, backed by `OkHttpDownloader` for in-app trailer stream resolution).
- **Local Server:** Ktor Embedded Server (`CIO` engine) for the QR/LAN Web Portal.

## Strict Anti-Bloat & Anti-Hallucination Rules
1. **No Placeholder Code:** Never write `// TODO: implement later`, stubbed functions, or fake mock data in production code. Every milestone must ship working, compiled, tested logic.
2. **File Size Limit:** Keep Kotlin files under 350 lines. Split large screens into cohesive sub-composables (`HeroSection.kt`, `ExpandingCatalogRow.kt`, `TopNavBar.kt`).
3. **Zero Unapproved Libraries:** Do not add third-party dependencies outside `ARCHITECTURE.md` without explicit approval.
4. **D-Pad First & Key-Up Safety:** Every interactive element must be D-pad focusable, show clear visual focus indicators, and handle Up/Down/Left/Right/Back deterministically. Never trap focus, and ensure overlays/modals opened via `KeyDown` or `onLongClick` ignore the trailing `KeyUp` so newly focused buttons do not fire phantom clicks.
5. **Respect TMDB Rate Limits:** Never fetch `/external_ids` in batch loops on home/catalog screens. Only fetch `/external_ids` lazily for the single title the user opened: its Details screen, or a `Continue Watching` card played directly (*Auto-play from Continue Watching*).
6. **Strict Age Enforcement & Child-Safe Profile Gate:** Age restrictions are enforced at the Repository/UseCase layer *before* data reaches any ViewModel or UI component. Featured backdrop artwork on `ProfileSelectScreen` must also be filtered at the UseCase/Repository layer to strip Horror (`27`), Romance (`10749`), Thriller (`53`), Crime (`80`), unrated items, and anything above `PG`/`TV-PG` when any Kids profile exists (or above `PG-13`/`TV-14` otherwise).

## Mandatory Build & Verification Commands
Before marking any task or milestone complete, you MUST run and pass:
- `./gradlew testDebugUnitTest` (All unit tests must pass)
- `./gradlew assembleDebug` (Must compile cleanly with zero errors)

## Reference Documents
- Product Requirements: `PRD.md`
- System Design & Schemas: `ARCHITECTURE.md`
- Stepwise Roadmap: `MILESTONES.md`
- Visual UI Targets: `docs/ui-references/`