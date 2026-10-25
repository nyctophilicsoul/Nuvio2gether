package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import com.nuvio.app.features.party.OwnSeek
import com.nuvio.app.features.party.PartyMember
import com.nuvio.app.features.party.AvatarStack
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import co.touchlab.kermit.Logger
import com.nuvio.app.features.party.ActiveParty
import com.nuvio.app.features.party.PartyAction
import com.nuvio.app.core.sync.AppForegroundMonitor
import com.nuvio.app.core.sync.AppVisibility
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.party.PartyChat
import com.nuvio.app.features.party.PartyCorrection
import com.nuvio.app.features.party.PartyEvent
import com.nuvio.app.features.party.PartyMedia
import com.nuvio.app.features.party.PartyMemberStatus
import com.nuvio.app.features.party.PartyNotice
import com.nuvio.app.features.party.PartyPanel
import com.nuvio.app.features.party.PartyPlayback
import com.nuvio.app.features.party.PartyRepository
import com.nuvio.app.features.party.PartySource
import com.nuvio.app.features.party.PartyStream
import com.nuvio.app.features.party.isShareableStreamUrl
import com.nuvio.app.features.party.PartySync
import com.nuvio.app.features.party.UnreadBadge
import com.nuvio.app.features.party.messageRes
import com.nuvio.app.features.party.partyMediaKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.party_action_paused
import nuvio.composeapp.generated.resources.party_action_resumed
import nuvio.composeapp.generated.resources.party_action_seeked
import nuvio.composeapp.generated.resources.party_action_speed
import nuvio.composeapp.generated.resources.party_behind
import nuvio.composeapp.generated.resources.party_caught_up
import nuvio.composeapp.generated.resources.party_host_only_note
import nuvio.composeapp.generated.resources.party_paused_for_you
import nuvio.composeapp.generated.resources.party_reconnecting
import nuvio.composeapp.generated.resources.party_waiting
import nuvio.composeapp.generated.resources.party_waiting_for
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal val PlayerScreenRuntime.partyKey: String
    get() = partyMediaKey(parentMetaType, parentMetaId, activeSeasonNumber, activeEpisodeNumber)

internal val PlayerScreenRuntime.isPartySynced: Boolean
    get() = PartyRepository.state.value.party?.media?.key == partyKey

internal val PlayerScreenRuntime.isPartyControlLocked: Boolean
    get() = PartyRepository.state.value.party?.let { party -> !party.canControl && party.media.key == partyKey } == true

internal fun PlayerScreenRuntime.partyBlocksControl(): Boolean {
    if (!isPartyControlLocked) return false
    scope.launch { showGestureMessage(getString(Res.string.party_host_only_note)) }
    return true
}

internal fun PlayerScreenRuntime.openPartyPanel() {
    if (PartyChat.unread.value > 0) showPartyChat = true
    showPartyPanel = true
    controlsVisible = false
}

internal fun PlayerScreenRuntime.toggleOwnPlayback() {
    controlsVisible = true
    when {
        shouldPlay -> {
            partyBehind = true
            shouldPlay = false
            playerController?.pause()
        }
        partyBehind -> {
            shouldPlay = true
            playerController?.play()
        }
        else -> partyBlocksControl()
    }
}

internal fun PlayerScreenRuntime.ownSeekTarget(targetMs: Long): Long? {
    val playback = PartyRepository.playback.value ?: return targetMs
    val partyPositionMs = PartySync.expectedPositionMs(playback, PartyRepository.nowMs(), playbackSnapshot.durationMs)
    return when (val seek = PartySync.ownSeek(targetMs, partyPositionMs, partyBehind)) {
        is OwnSeek.Behind -> {
            partyBehind = true
            seek.positionMs
        }
        OwnSeek.CatchUp -> {
            partyBehind = false
            null
        }
        OwnSeek.Blocked -> {
            partyBlocksControl()
            null
        }
    }
}

internal fun PlayerScreenRuntime.currentPartyMedia(): PartyMedia = PartyMedia(
    type = contentType ?: parentMetaType,
    metaId = parentMetaId,
    metaType = parentMetaType,
    videoId = activeVideoId,
    season = activeSeasonNumber,
    episode = activeEpisodeNumber,
    title = title,
    episodeTitle = activeEpisodeTitle,
    description = activePauseDescription?.take(DESCRIPTION_LIMIT),
    poster = poster,
    background = background,
    logo = logo,
    source = PartySource(
        infoHash = activeTorrentInfoHash,
        fileIdx = activeTorrentFileIdx,
        filename = activeSourceFilename ?: activeTorrentFilename,
        videoHash = activeStream?.behaviorHints?.videoHash,
        videoSize = activeStream?.behaviorHints?.videoSize,
        bingeGroup = currentStreamBingeGroup,
        addonId = activeProviderAddonId,
        name = activeStreamTitle,
    ),
)

