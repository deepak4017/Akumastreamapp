package com.akumasmp.streamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.rtmp.rtmp.RtmpClient
import com.pedro.rtmp.utils.ConnectCheckerRtmp
import com.pedro.library.util.sources.audio.MicrophoneSource
import com.pedro.library.util.sources.audio.InternalAudioSource
import com.pedro.library.generic.GenericStream

/**
 * StreamingService
 *
 * Foreground service that:
 *  1. Holds the MediaProjection token
 *  2. Creates a VirtualDisplay via RootEncoder's GenericStream
 *  3. Encodes screen frames as H.264 + audio as AAC
 *  4. Pushes the RTMP stream to YouTube / Twitch
 *
 * Compatible with RootEncoder library v2.4.6+
 * GitHub: https://github.com/pedroSG94/RootEncoder
 */
class StreamingService : Service(), ConnectCheckerRtmp {

    // -------------------------------------------------------------------------
    // Properties
    // -------------------------------------------------------------------------
    private var genericStream: GenericStream? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaProjection: MediaProjection? = null

    private var serverUrl  = ""
    private var streamKey  = ""
    private var resolution = "1080p"
    private var bitrate    = 4500
    private var audioSource = AUDIO_INTERNAL

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP  -> handleStop()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        serverUrl   = intent.getStringExtra(EXTRA_SERVER_URL)  ?: return
        streamKey   = intent.getStringExtra(EXTRA_STREAM_KEY)  ?: return
        resolution  = intent.getStringExtra(EXTRA_RESOLUTION)  ?: "1080p"
        bitrate     = intent.getIntExtra(EXTRA_BITRATE, 4500)
        audioSource = intent.getStringExtra(EXTRA_AUDIO_SOURCE) ?: AUDIO_INTERNAL

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val data       = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        } ?: run {
            Log.e(TAG, "Missing MediaProjection data — aborting")
            stopSelf()
            return
        }

        // Acquire MediaProjection
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)

        // Start foreground BEFORE using projection (required on Android 14+)
        startForeground(NOTIFICATION_ID, buildNotification())

        acquireWakeLock()
        initAndStartStream()
    }

    private fun handleStop() {
        genericStream?.stopStream()
        genericStream?.release()
        genericStream = null
        mediaProjection?.stop()
        mediaProjection = null
        wakeLock?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handleStop()
        super.onDestroy()
    }

    // -------------------------------------------------------------------------
    // Stream initialisation
    // -------------------------------------------------------------------------
    private fun initAndStartStream() {
        val (width, height) = resolutionToDimensions(resolution)

        try {
            // GenericStream supports screen capture + internal/mic audio via RootEncoder
            genericStream = GenericStream(applicationContext, this).apply {

                // Video config: H.264, target bitrate in kbps, 30 fps
                prepareVideo(
                    width, height,
                    30,              // fps
                    bitrate * 1000,  // RootEncoder expects bps
                    2,               // iFrame interval (seconds)
                    CameraHelper.Facing.BACK
                )

                // Audio config: AAC 128 kbps, 44100 Hz, stereo
                when (audioSource) {
                    AUDIO_INTERNAL -> prepareAudio(128 * 1024, 44100, true)
                    AUDIO_MIC      -> prepareAudio(128 * 1024, 44100, true)
                    AUDIO_BOTH     -> prepareAudio(128 * 1024, 44100, true)
                }

                // Attach MediaProjection for screen capture
                // RootEncoder 2.4.6+ supports setMediaProjection on GenericStream
                setMediaProjection(mediaProjection!!)
            }

            val rtmpUrl = buildRtmpUrl(serverUrl, streamKey)
            genericStream?.startStream(rtmpUrl)
            Log.i(TAG, "Stream started → $rtmpUrl")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start stream: ${e.message}", e)
            broadcastError("Failed to start: ${e.message}")
            stopSelf()
        }
    }

    // -------------------------------------------------------------------------
    // ConnectCheckerRtmp callbacks
    // -------------------------------------------------------------------------
    override fun onConnectionSuccessRtmp() {
        Log.i(TAG, "RTMP connected")
        broadcastStatus(STATUS_CONNECTED)
    }

    override fun onConnectionFailedRtmp(reason: String) {
        Log.e(TAG, "RTMP connection failed: $reason")
        broadcastError(reason)
        handleStop()
    }

    override fun onNewBitrateRtmp(bitrate: Long) {
        broadcastBitrate(bitrate)
    }

    override fun onDisconnectRtmp() {
        Log.i(TAG, "RTMP disconnected")
        broadcastStatus(STATUS_DISCONNECTED)
    }

    override fun onAuthErrorRtmp() {
        Log.e(TAG, "RTMP auth error")
        broadcastError("Authentication error — check your stream key.")
        handleStop()
    }

    override fun onAuthSuccessRtmp() {
        Log.i(TAG, "RTMP auth success")
    }

    // -------------------------------------------------------------------------
    // Notification
    // -------------------------------------------------------------------------
    private fun buildNotification(): Notification {
        createNotificationChannel()

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, StreamingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Akuma SMP — Live")
            .setContentText("Streaming to ${platformName()} • $resolution @ ${bitrate}kbps")
            .setSmallIcon(R.drawable.ic_live_notification)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Live Stream",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active while streaming is running"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    // -------------------------------------------------------------------------
    // Broadcasts to update MainActivity UI
    // -------------------------------------------------------------------------
    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent(BROADCAST_STATUS).putExtra(EXTRA_STATUS, status))
    }

    private fun broadcastError(msg: String) {
        sendBroadcast(Intent(BROADCAST_STATUS)
            .putExtra(EXTRA_STATUS, STATUS_ERROR)
            .putExtra(EXTRA_ERROR_MSG, msg))
    }

    private fun broadcastBitrate(bps: Long) {
        sendBroadcast(Intent(BROADCAST_BITRATE).putExtra(EXTRA_BITRATE_VALUE, bps))
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------
    private fun resolutionToDimensions(res: String): Pair<Int, Int> = when (res) {
        "1080p" -> Pair(1920, 1080)
        "720p"  -> Pair(1280, 720)
        else    -> Pair(854, 480)
    }

    private fun buildRtmpUrl(base: String, key: String): String {
        val cleanBase = base.trimEnd('/')
        return if (key.isNotEmpty()) "$cleanBase/$key" else cleanBase
    }

    private fun platformName(): String = when {
        serverUrl.contains("youtube") -> "YouTube"
        serverUrl.contains("twitch")  -> "Twitch"
        else                          -> "RTMP"
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AkumaSMP:StreamWakeLock"
        ).also { it.acquire(6 * 60 * 60 * 1000L) } // max 6 hours
    }

    // -------------------------------------------------------------------------
    // Companion — constants shared with MainActivity
    // -------------------------------------------------------------------------
    companion object {
        private const val TAG = "StreamingService"

        const val ACTION_START = "com.akumasmp.streamer.ACTION_START"
        const val ACTION_STOP  = "com.akumasmp.streamer.ACTION_STOP"

        const val EXTRA_RESULT_CODE  = "extra_result_code"
        const val EXTRA_DATA         = "extra_data"
        const val EXTRA_SERVER_URL   = "extra_server_url"
        const val EXTRA_STREAM_KEY   = "extra_stream_key"
        const val EXTRA_RESOLUTION   = "extra_resolution"
        const val EXTRA_BITRATE      = "extra_bitrate"
        const val EXTRA_AUDIO_SOURCE = "extra_audio_source"
        const val EXTRA_BITRATE_VALUE = "extra_bitrate_value"
        const val EXTRA_STATUS       = "extra_status"
        const val EXTRA_ERROR_MSG    = "extra_error_msg"

        const val AUDIO_INTERNAL = "internal"
        const val AUDIO_MIC      = "mic"
        const val AUDIO_BOTH     = "both"

        const val BROADCAST_STATUS  = "com.akumasmp.streamer.BROADCAST_STATUS"
        const val BROADCAST_BITRATE = "com.akumasmp.streamer.BROADCAST_BITRATE"

        const val STATUS_CONNECTED    = "connected"
        const val STATUS_DISCONNECTED = "disconnected"
        const val STATUS_ERROR        = "error"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "akuma_stream_channel"
    }
}
