package com.sagesearch.android.ui.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SetupScreen(
    state: SetupUiState,
    onChooseTree: (String) -> Unit,
    onChooseDocuments: (List<String>) -> Unit,
    onChooseModel: (String) -> Unit,
    onCancelModelImport: () -> Unit,
    onContinue: () -> Unit,
) {
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { onChooseTree(it.toString()) }
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onChooseDocuments(uris.map { it.toString() })
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onChooseModel(it.toString()) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("SageSearch", fontWeight = FontWeight.Bold)
        Text("Search your files privately on this device.")
        Text("SageSearch only searches files you approve. Nothing is uploaded.")

        SetupTaskCard(
            title = "Choose files to search",
            status = state.sourceStatus,
            detail = when {
                state.sourceStatus == SetupTaskStatus.IN_PROGRESS -> "Adding accessible file details..."
                state.needsAccessCount > 0 -> "SageSearch needs access again for ${state.needsAccessCount} source${if (state.needsAccessCount == 1) "" else "s"}."
                state.pendingIndexCount > 0 -> "${state.completedIndexCount} of ${state.completedIndexCount + state.pendingIndexCount} files checkpointed. All ${state.indexedCount} discovered files are already searchable by metadata."
                state.indexedCount > 0 -> "${state.indexedCount} file${if (state.indexedCount == 1) "" else "s"} searchable from ${state.approvedSourceCount} approved source${if (state.approvedSourceCount == 1) "" else "s"}."
                else -> "Choose a folder or individual files. Android may restrict storage roots."
            },
        ) {
            Button(
                onClick = { treePicker.launch(null) },
                enabled = state.sourceStatus != SetupTaskStatus.IN_PROGRESS,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Choose folder")
            }
            OutlinedButton(
                onClick = { documentPicker.launch(arrayOf("*/*")) },
                enabled = state.sourceStatus != SetupTaskStatus.IN_PROGRESS,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Choose individual files")
            }
        }

        SetupTaskCard(
            title = "Prepare AI model",
            status = state.modelStatus,
            detail = state.modelDetail,
        ) {
            state.modelProgressPercent?.let { percent ->
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedButton(
                onClick = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                enabled = state.modelStatus != SetupTaskStatus.IN_PROGRESS,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.modelStatus == SetupTaskStatus.READY) "Replace model" else "Choose .litertlm model")
            }
            if (state.modelStatus == SetupTaskStatus.IN_PROGRESS) {
                OutlinedButton(onClick = onCancelModelImport, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel model setup")
                }
            }
        }

        state.message?.let { Text(it) }
        if (state.canSearch) {
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text("Search indexed files")
            }
        }
    }
}

@Composable
private fun SetupTaskCard(
    title: String,
    status: SetupTaskStatus,
    detail: String,
    action: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(status.label())
            Text(detail)
            action()
        }
    }
}

private fun SetupTaskStatus.label(): String = when (this) {
    SetupTaskStatus.NOT_STARTED -> "Not started"
    SetupTaskStatus.IN_PROGRESS -> "In progress"
    SetupTaskStatus.READY -> "Ready"
    SetupTaskStatus.NEEDS_ATTENTION -> "Needs attention"
}
