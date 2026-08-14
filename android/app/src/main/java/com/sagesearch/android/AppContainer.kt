package com.sagesearch.android

import android.content.Context
import androidx.work.WorkManager
import com.sagesearch.android.data.db.SageSearchDatabase
import com.sagesearch.android.data.repository.DefaultPrototypeImageRepository
import com.sagesearch.android.data.repository.DefaultSourceAccessRepository
import com.sagesearch.android.data.repository.PrototypeImageRepository
import com.sagesearch.android.data.repository.SourceAccessRepository
import com.sagesearch.android.data.storage.AndroidDocumentThumbnailLoader
import com.sagesearch.android.data.storage.AndroidFileLaunchGateway
import com.sagesearch.android.data.storage.SafeOriginalFileOpener
import com.sagesearch.android.index.DocumentIndexWorkerRunner
import com.sagesearch.android.index.ExtractionPipeline
import com.sagesearch.android.index.HeavyWorkCoordinator
import com.sagesearch.android.index.ImageOcrExtractor
import com.sagesearch.android.index.IndexCoordinator
import com.sagesearch.android.index.MlKitOcrTextRecognizer
import com.sagesearch.android.index.PdfOcrExtractor
import com.sagesearch.android.index.RoomIndexDocumentRepository
import com.sagesearch.android.modelruntime.AndroidModelMetadataStore
import com.sagesearch.android.modelruntime.AndroidModelSourceResolver
import com.sagesearch.android.modelruntime.DefaultModelRepository
import com.sagesearch.android.modelruntime.LiteRtGemmaEngineFactory
import com.sagesearch.android.modelruntime.ModelRepository
import com.sagesearch.android.modelruntime.ReusableGemmaEngineManager
import com.sagesearch.android.modelruntime.privateModelFileStore
import com.sagesearch.android.planner.GemmaQueryInterpreter
import com.sagesearch.android.planner.QueryInterpreter
import com.sagesearch.android.planner.SafeRefinedSearchExecutor
import com.sagesearch.android.search.DefaultSearchRepository
import com.sagesearch.android.search.SearchRepository
import com.sagesearch.android.search.session.AndroidSearchSessionStore
import com.sagesearch.android.search.session.SearchSessionStore
import java.io.File

class AppContainer(context: Context) {
    val database: SageSearchDatabase = SageSearchDatabase.get(context)
    val heavyWorkCoordinator = HeavyWorkCoordinator()
    val indexCoordinator = IndexCoordinator(WorkManager.getInstance(context.applicationContext))
    private val extractionRecognizer = MlKitOcrTextRecognizer()
    val prototypeImageRepository: PrototypeImageRepository = DefaultPrototypeImageRepository(
        context = context.applicationContext,
        store = database.legacyIndexedImageStore(),
    )
    private val defaultSourceAccessRepository = DefaultSourceAccessRepository(
        resolver = context.contentResolver,
        database = database,
        indexScheduler = indexCoordinator,
    )
    val sourceAccessRepository: SourceAccessRepository = defaultSourceAccessRepository
    val searchRepository: SearchRepository = DefaultSearchRepository(database)
    val searchSessionStore: SearchSessionStore = AndroidSearchSessionStore(context.applicationContext)
    val originalFileOpener = SafeOriginalFileOpener(AndroidFileLaunchGateway(context.applicationContext))
    val thumbnailLoader = AndroidDocumentThumbnailLoader(context.contentResolver)
    val documentIndexWorkerRunner = DocumentIndexWorkerRunner(
        sources = defaultSourceAccessRepository,
        documents = RoomIndexDocumentRepository(database),
        processor = ExtractionPipeline(
            images = ImageOcrExtractor(context.contentResolver, extractionRecognizer),
            pdfs = PdfOcrExtractor(context.contentResolver, extractionRecognizer),
        ),
        heavyWork = heavyWorkCoordinator,
    )
    val gemmaEngineManager = ReusableGemmaEngineManager(
        factory = LiteRtGemmaEngineFactory(File(context.noBackupFilesDir, "litert-cache")),
        heavyWork = heavyWorkCoordinator,
    )
    val modelRepository: ModelRepository = DefaultModelRepository(
        sourceResolver = AndroidModelSourceResolver(context.contentResolver),
        files = privateModelFileStore(context.applicationContext),
        metadataStore = AndroidModelMetadataStore(context.applicationContext),
        engines = gemmaEngineManager,
    )
    val queryInterpreter: QueryInterpreter = GemmaQueryInterpreter(
        models = modelRepository,
        engines = gemmaEngineManager,
    )
    val refinedSearchExecutor = SafeRefinedSearchExecutor(searchRepository)
}
