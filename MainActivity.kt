package com.akumasmp.streamer

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.akumasmp.streamer.databinding.ActivityMainBinding
import android.Manifest
import android.content.pm.PackageManager

class MainActivity : AppCompatActivity() {

    // -------------------------------------------------------------------------
    // View binding
    // -------------------------------------------------------------------------
    private lateinit var binding: ActivityMainBinding

    // -------------------------------------------------------------------------
    // Encrypted preferences for secure stream key storage
    // -------------------------------------------------------------------------
    private val encryptedPrefs by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "akuma_secure_prefs",
            masterKeyAlias,
            applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // -------------------------------------------------------------------------
    // MediaProjection permission launcher
    // -------------------------------------------------------------------------
    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            saveCredentials()
            startStreamingService(result.resultCode, result.data!!)
        } else {
            showToast("Screen capture permission denied.")
            setUiState(streaming = false)
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSavedCredentials()
        setupPlatformToggle()
        setupClickListeners()
        syncUiWithServiceState()
    }

    override fun onResume() {
        super.onResume()
        syncUiWithServiceState()
    }

    // -------------------------------------------------------------------------
    // UI setup helpers
    // -------------------------------------------------------------------------
    private fun setupPlatformToggle() {
        binding.btnYoutube.setOnClickListener {
            binding.etServerUrl.setText(YOUTUBE_RTMP_URL)
            highlightPlatform(isYoutube = true)
        }
        binding.btnTwitch.setOnClickListener {
            binding.etServerUrl.setText(TWITCH_RTMP_URL)
            highlightPlatform(isYoutube = false)
        }
    }

    private fun highlightPlatform(isYoutube: Boolean) {
        binding.btnYoutube.alpha = if (isYoutube) 1.0f else 0.45f
        binding.btnTwitch.alpha  = if (isYoutube) 0.45f else 1.0f
    }

    private fun setupClickListeners() {
        binding.btnGoLive.setOnClickListener {
            if (isServiceRunning(StreamingService::class.java)) {
                stopStreamingService()
            } else {
                validateAndStartStream()
            }
        }

        binding.btnToggleKey.setOnClickListener {
            val et = binding.etStreamKey
            val isHidden = et.inputType ==
                (android.text.InputType.TYPE_CLASS_TEXT or
                 android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
            et.inputType = if (isHidden) {
                android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            et.setSelection(et.text?.length ?: 0)
            binding.btnToggleKey.text = if (isHidden) "Hide" else "Show"
        }
    }

    // -------------------------------------------------------------------------
    // Stream start / stop logic
    // -------------------------------------------------------------------------
    private fun validateAndStartStream() {
        val serverUrl = binding.etServerUrl.text.toString().trim()
        val streamKey = binding.etStreamKey.text.toString().trim()

        when {
            serverUrl.isEmpty() -> {
                binding.etServerUrl.error = "Enter a server URL"
                return
            }
            !serverUrl.startsWith("rtmp://") -> {
                binding.etServerUrl.error = "URL must start with rtmp://"
                return
            }
            streamKey.isEmpty() -> {
                binding.etStreamKey.error = "Enter your stream key"
                return
            }
        }

        // On Android 13+ we need POST_NOTIFICATIONS before starting foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIFICATION_PERMISSION
                )
                return
            }
        }

        requestMediaProjection()
    }

    private fun requestMediaProjection() {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    private fun startStreamingService(resultCode: Int, data: Intent) {
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_START
            putExtra(StreamingService.EXTRA_RESULT_CODE, resultCode)
            putExtra(StreamingService.EXTRA_DATA, data)
            putExtra(StreamingService.EXTRA_SERVER_URL,
                binding.etServerUrl.text.toString().trim())
            putExtra(StreamingService.EXTRA_STREAM_KEY,
                binding.etStreamKey.text.toString().trim())
            putExtra(StreamingService.EXTRA_RESOLUTION,
                selectedResolution())
            putExtra(StreamingService.EXTRA_BITRATE,
                selectedBitrate())
            putExtra(StreamingService.EXTRA_AUDIO_SOURCE,
                selectedAudioSource())
        }
        ContextCompat.startForegroundService(this, intent)
        setUiState(streaming = true)
        showToast("Stream starting…")
    }

    private fun stopStreamingService() {
        val intent = Intent(this, StreamingService::class.java).apply {
            action = StreamingService.ACTION_STOP
        }
        startService(intent)
        setUiState(streaming = false)
        showToast("Stream stopped.")
    }

    // -------------------------------------------------------------------------
    // Settings helpers
    // -------------------------------------------------------------------------
    private fun selectedResolution(): String = when {
        binding.rb1080p.isChecked -> "1080p"
        binding.rb720p.isChecked  -> "720p"
        else                      -> "480p"
    }

    private fun selectedBitrate(): Int = when {
        binding.rb1080p.isChecked -> 4500
        binding.rb720p.isChecked  -> 2500
        else                      -> 1200
    }

    private fun selectedAudioSource(): String = when {
        binding.rbInternal.isChecked -> StreamingService.AUDIO_INTERNAL
        binding.rbMic.isChecked      -> StreamingService.AUDIO_MIC
        else                         -> StreamingService.AUDIO_BOTH
    }

    // -------------------------------------------------------------------------
    // Credential persistence
    // -------------------------------------------------------------------------
    private fun saveCredentials() {
        encryptedPrefs.edit()
            .putString(PREF_SERVER_URL, binding.etServerUrl.text.toString().trim())
            .putString(PREF_STREAM_KEY, binding.etStreamKey.text.toString().trim())
            .apply()
    }

    private fun loadSavedCredentials() {
        val savedUrl = encryptedPrefs.getString(PREF_SERVER_URL, "") ?: ""
        val savedKey = encryptedPrefs.getString(PREF_STREAM_KEY, "") ?: ""
        if (savedUrl.isNotEmpty()) binding.etServerUrl.setText(savedUrl)
        if (savedKey.isNotEmpty()) binding.etStreamKey.setText(savedKey)

        // Auto-detect platform from saved URL
        when {
            savedUrl.contains("youtube") -> highlightPlatform(isYoutube = true)
            savedUrl.contains("twitch")  -> highlightPlatform(isYoutube = false)
        }
    }

    // -------------------------------------------------------------------------
    // UI state helpers
    // -------------------------------------------------------------------------
    private fun setUiState(streaming: Boolean) {
        binding.btnGoLive.text = if (streaming) "Stop Stream" else "Go Live"
        binding.btnGoLive.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (streaming) R.color.stop_red else R.color.live_green
            )
        )
        binding.settingsGroup.visibility = if (streaming) View.GONE else View.VISIBLE
        binding.liveBadge.visibility     = if (streaming) View.VISIBLE else View.GONE
    }

    private fun syncUiWithServiceState() {
        setUiState(isServiceRunning(StreamingService::class.java))
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }

    // -------------------------------------------------------------------------
    // Permission result callback
    // -------------------------------------------------------------------------
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATION_PERMISSION &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            requestMediaProjection()
        } else {
            showToast("Notification permission needed for streaming.")
        }
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------
    private fun showToast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // -------------------------------------------------------------------------
    // Companion
    // -------------------------------------------------------------------------
    companion object {
        private const val YOUTUBE_RTMP_URL = "rtmp://a.rtmp.youtube.com/live2"
        private const val TWITCH_RTMP_URL  = "rtmp://live.twitch.tv/app"
        private const val PREF_SERVER_URL  = "server_url"
        private const val PREF_STREAM_KEY  = "stream_key"
        private const val REQ_NOTIFICATION_PERMISSION = 1001
    }
}
