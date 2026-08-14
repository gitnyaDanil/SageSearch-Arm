package com.sagesearch.android

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sagesearch.android.modelruntime.ModelState
import java.security.MessageDigest
import kotlinx.coroutines.flow.first

/** Debug-only lifecycle probe. It records no generated text or user content. */
class LiteRtConversationProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as SageSearchApplication).container
        setContent {
            var result by remember { mutableStateOf("Waiting for the private model...") }
            LaunchedEffect(Unit) {
                val modelState = container.modelRepository.state.first {
                    it !is ModelState.Importing && it !is ModelState.Initializing
                }
                if (modelState !is ModelState.Ready) {
                    result = "Needs attention: no initialized production model."
                    return@LaunchedEffect
                }
                result = "Running one-shot conversation..."
                val start = SystemClock.elapsedRealtime()
                result = runCatching {
                    val output = container.gemmaEngineManager.generateOneShot(
                        "Return exactly this JSON object and nothing else: {\"status\":\"ok\"}",
                    )
                    val outputHash = MessageDigest.getInstance("SHA-256")
                        .digest(output.encodeToByteArray())
                        .joinToString("") { "%02x".format(it) }
                        .take(12)
                    "Conversation passed | ${SystemClock.elapsedRealtime() - start} ms | " +
                        "${output.length} chars | SHA-256 $outputHash..."
                }.getOrElse { error -> "Conversation failed (${error.javaClass.simpleName})." }
            }
            MaterialTheme {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Gemma one-shot probe")
                    Text(result)
                    Text("Debug-only: generated text is neither displayed nor persisted.")
                }
            }
        }
    }
}
