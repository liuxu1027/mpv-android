package `is`.xyz.mpv

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import `is`.xyz.mpv.bridge.MpvClient
import `is`.xyz.mpv.bridge.PurePlayerSettingsBridge
import org.json.JSONArray
import java.util.Locale
import kotlin.math.abs

/**
 * 完整播放器 Activity：
 * - 集成 MPVView、PurePlayerSettingsBridge 与 SettingsActivity
 * - 支持 content:// 转 fd:// 安全读取与释放
 * - 支持 Android 8.0+ 原生画中画（PIP）、小窗播放/暂停控制与按 Home 键自动进入小窗
 */
class PurePlayerActivity : AppCompatActivity(), MPVLib.EventObserver {

    companion object {
        private const val ACTION_PIP_TOGGLE_PAUSE = "com.example.player.ACTION_PIP_TOGGLE_PAUSE"
        private const val REQ_PIP_PLAY_PAUSE = 1001
    }

    private lateinit var mpvView: MPVView
    private lateinit var playerOverlay: RelativeLayout
    private lateinit var tvTitle: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnDecoderMode: TextView
    private lateinit var btnAudioTrack: ImageButton
    private lateinit var btnSubtitleTrack: ImageButton
    private lateinit var btnMoreMenu: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnSpeed: TextView
    private lateinit var btnAspectRatio: ImageButton
    private lateinit var btnLock: ImageButton
    private lateinit var btnUnlockFloating: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalDuration: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var gestureHud: LinearLayout
    private lateinit var tvGestureTitle: TextView
    private lateinit var tvGestureValue: TextView

    private lateinit var audioManager: AudioManager
    private lateinit var settingsBridge: PurePlayerSettingsBridge
    private lateinit var gestureDetector: GestureDetector

    private var activePfd: ParcelFileDescriptor? = null
    private var isLocked = false
    private var isSeeking = false
    private var isPaused = false
    private var stickyVideoMode = "pip"
    private var totalDurationSec = 0.0
    private var currentPosSec = 0.0

    private enum class GestureMode { NONE, BRIGHTNESS, VOLUME, SEEK }
    private var gestureMode = GestureMode.NONE
    private var startBrightness = 0.5f
    private var startVolume = 0
    private var pendingSeekSec = 0.0

    private val speedPresets = listOf(0.5, 1.0, 1.25, 1.5, 2.0)
    private var speedIndex = 1

    private data class TrackItem(
        val id: Int,
        val label: String,
        val selected: Boolean
    )

