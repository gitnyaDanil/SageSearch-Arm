package com.sagesearch.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sagesearch.android.AppContainer
import com.sagesearch.android.ui.search.SearchScreen
import com.sagesearch.android.ui.search.SearchViewModel
import com.sagesearch.android.ui.setup.SetupScreen
import com.sagesearch.android.ui.setup.SetupTaskStatus
import com.sagesearch.android.ui.setup.SetupViewModel

private enum class Destination { SETUP, SEARCH }

@Composable
fun SageSearchApp(container: AppContainer) {
    val setupViewModel: SetupViewModel = viewModel(
        factory = SetupViewModel.Factory(
            images = container.prototypeImageRepository,
            models = container.modelRepository,
            sourceAccess = container.sourceAccessRepository,
        ),
    )
    val searchViewModel: SearchViewModel = viewModel(
        factory = SearchViewModel.Factory(
            container.searchRepository,
            container.originalFileOpener,
            container.queryInterpreter,
            container.refinedSearchExecutor,
            container.searchSessionStore,
        ),
    )
    val setupState by setupViewModel.state.collectAsStateWithLifecycle()
    val searchState by searchViewModel.state.collectAsStateWithLifecycle()
    var destinationName by rememberSaveable { mutableStateOf<String?>(null) }
    val destination = remember(destinationName) { destinationName?.let(Destination::valueOf) }

    LaunchedEffect(setupState.isLoading) {
        if (!setupState.isLoading && destinationName == null) {
            destinationName = if (setupState.canSearch) Destination.SEARCH.name else Destination.SETUP.name
        }
    }

    when (destination) {
        Destination.SEARCH -> SearchScreen(
            state = searchState,
            indexedCount = setupState.indexedCount,
            modelReady = setupState.modelStatus == SetupTaskStatus.READY,
            onQueryChanged = searchViewModel::onQueryChanged,
            onSearch = searchViewModel::search,
            onNewSearch = searchViewModel::newSearch,
            onShowMore = searchViewModel::showMore,
            onRetryRefinement = searchViewModel::retryRefinement,
            onUndoNewSearch = searchViewModel::undoNewSearch,
            onResultInteractionChanged = searchViewModel::onResultInteractionChanged,
            onOpenFile = searchViewModel::openFile,
            onRefreshSources = setupViewModel::refresh,
            thumbnailLoader = container.thumbnailLoader,
            onSetup = { destinationName = Destination.SETUP.name },
        )
        Destination.SETUP, null -> SetupScreen(
            state = setupState,
            onChooseTree = setupViewModel::approveTree,
            onChooseDocuments = setupViewModel::approveDocuments,
            onChooseModel = setupViewModel::importModel,
            onCancelModelImport = setupViewModel::cancelModelImport,
            onContinue = { destinationName = Destination.SEARCH.name },
        )
    }
}
