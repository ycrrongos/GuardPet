package com.geekathon.guardpet

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.geekathon.guardpet.friend.FriendAvatarCache
import com.geekathon.guardpet.friend.FriendClient
import com.geekathon.guardpet.friend.FriendPetSnapshot
import com.geekathon.guardpet.friend.FriendPrefs
import com.geekathon.guardpet.friend.HabitXpStore
import java.io.File
import dev.pranav.reef.accessibility.BlockerService
import kotlin.math.abs
import java.time.LocalTime
import kotlin.random.Random

class PetService : Service() {
    private lateinit var windowManager: WindowManager
    private var petView: PetCanvas? = null
    private var friendPetViews = linkedMapOf<String, PetCanvas>()
    private val friendShownKey = mutableMapOf<String, String>()
    private val friendMotionKey = mutableMapOf<String, String>()
    private val friendMotionAnimators = mutableMapOf<String, ObjectAnimator>()
    private var lastPushedFriendState: FriendPetSnapshot? = null
    private var friendStopObserve: (() -> Unit)? = null
    private val friendHandler = Handler(Looper.getMainLooper())
    private val friendSyncTick = object : Runnable {
        override fun run() {
            try {
                FriendClient.reportAnimState(currentState.key)
                pushLocalFriendState(force = false)
                layoutFriendOverlays()
            } catch (t: Throwable) {
                android.util.Log.w("PetService", "friend sync tick", t)
            } finally {
                friendHandler.postDelayed(this, 2_500L)
            }
        }
    }
    private var idleAnimator: ObjectAnimator? = null
    private lateinit var assets: PetAssetRepository
    private lateinit var settings: PetSettings
    private var currentState = PetState.IDLE
    private var walkAnimator: ValueAnimator? = null
    private var panelOverlay: PetPanelOverlay? = null
    private var menuOverlay: PetMenuOverlay? = null
    private var sleepLockOverlay: SleepLockOverlay? = null
    private var habitConfirmOverlay: HabitConfirmOverlay? = null
    private var habitRestrictOverlay: HabitRestrictOverlay? = null
    private var speechBubbleOverlay: PetSpeechBubbleOverlay? = null
    private var textCropOverlay: TextCropOverlay? = null
    private var phoneShakeListener: android.hardware.SensorEventListener? = null
    private var draggingPet = false
    private var phoneShakeCooling = false
    /** 拖拽中摇手机触发提取后，抑制后续 MOVE 的 updateViewLayout，避免与框选 overlay 抢 WM。 */
    private var suppressDragUntilUp = false
    private val tapHandler = Handler(Looper.getMainLooper())
    private val timeHandler = Handler(Looper.getMainLooper())
    private var lastTimeActionKey: String? = null
    private var micCaptureOn = false
    private var mediaPlaybackOn = false
    private val timeCheck = object : Runnable {
        override fun run() {
            evaluateLocalTime()
            timeHandler.postDelayed(this, TimeBehaviorConfig.CHECK_INTERVAL_MS)
        }
    }
    private val interactionHandler = Handler(Looper.getMainLooper())
    private val behaviorHandler = Handler(Looper.getMainLooper())
    private val behaviorTick = object : Runnable {
        override fun run() {
            playRandomBehavior()
            behaviorHandler.postDelayed(this, 180_000L)
        }
    }
    override fun onCreate() {
        super.onCreate()
        assets = PetAssetRepository(this)
        settings = PetSettings(this)
        isRunning = false
        runCatching {
            createNotificationChannel()
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            check(Settings.canDrawOverlays(this)) { getString(R.string.overlay_permission_required) }
            showPet()
            isRunning = true
            instance = this
            clearLastStartError()
            startFriendPresence()
        }.onFailure {
            isRunning = false
            saveLastStartError(it)
            stopSelf()
        }
        evaluateLocalTime()
        timeHandler.postDelayed(timeCheck, TimeBehaviorConfig.CHECK_INTERVAL_MS)
        behaviorHandler.postDelayed(behaviorTick, 180_000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REFRESH, ACTION_REFRESH_SETTINGS -> {
                currentState = PetState.IDLE
                petView?.show(assets.randomFileFor(currentState))
                applyVisualState()
                evaluateLocalTime()
            }
            ACTION_SET_STATE -> {
                currentState = PetState.fromKey(intent.getStringExtra(EXTRA_STATE))
                if (isSleepingByTime()) return START_STICKY
                walkAnimator?.cancel()
                petView?.show(assets.randomFileFor(currentState))
                applyVisualState()
                pushLocalFriendState(force = true)
                if (currentState == PetState.SLEEP) {
                    idleAnimator?.cancel()
                } else {
                    startIdleAnimation()
                    resumeFreeWalkAfter(INTERACTION_DISPLAY_MS)
                }
            }
            ACTION_SLEEP_LOCK -> showSleepLock()
            ACTION_SLEEP_UNLOCK -> hideSleepLock()
            ACTION_SHOW_PET -> {
                petView?.visibility = View.VISIBLE
                resumeFreeWalkAfter(INTERACTION_DISPLAY_MS)
            }
            ACTION_OPEN_TIMER -> openFocusTimer()
            ACTION_PLAY_LAST_FLASH -> playLastFlashAudio()
            ACTION_HABIT_REACTION -> applyHabitReaction(intent)
            ACTION_HABIT_CONFIRM -> {
                val pkg = intent.getStringExtra(EXTRA_CONFIRM_PKG).orEmpty()
                val active = intent.getStringExtra(EXTRA_CONFIRM_ACTIVE).orEmpty()
                val label = intent.getStringExtra(EXTRA_CONFIRM_LABEL).orEmpty()
                if (pkg.isNotBlank()) showHabitConfirm(pkg, active, label)
            }
            ACTION_HABIT_RESTRICT -> {
                val msg = intent.getStringExtra(EXTRA_RESTRICT_MSG)
                    ?: getString(R.string.habit_restrict_video_msg)
                showHabitRestrict(msg)
            }
            ACTION_HABIT_RESTRICT_HIDE -> hideHabitRestrict()
            ACTION_SPEECH_BUBBLE -> {
                val text = intent.getStringExtra(EXTRA_BUBBLE_TEXT).orEmpty()
                if (text.isNotBlank()) showSpeechBubble(text)
            }
            ACTION_EXTRACT_TEXT -> extractText()
        }
        if (petView == null && Settings.canDrawOverlays(this)) showPet()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        resizeCanvasForCurrentScreen()
        updateEdgeWalk()
    }

