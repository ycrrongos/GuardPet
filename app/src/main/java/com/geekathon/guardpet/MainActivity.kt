package com.geekathon.guardpet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.geekathon.guardpet.databinding.ActivityMainBinding
import dev.pranav.reef.PermissionsCheckActivity
import dev.pranav.reef.util.checkAndRequestMissingPermissions
import dev.pranav.reef.util.hasUsageStatsPermission
import dev.pranav.reef.util.isAccessibilityServiceEnabledForBlocker

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var assets: PetAssetRepository
    private lateinit var settings: PetSettings
    private var hasPromptedPermissions = false
    private var skipPermissionPromptOnce = false
    private var stopFlashObserve: (() -> Unit)? = null
    private var settingsExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        binding.contentColumn.layoutParams = binding.contentColumn.layoutParams.apply {
            width = minOf(
                resources.displayMetrics.widthPixels,
                (600 * resources.displayMetrics.density).toInt()
            )
        }
        assets = PetAssetRepository(this)
        settings = PetSettings(this)

        binding.petPreview.fitPreviewToCanvas()
        binding.petPreview.show(assets.randomFileFor(PetState.HAPPY))
        binding.startButton.setOnClickListener { requestPermissionsAndStart() }
        binding.stopButton.setOnClickListener { stopPet() }
        binding.petButton.setOnClickListener { changeMood(PetState.TOUCH, 5) }
        binding.sleepButton.setOnClickListener { sendState(PetState.SLEEP) }
        binding.feedButton.setOnClickListener { feedPet() }
        binding.todoButton.setOnClickListener {
            startActivity(Intent(this, PetPanelActivity::class.java))
        }
        if (intent?.getBooleanExtra(EXTRA_AUTO_EXTRACT, false) == true) {
            scheduleAutoExtract()
        }
        intent?.getStringExtra(EXTRA_DEBUG_BIGBANG)?.takeIf { it.isNotBlank() }?.let { raw ->
            skipPermissionPromptOnce = true
            val text = raw.replace("\\n", "\n")
            TextCaptureHolder.tokens = TextTokenizer.splitAll(listOf(text))
            startActivity(
                Intent(this, BigBangActivity::class.java).apply {
                    if (intent.getBooleanExtra(EXTRA_DEBUG_AUTO_AI, false)) {
                        putExtra(BigBangActivity.EXTRA_AUTO_AI_NOTE, true)
                    }
                }
            )
        }
        settingsExpanded = savedInstanceState?.getBoolean(KEY_SETTINGS_EXPANDED) ?: false
        updateSettingsExpansion()
        binding.settingsToggle.setOnClickListener {
            settingsExpanded = !settingsExpanded
            updateSettingsExpansion()
        }
        binding.freeWalkSwitch.isChecked = settings.edgeWalkEnabled
        binding.freeWalkSwitch.setOnCheckedChangeListener { _, enabled ->
            settings.edgeWalkEnabled = enabled
            sendServiceAction(PetService.ACTION_REFRESH_SETTINGS)
        }
        binding.petSizeSeek.progress = ((settings.petScale - 0.55f) / 0.9f * 100f).toInt()
        binding.petSizeSeek.setOnSeekBarChangeListener(simpleSeekBarListener { progress ->
            settings.petScale = 0.55f + progress / 100f * 0.9f
            updateSettingLabels()
            sendServiceAction(PetService.ACTION_REFRESH_SETTINGS)
        })
        binding.walkSpeedSeek.progress = ((settings.walkSpeed - WALK_SPEED_MIN) * 100 /
            (WALK_SPEED_MAX - WALK_SPEED_MIN)).coerceIn(0, 100)
        binding.walkSpeedSeek.setOnSeekBarChangeListener(simpleSeekBarListener { progress ->
            settings.walkSpeed = WALK_SPEED_MIN + progress * (WALK_SPEED_MAX - WALK_SPEED_MIN) / 100
            updateSettingLabels()
            sendServiceAction(PetService.ACTION_REFRESH_SETTINGS)
        })
        bindGestureShortcut(binding.gestureSingleTap, PetGesture.SINGLE_TAP)
        bindGestureShortcut(binding.gestureDoubleTap, PetGesture.DOUBLE_TAP)
        bindGestureShortcut(binding.gestureShake, PetGesture.SHAKE)
        bindGestureShortcut(binding.gestureDoubleTapHold, PetGesture.DOUBLE_TAP_HOLD)
        bindGestureShortcut(binding.gestureDragPhoneShake, PetGesture.DRAG_PHONE_SHAKE)
        binding.focusButton.setOnClickListener {
            if (!PetService.isRunning) {
                Toast.makeText(this, R.string.start_pet_first, Toast.LENGTH_SHORT).show()
            } else {
                sendServiceAction(PetService.ACTION_OPEN_TIMER)
            }
        }
        binding.habitGuardButton.setOnClickListener {
            startActivity(Intent(this, HabitGuardianActivity::class.java))
        }
        binding.reefSettingsButton.setOnClickListener { FocusLauncher.openSettings(this) }
        binding.flashNoteButton.setOnClickListener { openFlashNoteComposer() }
        binding.focusAttribution.setOnClickListener { FocusLauncher.openAbout(this) }

        updateValues()
        updateSettingLabels()
        updateStatus()
        renderFlashNotes()
    }

    override fun onResume() {
        super.onResume()
        if (::settings.isInitialized) {
            updateValues()
            binding.freeWalkSwitch.isChecked = settings.edgeWalkEnabled
            updateStatus()
            renderFlashNotes()
        }
        stopFlashObserve?.invoke()
        stopFlashObserve = FlashNoteStore.observe { renderFlashNotes() }
        if (!hasPromptedPermissions) {
            hasPromptedPermissions = true
            if (!skipPermissionPromptOnce) {
                checkAndRequestMissingPermissions()
            }
            skipPermissionPromptOnce = false
        }
    }

    override fun onPause() {
        stopFlashObserve?.invoke()
        stopFlashObserve = null
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_SETTINGS_EXPANDED, settingsExpanded)
        super.onSaveInstanceState(outState)
    }

    private fun openFlashNoteComposer() {
        if (Settings.canDrawOverlays(this)) {
            FlashNoteHud.startCapture(this)
        } else {
            startActivity(Intent(this, FlashNoteActivity::class.java))
        }
    }

    private fun requestPermissionsAndStart() {
        if (!Settings.canDrawOverlays(this) ||
            !hasUsageStatsPermission() ||
            !isAccessibilityServiceEnabledForBlocker()
        ) {
            startActivity(Intent(this, PermissionsCheckActivity::class.java))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            startActivity(Intent(this, PermissionsCheckActivity::class.java))
            return
        }
        startPetService()
    }

    private fun startPetService() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
            updateStatus(false)
            return
        }
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, PetService::class.java))
        }.onSuccess {
            updateStatus(true)
            binding.root.postDelayed({
                if (!PetService.isRunning) {
                    updateStatus(false)
                    showLastStartError()
                }
            }, SERVICE_START_CHECK_DELAY_MS)
        }.onFailure {
            updateStatus(false)
            Toast.makeText(
                this,
                getString(R.string.start_failed, it.localizedMessage ?: it.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showLastStartError() {
        val message = getSharedPreferences("pet_runtime", MODE_PRIVATE)
            .getString("last_start_error", null) ?: return
        Toast.makeText(this, getString(R.string.start_failed, message), Toast.LENGTH_LONG).show()
    }

    private fun stopPet() {
        stopService(Intent(this, PetService::class.java))
        binding.petPreview.show(assets.randomFileFor(PetState.HAPPY))
        updateStatus(false)
    }

    private fun feedPet() {
        if (settings.foodCount <= 0) {
            Toast.makeText(this, R.string.no_food, Toast.LENGTH_SHORT).show()
            return
        }
        settings.foodCount -= 1
        settings.hunger += 20
        settings.mood += 3
        updateValues()
        sendState(PetState.FEED)
    }

    private fun changeMood(state: PetState, delta: Int) {
        settings.mood += delta
        updateValues()
        sendState(state)
    }

    private fun sendState(state: PetState) {
        if (!PetService.isRunning) {
            Toast.makeText(this, R.string.start_pet_first, Toast.LENGTH_SHORT).show()
            return
        }
        startService(
            Intent(this, PetService::class.java)
                .setAction(PetService.ACTION_SET_STATE)
                .putExtra(PetService.EXTRA_STATE, state.key)
        )
        binding.petPreview.show(assets.randomFileFor(state))
        binding.companionMessage.setText(
            when (state) {
                PetState.FEED -> R.string.hero_message_feed
                PetState.TOUCH -> R.string.hero_message_pet
                PetState.SLEEP -> R.string.hero_message_sleep
                else -> R.string.hero_message_running
            }
        )
    }

    private fun updateValues() {
        val mood = settings.mood
        val hunger = settings.hunger
        val food = settings.foodCount
        binding.moodText.text = mood.toString()
        binding.moodText.contentDescription = getString(R.string.mood_accessibility, mood)
        binding.moodProgress.progress = mood
        binding.hungerText.text = hunger.toString()
        binding.hungerText.contentDescription = getString(R.string.hunger_accessibility, hunger)
        binding.hungerProgress.progress = hunger
        binding.foodText.text = food.toString()
        binding.foodText.contentDescription = getString(R.string.food_accessibility, food)
    }

    private fun updateStatus(running: Boolean = PetService.isRunning) {
        binding.statusText.text = when {
            !Settings.canDrawOverlays(this) -> getString(R.string.status_permission)
            running -> getString(R.string.status_running)
            else -> getString(R.string.status_stopped)
        }
        binding.statusBadge.setText(
            when {
                running -> R.string.badge_running
                else -> R.string.badge_stopped
            }
        )
        binding.companionMessage.setText(
            if (running) R.string.hero_message_running else R.string.hero_message_idle
        )
        binding.startButton.visibility = if (running) View.GONE else View.VISIBLE
        binding.stopButton.visibility = if (running) View.VISIBLE else View.GONE
        binding.startButton.isEnabled = !running
        binding.stopButton.isEnabled = running
        binding.feedButton.isEnabled = running
        binding.petButton.isEnabled = running
        binding.sleepButton.isEnabled = running
        updatePermissionStatus()
    }

    private fun updateSettingLabels() {
        binding.petSizeValue.text = getString(R.string.percent_value, (settings.petScale * 100).toInt())
        binding.walkSpeedValue.text = getString(R.string.percent_value, binding.walkSpeedSeek.progress)
    }

    private fun updateSettingsExpansion() {
        binding.settingsDetails.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
        binding.settingsSummary.setText(
            if (settingsExpanded) R.string.settings_expanded else R.string.settings_collapsed
        )
        binding.settingsChevron.rotation = if (settingsExpanded) 180f else 0f
        binding.settingsToggle.contentDescription = getString(
            if (settingsExpanded) R.string.settings_collapse else R.string.settings_expand
        )
    }

    private fun updatePermissionStatus() {
        fun mark(granted: Boolean) =
            getString(if (granted) R.string.permission_ok else R.string.permission_missing)
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        binding.permissionStatus.text = buildString {
            appendLine(getString(R.string.permission_overlay, mark(Settings.canDrawOverlays(this@MainActivity))))
            appendLine(getString(R.string.permission_usage, mark(hasUsageStatsPermission())))
            appendLine(getString(R.string.permission_a11y, mark(isAccessibilityServiceEnabledForBlocker())))
            appendLine(getString(R.string.permission_mic, mark(micGranted)))
            append(
                getString(
                    R.string.permission_sensevoice,
                    getString(
                        if (SenseVoiceModelStore.isPackInstalled(this@MainActivity) ||
                            SenseVoiceModelStore.hasLocalModel(this@MainActivity)
                        ) {
                            R.string.permission_sensevoice_ok
                        } else {
                            R.string.permission_sensevoice_missing
                        }
                    )
                )
            )
        }
    }

    private fun renderFlashNotes() {
        binding.flashNoteContainer.removeAllViews()
        val notes = runCatching { FlashNoteStore.all() }.getOrDefault(emptyList())
        if (notes.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.flash_note_empty_list)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                textSize = 14f
            }
            binding.flashNoteContainer.addView(empty)
            return
        }
        val inflater = LayoutInflater.from(this)
        notes.forEach { note ->
            val row = inflater.inflate(R.layout.item_flash_note, binding.flashNoteContainer, false)
            val category = row.findViewById<TextView>(R.id.noteCategory)
            val text = row.findViewById<TextView>(R.id.noteText)
            val delete = row.findViewById<TextView>(R.id.noteDelete)
            val colorDot = row.findViewById<View>(R.id.noteColorDot)
            val categoryLabel = getString(note.category.labelRes)
            category.text = if (note.scheduleDate.isNullOrBlank()) {
                categoryLabel
            } else {
                "$categoryLabel · ${note.scheduleDate}"
            }
            text.text = note.text
            colorDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(FlashNoteColor.argb(note.color))
            }
            delete.setOnClickListener {
                if (FlashNotePlayer.playingId == note.id) FlashNotePlayer.stop()
                FlashNoteStore.delete(note.id)
            }
            binding.flashNoteContainer.addView(row)
        }
    }

    private fun bindGestureShortcut(spinner: Spinner, gesture: PetGesture) {
        val actions = PetAction.entries
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            actions.map { getString(it.labelRes) }
        )
        spinner.setSelection(actions.indexOf(settings.actionFor(gesture)).coerceAtLeast(0), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                settings.setAction(gesture, actions[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun simpleSeekBarListener(onChanged: (Int) -> Unit) =
        object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChanged(progress)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        }

    private fun sendServiceAction(action: String) {
        if (PetService.isRunning) startService(Intent(this, PetService::class.java).setAction(action))
    }

    /** Debug/agent smoke: adb am start … --ez auto_extract true */
    private fun scheduleAutoExtract() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
            return
        }
        val trigger = Runnable {
            if (PetService.isRunning) {
                sendServiceAction(PetService.ACTION_EXTRACT_TEXT)
            } else {
                Toast.makeText(this, R.string.start_pet_first, Toast.LENGTH_SHORT).show()
            }
        }
        if (PetService.isRunning) {
            binding.root.postDelayed(trigger, 600L)
        } else {
            startPetService()
            binding.root.postDelayed(trigger, 1_800L)
        }
    }

    companion object {
        const val EXTRA_AUTO_EXTRACT = "auto_extract"
        /** 自测：直接打开大爆炸词块页（绕过抓字）。 */
        const val EXTRA_DEBUG_BIGBANG = "debug_bigbang_text"
        const val EXTRA_DEBUG_AUTO_AI = "debug_auto_ai"
        private const val KEY_SETTINGS_EXPANDED = "settings_expanded"
        private const val SERVICE_START_CHECK_DELAY_MS = 1_200L
        private const val WALK_SPEED_MIN = 10
        private const val WALK_SPEED_MAX = 500
    }
}
