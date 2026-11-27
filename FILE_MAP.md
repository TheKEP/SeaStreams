# FILE_MAP.md — seastreams (`app/src/main/java/com/seastreams/tv/`)

## App Entry & Core
- `SeaStreamsApp.kt` — Hilt app, Coil setup, startup orphan-cache sweep, `BuiltInAddonGuard` launch
- `MainActivity.kt` — Single-activity TV host, `BackLongPressGate` dispatch
- `core/cache/CacheManager.kt` — Cache size, clear cache, startup orphan sweep
- `core/image/ImageLoaderSetup.kt` — Coil 3 memory (25%) + disk (150 MB) cache
- `core/network/` — `NetworkModule.kt` (OkHttp 100 MB cache, Ktor, 429 retry), `TmdbApiService.kt`, `MdbListApiService.kt`, `StremioManifest.kt`, `StremioAddonClient.kt`, `LocalNetworkHost.kt`
- `core/security/SecretCipher.kt` — Android Keystore AES-256-GCM encryption for Usenet/Debrid secrets
- `core/database/` — `SeaStreamsDatabase.kt` (Room v9), migrations (`*Migration.kt`), `dao/` (`ProfileDao`, `LibraryDao`, `WatchProgressDao`, `AddonDao`, `LastUsedStreamDao`, `UsenetProviderDao`, `NewznabIndexerDao`, `DebridProviderDao`), `entity/`
- `core/datastore/` — `SettingsDataStore.kt` (per-profile `p{id}/`), `ProfileSettingsCodec.kt`, `PlaybackLevels.kt`, `UsenetSettingsStore.kt`, `DebridSettingsStore.kt`, `StreamProcessingStore.kt`, `StreamProcessingBackup.kt`
- `core/webserver/` — `LocalPortalServer.kt` (Ktor port 8765), `PortalRoutes.kt`, `PortalHtmlTemplate.kt`, `PortalUsenet*`, `PortalDebrid*`, `PortalStream*`, `PortalBackup*`, `NntpProxyRoutes.kt`

## Data & Domain
- `data/addon/AddonRepository.kt` — Per-profile addon CRUD/order, `BuiltInAddons` (`builtin://tmdb`, `usenet://builtin`, `debrid://builtin`), `UsenetAddonState`, `DebridAddonState`
- `data/profile/ProfileRepository.kt` — Profile CRUD + default addon seeding
- `data/setup/` — `ProfileSettingsMigrator.kt`, `BuiltInAddonGuard.kt`, `SourceTogglesMigrator.kt`, `DebugProfileSeeder.kt`
- `data/sources/SourceClears.kt` — 5s undoable `UsenetSettingsClear` and `DebridSettingsClear`
- `data/usenet/` — `UsenetSourcesRepository.kt`, `presets/`, `headers/` (`RequestHeaderPresets.kt`, `HeaderVersionUpdater.kt`), `newznab/` (`NewznabClient.kt`, `NzbGrabber.kt`, `UsenetStreamSearch.kt`, `ReleaseMatch.kt`)
- `data/debrid/` — `DebridSources.kt`, `DebridSourcesRepository.kt`, `DebridConnectionTester.kt`, `api/` (per-service clients + `DebridLibraryModels.kt`), `auth/` (`DeviceFlows.kt`, `OAuthCodeFlows.kt`, `DeviceAuthSessions.kt`, `DebridTokenRefresher.kt`), `library/` (`DebridLibraryCache.kt`, `DebridLibraryMatcher.kt`, `EpisodeFilePicker.kt`, `DebridLibrarySearch.kt`), `stream/` (`DebridStreamRegistry.kt`, `DebridLinkResolver.kt`)
- `data/backup/` — `AppBackupCodec.kt`, `BackupExporter.kt`, `BackupRestorer.kt`, `RestorePlan.kt`, `Backup*Writer.kt`, `HistoryBackup*.kt`
- `data/trailer/NewPipeTrailerExtractor.kt` — NewPipe YouTube trailer stream extraction
- `domain/model/` — `MediaItem`, `TitleDetails`, `Episode`, `StreamSource`, `SubtitleTrack`, `UserProfile`, `AddonConfig`, `PlaybackRequest`
- `domain/playback/` — `PlaybackTarget`, `PlaybackStreamResolver`, `EpisodeNavigator`, `AutoNextPolicy`, `PlaybackProgressRecorder`, `LastUsedStreams.kt`
- `domain/filter/` — `AgeRestrictionEngine.kt`, `FamilyCatalogPolicy.kt`, `SdhSubtitleCleaner.kt`
- `domain/streams/` — `StreamProcessingSettings.kt`, `StreamPipeline.kt`, `ProcessStreamsUseCase.kt`, `parse/`, `filter/`, `sort/`, `format/`
- `domain/usecase/` — `GetHomeCatalogsUseCase.kt`, `ResolveStreamsUseCase.kt`, `BuiltInSlot.kt`, `ResolveTrailerStreamUseCase.kt`, `ResolveContinueWatchingTargetUseCase.kt`, `ImportMdbListUseCase.kt`