    override fun onDestroy() {
        idleAnimator?.cancel()
        walkAnimator?.cancel()
        timeHandler.removeCallbacks(timeCheck)
        interactionHandler.removeCallbacksAndMessages(null)
        behaviorHandler.removeCallbacks(behaviorTick)
        tapHandler.removeCallbacksAndMessages(null)
        panelOverlay?.close()
        panelOverlay = null
        menuOverlay?.close()
        menuOverlay = null
        sleepLockOverlay?.close()
        sleepLockOverlay = null
        habitConfirmOverlay?.close()
        habitConfirmOverlay = null
        habitRestrictOverlay?.close()
        habitRestrictOverlay = null
        speechBubbleOverlay?.close()
        speechBubbleOverlay = null
        textCropOverlay?.close()
        textCropOverlay = null
        stopPhoneShakeListen()
        stopFriendPresence()
        petView?.let { runCatching { windowManager.removeView(it) } }
        petView = null
        isRunning = false
        instance = null
        super.onDestroy()
    }

    private fun showPet() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val canvasSize = PetCanvasConfig.resolve(this)
        val preferences = getSharedPreferences("pet_position", MODE_PRIVATE)
        val params = WindowManager.LayoutParams(
            canvasSize.widthPx,
            canvasSize.heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt("x", 40)
            y = preferences.getInt("y", 250)
        }

