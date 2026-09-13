package com.geekathon.guardpet

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.geekathon.guardpet.ui.ScheduleMd3Screen
import dev.pranav.reef.ui.ReefTheme

/**
 * 多日日程独立页：MD3 Compose，与底栏「日程」共用 [ScheduleMd3Screen]。
 */
class ScheduleActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReefTheme {
                ScheduleMd3Screen(
                    showCloseButton = true,
                    onClose = { finish() }
                )
            }
        }
    }
}
