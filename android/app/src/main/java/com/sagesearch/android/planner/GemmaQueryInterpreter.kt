package com.sagesearch.android.planner

import com.sagesearch.android.modelruntime.ModelRepository
import com.sagesearch.android.modelruntime.ModelState
import com.sagesearch.android.modelruntime.ReusableGemmaEngineManager
import com.sagesearch.android.search.DeterministicQueryParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

class GemmaQueryInterpreter(
    private val models: ModelRepository,
    private val engines: ReusableGemmaEngineManager,
    private val validator: PlannerOutputValidator = PlannerOutputValidator(),
    private val preliminaryParser: DeterministicQueryParser = DeterministicQueryParser(),
    private val reconciler: PlannerPlanReconciler = PlannerPlanReconciler(::todayIso),
    private val mapper: ValidatedPlanMapper = ValidatedPlanMapper(),
    private val prompt: QueryPlanPrompt = QueryPlanPrompt(::todayIso),
) : QueryInterpreter {
    override suspend fun interpret(
        accumulatedDetail: String,
        previousPlan: ValidatedPlannerPlan?,
        currentDetail: String,
    ): QueryInterpretation {
        val fallback = preliminaryParser.parse(accumulatedDetail)
        if (models.state.value !is ModelState.Ready) {
            return QueryInterpretation.Rejected(PlannerRejection.ENGINE_UNAVAILABLE, fallback)
        }
        val promptDetail = if (previousPlan == null) accumulatedDetail else currentDetail
        val rawOutput = try {
            engines.generateOneShot(
                prompt.build(promptDetail, previousPlan),
                QueryPlannerGeneration.POLICY,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return QueryInterpretation.Rejected(PlannerRejection.ENGINE_UNAVAILABLE, fallback)
        }
        return when (val validation = validator.validate(rawOutput)) {
            is PlanValidationResult.Valid -> {
                val reconciled = reconciler.reconcile(promptDetail, validation.plan)
                QueryInterpretation.Accepted(
                    validatedPlan = reconciled,
                    searchPlan = mapper.merge(fallback, reconciled),
                    fallbackPlan = fallback,
                )
            }
            is PlanValidationResult.Invalid -> QueryInterpretation.Rejected(validation.reason, fallback)
        }
    }

    companion object {
        private fun todayIso(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
}
