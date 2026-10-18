package com.nooxe.app.call

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.livekit.android.events.collect
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one place that owns the live call: it joins the LiveKit room, keeps the
 * call running in the background and tells the call screen what to show.
 */
object CallManager {

    private const val MISS_TIMEOUT_MS = 45000L
    private const val RING_TIMEOUT_MS = 60000L

    var data: CallData? = null
        private set
    var phase = CallPhase.IDLE
        private set
    var muted = false
        private set
    var speakerOn = false
        private set
    var videoOn = false
        private set
    var peerJoined = false
        private set
    var hasRemoteVideo = false
        private set
    var startedAt = 0L
        private set
    var problem = ""
        private set
    var busyName = ""
        private set
    var listener: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var room: Room? = null
    private var remoteRenderer: SurfaceViewRenderer? = null
    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRendererReady = false
    private var localRendererReady = false
    private var missTimeout: Runnable? = null
    private var ringTimeout: Runnable? = null

    fun isVideo(): Boolean {
        val current = data
        return current != null && current.isVideo()
    }

    // ---------- what the chat buttons and the push notifications call ----------

    fun startOutgoing(context: Context, payload: String) {
        handler.post {
            // Already ringing or talking: a call button tapped in the chat page
            // must never disturb the call that is already running.
            if (phase != CallPhase.IDLE) return@post
            val parsed = CallData.fromJson(payload)
            if (parsed == null) {
                // The server refused the call because the other person is busy.
                val status = runCatching { JSONObject(payload).optString("status") }.getOrDefault("")
                if (status == "busy") {
                    val name = runCatching { JSONObject(payload).optString("peer_name") }.getOrDefault("")
                    showBusy(context, name)
                }
                return@post
            }
            begin(context, parsed, false)
        }
    }

    fun handleIncoming(context: Context, push: Map<String, String>?) {
        handler.post {
            val parsed = CallData.fromPush(push) ?: return@post
            if (phase != CallPhase.IDLE) {
                // Already ringing or talking: turn the new call down straight away
                // so the caller is not left waiting.
                if (parsed.callId != data?.callId) {
                    postAction(parsed, "decline")
                }
                return@post
            }
            begin(context, parsed, true)
        }
    }

    fun handleCallEvent(context: Context, event: String, callId: String?) {
        handler.post {
            val current = data ?: return@post
            if (!callId.isNullOrEmpty() && callId != current.callId) return@post
            when (event) {
                "accepted" -> {
                    peerJoined = true
                    announce()
                }
                "declined", "ended" -> endLocally(context)
                else -> Unit
            }
        }
    }

    // ---------- what the call screen calls ----------

