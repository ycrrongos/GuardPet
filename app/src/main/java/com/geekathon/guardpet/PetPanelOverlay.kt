package com.geekathon.guardpet

import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.geekathon.guardpet.databinding.ActivityPetPanelBinding

/** Compact overlay panel hosted by the foreground service, so it stays above other apps. */
class PetPanelOverlay(
    private val service: PetService,
    private val windowManager: WindowManager,
    private val anchorX: Int,
    private val anchorY: Int,
    private val anchorHeight: Int
) {
    private val settings = PetSettings(service)
    private val themedContext = ContextThemeWrapper(service, R.style.Theme_DesktopPet)
    private val binding = ActivityPetPanelBinding.inflate(LayoutInflater.from(themedContext))
    private var attached = false
    private lateinit var params: WindowManager.LayoutParams

    fun show() {
        if (attached) return
        val metrics = service.resources.displayMetrics
        val width = (metrics.widthPixels * 0.92f).toInt().coerceAtLeast(280)
        val height = (metrics.heightPixels * 0.36f).toInt()
            .coerceIn((240 * metrics.density).toInt(), (360 * metrics.density).toInt())
            .coerceAtMost((metrics.heightPixels * 0.9f).toInt())
        params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = anchorX.coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
            y = (anchorY + anchorHeight + (8 * metrics.density).toInt())
                .coerceIn(0, (metrics.heightPixels - height).coerceAtLeast(0))
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        binding.freeWalkSwitch.isChecked = settings.edgeWalkEnabled
        binding.freeWalkSwitch.setOnCheckedChangeListener { _, enabled ->
            settings.edgeWalkEnabled = enabled
            service.refreshSettings()
        }
        binding.closePanelButton.setOnClickListener { close() }
        binding.stopPetButton.setOnClickListener {
            close()
            service.stopSelf()
        }
        binding.addTodoButton.setOnClickListener { addTodoRow("") }
        binding.focusButton.setOnClickListener {
            close()
            service.openFocusTimer()
        }
        loadTodos()
        windowManager.addView(binding.root, params)
        attached = true
        updateTodoCount()
    }

    fun close() {
        if (!attached) return
        saveTodos()
        runCatching { windowManager.removeView(binding.root) }
        attached = false
    }

    private fun loadTodos() {
        val preferences = service.getSharedPreferences(TODO_PREFERENCES, android.content.Context.MODE_PRIVATE)
        repeat(preferences.getInt(KEY_COUNT, 0)) { index ->
            addTodoRow(preferences.getString("todo_$index", "").orEmpty())
        }
    }

    private fun addTodoRow(initialText: String) {
        val row = LayoutInflater.from(themedContext).inflate(R.layout.item_todo, binding.todoContainer, false)
        val input = row.findViewById<EditText>(R.id.todoInput)
        val actions = row.findViewById<LinearLayout>(R.id.todoActions)
        val done = row.findViewById<View>(R.id.todoDone)
        val delete = row.findViewById<View>(R.id.todoDelete)
        input.setText(initialText)

        fun revealActions() { actions.visibility = View.VISIBLE }
        row.setOnClickListener { revealActions() }
        input.setOnClickListener { revealActions() }
        input.setOnFocusChangeListener { _, focused -> if (focused) revealActions() }
        done.setOnClickListener {
            if (input.text.isNullOrBlank()) {
                Toast.makeText(service, R.string.todo_empty_warning, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            settings.foodCount += 1
            settings.mood += 8
            binding.todoContainer.removeView(row)
            saveTodos()
            updateTodoCount()
            Toast.makeText(service, R.string.todo_reward, Toast.LENGTH_SHORT).show()
        }
        delete.setOnClickListener {
            binding.todoContainer.removeView(row)
            saveTodos()
            updateTodoCount()
        }
        binding.todoContainer.addView(row)
        updateTodoCount()
    }

    private fun saveTodos() {
        val editor = service.getSharedPreferences(TODO_PREFERENCES, android.content.Context.MODE_PRIVATE)
            .edit().clear()
        val values = buildList {
            for (index in 0 until binding.todoContainer.childCount) {
                val input = binding.todoContainer.getChildAt(index).findViewById<EditText>(R.id.todoInput)
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }
        editor.putInt(KEY_COUNT, values.size)
        values.forEachIndexed { index, value -> editor.putString("todo_$index", value) }
        editor.apply()
    }

    private fun updateTodoCount() {
        binding.todoCount.text = binding.todoContainer.childCount.toString()
        binding.todoEmpty.visibility =
            if (binding.todoContainer.childCount == 0) View.VISIBLE else View.GONE
    }

    companion object {
        private const val TODO_PREFERENCES = "todos"
        private const val KEY_COUNT = "count"
    }
}