        petView = PetCanvas(this).apply {
            show(assets.randomFileFor(currentState))
            contentDescription = getString(R.string.pet_description)
            setOnTouchListener(PetTouchListener(params))
        }
        windowManager.addView(petView, params)
        applyVisualState()
        startIdleAnimation()
        updateEdgeWalk()
        syncFriendPresence()
    }

    private fun startFriendPresence() {
        friendStopObserve?.invoke()
        // 房间广播只刷新 overlay，不要回推自己的 state，避免互相打爆房间
        friendStopObserve = FriendClient.observe { friendHandler.post { layoutFriendOverlays() } }
        friendHandler.removeCallbacks(friendSyncTick)
        friendHandler.post(friendSyncTick)
        val prefs = FriendPrefs(this)
        if (prefs.autoConnect && prefs.serverHost.isNotBlank() && !FriendClient.snapshot.connected) {
            FriendClient.connect(this, prefs.serverHost, prefs.roomCode, prefs.displayName)
        }
    }

    private fun stopFriendPresence() {
        friendHandler.removeCallbacks(friendSyncTick)
        friendStopObserve?.invoke()
        friendStopObserve = null
        lastPushedFriendState = null
        removeFriendPet()
    }

    /** 心跳 + 本机动作变化时上报心情/饱食/状态。 */
    private fun pushLocalFriendState(force: Boolean = false) {
        if (!FriendClient.snapshot.connected) return
        FriendClient.reportAnimState(currentState.key)
        val xp = HabitXpStore(this)
        val next = FriendPetSnapshot(
            mood = settings.mood,
            hunger = settings.hunger,
            food = settings.foodCount,
            level = xp.level,
            xp = xp.xp,
            state = currentState.key,
            avatarHash = assets.appearanceHash()
        )
        val prev = lastPushedFriendState
        if (!force && prev != null &&
            prev.mood == next.mood &&
            prev.hunger == next.hunger &&
            prev.food == next.food &&
            prev.level == next.level &&
            prev.xp == next.xp &&
            prev.state == next.state &&
            prev.avatarHash == next.avatarHash
        ) {
            return
        }
        lastPushedFriendState = next
        FriendClient.sendPetState(next)
        FriendClient.pushLocalAvatar(this, force = false)
    }

    private fun syncFriendPresence() {
        pushLocalFriendState(force = false)
        layoutFriendOverlays()
    }

    private fun layoutFriendOverlays() {
        if (!::windowManager.isInitialized || petView == null) return
        val friends = FriendClient.otherMembers().take(MAX_FRIEND_OVERLAYS)
        val keep = friends.map { it.userId }.toSet()
        friendPetViews.keys.filter { it !in keep }.toList().forEach { removeFriendPet(it) }
        if (friends.isEmpty()) return

        val selfParams = petView?.layoutParams as? WindowManager.LayoutParams ?: return
        val gap = (8 * resources.displayMetrics.density).toInt()
        val canvasSize = PetCanvasConfig.resolve(this)
        val screen = resources.displayMetrics

        friends.forEachIndexed { index, friend ->
            val slot = index + 1
            var view = friendPetViews[friend.userId]
            if (view == null) {
                val fp = WindowManager.LayoutParams(
                    canvasSize.widthPx,
                    canvasSize.heightPx,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = (selfParams.x + slot * (selfParams.width + gap))
                        .coerceIn(0, (screen.widthPixels - canvasSize.widthPx).coerceAtLeast(0))
                    y = selfParams.y
                }
                val created = PetCanvas(this).apply {
                    contentDescription = getString(R.string.friend_pet_content_desc, friend.name)
                    alpha = 0.95f
                }
                runCatching {
                    windowManager.addView(created, fp)
                    friendPetViews[friend.userId] = created
                    view = created
                }
            }
            val canvas = view ?: return@forEachIndexed
            val fp = canvas.layoutParams as? WindowManager.LayoutParams ?: return@forEachIndexed
            fp.x = (selfParams.x + slot * (selfParams.width + gap))
                .coerceIn(0, (screen.widthPixels - fp.width).coerceAtLeast(0))
            fp.y = selfParams.y
            runCatching { windowManager.updateViewLayout(canvas, fp) }

            val remoteState = PetState.fromKey(friend.pet.state)
            val hash = friend.pet.avatarHash
            val cached = if (hash.isNotBlank()) FriendAvatarCache.fileFor(this, hash) else null
            val file: File? = cached
                ?: assets.fileFor(remoteState)
                ?: assets.randomFileFor(remoteState)

            // 形象文件与动作分离：有定制图时仍跟对方 state 做动效，避免永远静止
            val assetKey = if (cached != null) {
                "avatar:$hash"
            } else {
                "fallback:${remoteState.key}:${file?.absolutePath.orEmpty()}"
            }
            if (friendShownKey[friend.userId] != assetKey) {
                canvas.show(file)
                friendShownKey[friend.userId] = assetKey
            }
            val motionKey =
                "$assetKey:${remoteState.key}:${friend.pet.mood / 5}:${friend.pet.hunger / 5}"
            if (friendMotionKey[friend.userId] != motionKey) {
                applyFriendRemoteVisual(
                    friend.userId,
                    canvas,
                    remoteState,
                    friend.pet.mood,
                    friend.pet.hunger
                )
                friendMotionKey[friend.userId] = motionKey
            }
            canvas.contentDescription = getString(R.string.friend_pet_content_desc, friend.name)
            canvas.setOnClickListener {
                val p = friend.pet
                Toast.makeText(
                    this,
                    getString(
                        R.string.friend_pet_status_toast,
                        friend.name,
                        p.level,
                        p.mood,
                        p.hunger,
                        p.food
                    ) + " · ${remoteState.key}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun applyFriendRemoteVisual(
        userId: String,
        canvas: PetCanvas,
        state: PetState,
        mood: Int,
        hunger: Int
    ) {
        friendMotionAnimators.remove(userId)?.cancel()
        canvas.animate().cancel()
        canvas.translationX = 0f
        canvas.translationY = 0f
        canvas.rotation = 0f
        val density = resources.displayMetrics.density
        val baseScale = settings.petScale * 0.92f
        when (state) {
            PetState.SLEEP, PetState.HIDDEN -> {
                canvas.setVisualScale(baseScale * 0.88f, 0.5f)
                canvas.translationY = 8f * density
            }
            PetState.SAD, PetState.BORED -> {
                canvas.setVisualScale(baseScale * 0.94f, 0.82f)
                startFriendBob(userId, canvas, 4f * density, 2600L)
            }
            PetState.ANGRY -> {
                canvas.setVisualScale(baseScale * 1.04f, 1f)
                friendMotionAnimators[userId] = ObjectAnimator.ofFloat(
                    canvas,
                    View.TRANSLATION_X,
                    -5f * density,
                    5f * density
                ).apply {
                    duration = 100
                    repeatCount = ObjectAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    start()
                }
            }
            PetState.WALK -> {
                canvas.setVisualScale(baseScale, 0.95f)
                startFriendBob(userId, canvas, 5f * density, 420L)
            }
            PetState.HAPPY, PetState.PLAY, PetState.TOUCH, PetState.PET, PetState.FEED -> {
                canvas.setVisualScale(baseScale * 1.06f, 1f)
                startFriendBob(userId, canvas, 12f * density, 850L)
            }
            else -> {
                val dim = if (hunger < 25) 0.8f else 0.95f
                canvas.setVisualScale(baseScale, dim)
                val amp = if (mood >= 50) 8f * density else 5f * density
                startFriendBob(userId, canvas, amp, if (hunger < 30) 2300L else 1700L)
            }
        }
    }

    private fun startFriendBob(userId: String, canvas: PetCanvas, amplitude: Float, durationMs: Long) {
        friendMotionAnimators[userId] = ObjectAnimator.ofFloat(
            canvas,
            View.TRANSLATION_Y,
            0f,
            -amplitude,
            0f
        ).apply {
            duration = durationMs
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun removeFriendPet(userId: String) {
        friendMotionAnimators.remove(userId)?.cancel()
        friendMotionKey.remove(userId)
        friendPetViews.remove(userId)?.let { runCatching { windowManager.removeView(it) } }
        friendShownKey.remove(userId)
    }

    private fun removeFriendPet() {
        friendPetViews.keys.toList().forEach { removeFriendPet(it) }
    }

    private fun applyVisualState() {
        val hidden = settings.semiHidden || currentState == PetState.HIDDEN
        val scale = settings.petScale * if (hidden) 0.72f else 1f
        petView?.setVisualScale(scale, if (hidden) 0.45f else 1f)
    }

    private fun updateEdgeWalk() {
        updateFreeWalkRandom()
    }

    fun refreshSettings() {
        applyVisualState()
        updateEdgeWalk()
    }

    fun openControlPanel() {
        showControlPanel()
    }

    fun extractText() {
        menuOverlay?.close()
        runCatching {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
                return
            }
            if (!::windowManager.isInitialized) {
                windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            }
            val view = petView
            if (view == null) {
                Toast.makeText(this, R.string.start_pet_first, Toast.LENGTH_SHORT).show()
                return
            }
            val petParams = view.layoutParams as? WindowManager.LayoutParams ?: return
            if (textCropOverlay?.isShowing() == true) {
                textCropOverlay?.close()
            }
            if (textCropOverlay == null) {
                textCropOverlay = TextCropOverlay(this, windowManager)
            }
            textCropOverlay?.show(petParams.x, petParams.y, petParams.width, petParams.height)
            android.util.Log.i("PetService", "extractText crop shown")
        }.onFailure {
            android.util.Log.e("PetService", "extractText failed", it)
            Toast.makeText(
                this,
                getString(R.string.panel_open_failed, it.localizedMessage ?: it.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun onTextCropCancelled() {
        petView?.visibility = View.VISIBLE
    }

    fun onTextCropConfirmed(rect: android.graphics.Rect) {
        petView?.visibility = View.INVISIBLE
        tapHandler.postDelayed({
            runCatching {
                val raw = if (rect.width() > 0 && rect.height() > 0) {
                    BlockerService.captureVisibleTextInRect(rect)
                } else {
                    BlockerService.captureVisibleText()
                }
                if (raw.isEmpty() && !BlockerService.isConnected) {
                    petView?.visibility = View.VISIBLE
                    Toast.makeText(this, R.string.a11y_required, Toast.LENGTH_LONG).show()
                    return@runCatching
                }
                val tokens = TextTokenizer.splitAll(raw)
                if (tokens.isEmpty()) {
                    petView?.visibility = View.VISIBLE
                    Toast.makeText(this, R.string.bigbang_empty, Toast.LENGTH_LONG).show()
                    return@runCatching
                }
                TextCaptureHolder.tokens = tokens
                startActivity(
                    Intent(this, BigBangActivity::class.java)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        ),
                    ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()
                )
            }.onFailure {
                android.util.Log.e("PetService", "onTextCropConfirmed failed", it)
                petView?.visibility = View.VISIBLE
                Toast.makeText(
                    this,
                    getString(R.string.panel_open_failed, it.localizedMessage ?: it.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }, CAPTURE_DELAY_MS)
    }

    private fun startPhoneShakeListen() {
        if (phoneShakeListener != null) return
        val sm = getSystemService(SENSOR_SERVICE) as? android.hardware.SensorManager ?: return
        val accel = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
        var lastShakeAt = 0L
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                if (!draggingPet || phoneShakeCooling || suppressDragUntilUp) return
                if (event == null || event.values.size < 3) return
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val gForce = kotlin.math.sqrt((x * x + y * y + z * z).toDouble()).toFloat() /
                    android.hardware.SensorManager.GRAVITY_EARTH
                val now = android.os.SystemClock.uptimeMillis()
                // ~2.4g：拖拽抖动不够，真实摇手机才触发
                if (gForce > 2.4f && now - lastShakeAt > 1_400L) {
                    lastShakeAt = now
                    phoneShakeCooling = true
                    // 立刻停听，避免传感器线程连发；主线程再开框选
                    stopPhoneShakeListen()
                    suppressDragUntilUp = true
                    tapHandler.post {
                        runCatching {
                            performAssigned(PetGesture.DRAG_PHONE_SHAKE)
                        }.onFailure {
                            android.util.Log.e("PetService", "DRAG_PHONE_SHAKE failed", it)
                            Toast.makeText(
                                this@PetService,
                                getString(
                                    R.string.panel_open_failed,
                                    it.localizedMessage ?: it.javaClass.simpleName
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        tapHandler.postDelayed({ phoneShakeCooling = false }, 1_800L)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
        }
        phoneShakeListener = listener
        runCatching {
            sm.registerListener(
                listener,
                accel,
                android.hardware.SensorManager.SENSOR_DELAY_UI
            )
        }.onFailure {
            phoneShakeListener = null
            android.util.Log.e("PetService", "register accelerometer failed", it)
        }
    }

    private fun stopPhoneShakeListen() {
        draggingPet = false
        val listener = phoneShakeListener ?: return
        phoneShakeListener = null
        val sm = getSystemService(SENSOR_SERVICE) as? android.hardware.SensorManager
        runCatching { sm?.unregisterListener(listener) }
    }

    fun openFocusTimer() {
        menuOverlay?.close()
        runCatching {
            // 用独立透明 Activity 承载 Reef Compose（Service overlay 易因 Lifecycle/SavedState 崩溃）
            FocusTimerActivity.open(this)
        }.onFailure {
            android.util.Log.e("PetService", "openFocusTimer failed", it)
            Toast.makeText(
                this,
                getString(R.string.panel_open_failed, it.localizedMessage ?: it.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun openFlashNote(prefill: String? = null, source: String = "typed") {
        menuOverlay?.close()
        runCatching {
            FlashNoteHud.startCapture(this, prefill = prefill, source = source)
        }.onFailure {
            Toast.makeText(
                this,
                getString(R.string.panel_open_failed, it.localizedMessage ?: it.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun openFlashNoteList() {
        menuOverlay?.close()
        runCatching {
            FlashNoteHud.showList(this)
        }.onFailure {
            Toast.makeText(
                this,
                getString(R.string.panel_open_failed, it.localizedMessage ?: it.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun openSchedulePage() {
        menuOverlay?.close()
        runCatching {
            startActivity(
                android.content.Intent(this, ScheduleActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Toast.makeText(
                this,
                getString(R.string.panel_open_failed, it.localizedMessage ?: it.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showFeatureMenu() {
        runCatching {
            check(Settings.canDrawOverlays(this)) { getString(R.string.overlay_permission_required) }
            val view = petView ?: return@runCatching
            val petParams = view.layoutParams as? WindowManager.LayoutParams ?: return@runCatching
            menuOverlay?.close()
            panelOverlay?.close()
            menuOverlay = PetMenuOverlay(
                this,
                windowManager,
                petParams.x,
                petParams.y,
                petParams.width,
                petParams.height
            )
            menuOverlay?.show()
        }.onFailure {
            Toast.makeText(
                this,
                getString(R.string.panel_open_failed, it.localizedMessage ?: it.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showSleepLock() {
        if (!Settings.canDrawOverlays(this)) return
        currentState = PetState.SLEEP
        petView?.show(assets.randomFileFor(PetState.SLEEP))
        applyVisualState()
        idleAnimator?.cancel()
        walkAnimator?.cancel()
        if (sleepLockOverlay == null) {
            sleepLockOverlay = SleepLockOverlay(this, windowManager)
        }
        sleepLockOverlay?.show()
    }

    private fun hideSleepLock() {
        HabitGuardian.clearSleepLock()
        sleepLockOverlay?.close()
        sleepLockOverlay = null
    }

    private fun showControlPanel() {
        runCatching {
            check(Settings.canDrawOverlays(this)) { getString(R.string.overlay_permission_required) }
            val view = petView ?: return@runCatching
            val petParams = view.layoutParams as? WindowManager.LayoutParams
                ?: return@runCatching
            menuOverlay?.close()
            panelOverlay?.close()
            panelOverlay = PetPanelOverlay(this, windowManager, petParams.x, petParams.y, petParams.height)
            panelOverlay?.show()
        }.onFailure {
            panelOverlay?.close()
            panelOverlay = null
            Toast.makeText(
                this,
                getString(R.string.panel_open_failed, it.localizedMessage ?: it.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun saveLastStartError(error: Throwable) {
        getSharedPreferences(RUNTIME_PREFERENCES, MODE_PRIVATE).edit()
            .putString(KEY_LAST_ERROR, error.localizedMessage ?: error.javaClass.simpleName)
            .apply()
    }

    private fun clearLastStartError() {
        getSharedPreferences(RUNTIME_PREFERENCES, MODE_PRIVATE).edit()
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    private fun updateFreeWalkRandom() {
        interactionHandler.removeCallbacksAndMessages(null)
        walkAnimator?.cancel()
        if (!settings.edgeWalkEnabled || petView == null || isSleepingByTime() || currentState == PetState.SLEEP) return
        val view = petView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - params.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - params.height).coerceAtLeast(0)
        val startX = params.x.coerceIn(0, maxX)
        val startY = params.y.coerceIn(0, maxY)
        val targetX = Random.nextInt(0, maxX + 1)
        val targetY = Random.nextInt(0, maxY + 1)
        val distance = kotlin.math.hypot((targetX - startX).toDouble(), (targetY - startY).toDouble())
        val duration = (distance * 18 - settings.walkSpeed * 28).toLong().coerceIn(450L, 6500L)

        currentState = PetState.WALK
        view.show(assets.randomFileFor(PetState.WALK))
        applyVisualState()
        pushLocalFriendState(force = true)
        // The directional walk GIF faces left in its source file. Mirror once per leg only.
        val movingRight = targetX >= startX
        view.setFacingRight(!movingRight)
        var wasCancelled = false
        walkAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            addUpdateListener { animation ->
                val p = animation.animatedFraction
                params.x = (startX + (targetX - startX) * p).toInt().coerceIn(0, maxX)
                params.y = (startY + (targetY - startY) * p).toInt().coerceIn(0, maxY)
                windowManager.updateViewLayout(view, params)
                layoutFriendOverlays()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    wasCancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!wasCancelled && settings.edgeWalkEnabled) {
                        interactionHandler.postDelayed(
                            { updateFreeWalkRandom() },
                            Random.nextLong(700L, 2600L)
                        )
                    }
                }
            })
            start()
        }
    }

    private fun playRandomBehavior(allowWhileWalking: Boolean = false) {
        if (isSleepingByTime() || petView == null) return
        if (settings.edgeWalkEnabled && !allowWhileWalking) return
        val roll = Random.nextInt(5)
        currentState = when (roll) {
            0 -> PetState.PLAY
            1 -> PetState.RANDOM
            2 -> PetState.BORED
            else -> if (settings.mood >= 50) PetState.HAPPY else PetState.SAD
        }
        petView?.show(assets.randomFileFor(currentState))
        applyVisualState()
        pushLocalFriendState(force = true)
    }

    private fun evaluateLocalTime() {
        val now = LocalTime.now()
        HabitGuardian.onSleepWindowTick(this)
        if (TimeBehaviorConfig.isSleepTime(now)) {
            if (currentState != PetState.SLEEP && currentState != PetState.ANGRY) {
                setTimedState(PetState.SLEEP, "sleep")
            }
            walkAnimator?.cancel()
            return
        }
        hideSleepLock()
        if (currentState == PetState.SLEEP) setTimedState(PetState.IDLE, "wake")
        val key = "${now.hour}:${now.minute}"
        if (now.hour == TimeBehaviorConfig.lunchTime.hour && now.minute == 0 && lastTimeActionKey != key) {
            lastTimeActionKey = key
            setTimedState(PetState.FEED, "lunch")
        } else if (currentState == PetState.FEED && now.minute != 0) {
            setTimedState(PetState.IDLE, "day")
        }
        val canStartWalking = currentState == PetState.IDLE || currentState == PetState.WALK
        if (canStartWalking && walkAnimator?.isRunning != true) updateEdgeWalk()
    }

    private fun applyHabitReaction(intent: Intent) {
        val delta = intent.getIntExtra(EXTRA_MOOD_DELTA, 0)
        if (delta != 0) settings.mood = settings.mood + delta
        val state = PetState.fromKey(intent.getStringExtra(EXTRA_STATE))
        val reaction = intent.getStringExtra(EXTRA_REACTION).orEmpty()
        currentState = when {
            reaction == "sleep_angry" -> PetState.ANGRY
            state != PetState.IDLE -> state
            reaction == "angry" -> PetState.ANGRY
            reaction == "happy" || reaction == "praise" -> PetState.HAPPY
            reaction == "bored" -> PetState.BORED
            else -> PetState.SAD
        }
        walkAnimator?.cancel()
        petView?.show(assets.randomFileFor(currentState))
        applyVisualState()
        pushLocalFriendState(force = true)
        if (currentState == PetState.SLEEP) {
            idleAnimator?.cancel()
        } else {
            startIdleAnimation()
            resumeFreeWalkAfter(INTERACTION_DISPLAY_MS)
        }
    }

    private fun showHabitConfirm(packageName: String, activeKey: String, label: String) {
        if (!Settings.canDrawOverlays(this)) return
        if (!::windowManager.isInitialized) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }
        habitConfirmOverlay?.close()
        habitConfirmOverlay = HabitConfirmOverlay(this, windowManager)
        habitConfirmOverlay?.show(packageName, activeKey, label)
    }

    private fun showHabitRestrict(message: String) {
        if (!Settings.canDrawOverlays(this)) return
        if (!::windowManager.isInitialized) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }
        if (habitRestrictOverlay == null) {
            habitRestrictOverlay = HabitRestrictOverlay(this, windowManager)
        }
        habitRestrictOverlay?.show(message)
    }

    private fun hideHabitRestrict() {
        habitRestrictOverlay?.close()
    }

    private fun showSpeechBubble(message: String) {
        if (!Settings.canDrawOverlays(this)) return
        if (!::windowManager.isInitialized) {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        }
        val view = petView
        if (view == null) {
            if (Settings.canDrawOverlays(this)) showPet()
        }
        val pet = petView ?: return
        val petParams = pet.layoutParams as? WindowManager.LayoutParams ?: return
        if (speechBubbleOverlay == null) {
            speechBubbleOverlay = PetSpeechBubbleOverlay(this, windowManager)
        }
        speechBubbleOverlay?.show(
            message,
            petParams.x,
            petParams.y,
            petParams.width,
            petParams.height
        )
        // 生气/提醒时让桌宠更明显一点
        pet.animate().cancel()
        pet.animate()
            .scaleX(1.1f).scaleY(1.1f)
            .setDuration(120)
            .withEndAction {
                pet.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            }.start()
    }

    private fun setTimedState(state: PetState, key: String) {
        if (lastTimeActionKey == key && currentState == state) return
        lastTimeActionKey = key
        currentState = state
        petView?.show(assets.randomFileFor(state))
        applyVisualState()
        pushLocalFriendState(force = true)
        if (state == PetState.SLEEP) {
            idleAnimator?.cancel()
            walkAnimator?.cancel()
        } else {
            startIdleAnimation()
        }
    }

    private fun isSleepingByTime() = TimeBehaviorConfig.isSleepTime(LocalTime.now())

    private fun resizeCanvasForCurrentScreen() {
        val view = petView ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val canvasSize = PetCanvasConfig.resolve(this)
        params.width = canvasSize.widthPx
        params.height = canvasSize.heightPx
        val screen = resources.displayMetrics
        params.x = params.x.coerceIn(0, (screen.widthPixels - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screen.heightPixels - params.height).coerceAtLeast(0))
        windowManager.updateViewLayout(view, params)
    }

    private fun startIdleAnimation() {
        idleAnimator?.cancel()
        val distance = 8 * resources.displayMetrics.density
        idleAnimator = ObjectAnimator.ofFloat(petView, View.TRANSLATION_Y, 0f, -distance, 0f).apply {
            duration = 1800
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun nextExpression() {
        walkAnimator?.cancel()
        playRandomBehavior(allowWhileWalking = true)
        petView?.animate()?.scaleX(1.08f)?.scaleY(1.08f)?.rotationBy(8f)?.setDuration(120)?.withEndAction {
            petView?.animate()?.scaleX(1f)?.scaleY(1f)?.rotation(0f)?.setDuration(180)?.start()
        }?.start()
        resumeFreeWalkAfter(INTERACTION_DISPLAY_MS)
    }

    private fun performAssigned(gesture: PetGesture) {
        performAction(settings.actionFor(gesture))
    }

    fun performAction(action: PetAction) {
        when (action) {
            PetAction.CHANGE_STYLE -> nextExpression()
            PetAction.FEATURE_MENU -> showFeatureMenu()
            PetAction.CONTROL_PANEL -> showControlPanel()
            PetAction.EXTRACT_TEXT -> extractText()
            PetAction.FLASH_NOTE -> openFlashNote()
            PetAction.FLASH_NOTE_LIST -> openFlashNoteList()
            PetAction.FOCUS_TIMER -> openFocusTimer()
            PetAction.PET -> touchInteraction()
        }
    }

    private fun resumeFreeWalkAfter(delayMs: Long) {
        interactionHandler.removeCallbacksAndMessages(null)
        if (settings.edgeWalkEnabled) {
            interactionHandler.postDelayed({ updateFreeWalkRandom() }, delayMs)
        }
    }

    private inner class PetTouchListener(
        private val params: WindowManager.LayoutParams
    ) : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var downX = 0f
        private var downY = 0f
        private var lastMoveX = 0f
        private var lastMoveY = 0f
        private var lastDirX = 0
        private var lastDirY = 0
        private var reverseCount = 0
        private var moved = false
        private var lastTapAt = 0L
        private var secondTap = false
        private var holdFired = false
        private var shakeFired = false

        private val singleTapRunnable = Runnable { performAssigned(PetGesture.SINGLE_TAP) }
        private val holdRunnable = Runnable {
            holdFired = true
            performAssigned(PetGesture.DOUBLE_TAP_HOLD)
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    downX = event.rawX
                    downY = event.rawY
                    lastMoveX = event.rawX
                    lastMoveY = event.rawY
                    lastDirX = 0
                    lastDirY = 0
                    reverseCount = 0
                    downAt = android.os.SystemClock.uptimeMillis()
                    moved = false
                    holdFired = false
                    shakeFired = false
                    secondTap = downAt - lastTapAt <= DOUBLE_TAP_MS
                    if (secondTap) {
                        tapHandler.removeCallbacks(singleTapRunnable)
                        tapHandler.postDelayed(holdRunnable, LONG_PRESS_MS)
                    }
                    idleAnimator?.pause()
                    walkAnimator?.cancel()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (suppressDragUntilUp) return true
                    val dx = (event.rawX - downX).toInt()
                    val dy = (event.rawY - downY).toInt()
                    if (abs(dx) > viewConfigurationTouchSlop || abs(dy) > viewConfigurationTouchSlop) {
                        if (!moved) {
                            moved = true
                            draggingPet = true
                            startPhoneShakeListen()
                        }
                        tapHandler.removeCallbacks(holdRunnable)
                    }
                    trackShake(event.rawX, event.rawY)
                    val screen = resources.displayMetrics
                    params.x = (initialX + dx).coerceIn(
                        0,
                        (screen.widthPixels - view.width).coerceAtLeast(0)
                    )
                    params.y = (initialY + dy).coerceIn(
                        0,
                        (screen.heightPixels - view.height).coerceAtLeast(0)
                    )
                    runCatching { windowManager.updateViewLayout(view, params) }
                    layoutFriendOverlays()
                    if (!shakeFired && reverseCount >= SHAKE_REVERSES) {
                        shakeFired = true
                        tapHandler.removeCallbacks(holdRunnable)
                        performAssigned(PetGesture.SHAKE)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopPhoneShakeListen()
                    suppressDragUntilUp = false
                    idleAnimator?.resume()
                    tapHandler.removeCallbacks(holdRunnable)
                    val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                    if (!cancelled && !moved && !holdFired && !shakeFired) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (secondTap) {
                            tapHandler.removeCallbacks(singleTapRunnable)
                            performAssigned(PetGesture.DOUBLE_TAP)
                            lastTapAt = 0L
                        } else {
                            lastTapAt = now
                            tapHandler.removeCallbacks(singleTapRunnable)
                            tapHandler.postDelayed(singleTapRunnable, DOUBLE_TAP_MS)
                        }
                    } else {
                        lastTapAt = 0L
                    }
                    if (moved || cancelled) updateEdgeWalk()
                    getSharedPreferences("pet_position", MODE_PRIVATE).edit()
                        .putInt("x", params.x)
                        .putInt("y", params.y)
                        .apply()
                    return true
                }
            }
            return false
        }

        private fun trackShake(rawX: Float, rawY: Float) {
            val segment = SHAKE_SEGMENT_PX * resources.displayMetrics.density
            val vx = rawX - lastMoveX
            val vy = rawY - lastMoveY
            if (abs(vx) >= segment) {
                val dir = if (vx > 0) 1 else -1
                if (lastDirX != 0 && dir != lastDirX) reverseCount++
                lastDirX = dir
                lastMoveX = rawX
            }
            if (abs(vy) >= segment) {
                val dir = if (vy > 0) 1 else -1
                if (lastDirY != 0 && dir != lastDirY) reverseCount++
                lastDirY = dir
                lastMoveY = rawY
            }
        }

        private var downAt = 0L

        private val viewConfigurationTouchSlop: Int
            get() = (8 * resources.displayMetrics.density).toInt()
    }

    private fun touchInteraction() {
        if (isSleepingByTime()) return
        walkAnimator?.cancel()
        settings.mood = settings.mood + 5
        currentState = PetState.TOUCH
        petView?.show(assets.randomFileFor(PetState.TOUCH))
        applyVisualState()
        pushLocalFriendState(force = true)
        petView?.animate()?.scaleX(1.08f)?.scaleY(1.08f)?.setDuration(120)?.withEndAction {
            petView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(180)?.start()
        }?.start()
        resumeFreeWalkAfter(INTERACTION_DISPLAY_MS)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_pet_notification)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_text))
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    internal fun setMicrophoneCapture(enable: Boolean) {
        micCaptureOn = enable
        applyForegroundTypes()
    }

    internal fun setMediaPlayback(enable: Boolean) {
        mediaPlaybackOn = enable
        applyForegroundTypes()
    }

    private fun applyForegroundTypes() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (micCaptureOn) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (mediaPlaybackOn) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        runCatching {
            startForeground(NOTIFICATION_ID, buildNotification(), type)
        }
    }

    private fun playLastFlashAudio() {
        val note = runCatching { FlashNoteStore.all() }.getOrDefault(emptyList())
            .firstOrNull { it.hasAudio } ?: return
        FlashNotePlayer.play(this, note.audioPath.orEmpty(), note.id)
    }

    companion object {
        const val ACTION_REFRESH = "com.geekathon.guardpet.action.REFRESH"
        const val ACTION_REFRESH_SETTINGS = "com.geekathon.guardpet.action.REFRESH_SETTINGS"
        const val ACTION_SET_STATE = "com.geekathon.guardpet.action.SET_STATE"
        const val ACTION_SLEEP_LOCK = "com.geekathon.guardpet.action.SLEEP_LOCK"
        const val ACTION_SLEEP_UNLOCK = "com.geekathon.guardpet.action.SLEEP_UNLOCK"
        const val ACTION_SHOW_PET = "com.geekathon.guardpet.action.SHOW_PET"
        const val ACTION_OPEN_TIMER = "com.geekathon.guardpet.action.OPEN_TIMER"
        const val ACTION_PLAY_LAST_FLASH = "com.geekathon.guardpet.action.PLAY_LAST_FLASH"
        const val ACTION_HABIT_REACTION = "com.geekathon.guardpet.action.HABIT_REACTION"
        const val ACTION_HABIT_CONFIRM = "com.geekathon.guardpet.action.HABIT_CONFIRM"
        const val ACTION_HABIT_RESTRICT = "com.geekathon.guardpet.action.HABIT_RESTRICT"
        const val ACTION_HABIT_RESTRICT_HIDE = "com.geekathon.guardpet.action.HABIT_RESTRICT_HIDE"
        const val ACTION_SPEECH_BUBBLE = "com.geekathon.guardpet.action.SPEECH_BUBBLE"
        const val ACTION_EXTRACT_TEXT = "com.geekathon.guardpet.action.EXTRACT_TEXT"
        const val EXTRA_STATE = "state"
        const val EXTRA_REACTION = "reaction"
        const val EXTRA_MOOD_DELTA = "mood_delta"
        const val EXTRA_CONFIRM_PKG = "confirm_pkg"
        const val EXTRA_CONFIRM_ACTIVE = "confirm_active"
        const val EXTRA_CONFIRM_LABEL = "confirm_label"
        const val EXTRA_RESTRICT_MSG = "restrict_msg"
        const val EXTRA_BUBBLE_TEXT = "bubble_text"
        private const val LONG_PRESS_MS = 650L
        private const val DOUBLE_TAP_MS = 350L
        private const val SHAKE_REVERSES = 4
        private const val SHAKE_SEGMENT_PX = 36f
        private const val CAPTURE_DELAY_MS = 280L
        private const val INTERACTION_DISPLAY_MS = 1_800L
        private const val MAX_FRIEND_OVERLAYS = 4
        private const val CHANNEL_ID = "desktop_pet_channel"
        private const val NOTIFICATION_ID = 1001
        private const val RUNTIME_PREFERENCES = "pet_runtime"
        private const val KEY_LAST_ERROR = "last_start_error"
        @Volatile var isRunning = false
            private set
        @Volatile internal var instance: PetService? = null
            private set
    }
}