    private val mpvClientAdapter = object : MpvClient {
        override fun setOptionString(name: String, value: String) = MPVLib.setOptionString(name, value)
        override fun setPropertyString(name: String, value: String) = MPVLib.setPropertyString(name, value)
        override fun setPropertyBoolean(name: String, value: Boolean) = MPVLib.setPropertyBoolean(name, value)
        override fun setPropertyInt(name: String, value: Int) = MPVLib.setPropertyInt(name, value)
        override fun setPropertyDouble(name: String, value: Double) = MPVLib.setPropertyDouble(name, value)
        override fun command(args: Array<String>) = MPVLib.command(args)
    }

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PIP_TOGGLE_PAUSE) {
                MPVLib.command(arrayOf("cycle", "pause"))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pure_player)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        bindViews()

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        stickyVideoMode = prefs.getString("sticky_video", "pip") ?: "pip"

        settingsBridge = PurePlayerSettingsBridge(
            prefs,
            mpvClientAdapter,
            object : PurePlayerSettingsBridge.UiSettingCallbacks {
                override fun onStickyModeChanged(videoMode: String, audioSticky: Boolean) {
                    stickyVideoMode = videoMode
                }
            }
        )

        mpvView.initialize(filesDir.path, cacheDir.path)
        settingsBridge.applyInitialOptions()
        observeMpvProperties()

        setupControls()
        setupGestures()

        ContextCompat.registerReceiver(
            this,
            pipActionReceiver,
            IntentFilter(ACTION_PIP_TOGGLE_PAUSE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val mediaUri: Uri? = intent?.data
        if (mediaUri != null) {
            val (playablePath, pfd) = resolvePlayableUri(mediaUri)
            activePfd = pfd
            tvTitle.text = mediaUri.lastPathSegment ?: "本地视频"
            mpvView.playFile(playablePath)
        } else {
            val fallbackPath = intent?.getStringExtra("video_path")
            if (!fallbackPath.isNullOrBlank()) {
                tvTitle.text = fallbackPath.substringAfterLast('/')
                mpvView.playFile(fallbackPath)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        settingsBridge.startSync()
    }

    override fun onStop() {
        settingsBridge.stopSync()
        super.onStop()
    }

    /**
     * 用户按 Home 键或切换任务时触发：若开启了画中画且正在播放，则自动进入小窗
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            stickyVideoMode == "pip" &&
            !isPaused &&
            !isInPictureInPictureMode
        ) {
            enterPipMode()
        }
    }

    override fun onPause() {
        super.onPause()
        // 仅在非画中画模式下暂停（进入 PIP 时保持继续播放）
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!inPip) {
            MPVLib.setPropertyBoolean("pause", true)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(pipActionReceiver)
        MPVLib.removeObserver(this)
        mpvView.destroy()
        activePfd?.close()
        activePfd = null
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPipMode() {
        val params = buildPipParams()
        enterPictureInPictureMode(params)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val aspect = computePipAspectRatio()
        val actionIcon = Icon.createWithResource(
            this,
            if (isPaused) R.drawable.ic_play else R.drawable.ic_pause
        )
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            REQ_PIP_PLAY_PAUSE,
            Intent(ACTION_PIP_TOGGLE_PAUSE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseAction = RemoteAction(
            actionIcon,
            if (isPaused) "播放" else "暂停",
            if (isPaused) "恢复播放" else "暂停播放",
            pendingIntent
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(aspect)
            .setActions(listOf(playPauseAction))
            .build()
    }

    private fun computePipAspectRatio(): Rational {
        val rawAspect = MPVLib.getPropertyDouble("video-params/aspect") ?: (16.0 / 9.0)
        // Android PIP 强制要求宽高比在 [0.4184, 2.39] 之间
        val clamped = rawAspect.coerceIn(0.42, 2.39)
        return Rational((clamped * 100).toInt(), 100)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            playerOverlay.visibility = View.GONE
            btnUnlockFloating.visibility = View.GONE
            gestureHud.visibility = View.GONE
        } else if (!isLocked) {
            playerOverlay.visibility = View.VISIBLE
        }
    }


    private fun resolvePlayableUri(uri: Uri): Pair<String, ParcelFileDescriptor?> {
        if (uri.scheme.equals("content", ignoreCase = true)) {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                return "fd://${pfd.fd}" to pfd
            }
        }
        return uri.toString() to null
    }

    private fun bindViews() {
        mpvView = findViewById(R.id.mpv_view)
        playerOverlay = findViewById(R.id.player_overlay)
        tvTitle = findViewById(R.id.tv_title)
        btnBack = findViewById(R.id.btn_back)
        btnDecoderMode = findViewById(R.id.btn_decoder_mode)
        btnAudioTrack = findViewById(R.id.btn_audio_track)
        btnSubtitleTrack = findViewById(R.id.btn_subtitle_track)
        btnMoreMenu = findViewById(R.id.btn_more_menu)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnSpeed = findViewById(R.id.btn_speed)
        btnAspectRatio = findViewById(R.id.btn_aspect_ratio)
        btnLock = findViewById(R.id.btn_lock)
        btnUnlockFloating = findViewById(R.id.btn_unlock_floating)
        seekBar = findViewById(R.id.seekbar_progress)
        tvCurrentTime = findViewById(R.id.tv_current_time)
        tvTotalDuration = findViewById(R.id.tv_total_duration)
        loadingIndicator = findViewById(R.id.loading_indicator)
        gestureHud = findViewById(R.id.gesture_hud)
        tvGestureTitle = findViewById(R.id.tv_gesture_title)
        tvGestureValue = findViewById(R.id.tv_gesture_value)
    }

    private fun observeMpvProperties() {
        MPVLib.addObserver(this)
        MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        MPVLib.observeProperty("hwdec-current", MPVLib.MpvFormat.MPV_FORMAT_STRING)
    }

    private fun setupControls() {
        btnBack.setOnClickListener { finish() }

        btnPlayPause.setOnClickListener {
            MPVLib.command(arrayOf("cycle", "pause"))
        }

        btnDecoderMode.setOnClickListener {
            val current = MPVLib.getPropertyString("hwdec-current") ?: "no"
            val next = if (current == "no") "mediacodec-copy" else "no"
            MPVLib.setPropertyString("hwdec", next)
        }

        btnAudioTrack.setOnClickListener {
            showTrackPickerDialog(trackType = "audio", dialogTitle = "选择音轨", propName = "aid")
        }

        btnSubtitleTrack.setOnClickListener {
            showTrackPickerDialog(trackType = "sub", dialogTitle = "选择字幕", propName = "sid")
        }

        btnAspectRatio.setOnClickListener {
            showAspectRatioDialog()
        }

        btnMoreMenu.setOnClickListener {
            startActivity(Intent(this, PureSettingsActivity::class.java))
        }

        btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speedPresets.size
            val nextSpeed = speedPresets[speedIndex]
            MPVLib.setPropertyDouble("speed", nextSpeed)
            btnSpeed.text = "${nextSpeed}x"
        }

        btnLock.setOnClickListener { setLockState(true) }
        btnUnlockFloating.setOnClickListener { setLockState(false) }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && totalDurationSec > 0) {
                    val targetSec = totalDurationSec * (progress / 1000.0)
                    tvCurrentTime.text = formatTime(targetSec)
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                isSeeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                if (totalDurationSec > 0 && bar != null) {
                    val targetSec = totalDurationSec * (bar.progress / 1000.0)
                    MPVLib.command(arrayOf("seek", targetSec.toString(), "absolute"))
                }
                isSeeking = false
            }
        })
    }

    private fun showTrackPickerDialog(trackType: String, dialogTitle: String, propName: String) {
        val rawJson = MPVLib.getPropertyString("track-list") ?: "[]"
        val tracks = mutableListOf<TrackItem>()

        try {
            val array = JSONArray(rawJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optString("type") == trackType) {
                    val id = obj.optInt("id", -1)
                    val title = obj.optString("title").takeIf { it.isNotBlank() }
                    val lang = obj.optString("lang").takeIf { it.isNotBlank() }
                    val codec = obj.optString("codec").takeIf { it.isNotBlank() }
                    val selected = obj.optBoolean("selected", false)

                    val parts = listOfNotNull(title, lang, codec?.uppercase(Locale.US))
                    val display = if (parts.isEmpty()) "轨道 #$id" else parts.joinToString(" · ")
                    tracks.add(TrackItem(id, display, selected))
                }
            }
        } catch (_: Exception) {
        }

        val labels = mutableListOf("关闭 / 禁用")
        labels.addAll(tracks.map { it.label })

        val checkedIdx = tracks.indexOfFirst { it.selected }.let { if (it >= 0) it + 1 else 0 }

        AlertDialog.Builder(this)
            .setTitle(dialogTitle)
            .setSingleChoiceItems(labels.toTypedArray(), checkedIdx) { dialog, which ->
                if (which == 0) {
                    MPVLib.setPropertyString(propName, "no")
                } else {
                    MPVLib.setPropertyInt(propName, tracks[which - 1].id)
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAspectRatioDialog() {
        val options = arrayOf(
            "适应屏幕 (默认)" to Pair("-1", 0.0),
            "铺满裁剪 (Crop)" to Pair("-1", 1.0),
            "拉伸全屏 (16:9)" to Pair("16:9", 0.0),
            "4:3" to Pair("4:3", 0.0),
            "21:9" to Pair("21:9", 0.0)
        )
        val labels = options.map { it.first }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("画面比例")
            .setItems(labels) { dialog, which ->
                val (aspect, panscan) = options[which].second
                MPVLib.setPropertyString("video-aspect-override", aspect)
                MPVLib.setPropertyDouble("panscan", panscan)
                dialog.dismiss()
            }
            .show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLocked) {
                    btnUnlockFloating.visibility =
                        if (btnUnlockFloating.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                } else {
                    playerOverlay.visibility =
                        if (playerOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isLocked) {
                    MPVLib.command(arrayOf("cycle", "pause"))
                }
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (isLocked || e1 == null) return false

                val dx = e2.x - e1.x
                val dy = e2.y - e1.y

                if (gestureMode == GestureMode.NONE) {
                    gestureMode = if (abs(dx) > abs(dy)) {
                        pendingSeekSec = currentPosSec
                        GestureMode.SEEK
                    } else if (e1.x < mpvView.width / 2f) {
                        val lp = window.attributes
                        startBrightness = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                        GestureMode.BRIGHTNESS
                    } else {
                        startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        GestureMode.VOLUME
                    }
                }

                when (gestureMode) {
                    GestureMode.BRIGHTNESS -> {
                        val delta = -dy / mpvView.height
                        val newVal = (startBrightness + delta).coerceIn(0.01f, 1.0f)
                        val lp = window.attributes
                        lp.screenBrightness = newVal
                        window.attributes = lp
                        showHud("亮度", "${(newVal * 100).toInt()}%")
                    }

                    GestureMode.VOLUME -> {
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        val delta = ((-dy / mpvView.height) * maxVol).toInt()
                        val newVol = (startVolume + delta).coerceIn(0, maxVol)
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                        showHud("音量", "${(newVol * 100 / maxVol)}%")
                    }

                    GestureMode.SEEK -> {
                        val offsetSec = (dx / mpvView.width) * 90.0
                        pendingSeekSec = (currentPosSec + offsetSec).coerceIn(0.0, totalDurationSec)
                        showHud("定位", formatTime(pendingSeekSec))
                    }

                    GestureMode.NONE -> Unit
                }
                return true
            }
        })

        mpvView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                if (gestureMode == GestureMode.SEEK) {
                    MPVLib.command(arrayOf("seek", pendingSeekSec.toString(), "absolute"))
                }
                gestureMode = GestureMode.NONE
                gestureHud.visibility = View.GONE
            }
            true
        }
    }

    private fun setLockState(locked: Boolean) {
        isLocked = locked
        playerOverlay.visibility = if (locked) View.GONE else View.VISIBLE
        btnUnlockFloating.visibility = if (locked) View.VISIBLE else View.GONE
    }

    private fun showHud(title: String, value: String) {
        gestureHud.visibility = View.VISIBLE
        tvGestureTitle.text = title
        tvGestureValue.text = value
    }

    private fun formatTime(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    override fun eventProperty(property: String) = Unit
    override fun eventProperty(property: String, value: Long) = Unit

    override fun eventProperty(property: String, value: Double) {
        runOnUiThread {
            when (property) {
                "duration" -> {
                    totalDurationSec = value
                    tvTotalDuration.text = formatTime(value)
                }

                "time-pos" -> {
                    currentPosSec = value
                    if (!isSeeking && totalDurationSec > 0) {
                        tvCurrentTime.text = formatTime(value)
                        seekBar.progress = ((value / totalDurationSec) * 1000).toInt().coerceIn(0, 1000)
                    }
                }
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        runOnUiThread {
            when (property) {
                "pause" -> {
                    isPaused = value
                    btnPlayPause.setImageResource(
                        if (value) R.drawable.ic_play
                        else R.drawable.ic_pause
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                        setPictureInPictureParams(buildPipParams())
                    }
                }

                "paused-for-cache" -> {
                    loadingIndicator.visibility = if (value) View.VISIBLE else View.GONE
                }
            }
        }
    }

    override fun eventProperty(property: String, value: String) {
        runOnUiThread {
            if (property == "hwdec-current") {
                btnDecoderMode.text = if (value == "no" || value.isBlank()) "SW" else "HW+"
            }
        }
    }

    override fun event(eventId: Int) = Unit
}
