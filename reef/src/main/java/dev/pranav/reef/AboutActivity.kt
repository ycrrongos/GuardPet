package dev.pranav.reef

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.pranav.reef.screens.AboutScreen
import dev.pranav.reef.ui.ReefTheme

class AboutActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ReefTheme {
                AboutScreen(
                    onBackPressed = { finish() }
                )
            }
        }
    }
}
