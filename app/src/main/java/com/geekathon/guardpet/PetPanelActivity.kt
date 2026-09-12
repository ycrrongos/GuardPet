package com.geekathon.guardpet

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.geekathon.guardpet.databinding.ActivityPetPanelBinding

class PetPanelActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPetPanelBinding
    private lateinit var settings: PetSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPetPanelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = PetSettings(this)

        window.setDimAmount(0f)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        loadTodos()
        binding.freeWalkSwitch.isChecked = settings.edgeWalkEnabled
        binding.freeWalkSwitch.setOnCheckedChangeListener { _, enabled ->
            settings.edgeWalkEnabled = enabled
            if (PetService.isRunning) {
                startService(
                    Intent(this, PetService::class.java)
                        .setAction(PetService.ACTION_REFRESH_SETTINGS)
                )
            }
        }
        binding.addTodoButton.setOnClickListener { addTodoRow("") }
        binding.focusButton.setOnClickListener {
            if (PetService.isRunning) {
                startService(
                    Intent(this, PetService::class.java)
                        .setAction(PetService.ACTION_OPEN_TIMER)
                )
                finish()
            } else {
                Toast.makeText(this, R.string.start_pet_first, Toast.LENGTH_SHORT).show()
            }
        }
        binding.closePanelButton.setOnClickListener { finish() }
        binding.stopPetButton.setOnClickListener {
            stopService(Intent(this, PetService::class.java))
            finish()
        }
        binding.stopPetButton.isEnabled = PetService.isRunning
        updateTodoCount()
    }

    override fun onStart() {
        super.onStart()
        val metrics = resources.displayMetrics
        val maxHeight = (420 * metrics.density).toInt()
        val minHeight = (280 * metrics.density).toInt()
        val panelHeight = (metrics.heightPixels * 0.42f).toInt().coerceIn(minHeight, maxHeight)
            .coerceAtMost((metrics.heightPixels * 0.9f).toInt())
        val panelWidth = minOf((metrics.widthPixels * 0.92f).toInt(), (520 * metrics.density).toInt())
        window.setLayout(panelWidth, panelHeight)
    }

    override fun onPause() {
        saveTodos()
        super.onPause()
    }

    private fun loadTodos() {
        val preferences = getSharedPreferences(TODO_PREFERENCES, MODE_PRIVATE)
        repeat(preferences.getInt(KEY_COUNT, 0)) { index ->
            addTodoRow(preferences.getString("todo_$index", "").orEmpty())
        }
    }

    private fun addTodoRow(initialText: String) {
        val row = layoutInflater.inflate(R.layout.item_todo, binding.todoContainer, false)
        val input = row.findViewById<EditText>(R.id.todoInput)
        val doneButton = row.findViewById<android.view.View>(R.id.todoDone)
        val deleteButton = row.findViewById<android.view.View>(R.id.todoDelete)
        val actions = row.findViewById<LinearLayout>(R.id.todoActions)
        input.setText(initialText)

        row.setOnClickListener { actions.visibility = android.view.View.VISIBLE }
        input.setOnFocusChangeListener { _, focused -> if (focused) actions.visibility = android.view.View.VISIBLE }

        doneButton.setOnClickListener {
            if (input.text.isNullOrBlank()) {
                Toast.makeText(this, R.string.todo_empty_warning, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            settings.foodCount += 1
            settings.mood += TODO_MOOD_REWARD
            binding.todoContainer.removeView(row)
            saveTodos()
            updateTodoCount()
            Toast.makeText(this, R.string.todo_reward, Toast.LENGTH_SHORT).show()
        }
        deleteButton.setOnClickListener {
            binding.todoContainer.removeView(row)
            saveTodos()
            updateTodoCount()
        }
        binding.todoContainer.addView(row)
        updateTodoCount()
    }

    private fun saveTodos() {
        val editor = getSharedPreferences(TODO_PREFERENCES, MODE_PRIVATE).edit().clear()
        val todoTexts = buildList {
            for (index in 0 until binding.todoContainer.childCount) {
                val input = binding.todoContainer.getChildAt(index)
                    .findViewById<EditText>(R.id.todoInput)
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) add(text)
            }
        }
        editor.putInt(KEY_COUNT, todoTexts.size)
        todoTexts.forEachIndexed { index, text -> editor.putString("todo_$index", text) }
        editor.apply()
    }

    private fun updateTodoCount() {
        binding.todoCount.text = binding.todoContainer.childCount.toString()
        binding.todoEmpty.visibility =
            if (binding.todoContainer.childCount == 0) android.view.View.VISIBLE
            else android.view.View.GONE
    }

    companion object {
        private const val TODO_PREFERENCES = "todos"
        private const val KEY_COUNT = "count"
        private const val TODO_MOOD_REWARD = 8
    }
}
