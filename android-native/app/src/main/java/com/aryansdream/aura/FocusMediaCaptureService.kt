package com.aryansdream.aura

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.media.ImageReader
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier.AudioClassifierOptions
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import java.util.concurrent.atomic.AtomicBoolean

/** On-device screen-frame and playback-audio classification. Raw samples are never persisted or sent. */
class FocusMediaCaptureService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val audioRunning = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var frameThread: HandlerThread? = null
    private var frameHandler: Handler? = null
    private var labeler: com.google.mlkit.vision.label.ImageLabeler? = null
    private var audioClassifier: AudioClassifier? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var entertainingAudio = false
    private var entertainingVideo = false
    private var positiveStreak = 0
    private var clearStreak = 0
    private var blockPublished = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopCapture(); stopSelf() }
    }
    private val focusStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_FOREGROUND_APP_CHANGED) {
                synchronized(this@FocusMediaCaptureService) {
                    entertainingVideo = false
                    positiveStreak = 0
                    clearStreak = 0
                    if (!entertainingAudio && blockPublished) publishBlock(false)
                }
            } else if (intent?.action == FocusService.ACTION_FOCUS_STATE_CHANGED &&
                !getSharedPreferences("aura_native", MODE_PRIVATE).getBoolean("focus_running", false)) {
                synchronized(this@FocusMediaCaptureService) {
                    entertainingAudio = false
                    entertainingVideo = false
                    positiveStreak = 0
                    clearStreak = 0
                    if (blockPublished) publishBlock(false)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = android.content.IntentFilter().apply {
            addAction(FocusService.ACTION_FOCUS_STATE_CHANGED)
            addAction(ACTION_FOREGROUND_APP_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(focusStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(focusStateReceiver, filter)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCaptureNotice()
        if (!running.get()) {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            val grant = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            if (resultCode == 0 || grant == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            startCapture(resultCode, grant)
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCaptureNotice() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "AURA content classifier", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Visible status while AURA classifies screen and playback content locally"
            }
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, FocusMediaCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("AURA audio & video classifier is on")
            .setContentText("Screen and playback audio are analyzed on this phone; nothing is uploaded.")
            .setOngoing(true)
            .addAction(0, "Turn off", stopIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun startCapture(resultCode: Int, grant: Intent) {
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = manager.getMediaProjection(resultCode, grant) ?: error("Screen capture permission was not granted")
            projection = mediaProjection
            mediaProjection.registerCallback(projectionCallback, mainHandler)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val scale = minOf(1f, 720f / metrics.widthPixels.toFloat())
            val width = (metrics.widthPixels * scale).toInt().coerceAtLeast(1)
            val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(1)
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
            imageReader = reader
            frameThread = HandlerThread("AuraFrameClassifier").also { it.start() }
            frameHandler = Handler(frameThread!!.looper)
            reader.setOnImageAvailableListener({ source -> classifyLatestFrame(source) }, frameHandler)
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "AURA local content classifier", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, frameHandler
            )
            running.set(true)
            frameHandler?.post {
                if (!running.get()) return@post
                labeler = runCatching { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }.getOrNull()
                audioClassifier = runCatching {
                    AudioClassifier.createFromOptions(
                        this,
                        AudioClassifierOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setModelAssetPath("yamnet.tflite").build())
                            .setRunningMode(RunningMode.AUDIO_CLIPS)
                            .setMaxResults(12)
                            .build()
                    )
                }.getOrNull()
                if (audioClassifier != null) runCatching { startPlaybackCapture(mediaProjection) }
            }
        } catch (_: SecurityException) {
            stopCapture()
            stopSelf()
        } catch (_: Exception) {
            stopCapture()
            stopSelf()
        }
    }

    private fun classifyLatestFrame(source: ImageReader) {
        if (!running.get() || !getSharedPreferences("aura_native", MODE_PRIVATE).getBoolean("focus_running", false)) {
            runCatching { source.acquireLatestImage()?.close() }
            return
        }
        val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return
        try {
            // Do not let our own blur overlay train the decision back to "safe". Re-evaluate
            // frames when Accessibility reports that the foreground app changes.
            if (blockPublished) return
            val plane = image.planes.firstOrNull() ?: return
            val width = image.width
            val height = image.height
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            val bitmap = if (paddedWidth == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
            val task = labeler?.process(InputImage.fromBitmap(bitmap, 0))
            if (task != null) {
                task.addOnSuccessListener { labels ->
                    val names = labels.map { it.text.lowercase() to it.confidence }
                    val entertaining = MediaContentPolicy.entertainmentInVisionLabels(names)
                    updateModality(videoEntertainment = entertaining)
                }.addOnCompleteListener {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    if (padded !== bitmap && !padded.isRecycled) padded.recycle()
                }
            } else {
                if (!bitmap.isRecycled) bitmap.recycle()
                if (padded !== bitmap && !padded.isRecycled) padded.recycle()
            }
        } catch (_: Exception) {
            // A failed/secure frame is treated as unknown; it never causes a block by itself.
        } finally {
            image.close()
        }
    }

    private fun startPlaybackCapture(mediaProjection: MediaProjection) {
        if (Build.VERSION.SDK_INT < 29 || checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val config = android.media.AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AUDIO_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuffer = AudioRecord.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = runCatching { AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, AUDIO_RATE * 2))
            .setAudioPlaybackCaptureConfig(config)
            .build() }.getOrNull() ?: return
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return
        }
        audioRecord = recorder
        audioRunning.set(true)
        audioThread = Thread({
            val buffer = ShortArray(AUDIO_SAMPLES)
            try {
                recorder.startRecording()
                while (audioRunning.get()) {
                    var count = 0
                    while (count < buffer.size && audioRunning.get()) {
                        val read = recorder.read(buffer, count, buffer.size - count, AudioRecord.READ_BLOCKING)
                        if (read < 0) break
                        count += read
                    }
                    if (count != buffer.size) continue
                    if (!getSharedPreferences("aura_native", MODE_PRIVATE).getBoolean("focus_running", false)) continue
                    val samples = FloatArray(count) { buffer[it] / 32768f }
                    val data = AudioData.create(
                        AudioData.AudioDataFormat.create(recorder.format), count
                    )
                    data.load(samples)
                    val result = audioClassifier?.classify(data)
                    val categories = result?.classificationResults()?.flatMap { it.classifications() }?.flatMap { it.categories() }
                        ?.map { it.categoryName().lowercase() to it.score() }.orEmpty()
                    updateModality(audioEntertainment = MediaContentPolicy.entertainmentInAudioLabels(categories))
                }
            } catch (_: SecurityException) {
                // Some player apps opt out of Android playback capture.
            } catch (_: Exception) {
                // Audio capture is optional; visual classification continues if unavailable.
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
            }
        }, "AuraAudioClassifier").apply { start() }
    }

    @Synchronized
    private fun updateModality(videoEntertainment: Boolean? = null, audioEntertainment: Boolean? = null) {
        if (!running.get()) return
        if (videoEntertainment != null) entertainingVideo = videoEntertainment
        if (audioEntertainment != null) entertainingAudio = audioEntertainment
        val shouldBlock = entertainingVideo || entertainingAudio
        if (shouldBlock) {
            positiveStreak++
            clearStreak = 0
            if (positiveStreak >= 2 && !blockPublished) publishBlock(true)
        } else {
            clearStreak++
            positiveStreak = 0
            if (clearStreak >= 3 && blockPublished) publishBlock(false)
        }
    }

    private fun publishBlock(block: Boolean) {
        blockPublished = block
        val prefs = getSharedPreferences("aura_native", MODE_PRIVATE)
        prefs.edit().putBoolean("media_content_blocked", block).apply()
        sendBroadcast(Intent(FocusBlockAccessibilityService.ACTION_MEDIA_CLASSIFICATION)
            .setPackage(packageName).putExtra(FocusBlockAccessibilityService.EXTRA_CONTENT_BLOCKED, block))
    }

    @Synchronized
    private fun stopCapture() {
        running.set(false)
        audioRunning.set(false)
        runCatching { audioRecord?.stop() }
        audioRecord = null
        audioThread = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.setOnImageAvailableListener(null, null); imageReader?.close() }
        imageReader = null
        frameThread?.quitSafely()
        frameThread = null
        frameHandler = null
        runCatching { projection?.unregisterCallback(projectionCallback); projection?.stop() }
        projection = null
        runCatching { labeler?.close() }
        labeler = null
        runCatching { audioClassifier?.close() }
        audioClassifier = null
        entertainingAudio = false
        entertainingVideo = false
        positiveStreak = 0
        clearStreak = 0
        if (blockPublished) publishBlock(false)
        blockPublished = false
        val prefs = getSharedPreferences("aura_native", MODE_PRIVATE)
        prefs.edit().putBoolean("av_classifier_enabled", false).putBoolean("media_auto_pause_enabled", false).apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopCapture()
        runCatching { unregisterReceiver(focusStateReceiver) }
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.aryansdream.aura.STOP_MEDIA_CAPTURE"
        const val ACTION_FOREGROUND_APP_CHANGED = "com.aryansdream.aura.MEDIA_FOREGROUND_CHANGED"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val EXTRA_FOREGROUND_PACKAGE = "foreground_package"
        private const val CHANNEL_ID = "aura_content_classifier"
        private const val NOTIFICATION_ID = 7382
        private const val AUDIO_RATE = 16_000
        private const val AUDIO_SAMPLES = 15_600 // YAMNet input window: 0.975 s at 16 kHz.
    }
}

private object MediaContentPolicy {
    private val visualEntertainment = setOf(
        "video game", "computer game", "gaming", "game", "television", "cartoon", "anime",
        "comic book", "football", "soccer", "basketball", "sports", "stadium", "concert",
        "musical instrument", "dance", "food", "cooking", "restaurant", "dessert"
    )
    private val audioEntertainment = setOf(
        "music", "musical instrument", "singing", "song", "pop music", "rock music", "rap music",
        "laughter", "comedy", "video game", "playing video games", "cheering", "applause",
        "sports", "crowd", "television", "radio", "music television"
    )

    fun entertainmentInVisionLabels(labels: List<Pair<String, Float>>): Boolean =
        labels.any { (label, score) -> score >= 0.72f && visualEntertainment.any(label::contains) }

    fun entertainmentInAudioLabels(labels: List<Pair<String, Float>>): Boolean =
        labels.any { (label, score) -> score >= 0.45f && audioEntertainment.any(label::contains) }
}