internal fun PlayerScreenRuntime.currentPartyStream(): PartyStream? {
    val stream = activeStream ?: return null
    if (!isShareableStreamUrl(activeSourceUrl) || activeSourceAudioUrl != null) return null
    return PartyStream(
        mediaKey = partyKey,
        url = activeSourceUrl,
        headers = activeSourceHeaders,
        responseHeaders = activeSourceResponseHeaders,
        name = stream.name,
        description = stream.description,
        addonName = stream.addonName,
        addonId = stream.addonId,
        addonLogo = stream.addonLogo,
        filename = activeSourceFilename ?: stream.behaviorHints.filename,
        videoSize = stream.behaviorHints.videoSize,
        bingeGroup = stream.behaviorHints.bingeGroup,
        streamType = activeStreamType,
        badges = stream.badges,
    )
}

internal fun PlayerScreenRuntime.partyBaseRate(): Float =
    PartyRepository.playback.value?.rate ?: playbackSnapshot.playbackSpeed

internal fun PlayerScreenRuntime.currentPlaybackPositionMs(): Long {
    val snapshot = playbackSnapshot
    if (!snapshot.isPlaying || snapshot.isLoading) return snapshot.positionMs
    val elapsedMs = playbackSnapshotMark?.elapsedNow()?.inWholeMilliseconds?.coerceIn(0L, 1_000L) ?: 0L
    return snapshot.positionMs + (elapsedMs * snapshot.playbackSpeed).toLong()
}

internal fun PlayerScreenRuntime.publishPartyPlayback(
    positionMs: Long = currentPlaybackPositionMs(),
    rate: Float = partyBaseRate(),
) {
    if (!isPartySynced) return
    PartyRepository.publishPlayback(paused = !shouldPlay, positionMs = positionMs, rate = rate)
}

internal fun PlayerScreenRuntime.startParty() {
    scope.launch {
        val error = PartyRepository.create(currentPartyMedia()) ?: return@launch
        NuvioToastController.show(getString(error.messageRes))
    }
}

private fun PlayerScreenRuntime.applyPartyPaused(paused: Boolean, force: Boolean) {
    if (!force && shouldPlay == !paused) return
    shouldPlay = !paused
    if (paused) playerController?.pause() else playerController?.play()
}

private fun PlayerScreenRuntime.handlePartyMediaChange(media: PartyMedia) {
    if (media.key == partyKey) return
    val season = media.season
    val episode = media.episode
    if (media.metaId == parentMetaId && isSeries && season != null && episode != null) {
        val next = nextEpisodeInfo
        if (next != null && next.hasAired && next.season == season && next.episode == episode) {
            playNextEpisode()
            return
        }
        val video = playerMetaVideos.firstOrNull { it.season == season && it.episode == episode }
        if (video != null) {
            episodeStreamsPanelState = EpisodeStreamsPanelState(showStreams = true, selectedEpisode = video)
            showEpisodesPanel = true
            showPartyPanel = false
            return
        }
    }
    showPartyChat = false
    showPartyPanel = true
}