    /** The person we called is already on another call. */
    private fun showBusy(context: Context, peerName: String) {
        busyName = peerName
        data = null
        peerJoined = false
        hasRemoteVideo = false
        muted = false
        videoOn = false
        speakerOn = false
        startedAt = 0L
        problem = ""
        phase = CallPhase.BUSY
        runCatching {
            context.startActivity(
                Intent(context, CallActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        announce()
        handler.postDelayed({
            if (phase == CallPhase.BUSY) {
                endLocally(context)
            }
        }, 4000L)
    }

    fun accept(context: Context) {
        handler.post {
            val current = data
            if (current == null || phase != CallPhase.RINGING) return@post
            clearMissTimeout()
            Ringer.stop()
            CallNotification.cancelIncoming(context)
            CallService.showOngoing(context, current)
            phase = CallPhase.CONNECTING
            announce()
            postAction(current, "answer")
            connect(context, current)
        }
    }

    fun decline(context: Context) {
        handler.post {
            val current = data ?: return@post
            postAction(current, "decline")
            endLocally(context)
        }
    }

    fun hangUp(context: Context) {
        handler.post {
            if (phase == CallPhase.BUSY) {
                endLocally(context)
                return@post
            }
            val current = data ?: return@post
            postAction(current, "end")
            endLocally(context)
        }
    }

    fun toggleMute() {
        val current = room ?: return
        muted = !muted
        scope.launch { runCatching { current.localParticipant.setMicrophoneEnabled(!muted) } }
        announce()
    }

    fun toggleVideo() {
        val current = room ?: return
        videoOn = !videoOn
        scope.launch {
            runCatching { current.localParticipant.setCameraEnabled(videoOn) }
            if (videoOn) attachLocalVideo(current)
            announce()
        }
        announce()
    }

    fun toggleSpeaker(context: Context) {
        speakerOn = !speakerOn
        applyAudio(context)
        announce()
    }

    fun attachRenderers(remote: SurfaceViewRenderer, local: SurfaceViewRenderer) {
        remoteRenderer = remote
        localRenderer = local
        remoteRendererReady = false
        localRendererReady = false
        val current = room ?: return
        prepareRenderers(current)
        attachRemoteVideo(current)
        attachLocalVideo(current)
    }

    // ---------- the call itself ----------

    private fun begin(context: Context, parsed: CallData, incoming: Boolean) {
        val existing = data
        if (existing != null && existing.callId == parsed.callId && phase != CallPhase.IDLE) {
            // Both called each other at the same moment: this is the call that is
            // already on screen, so just join it.
            data = parsed
            clearMissTimeout()
            Ringer.stop()
            CallNotification.cancelIncoming(context)
            CallService.showOngoing(context, parsed)
            phase = CallPhase.CONNECTING
            connect(context.applicationContext, parsed)
            announce()
            return
        }
        data = parsed
        phase = if (incoming) CallPhase.RINGING else CallPhase.CONNECTING
        muted = false
        videoOn = parsed.isVideo()
        speakerOn = parsed.isVideo()
        peerJoined = false
        hasRemoteVideo = false
        startedAt = 0L
        problem = ""

        val app = context.applicationContext
        CallService.start(app, parsed, incoming)

        if (incoming) {
            scheduleMissTimeout(app)
            // Shows the call screen right away when the app is open; when the
            // phone is locked or the app is closed the full screen notification
            // brings it up instead.
            runCatching {
                context.startActivity(
                    Intent(context, CallActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        } else {
            runCatching {
                context.startActivity(
                    Intent(context, CallActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
            connect(app, parsed)
            scheduleRingTimeout(app)
        }
        announce()
    }

    private fun connect(context: Context, parsed: CallData) {
        if (room != null) return
        val created = LiveKit.create(appContext = context)
        room = created
        prepareRenderers(created)

        scope.launch {
            created.events.collect { event -> onEvent(context, created, event) }
        }
        scope.launch {
            try {
                created.connect(parsed.url, parsed.token)
                created.localParticipant.setMicrophoneEnabled(true)
                if (parsed.isVideo()) {
                    created.localParticipant.setCameraEnabled(true)
                }
                phase = CallPhase.ACTIVE
                startedAt = System.currentTimeMillis()
                applyAudio(context)
                attachRemoteVideo(created)
                attachLocalVideo(created)
                announce()
            } catch (error: Exception) {
                problem = "কল সংযোগ করা যায়নি"
                announce()
                handler.postDelayed({ endLocally(context) }, 2500L)
            }
        }
    }

    private fun onEvent(context: Context, room: Room, event: RoomEvent) {
        when (event) {
            is RoomEvent.TrackSubscribed -> {
                val track = event.track
                if (track is VideoTrack) {
                    hasRemoteVideo = true
                    val renderer = remoteRenderer
                    if (renderer != null) runCatching { track.addRenderer(renderer) }
                }
            }
            is RoomEvent.TrackUnsubscribed -> {
                val track = event.track
                if (track is VideoTrack) {
                    hasRemoteVideo = false
                    val renderer = remoteRenderer
                    if (renderer != null) runCatching { track.removeRenderer(renderer) }
                }
            }
            is RoomEvent.ParticipantConnected -> {
                peerJoined = true
                clearRingTimeout()
            }
            is RoomEvent.ParticipantDisconnected -> {
                handler.postDelayed({
                    if (phase == CallPhase.ACTIVE && room.remoteParticipants.isEmpty()) {
                        endLocally(context)
                    }
                }, 1500L)
                return
            }
            is RoomEvent.Reconnecting -> {
                problem = "সংযোগ পুনরুদ্ধার হচ্ছে…"
            }
            is RoomEvent.Reconnected -> {
                problem = ""
            }
            is RoomEvent.Disconnected -> {
                handler.post { endLocally(context) }
                return
            }
            else -> Unit
        }
        announce()
    }

    private fun prepareRenderers(room: Room) {
        val remote = remoteRenderer
        if (remote != null && !remoteRendererReady) {
            runCatching {
                room.initVideoRenderer(remote)
                remoteRendererReady = true
            }
        }
        val local = localRenderer
        if (local != null && !localRendererReady && isVideo()) {
            runCatching {
                room.initVideoRenderer(local)
                localRendererReady = true
            }
        }
    }

    private fun attachRemoteVideo(room: Room) {
        val renderer = remoteRenderer ?: return
        room.remoteParticipants.values.forEach { participant ->
            val track = participant.getTrackPublication(Track.Source.CAMERA)?.track
            if (track is VideoTrack) {
                hasRemoteVideo = true
                runCatching { track.addRenderer(renderer) }
            }
        }
    }

    private fun attachLocalVideo(room: Room) {
        val renderer = localRenderer ?: return
        if (!isVideo() || !videoOn) return
        val track = room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track
        if (track is VideoTrack) {
            runCatching { track.addRenderer(renderer) }
        }
    }

    private fun endLocally(context: Context) {
        clearMissTimeout()
        clearRingTimeout()
        val finished = data
        if (finished != null && phase != CallPhase.BUSY) {
            // The server must never keep a call the phones have already let go of.
            postAction(finished, "end")
        }
        Ringer.stop()
        CallService.stop(context)
        CallNotification.cancelAll(context)

        val current = room
        room = null
        remoteRenderer = null
        localRenderer = null
        remoteRendererReady = false
        localRendererReady = false
        if (current != null) {
            scope.launch { runCatching { current.disconnect() } }
        }

        restoreAudio(context)
        phase = CallPhase.IDLE
        data = null
        busyName = ""
        peerJoined = false
        hasRemoteVideo = false
        startedAt = 0L
        announce()
    }

    private fun scheduleMissTimeout(context: Context) {
        clearMissTimeout()
        val runnable = Runnable {
            val current = data
            if (current != null) {
                postAction(current, "end")
            }
            endLocally(context)
        }
        missTimeout = runnable
        handler.postDelayed(runnable, MISS_TIMEOUT_MS)
    }

    private fun clearMissTimeout() {
        val runnable = missTimeout
        missTimeout = null
        if (runnable != null) {
            handler.removeCallbacks(runnable)
        }
    }

    /** Nobody picked up in time: hang up so both phones and the server clean up. */
    private fun scheduleRingTimeout(context: Context) {
        clearRingTimeout()
        val runnable = Runnable {
            if (!peerJoined) {
                endLocally(context)
            }
        }
        ringTimeout = runnable
        handler.postDelayed(runnable, RING_TIMEOUT_MS)
    }

    private fun clearRingTimeout() {
        val runnable = ringTimeout
        ringTimeout = null
        if (runnable != null) {
            handler.removeCallbacks(runnable)
        }
    }

    /** Tells the server what happened, so the other phone updates as well. */
    private fun postAction(data: CallData, action: String) {
        val endpoint = data.actionUrl
        if (endpoint.isEmpty()) return
        val body = JSONObject()
        body.put("call_id", data.callId)
        body.put("action", action)
        body.put("token", data.token)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { stream -> stream.write(body.toString().toByteArray()) }
                connection.responseCode
                connection.disconnect()
            }
        }
    }

    private fun applyAudio(context: Context) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (phase != CallPhase.ACTIVE) return
        runCatching {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            audio.isSpeakerphoneOn = speakerOn
        }
    }

    private fun restoreAudio(context: Context) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            audio.isSpeakerphoneOn = false
            audio.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun announce() {
        handler.post { listener?.invoke() }
    }
}
