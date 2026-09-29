package app.pulse.playback

import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.pulse.R
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

// Custom session commands bridging the notification/lock-screen/Bluetooth buttons to the app's manual
// on-demand queue (which lives in PlayerViewModel, connected as a MediaController):
//  - ADVANCE / PREV are broadcast FROM the service TO the ViewModel controller when a skip button fires.
//  - STOP is sent FROM the notification's Close button TO the service Callback.
const val CMD_ADVANCE = "app.pulse.ADVANCE"
const val CMD_PREV = "app.pulse.PREV"
const val CMD_STOP = "app.pulse.STOP"

class PlaybackService : MediaSessionService() {

    companion object {
        /** True while a PlayerViewModel (which owns the real queue and starts every next track) is alive. */
        @Volatile var queueOwnerAlive = false
    }

    private var session: MediaSession? = null

    /**
     * The player handed to MediaSession. The app feeds ExoPlayer ONE MediaItem at a time (on-demand
     * stream resolution), so the timeline never has a next/prev item. This wrapper fakes the seek
     * commands' availability — which is what makes DefaultMediaNotificationProvider draw the prev/next
     * buttons — and routes every skip dispatch path back to the ViewModel's real queue via broadcasts.
     */
    private class QueuePlayer(
        inner: Player,
        val onAdvance: () -> Unit,
        val onPrevious: () -> Unit,
    ) : ForwardingPlayer(inner) {

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

        override fun isCommandAvailable(command: Int): Boolean = when (command) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
            else -> super.isCommandAvailable(command)
        }

        // Belt-and-suspenders for legacy / Bluetooth enablement checks.
        override fun hasNextMediaItem(): Boolean = true
        override fun hasPreviousMediaItem(): Boolean = true

