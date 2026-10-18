package com.nooxe.app.call

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.nooxe.app.R
import io.livekit.android.renderer.SurfaceViewRenderer

/** The call screen: ringing, connecting and the live audio or video call. */
class CallActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var remoteVideo: SurfaceViewRenderer
    private lateinit var localVideo: SurfaceViewRenderer
    private lateinit var audioPanel: View
    private lateinit var topBar: View
    private lateinit var incomingRow: View
    private lateinit var activeRow: View
    private lateinit var cameraBlock: View
    private lateinit var avatarView: TextView
    private lateinit var nameView: TextView
    private lateinit var statusView: TextView
    private lateinit var topName: TextView
    private lateinit var topStatus: TextView
    private lateinit var muteButton: TextView
    private lateinit var speakerButton: TextView
    private lateinit var cameraButton: TextView

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        setContentView(R.layout.activity_call)

        remoteVideo = findViewById(R.id.remote_video)
        localVideo = findViewById(R.id.local_video)
        audioPanel = findViewById(R.id.audio_panel)
        topBar = findViewById(R.id.top_bar)
        incomingRow = findViewById(R.id.incoming_row)
        activeRow = findViewById(R.id.active_row)
        cameraBlock = findViewById(R.id.btn_camera_block)
        avatarView = findViewById(R.id.avatar)
        nameView = findViewById(R.id.peer_name)
        statusView = findViewById(R.id.status)
        topName = findViewById(R.id.top_name)
        topStatus = findViewById(R.id.top_status)
        muteButton = findViewById(R.id.btn_mute)
        speakerButton = findViewById(R.id.btn_speaker)
        cameraButton = findViewById(R.id.btn_camera)

        findViewById<View>(R.id.btn_mute).setOnClickListener { CallManager.toggleMute() }
        findViewById<View>(R.id.btn_speaker).setOnClickListener { CallManager.toggleSpeaker(this) }
        findViewById<View>(R.id.btn_camera).setOnClickListener { CallManager.toggleVideo() }
        findViewById<View>(R.id.btn_end).setOnClickListener { CallManager.hangUp(this) }
        findViewById<View>(R.id.btn_decline).setOnClickListener { CallManager.decline(this) }
        findViewById<View>(R.id.btn_accept).setOnClickListener { CallManager.accept(this) }

        CallManager.attachRenderers(remoteVideo, localVideo)
        CallManager.listener = { render() }
        render()
        requestCallPermissions()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        CallManager.listener = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    private fun requestCallPermissions() {
        val wanted = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.RECORD_AUDIO)
        }
        if (CallManager.isVideo() && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isNotEmpty()) {
            requestPermissions(wanted.toTypedArray(), 7001)
        }
    }

    private fun render() {
        if (CallManager.phase == CallPhase.BUSY) {
            renderBusy()
            return
        }
        val data = CallManager.data
        if (data == null || CallManager.phase == CallPhase.IDLE) {
            if (!isFinishing) finish()
            return
        }

        val video = data.isVideo()
        val ringing = CallManager.phase == CallPhase.RINGING
        val label = statusLabel(data, ringing)

        val peer = if (data.peerName.isEmpty()) "Nooxe" else data.peerName
        nameView.text = peer
        avatarView.text = initials(peer)
        statusView.text = label
        topName.text = peer
        topStatus.text = label

        remoteVideo.visibility = if (video && CallManager.hasRemoteVideo) View.VISIBLE else View.GONE
        audioPanel.visibility = if (video && CallManager.hasRemoteVideo) View.GONE else View.VISIBLE
        localVideo.visibility = if (video && CallManager.videoOn && !ringing) View.VISIBLE else View.GONE
        topBar.visibility = if (video && !ringing) View.VISIBLE else View.GONE

        incomingRow.visibility = if (ringing) View.VISIBLE else View.GONE
        activeRow.visibility = if (ringing) View.GONE else View.VISIBLE
        cameraBlock.visibility = if (video) View.VISIBLE else View.GONE

        muteButton.setBackgroundResource(if (CallManager.muted) R.drawable.bg_call_off else R.drawable.bg_call_round)
        cameraButton.setBackgroundResource(if (CallManager.videoOn) R.drawable.bg_call_round else R.drawable.bg_call_off)
        speakerButton.setBackgroundResource(if (CallManager.speakerOn) R.drawable.bg_call_on else R.drawable.bg_call_round)
    }

    private fun renderBusy() {
        val peer = if (CallManager.busyName.isEmpty()) "Nooxe" else CallManager.busyName
        nameView.text = peer
        avatarView.text = initials(peer)
        statusView.text = "ব্যস্ত"
        topName.text = peer
        topStatus.text = "ব্যস্ত"
        remoteVideo.visibility = View.GONE
        localVideo.visibility = View.GONE
        audioPanel.visibility = View.VISIBLE
        topBar.visibility = View.GONE
        incomingRow.visibility = View.GONE
        activeRow.visibility = View.GONE
        cameraBlock.visibility = View.GONE
    }

    private fun statusLabel(data: CallData, ringing: Boolean): String {
        if (ringing) {
            return if (data.isVideo()) "ইনকামিং ভিডিও কল" else "ইনকামিং অডিও কল"
        }
        if (CallManager.problem.isNotEmpty()) return CallManager.problem
        if (CallManager.phase == CallPhase.CONNECTING) return "সংযোগ হচ্ছে…"
        if (CallManager.peerJoined || CallManager.hasRemoteVideo) return elapsed(CallManager.startedAt)
        return "রিং হচ্ছে…"
    }

    private fun initials(name: String): String {
        val clean = name.trim()
        if (clean.isEmpty()) return "N"
        return clean.substring(0, 1).uppercase()
    }

    private fun elapsed(startedAt: Long): String {
        if (startedAt <= 0L) return "সংযোগ হচ্ছে…"
        val seconds = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
        return String.format("%02d:%02d", seconds / 60, seconds % 60)
    }
}
