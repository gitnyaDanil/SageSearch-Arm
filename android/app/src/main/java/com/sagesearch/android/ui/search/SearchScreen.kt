package com.sagesearch.android.ui.search

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sagesearch.android.data.storage.DocumentThumbnailLoader
import com.sagesearch.android.model.SearchPhase
import com.sagesearch.android.model.SearchResult
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun SearchScreen(
    state: SearchUiState,
    indexedCount: Int,
    modelReady: Boolean,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onNewSearch: () -> Unit,
    onShowMore: () -> Unit,
    onRetryRefinement: () -> Unit,
    onUndoNewSearch: () -> Unit,
    onResultInteractionChanged: (Boolean) -> Unit,
    onOpenFile: (SearchResult) -> Unit,
    onRefreshSources: () -> Unit,
    onSetup: () -> Unit,
    thumbnailLoader: DocumentThumbnailLoader,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    LaunchedEffect(state.focusRequestId) {
        if (state.focusRequestId > 0L) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect(onResultInteractionChanged)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SageSearch", fontWeight = FontWeight.Bold)
            if (state.hasSearched || state.query.isNotEmpty()) {
                OutlinedButton(onClick = onNewSearch) { Text("New search") }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onRefreshSources) { Text("Refresh files") }
            OutlinedButton(onClick = onSetup) { Text("Setup") }
        }
        Text(
            "$indexedCount indexed file${if (indexedCount == 1) "" else "s"} | " +
                phaseLabel(state, modelReady),
        )
        if (state.turns.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Search details", fontWeight = FontWeight.Bold)
                state.turns.forEachIndexed { index, turn ->
                    Text("${index + 1}. ${turn.text}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChanged,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            label = { Text(state.inputLabel) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                onSearch()
                keyboard?.hide()
            }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onSearch,
                enabled = state.query.isNotBlank() && !state.isSearching && !state.isRestoring,
            ) {
                Text(if (state.hasSearched) "Add detail" else "Search")
            }
            if (state.phase == SearchPhase.REFINEMENT_UNAVAILABLE && modelReady) {
                OutlinedButton(onClick = onRetryRefinement) { Text("Try AI again") }
            }
        }
        state.message?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message, modifier = Modifier.weight(1f))
                if (state.canUndo) OutlinedButton(onClick = onUndoNewSearch) { Text("Undo") }
            }
        }
        if (state.hasSearched && state.results.isEmpty() && !state.isSearching && !state.isRestoring) {
            Text("No likely files found")
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.results, key = SearchResult::documentId) { result ->
                SearchResultCard(result, thumbnailLoader, onOpenFile)
            }
            if (state.canShowMore) {
                item {
                    OutlinedButton(onClick = onShowMore, modifier = Modifier.fillMaxWidth()) {
                        Text("Show more")
                    }
                }
            }
        }
    }
}

private fun phaseLabel(state: SearchUiState, modelReady: Boolean): String = when (state.phase) {
    SearchPhase.READY -> if (modelReady) "On-device refinement ready" else "Basic search ready"
    SearchPhase.SEARCHING_PRELIMINARY -> "Searching indexed files..."
    SearchPhase.REFINING_ON_DEVICE -> "Preliminary matches shown | Refining on device..."
    SearchPhase.REFINED -> if (state.hasPendingReorder) "Refinement ready" else "Refined on device"
    SearchPhase.REFINEMENT_UNAVAILABLE -> "Preliminary matches"
}

@Composable
private fun SearchResultCard(
    result: SearchResult,
    thumbnailLoader: DocumentThumbnailLoader,
    onOpenFile: (SearchResult) -> Unit,
) {
    val thumbnail by produceState<Bitmap?>(initialValue = null, result.contentUri) {
        value = thumbnailLoader.load(result.contentUri)
    }
    // Compose graphics layers may retain the painter beyond item disposal. The
    // UI must not recycle this bitmap while a queued frame can still draw it.
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ResultThumbnail(thumbnail, result.mimeType)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    result.displayName,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("Matched result")
                result.evidence.take(3).forEach { evidence ->
                    Text("${evidence.label}: ${evidence.value}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { onOpenFile(result) }) { Text("Open file") }
            }
        }
    }
}

@Composable
private fun ResultThumbnail(bitmap: Bitmap?, mimeType: String) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null && !bitmap.isRecycled) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "File thumbnail",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                when {
                    mimeType.startsWith("image/") -> "IMG"
                    mimeType == "application/pdf" -> "PDF"
                    else -> "FILE"
                },
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
