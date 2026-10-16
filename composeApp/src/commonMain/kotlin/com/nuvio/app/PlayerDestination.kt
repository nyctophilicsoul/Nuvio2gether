package com.nuvio.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.player.ExternalPlayerIntentResult
import com.nuvio.app.features.player.ExternalPlayerPlatform
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.player.PlayerScreen
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import com.nuvio.app.features.player.sanitizePlaybackResponseHeaders
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.watchprogress.ResumePromptRepository
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.PlayerRoute

private data class PlaybackFallbackState(
    val currentUrl: String,
    val remaining: List<StreamItem>,
)

@Composable
internal fun PlayerDestination(
    route: PlayerRoute,
    navController: NuvioNavigator,
    externalPlayerId: String?,
    externalPlayerNotConfiguredText: String,
    externalPlayerFailedText: String,
    onExternalPlayerLaunch: (PlayerLaunch) -> Unit,
    launchExternalPlayer: (ExternalPlayerIntentResult.Success) -> Boolean,
    openExternalStreamUrl: (String) -> Boolean,
) {
    val onBack = rememberGuardedPopBackStack(navController, route)
    val launch = remember(route.launchId) { PlayerLaunchStore.get(route.launchId) }
    if (launch == null) {
        LaunchedEffect(route.launchId) {
            onBack()
        }
        Box(modifier = Modifier.fillMaxSize())
        return
    }
    LaunchedEffect(launch.videoId) {
        launch.videoId?.let { ResumePromptRepository.markPlayerEntered(it) }
    }
    val fallbackState = remember(route.launchId) {
        mutableStateOf(PlaybackFallbackState(launch.sourceUrl, launch.autoPlayFallbacks))
    }
    val onPlaybackFailure: (Long) -> Boolean = { positionMs ->
        val state = fallbackState.value
        val nextStream = state.remaining.firstOrNull { candidate ->
            val url = candidate.playableDirectUrl
            url != null && url != state.currentUrl
        }
        val nextUrl = nextStream?.playableDirectUrl
        if (nextStream == null || nextUrl == null) {
            false
        } else {
            val remainingFallbacks = state.remaining
                .dropWhile { it != nextStream }
                .drop(1)
                .filter { candidate ->
                    val url = candidate.playableDirectUrl
                    url != null && url != nextUrl && url != state.currentUrl
                }
                .distinctBy { it.playableDirectUrl }
            fallbackState.value = PlaybackFallbackState(nextUrl, remainingFallbacks)
            val nextLaunch = launch.copy(
                sourceUrl = nextUrl,
                sourceAudioUrl = null,
                sourceHeaders = sanitizePlaybackHeaders(nextStream.behaviorHints.proxyHeaders?.request),
                sourceResponseHeaders = sanitizePlaybackResponseHeaders(nextStream.behaviorHints.proxyHeaders?.response),
                externalSubtitles = nextStream.externalSubtitles,
                streamType = nextStream.streamType,
                streamTitle = nextStream.streamLabel,
                streamSubtitle = nextStream.streamSubtitle,
                bingeGroup = nextStream.behaviorHints.bingeGroup,
                providerName = nextStream.addonName,
                providerAddonId = nextStream.addonId,
                torrentInfoHash = null,
                torrentFileIdx = null,
                torrentFilename = null,
                torrentTrackers = emptyList(),
                initialPositionMs = positionMs.coerceAtLeast(0L),
                initialProgressFraction = null,
                autoPlayFallbacks = remainingFallbacks,
            )
            val nextLaunchId = PlayerLaunchStore.put(nextLaunch)
            navController.navigate(PlayerRoute(launchId = nextLaunchId, title = nextLaunch.title)) {
                popUpTo<PlayerRoute> { inclusive = true }
            }
            true
        }
    }
    PlayerScreen(
        profileId = launch.profileId,
        title = launch.title,
        sourceUrl = launch.sourceUrl,
        sourceAudioUrl = launch.sourceAudioUrl,
        sourceHeaders = launch.sourceHeaders,
        sourceResponseHeaders = launch.sourceResponseHeaders,
        externalSubtitles = launch.externalSubtitles,
        streamType = launch.streamType,
        logo = launch.logo,
        poster = launch.poster,
        background = launch.background,
        seasonNumber = launch.seasonNumber,
        episodeNumber = launch.episodeNumber,
        episodeTitle = launch.episodeTitle,
        episodeThumbnail = launch.episodeThumbnail,
        streamTitle = launch.streamTitle,
        streamSubtitle = launch.streamSubtitle,
        initialBingeGroup = launch.bingeGroup,
        pauseDescription = launch.pauseDescription,
        providerName = launch.providerName,
        providerAddonId = launch.providerAddonId,
        contentType = launch.contentType,
        videoId = launch.videoId,
        parentMetaId = launch.parentMetaId,
        parentMetaType = launch.parentMetaType,
        torrentInfoHash = launch.torrentInfoHash,
        torrentFileIdx = launch.torrentFileIdx,
        torrentFilename = launch.torrentFilename,
        torrentTrackers = launch.torrentTrackers,
        initialPositionMs = launch.initialPositionMs,
        initialProgressFraction = launch.initialProgressFraction,
        contentLanguage = launch.contentLanguage,
        launchId = route.launchId,
        onBack = onBack,
        onPlaybackFailure = onPlaybackFailure,
        onOpenInExternalPlayer = { request ->
            val playerLaunch = PlayerLaunch(
                profileId = launch.profileId,
                title = launch.title,
                sourceUrl = request.sourceUrl,
                sourceHeaders = request.sourceHeaders,
                logo = launch.logo,
                poster = launch.poster,
                background = launch.background,
                seasonNumber = launch.seasonNumber,
                episodeNumber = launch.episodeNumber,
                episodeTitle = launch.episodeTitle,
                episodeThumbnail = launch.episodeThumbnail,
                streamTitle = request.streamTitle ?: launch.streamTitle,
                streamSubtitle = launch.streamSubtitle,
                bingeGroup = launch.bingeGroup,
                pauseDescription = launch.pauseDescription,
                providerName = launch.providerName,
                providerAddonId = launch.providerAddonId,
                contentType = launch.contentType,
                videoId = launch.videoId,
                parentMetaId = launch.parentMetaId,
                parentMetaType = launch.parentMetaType,
                initialPositionMs = request.resumePositionMs,
            )
            onExternalPlayerLaunch(playerLaunch)
            val intentResult = ExternalPlayerPlatform.buildIntent(
                request = request,
                playerId = externalPlayerId,
            )
            when (intentResult) {
                is ExternalPlayerIntentResult.Success -> {
                    val launched = launchExternalPlayer(intentResult)
                    if (!launched) {
                        NuvioToastController.show(externalPlayerFailedText)
                    } else if (externalPlayerId == "infuse") {
                        onBack()
                    }
                }
                ExternalPlayerIntentResult.NotConfigured -> {
                    NuvioToastController.show(externalPlayerNotConfiguredText)
                }
                ExternalPlayerIntentResult.Failed -> {
                    NuvioToastController.show(externalPlayerFailedText)
                }
            }
        },
        onOpenExternalUrl = { url ->
            openExternalStreamUrl(url)
        },
        modifier = Modifier.fillMaxSize(),
    )
}