        // Intercept every skip dispatch path (notification uses seekToNext/Previous; the legacy/BT
        // MediaSessionCompat stub uses the *MediaItem variants). Do NOT call super — there is no real
        // timeline to navigate; the ViewModel swaps in the next resolved MediaItem instead.
        override fun seekToNext() = onAdvance()
        override fun seekToNextMediaItem() = onAdvance()
        override fun seekToPrevious() = onPrevious()
        override fun seekToPreviousMediaItem() = onPrevious()
    }

    private inner class Cb : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // Grant the three custom commands to EVERY controller (including the internal media-notification
            // controller and the ViewModel controller) or the Close button + skip broadcasts silently no-op.
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_ADVANCE, Bundle.EMPTY))
                .add(SessionCommand(CMD_PREV, Bundle.EMPTY))
                .add(SessionCommand(CMD_STOP, Bundle.EMPTY))
                .build()

            val closeButton = CommandButton.Builder()
                .setSessionCommand(SessionCommand(CMD_STOP, Bundle.EMPTY))
                .setIconResId(R.drawable.ic_notif_close)
                .setDisplayName("Close")
                .setEnabled(true)
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setCustomLayout(ImmutableList.of(closeButton))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = when (customCommand.customAction) {
            CMD_STOP -> {
                // Tell the ViewModel to reset its UI (hide the mini-player), then tear down playback + the
                // notification. pause() first so playWhenReady goes false and no foreground-worthiness check
                // (Media3's shouldRunInForeground, our onTaskRemoved) can still count this session as
                // playing. stop() BEFORE clearMediaItems() so the player goes to IDLE (not ENDED) and the
                // ViewModel never treats the close as an end-of-track auto-advance.
                session.broadcastCustomCommand(SessionCommand(CMD_STOP, Bundle.EMPTY), Bundle.EMPTY)
                session.player.pause()
                session.player.stop()
                session.player.clearMediaItems()
                // Media3 1.2.1's own removal (its internal notification controller reacting to IDLE + empty
                // timeline) is a chain of async posted updates that reliably fails to clear the bar on device
                // — and stopSelf() below is a NO-OP while the ViewModel's app-lifetime MediaController keeps
                // this service bound, so nothing else would ever demote us. Remove the notification
                // deterministically, right here.
                dismissNotification()
                stopSelf()   // harmless while bound; real teardown for the nothing-bound case
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(HoldAwareProvider(DefaultMediaNotificationProvider(this)))
        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val player = QueuePlayer(
            exo,
            onAdvance = { session?.broadcastCustomCommand(SessionCommand(CMD_ADVANCE, Bundle.EMPTY), Bundle.EMPTY) },
            onPrevious = { session?.broadcastCustomCommand(SessionCommand(CMD_PREV, Bundle.EMPTY), Bundle.EMPTY) },
        )
        session = MediaSession.Builder(this, player).setCallback(Cb()).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Demote from foreground AND cancel the posted notification. ServiceCompat handles the pre-N
     * fallback (minSdk 21); the explicit cancel is belt-and-braces for the paused/"detached" state,
     * where Media3 has already called stopForeground(DETACH) and the bar is a plain posted
     * notification that stopForeground() alone won't remove. The id is DefaultMediaNotificationProvider's
     * DEFAULT_NOTIFICATION_ID (1001, verified in media3-session 1.2.1) — we never install a custom
     * provider or id, so that constant is what Media3 posts under.
     */
    private fun dismissNotification() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
    }

    /**
     * True while playback is still WANTED but the player is between streams: a track just errored (IDLE)
     * or ended (ENDED) and the ViewModel is resolving what plays next. Media3 1.2.1 drops the service out
     * of the foreground in exactly that window, and on Android 12+ it may not get back in from the
     * background ("Background started FGS: Disallowed" on the phone). Out of the foreground, the app was then
     * killed within minutes ("excessive cpu" — decoding audio is too much for a plain background app) and the
     * music stopped for good. Every give-up path in the ViewModel pauses first, which ends the hold.
     */
    private fun holdingForeground(): Boolean {
        if (!queueOwnerAlive) return false   // nobody left to start the next track — let it go
        val p = session?.player ?: return false
        return p.playWhenReady && p.mediaItemCount > 0 &&
            (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED)
    }

    // Media3 1.2.1 can leave the foreground from exactly two places: this per-event update, and the provider's
    // delayed callback (artwork finished loading) below. While holding, both keep the current notification and
    // foreground state untouched; the next READY/BUFFERING event refreshes the notification normally.
    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (holdingForeground()) return
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    private inner class HoldAwareProvider(private val inner: MediaNotification.Provider) : MediaNotification.Provider {
        override fun createNotification(
            mediaSession: MediaSession,
            customLayout: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback,
        ): MediaNotification = inner.createNotification(mediaSession, customLayout, actionFactory) { n ->
            if (!holdingForeground()) onNotificationChangedCallback.onNotificationChanged(n)
        }

        override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean =
            inner.handleCustomCommand(session, action, extras)
    }

    // Media3 1.2.1's MediaSessionService does NOT override onTaskRemoved (verified against the 1.2.1
    // artifact: the base is plain Service, a no-op), so this override is the only handling. When the
    // user swipes the APP away with playback stopped/paused (or never started), the session +
    // notification must die with it; only an actively-playing session survives the swipe.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        // "Actively playing" must be playback INTENT (mirrors the ViewModel's isPlaying): ENDED (queue
        // finished) and IDLE (fatal error / circuit breaker) both KEEP playWhenReady=true, so checking
        // playWhenReady alone left the service — and, in the ENDED case, its still-posted paused-style
        // notification — alive as a zombie after the user swiped the app away.
        val activelyPlaying = player != null && player.playWhenReady && player.mediaItemCount > 0 &&
            player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE
        if (!activelyPlaying) {
            player?.pause()
            player?.stop()
            player?.clearMediaItems()
            dismissNotification()
            stopSelf()
        }
    }

    override fun onDestroy() {
        // If the service ever dies with the bar still posted (system stop, unbind after a Close),
        // the notification must not outlive it.
        dismissNotification()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
