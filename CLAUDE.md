# CLAUDE.md — SeaStreams Engineering Constitution

## Project Identity
SeaStreams is a high-performance, native Kotlin Android TV streaming client compatible with Stremio addons, TMDB metadata, and MDBList catalogs, targeting the Onn 4K Pro and Google TV Streamer 4K.

## Core Architecture & Stack
- **Language:** Kotlin 2.0+ (Strict mode, no `!!` non-null assertions).
- **UI:** Jetpack Compose for TV (`androidx.tv.material3.*`, `TvLazyRow`, `TvLazyVerticalGrid`). Never use standard mobile Compose buttons/cards where `androidx.tv` equivalents exist.
- **Architecture:** Clean MVVM + Unidirectional Data Flow (`UI -> ViewModel -> UseCase/Repository -> Room/Ktor`).
- **DI:** Hilt (`@HiltViewModel`, `@Singleton`).
- **Playback:** AndroidX Media3 (`ExoPlayer`).
- **Local Server:** Ktor Embedded Server (`CIO` engine) for the QR/LAN Web Portal.

## Strict Anti-Bloat & Anti-Hallucination Rules
1. **No Placeholder Code:** Never write `// TODO: implement later`, stubbed functions, or fake mock data in production code. Every milestone must ship working, compiled, tested logic.
2. **File Size Limit:** Keep Kotlin files under 350 lines. Split large screens into cohesive sub-composables (`HeroSection.kt`, `ExpandingCatalogRow.kt`, `TopNavBar.kt`).
3. **Zero Unapproved Libraries:** Do not add third-party dependencies outside `ARCHITECTURE.md` without explicit approval.
4. **D-Pad First:** Every interactive element must be D-pad focusable, show clear visual focus indicators, and handle Up/Down/Left/Right/Back deterministically. Never trap focus.
5. **Respect TMDB Rate Limits:** Never fetch `/external_ids` in batch loops on home/catalog screens. Only fetch `/external_ids` lazily when opening an item's Details screen.
6. **Strict Age Enforcement:** Age restrictions are enforced at the Repository/UseCase layer *before* data reaches any ViewModel or UI component.

## Mandatory Build & Verification Commands
Before marking any task or milestone complete, you MUST run and pass:
- `./gradlew testDebugUnitTest` (All unit tests must pass)
- `./gradlew assembleDebug` (Must compile cleanly with zero errors)

## Reference Documents
- Product Requirements: `PRD.md`
- System Design & Schemas: `ARCHITECTURE.md`
- Stepwise Roadmap: `MILESTONES.md`
- Visual UI Targets: `docs/ui-references/`