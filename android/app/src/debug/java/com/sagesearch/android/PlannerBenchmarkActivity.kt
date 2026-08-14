package com.sagesearch.android

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.sagesearch.android.modelruntime.DefaultModelRepository
import com.sagesearch.android.modelruntime.GenerationPolicy
import com.sagesearch.android.modelruntime.ModelState
import com.sagesearch.android.planner.PlanValidationResult
import com.sagesearch.android.planner.PlannerOutputValidator
import com.sagesearch.android.planner.PlannerPlanJson
import com.sagesearch.android.planner.PlannerPlanReconciler
import com.sagesearch.android.planner.QueryPlanPrompt
import com.sagesearch.android.planner.QueryPlannerGeneration
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Debug-only synthetic matrix runner. It receives no document or user data. */
class PlannerBenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as SageSearchApplication).container
        val configuration = BenchmarkConfiguration.fromSlug(
            intent.getStringExtra(EXTRA_CONFIGURATION),
        )
        setContent {
            var status by remember { mutableStateOf("Waiting for the private model...") }
            LaunchedEffect(configuration) {
                status = runCatching {
                    runBenchmark(container, configuration) { status = it }
                }.fold(
                    onSuccess = { "Complete | ${configuration.slug} | private reports ready" },
                    onFailure = { "Failed | ${configuration.slug} | ${it.javaClass.simpleName}" },
                )
            }
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("Gemma planner benchmark")
                    Text(configuration.slug)
                    Text(status)
                    Text("Debug-only synthetic queries; no indexed file data is provided to Gemma.")
                }
            }
        }
    }

    private suspend fun runBenchmark(
        container: AppContainer,
        configuration: BenchmarkConfiguration,
        updateStatus: (String) -> Unit,
    ) {
        val modelState = container.modelRepository.state.first {
            it !is ModelState.Importing && it !is ModelState.Initializing
        }
        check(modelState is ModelState.Ready) { "No initialized production model." }
        val modelFile = File(noBackupFilesDir, "models/${modelState.metadata.fileName}")
        check(modelFile.isFile && modelFile.length() == modelState.metadata.sizeBytes) {
            "The private model copy is missing."
        }

        val outputFile = File(filesDir, "task11-${configuration.slug}-outputs.jsonl")
        val metadataFile = File(filesDir, "task11-${configuration.slug}-metadata.json")
        val completionFile = File(filesDir, "task11-${configuration.slug}-complete.json")
        outputFile.delete()
        metadataFile.delete()
        completionFile.delete()

        val cases = withContext(Dispatchers.IO) { loadCases() }
        val validator = PlannerOutputValidator()
        val reconciler = PlannerPlanReconciler(referenceDate = { REFERENCE_DATE })
        val promptBuilder = QueryPlanPrompt { REFERENCE_DATE }
        val startSnapshot = deviceSnapshot()
        val startedAtMillis = System.currentTimeMillis()
        var engineInitializationMillis: Long? = null
        var accepted = 0
        var failure: Throwable? = null

        container.gemmaEngineManager.shutdown(clearPrivateCache = false)
        try {
            withContext(Dispatchers.IO) {
                BenchmarkEngine(
                    modelFile = modelFile,
                    cacheDirectory = File(cacheDir, "task11-${configuration.slug}"),
                    backend = configuration.backend,
                ).use { engine ->
                    val initializeStarted = SystemClock.elapsedRealtime()
                    engine.initialize()
                    engineInitializationMillis = SystemClock.elapsedRealtime() - initializeStarted
                    outputFile.bufferedWriter().use { writer ->
                        cases.forEachIndexed { index, case ->
                            updateStatus("Running ${index + 1}/${cases.size} | accepted $accepted")
                            val record = JSONObject().put("id", case.id)
                            val started = SystemClock.elapsedRealtime()
                            runCatching {
                                val prompt = configuration.buildPrompt(this@PlannerBenchmarkActivity, promptBuilder, case.query)
                                engine.generate(prompt, configuration.policy)
                            }.onSuccess { output ->
                                val validation = validator.validate(output)
                                if (validation is PlanValidationResult.Valid) accepted += 1
                                val executedOutput = if (
                                    configuration.reconcile && validation is PlanValidationResult.Valid
                                ) {
                                    PlannerPlanJson.encode(reconciler.reconcile(case.query, validation.plan))
                                } else {
                                    output
                                }
                                record
                                    .put("raw_output", output)
                                    .put("output", executedOutput)
                                    .put("latency_ms", SystemClock.elapsedRealtime() - started)
                                    .put("peak_pss_mb", Debug.getPss().toDouble() / 1024.0)
                                    .put("accepted", validation is PlanValidationResult.Valid)
                                    .put(
                                        "rejection",
                                        (validation as? PlanValidationResult.Invalid)?.reason?.name,
                                    )
                            }.onFailure { error ->
                                record
                                    .put("latency_ms", SystemClock.elapsedRealtime() - started)
                                    .put("peak_pss_mb", Debug.getPss().toDouble() / 1024.0)
                                    .put("accepted", false)
                                    .put("error_type", error.javaClass.simpleName)
                            }
                            writer.appendLine(record.toString())
                            writer.flush()
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val endSnapshot = deviceSnapshot()
            val metadata = JSONObject()
                .put("configuration", configuration.slug)
                .put("backend", configuration.backend.label)
                .put("prompt", configuration.promptKind.label)
                .put("response_constraint", configuration.policy.responseJsonSchema != null)
                .put("deterministic_reconciliation", configuration.reconcile)
                .put("reference_date", REFERENCE_DATE)
                .put("case_count", cases.size)
                .put("accepted_count", accepted)
                .put("started_at_millis", startedAtMillis)
                .put("completed_at_millis", System.currentTimeMillis())
                .put("engine_initialization_ms", engineInitializationMillis)
                .put("model_size_bytes", modelState.metadata.sizeBytes)
                .put("model_sha256", modelState.metadata.sha256)
                .put("runtime", "LiteRT-LM ${DefaultModelRepository.LITERT_LM_VERSION}")
                .put("generation", generationMetadata(configuration.policy))
                .put("device_start", startSnapshot)
                .put("device_end", endSnapshot)
                .put("failure_type", failure?.javaClass?.simpleName)
                .put(
                    "limitations",
                    JSONArray()
                        .put("The synchronous LiteRT-LM API does not expose TTFT, prefill, decode, or energy metrics here.")
                        .put("The 20 public synthetic cases are an engineering smoke set, not broad user-query accuracy."),
                )
            metadataFile.writeText(metadata.toString(2))
            completionFile.writeText(
                JSONObject()
                    .put("configuration", configuration.slug)
                    .put("success", failure == null)
                    .put("failure_type", failure?.javaClass?.simpleName)
                    .toString(),
            )
            container.gemmaEngineManager.activate(modelFile)
        }
    }

    private fun loadCases(): List<PlannerCase> = assets.open(CASES_ASSET).bufferedReader().useLines { lines ->
        lines.filter(String::isNotBlank).map { line ->
            val json = JSONObject(line)
            PlannerCase(json.getString("id"), json.getString("query"))
        }.toList()
    }

    private fun deviceSnapshot(): JSONObject {
        val batteryManager = getSystemService(BatteryManager::class.java)
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val power = getSystemService(PowerManager::class.java)
        return JSONObject()
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("android_release", Build.VERSION.RELEASE)
            .put("security_patch", Build.VERSION.SECURITY_PATCH)
            .put("build_fingerprint", Build.FINGERPRINT)
            .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("battery_percent", batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("battery_temperature_celsius", battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)?.div(10.0))
            .put("thermal_status", if (Build.VERSION.SDK_INT >= 29) power.currentThermalStatus else null)
            .put("process_pss_mb", Debug.getPss().toDouble() / 1024.0)
    }

    private fun generationMetadata(policy: GenerationPolicy): JSONObject = JSONObject()
        .put("temperature", policy.temperature)
        .put("top_k", policy.topK)
        .put("top_p", policy.topP)
        .put("seed", policy.seed)
        .put("max_output_tokens", policy.maxOutputTokens)

    private data class PlannerCase(val id: String, val query: String)

    companion object {
        private const val CASES_ASSET = "task9-planner-cases.jsonl"
        private const val REFERENCE_DATE = "2026-08-14"
        const val EXTRA_CONFIGURATION = "configuration"
    }
}

private enum class BenchmarkBackend(val label: String) {
    CPU("CPU"),
    GPU("GPU");

    fun create(): Backend = when (this) {
        CPU -> Backend.CPU()
        GPU -> Backend.GPU()
    }
}

private enum class PromptKind(val label: String, val assetName: String?) {
    BASELINE("baseline-v1", "task11-baseline-prompt.txt"),
    OPTIMIZED("optimized-v1", "task11-optimized-prompt.txt"),
    PRODUCTION("sagesearch-query-plan-v1-opt2", null),
}

private enum class BenchmarkConfiguration(
    val slug: String,
    val backend: BenchmarkBackend,
    val promptKind: PromptKind,
    val policy: GenerationPolicy,
    val reconcile: Boolean,
) {
    GPU_BASELINE_UNCONSTRAINED(
        slug = "gpu-baseline-unconstrained",
        backend = BenchmarkBackend.GPU,
        promptKind = PromptKind.BASELINE,
        policy = QueryPlannerGeneration.POLICY.copy(responseJsonSchema = null),
        reconcile = false,
    ),
    CPU_BASELINE_UNCONSTRAINED(
        slug = "cpu-baseline-unconstrained",
        backend = BenchmarkBackend.CPU,
        promptKind = PromptKind.BASELINE,
        policy = QueryPlannerGeneration.POLICY.copy(responseJsonSchema = null),
        reconcile = false,
    ),
    CPU_OPTIMIZED_UNCONSTRAINED(
        slug = "cpu-optimized-unconstrained",
        backend = BenchmarkBackend.CPU,
        promptKind = PromptKind.OPTIMIZED,
        policy = QueryPlannerGeneration.POLICY.copy(responseJsonSchema = null),
        reconcile = false,
    ),
    CPU_OPTIMIZED_CONSTRAINED_HYBRID(
        slug = "cpu-optimized-constrained-hybrid",
        backend = BenchmarkBackend.CPU,
        promptKind = PromptKind.PRODUCTION,
        policy = QueryPlannerGeneration.POLICY,
        reconcile = true,
    );

    fun buildPrompt(
        activity: PlannerBenchmarkActivity,
        productionPrompt: QueryPlanPrompt,
        query: String,
    ): String = promptKind.assetName?.let { assetName ->
        activity.assets.open(assetName).bufferedReader().use { it.readText() }.replace("{{QUERY}}", query)
    } ?: productionPrompt.build(query, null)

    companion object {
        fun fromSlug(slug: String?): BenchmarkConfiguration =
            entries.firstOrNull { it.slug == slug } ?: CPU_OPTIMIZED_CONSTRAINED_HYBRID
    }
}

private class BenchmarkEngine(
    modelFile: File,
    cacheDirectory: File,
    backend: BenchmarkBackend,
) : AutoCloseable {
    private val engine = Engine(
        EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = backend.create(),
            cacheDir = cacheDirectory.apply(File::mkdirs).absolutePath,
        ),
    )

    suspend fun initialize() = engine.initialize()

    suspend fun generate(prompt: String, policy: GenerationPolicy): String {
        val config = ConversationConfig(
            systemInstruction = Contents.of(
                "Return only the compact search-plan data requested. Never invent file contents.",
            ),
            samplerConfig = SamplerConfig(
                topK = policy.topK,
                topP = policy.topP,
                temperature = policy.temperature,
                seed = policy.seed,
            ),
            maxOutputToken = policy.maxOutputTokens,
            enableResponseFormat = policy.responseJsonSchema != null,
        )
        return engine.createConversation(config).use { conversation ->
            val response = conversation.sendMessage(
                text = prompt,
                responseFormat = policy.responseJsonSchema?.let(ResponseFormat::json),
            )
            response.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString(separator = "") { it.text }
                .trim()
                .take(8_192)
        }
    }

    override fun close() = engine.close()
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(encodeToByteArray())
    .joinToString("") { "%02x".format(it) }
