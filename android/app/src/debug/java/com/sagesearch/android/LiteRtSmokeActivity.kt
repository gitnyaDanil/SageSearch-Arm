package com.sagesearch.android

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val LITERT_LM_VERSION = "0.16.0"
private const val CPU_BACKEND = "CPU"

class LiteRtSmokeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LiteRtSmokeScreen()
                }
            }
        }
    }
}

private sealed interface SmokeState {
    data object Idle : SmokeState
    data object Copying : SmokeState
    data class Initializing(val modelBytes: Long, val sha256Prefix: String) : SmokeState
    data class Ready(val result: SmokeResult) : SmokeState
    data class Failed(val stage: String, val errorType: String) : SmokeState
}

private data class ImportedModel(
    val file: File,
    val sizeBytes: Long,
    val sha256: String,
)

private data class SmokeResult(
    val sizeBytes: Long,
    val sha256: String,
    val initializeMillis: Long,
)

@Composable
private fun LiteRtSmokeScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var state: SmokeState by remember { mutableStateOf(SmokeState.Idle) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        scope.launch {
            state = SmokeState.Copying
            try {
                val imported = withContext(Dispatchers.IO) { importForSmokeTest(context, uri) }
                state = SmokeState.Initializing(
                    modelBytes = imported.sizeBytes,
                    sha256Prefix = imported.sha256.take(12),
                )
                val result = withContext(Dispatchers.IO) { initializeAndRecord(context, imported) }
                state = SmokeState.Ready(result)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: SmokeFailure) {
                state = SmokeState.Failed(error.stage, error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName)
            } catch (error: Throwable) {
                state = SmokeState.Failed("unexpected", error.javaClass.simpleName)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Gemma smoke gate", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Debug-only check: select a compatible .litertlm model. SageSearch copies it to " +
                "temporary private storage, initializes LiteRT-LM on CPU, then closes the engine.",
        )

        when (val current = state) {
            SmokeState.Idle -> Text("No model selected.")
            SmokeState.Copying -> Text("Copying model into temporary private storage...")
            is SmokeState.Initializing -> {
                Text("Initializing LiteRT-LM on CPU...")
                Text("Model: ${formatBytes(current.modelBytes)} | SHA-256 ${current.sha256Prefix}...")
            }
            is SmokeState.Ready -> {
                Text("Ready", fontWeight = FontWeight.Bold)
                Text("LiteRT-LM $LITERT_LM_VERSION | $CPU_BACKEND")
                Text("Model size: ${formatBytes(current.result.sizeBytes)}")
                Text("SHA-256: ${current.result.sha256}")
                Text("Initialization: ${current.result.initializeMillis} ms")
                Text("Privacy-safe report saved in app-private storage.")
            }
            is SmokeState.Failed -> {
                Text("Needs attention", fontWeight = FontWeight.Bold)
                Text("${current.stage} failed (${current.errorType}). Basic SageSearch remains unchanged.")
            }
        }

        Button(
            onClick = { picker.launch(arrayOf("*/*")) },
            enabled = state !is SmokeState.Copying && state !is SmokeState.Initializing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state is SmokeState.Idle) "Choose .litertlm model" else "Choose another model")
        }
    }
}

private fun importForSmokeTest(context: Context, uri: Uri): ImportedModel {
    val smokeDir = File(context.cacheDir, "litert-smoke").apply { mkdirs() }
    val partial = File(smokeDir, "model.litertlm.partial")
    val destination = File(smokeDir, "model.litertlm")
    partial.delete()
    destination.delete()

    val digest = MessageDigest.getInstance("SHA-256")
    try {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Selected document cannot be opened")
        input.use { source ->
            FileOutputStream(partial).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                }
                output.fd.sync()
            }
        }
        check(partial.length() > 0L) { "Selected model is empty" }
        check(partial.renameTo(destination)) { "Temporary model could not be finalized" }
    } catch (error: Throwable) {
        partial.delete()
        throw SmokeFailure("model copy", error)
    }

    return ImportedModel(
        file = destination,
        sizeBytes = destination.length(),
        sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
    )
}

private fun initializeAndRecord(context: Context, model: ImportedModel): SmokeResult {
    val elapsed = try {
        val start = SystemClock.elapsedRealtime()
        Engine(
            EngineConfig(
                modelPath = model.file.absolutePath,
                backend = Backend.CPU(),
                cacheDir = File(context.cacheDir, "litert-cache").apply { mkdirs() }.absolutePath,
            ),
        ).use { engine -> engine.initialize() }
        SystemClock.elapsedRealtime() - start
    } catch (error: Throwable) {
        throw SmokeFailure("engine initialization", error)
    }

    val report = JSONObject()
        .put("success", true)
        .put("litertLmVersion", LITERT_LM_VERSION)
        .put("backend", CPU_BACKEND)
        .put("modelBytes", model.sizeBytes)
        .put("modelSha256", model.sha256)
        .put("initializeMillis", elapsed)
        .put("deviceModel", Build.MODEL)
        .put("deviceSdk", Build.VERSION.SDK_INT)
        .put("recordedAtMillis", System.currentTimeMillis())
    File(context.filesDir, "litert-smoke-report.json").writeText(report.toString(2))

    return SmokeResult(
        sizeBytes = model.sizeBytes,
        sha256 = model.sha256,
        initializeMillis = elapsed,
    )
}

private class SmokeFailure(
    val stage: String,
    cause: Throwable,
) : RuntimeException(cause)

private fun formatBytes(bytes: Long): String {
    val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) "%.2f GiB".format(gib) else "%.1f MiB".format(bytes / (1024.0 * 1024.0))
}
