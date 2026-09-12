package dev.pranav.reef

import android.content.ClipData
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import dev.pranav.reef.ui.ReefTheme
import kotlinx.coroutines.runBlocking

class DebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val stack = intent.getStringExtra("error") ?: return

        setContent {
            ReefTheme {
                val clipboard = LocalClipboard.current

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(Modifier.padding(innerPadding)) {
                        Text(text = stack)

                        Spacer(modifier = Modifier.height(16.dp))

                        Row {
                            Button(
                                onClick = {
                                    runBlocking {
                                        clipboard.setClipEntry(
                                            ClipEntry(
                                                ClipData.newPlainText(
                                                    "Error Stack Trace",
                                                    stack
                                                )
                                            )
                                        )
                                    }
                                },
                                shapes = ButtonDefaults.shapes()
                            ) {
                                Text(text = "Copy Error")
                            }
                        }
                    }
                }
            }
        }
    }
}