@Composable
internal fun PlayerScreenRuntime.BindPartyEffects() {
    // This binding belongs to the player session, including background PiP.
    val partyState by PartyRepository.state.collectAsState()
    val partyId = partyState.party?.id
    val key = partyKey
    val sharing = partyState.party?.let { it.isHost && it.sharing } == true
    val sharedStream = if (sharing) currentPartyStream() else null

    DisposableEffect(playerController) {
        val source = playerController as? PlayerSystemControlSource
        // System controls follow the same party rules as the in-player controls.
        source?.setSystemControlHandler(object : PlayerSystemControlHandler {
            override fun handleSetPlaying(playing: Boolean): Boolean {
                if (!isPartySynced) return false
                setPlaybackPlaying(playing)
                return true
            }

            override fun handleSeekTo(positionMs: Long): Boolean {
                if (!isPartySynced) return false
                val targetMs = if (isPartyControlLocked) ownSeekTarget(positionMs) else positionMs
                if (targetMs != null) {
                    playerController?.seekTo(targetMs)
                    scheduleProgressSyncAfterSeek()
                    publishPartyPlayback(positionMs = targetMs)
                }
                return true
            }

            override fun handleSeekBy(offsetMs: Long): Boolean {
                if (!isPartySynced) return false
                this@BindPartyEffects.seekBy(offsetMs)
                return true
            }
        })
        onDispose { source?.setSystemControlHandler(null) }
    }

    LaunchedEffect(sharing, sharedStream) {
        if (sharing) PartyRepository.shareStream(sharedStream)
    }

    LaunchedEffect(key) {
        partyBehind = false
        val previous = partyPreviousKey
        partyPreviousKey = key
        val party = PartyRepository.state.value.party ?: return@LaunchedEffect
        if (previous != null && previous != key && party.media.key == previous) {
            PartyRepository.publishMedia(currentPartyMedia(), positionMs = 0L)
        }
    }

    LaunchedEffect(Unit) {
        PartyRepository.events.collect { event ->
            if (event is PartyEvent.MediaChanged) handlePartyMediaChange(event.media)
        }
    }

    LaunchedEffect(Unit) {
        AppForegroundMonitor.events().collect { visibility ->
            partyAppInBackground = visibility == AppVisibility.Background
        }
    }

    LaunchedEffect(partyId) {
        if (partyId == null) {
            clearPartyNudge()
            return@LaunchedEffect
        }
        val clock = TimeSource.Monotonic
        var bufferingSince: TimeMark? = null
        var mismatchSince: TimeMark? = null
        var lastSeek: TimeMark? = null
        var lastCorrection: TimeMark? = null
        var lastSeq: Pair<Long, String?>? = null
        var lastStatus: Triple<PartyMemberStatus, String, Long?>? = null
        var wasInBackground = false
        var publishedInitial = false
        var seekLeadMs = 0L
        var measureSeekLead = false
        var wasBehind = false
        while (isActive) {
            delay(PARTY_TICK)
            val party = PartyRepository.state.value.party ?: break
            val snapshot = playbackSnapshot
            val awayFromPlayback = partyAppInBackground &&
                (playerController as? PlayerSystemControlSource)?.keepsPlaybackInBackground != true
            val resumedFromBackground = wasInBackground && !partyAppInBackground
            wasInBackground = partyAppInBackground
            if (resumedFromBackground) lastSeq = null
            val behindGap = PartyRepository.playback.value
                ?.takeIf { partyBehind && party.media.key == partyKey }
                ?.let { PartySync.expectedPositionMs(it, PartyRepository.nowMs(), snapshot.durationMs) - currentPlaybackPositionMs() }
            val status = when {
                errorMessage != null || awayFromPlayback -> PartyMemberStatus.Browsing
                behindGap != null -> PartyMemberStatus.Behind
                !initialLoadCompleted -> PartyMemberStatus.Loading
                snapshot.isLoading && !snapshot.isEnded -> {
                    val since = bufferingSince ?: clock.markNow().also { bufferingSince = it }
                    if (since.elapsedNow() > BUFFERING_REPORT_DELAY) PartyMemberStatus.Buffering else PartyMemberStatus.Ready
                }
                else -> {
                    bufferingSince = null
                    PartyMemberStatus.Ready
                }
            }
            val reportedStatus = Triple(
                status,
                partyKey,
                behindGap?.takeIf { status == PartyMemberStatus.Behind }?.let(PartySync::reportedGapMs),
            )
            if (reportedStatus != lastStatus) {
                lastStatus = reportedStatus
                PartyRepository.setLocalStatus(status, partyKey, reportedStatus.third)
            }

            val controller = playerController
            if (party.media.key != partyKey || controller == null || errorMessage != null || awayFromPlayback) {
                clearPartyNudge()
                continue
            }
            val playback = PartyRepository.playback.value
            if (playback == null) {
                if (initialLoadCompleted && !publishedInitial && party.isHost) {
                    publishedInitial = true
                    PartyRepository.publishPlayback(
                        paused = !shouldPlay,
                        positionMs = snapshot.positionMs,
                        rate = snapshot.playbackSpeed,
                    )
                }
                continue
            }
            publishedInitial = true
            if (!initialLoadCompleted) continue
            if (partyBehind && party.canControl) partyBehind = false
            if (partyBehind) {
                clearPartyNudge(playback)
                mismatchSince = null
                val gap = PartySync.expectedPositionMs(playback, PartyRepository.nowMs(), snapshot.durationMs) - currentPlaybackPositionMs()
                if (PartySync.shouldRejoin(gap, playing = shouldPlay && !snapshot.isLoading)) {
                    partyBehind = false
                } else {
                    val shown = partyBehindMs
                    if (shown == null || abs(gap - shown) >= BEHIND_DISPLAY_STEP_MS) partyBehindMs = gap.coerceAtLeast(0L)
                    wasBehind = true
                    continue
                }
            }
            if (wasBehind) {
                wasBehind = false
                lastSeq = null
                partyBehindMs = null
                if (!party.canControl) showGestureMessage(getString(Res.string.party_caught_up))
            }
            val expected = PartySync.expectedPositionMs(playback, PartyRepository.nowMs(), snapshot.durationMs)
            // A media-item re-prepare (including external subtitle attachment) can leave the