## Playback, Usenet Engine & Subtitles
- `player/` — `SeaStreamsPlayerManager.kt`, `PlayerEngineFactory.kt`, `StreamFallbackController.kt`, `StreamFailureReason.kt`, `StreamFailureMessages.kt`, `StreamPreflight.kt`, `DolbyVisionCompat.kt`, `DolbyVisionPolicy.kt`, `DisplayHdrSupport.kt`, `VideoDynamicRange.kt`, `AudioRendererPolicy.kt`, `ParallelRangeDataSource.kt`, `ParallelRangeReader.kt`, `RangeReadAhead.kt`, `InlineTrailerController.kt`, `InlineTrailerPlayerManager.kt`
- `player/audio/` — `DialogueNightAudioProcessor.kt`, `SpeechActivityTap.kt`, `PassthroughGateSink.kt`, `SafeDownmix.kt`, `PcmSamples.kt`
- `player/subtitle/` — `CleanSdhTextRenderer.kt`, `sync/` (`SubtitleAutoSync.kt`, `ReferenceTrackPicker.kt`, `ReferenceCueCollector.kt`, `ActivityGrid.kt`, `OffsetCorrelator.kt`, `OnsetRefiner.kt`, `HalfAgreement.kt`, `SyncRate.kt`, `RateFit.kt`, `SyncCueFilter.kt`)
- `player/usenet/` & `player/external/` — `NntpDataSource.kt`, `PlaybackDataSources.kt`, `UsenetLoadErrorPolicy.kt`, `UsenetPreflight.kt`, `NntpProxyService.kt`, `ExternalStreamUrls.kt`
- `player/debrid/` — `DebridPreflight.kt`
- `usenet/` — `nntp/` (`NntpEngine.kt`, `NntpConnection.kt`, `ProviderWorkerPool.kt`, `ProviderSelector.kt`, `CircuitBreaker.kt`, `NntpConnectionTester.kt`), `yenc/`, `nzb/`, `stream/` (`UsenetFile.kt`, `SegmentLocator.kt`, `ReadAheadWindow.kt`, `SegmentArena.kt`), `cache/`, `archive/` (`RarArchive.kt`, `SevenZipArchive.kt`, `MediaSelector.kt`, `ObfuscatedSetResolver.kt`), `verify/` (`CensusVerifier.kt`, `DamagePolicy.kt`, `HoleFiller.kt`, `ReleaseHealthCache.kt`), `session/` (`UsenetSessionRegistry.kt`, `UsenetSessionOpener.kt`, `UsenetStreamRegistry.kt`, `ReleaseAssembler.kt`)

