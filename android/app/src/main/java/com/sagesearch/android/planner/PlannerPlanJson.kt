package com.sagesearch.android.planner

import com.google.gson.JsonArray
import com.google.gson.JsonObject

object PlannerPlanJson {
    fun encode(plan: ValidatedPlannerPlan): String = JsonObject().apply {
        addProperty("version", plan.version)
        addStrings("textTerms", plan.textTerms)
        addStrings("contentKinds", plan.contentKinds)
        plan.merchant?.let { addProperty("merchant", it) }
        plan.amountRangeMinor?.let { bounds ->
            add("amountRangeMinor", JsonObject().apply {
                bounds.minimum?.let { addProperty("min", it) }
                bounds.maximum?.let { addProperty("max", it) }
            })
        }
        plan.currencyCode?.let { addProperty("currencyCode", it) }
        plan.transactionDateRange?.let { add("transactionDateRange", temporal(it)) }
        plan.mediaDateRange?.let { add("mediaDateRange", temporal(it)) }
        addStrings("labels", plan.labels)
        plan.faceFilter?.let { addProperty("faceFilter", it) }
        plan.albumHint?.let { addProperty("albumHint", it) }
    }.toString()

    private fun JsonObject.addStrings(name: String, values: List<String>) {
        if (values.isEmpty()) return
        add(name, JsonArray().apply { values.forEach(::add) })
    }

    private fun temporal(bounds: StringBounds): JsonObject = JsonObject().apply {
        bounds.start?.let { addProperty("start", it) }
        bounds.end?.let { addProperty("end", it) }
    }
}
