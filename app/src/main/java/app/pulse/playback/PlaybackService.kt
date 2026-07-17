package app.pulse.playback

import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
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
                // Tell the ViewModel to reset its UI (hide the mini-player), then tear down playback +
                // the notification. clearMediaItems() is what actually removes the media notification.
                session.broadcastCustomCommand(SessionCommand(CMD_STOP, Bundle.EMPTY), Bundle.EMPTY)
                session.player.clearMediaItems()
                session.player.stop()
                stopSelf()
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
        }
    }

    override fun onCreate() {
        super.onCreate()
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
