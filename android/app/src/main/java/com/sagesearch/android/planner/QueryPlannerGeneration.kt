package com.sagesearch.android.planner

import com.sagesearch.android.modelruntime.GenerationPolicy

object QueryPlannerGeneration {
    val POLICY = GenerationPolicy(
        temperature = 0.0,
        topK = 1,
        topP = 1.0,
        maxOutputTokens = 192,
        seed = 0,
        responseJsonSchema = JSON_SCHEMA,
    )

    const val JSON_SCHEMA = """{"type":"object","additionalProperties":false,"required":["version"],"properties":{"version":{"const":1},"textTerms":{"type":"array","maxItems":12,"items":{"type":"string","minLength":1,"maxLength":80}},"contentKinds":{"type":"array","maxItems":4,"items":{"enum":["receipt","picture","mixed","unknown"]}},"merchant":{"type":"string","minLength":1,"maxLength":120},"amountRangeMinor":{"type":"object","additionalProperties":false,"properties":{"min":{"type":"integer","minimum":0},"max":{"type":"integer","minimum":0}}},"currencyCode":{"type":"string","pattern":"^[A-Z]{3}$"},"transactionDateRange":{"type":"object","additionalProperties":false,"properties":{"start":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"},"end":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}$"}}},"mediaDateRange":{"type":"object","additionalProperties":false,"properties":{"start":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"},"end":{"type":"string","pattern":"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"}}},"labels":{"type":"array","maxItems":12,"items":{"type":"string","minLength":1,"maxLength":80}},"faceFilter":{"enum":["none","any","exactly_one","multiple"]},"albumHint":{"type":"string","minLength":1,"maxLength":160}}}"""
}