## UI (`ui/`)
- `ui/theme/` — `Color.kt`, `Type.kt`, `FocusModifiers.kt`, `SelectLongPress.kt`, `ListEdgeWrap.kt`, `ReorderScroll.kt`, `ReorderFocus.kt`
- `ui/navigation/` — `SeaStreamsNavGraph.kt`, `ProfileSwitcher.kt`, `ProfileCovers.kt`, `BackLongPressGate.kt`, `SearchShortcut.kt`
- `ui/components/` — `TopNavBar.kt`, `HeroCarousel.kt`, `ExpandingCatalogRow.kt`, `ExpandingCard.kt`, `CatalogShowcaseSlot.kt`, `CatalogStripGeometry.kt`, `WatchedIndicator.kt`, `HdrUiDim.kt`, `InlineTrailerOverlay.kt`, `PosterGrid.kt`, `PosterGridFocus.kt`
- `ui/screens/splash/` — `SplashScreen.kt`, `SplashMotion.kt`
- `ui/screens/profiles/` — `ProfileSelectScreen.kt`, `ProfileGateLayout.kt`, `ProfileCreateScreen.kt`
- `ui/screens/home/` — `HomeScreen.kt`, `HomeViewModel.kt`
- `ui/screens/catalog/` — `MoviesScreen.kt`, `TvShowsScreen.kt`, `ViewMoreScreen.kt`
- `ui/screens/details/` — `DetailsScreen.kt`, `DetailsActions.kt`, `DetailsMetadataBadges.kt`, `DetailsCastGrid.kt`, `EpisodesScreen.kt`
- `ui/screens/search/` — `SearchScreen.kt`, `VoiceSearch.kt`, `VoiceSearchRouting.kt`, `SearchTextField.kt`, `SearchFieldIme.kt`, `SearchFieldKeys.kt`, `SearchTopBarFocus.kt`
- `ui/screens/library/` — `LibraryScreen.kt`, `LibraryViewModel.kt`, `LibraryViews.kt`, `LibraryControls.kt`, `LetterJumpScheduler.kt`, `LibraryEdgeWrap.kt`, `LibraryGridFocusReset.kt`
- `ui/screens/playback/` — `PlaybackHost.kt`, `PlaybackViewModel.kt`, `ShuffleResume.kt`, `StreamPickerModal.kt`, `StreamListPanel.kt`, `AddonStreamGroups.kt`, `AddonColumn.kt`, `StreamResultCard.kt`
- `ui/screens/player/` — `PlayerScreen.kt`, `PlayerOverlayControls.kt`, `PlayerPanels.kt`, `PlayerTransport.kt`, `PlayerProgressBinding.kt`, `PlayerSeekBar.kt`, `PlayerLoading.kt`, `PlayerLoadingOverlay.kt`, `PlayerStreamFailure.kt`, `PlayerSubtitlePanel.kt`, `PlayerSubtitles.kt`, `PlayerAutoSync.kt`, `PlayerListPanels.kt`, `PlayerInfo.kt`, `InfoBitrate.kt`, `PlayerInfoPanel.kt`, `PlayerPanelScrollbar.kt`, `PlayerWindowReset.kt`
- `ui/screens/settings/` — `SettingsScreen.kt`, `SettingsCategory.kt`, `SettingsCategoryBar.kt`, `SettingsCategoryList.kt`, `SettingsItems.kt`, `SettingsCategoryItems.kt`, `SettingsLabels.kt`, `SettingsRows.kt`, `SettingsPanels.kt`, `AddonRemovalFocus.kt`, `SubtitleStyleScreen.kt`, `SectionedRowsPanel.kt`, `sources/` (`SourcesItems.kt`, `SourcesViewModel.kt`, `DebridSourcesViewModel.kt`, `SourcesPanelHost.kt`, `SourceListPanel.kt`, `UsenetPerformancePanel.kt`), `streams/` (`StreamProcessingViewModel.kt`, `StreamPanelHost.kt`, `MultiChoicePanel.kt`, `StreamFilterItems.kt`, `StreamSortPanel.kt`, `StreamFormatterPanel.kt`)