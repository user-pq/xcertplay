package com.shilapi.xcertplay

/** Pure scoring logic so the Xingrui/ECARX display heuristic can be tested off-device. */
internal object XingruiDisplayScorer {
    data class Candidate(
        val id: Int,
        val name: String,
        val width: Int,
        val height: Int,
        val presentation: Boolean,
        val valid: Boolean,
        val isDefault: Boolean,
    )

    private val nameHints = listOf(
        "dashboard", "cluster", "instrument", "navi", "navigation",
        "projection", "ecarx", "geely", "xsf", "dim", "map",
    )

    fun score(candidate: Candidate): Int {
        if (!candidate.valid || candidate.width <= 0 || candidate.height <= 0) return Int.MIN_VALUE
        if (candidate.isDefault) return -100

        var score = 20
        if (candidate.presentation) score += 30

        val lowerName = candidate.name.lowercase()
        if (nameHints.any(lowerName::contains)) score += 35

        val aspect = candidate.width.toDouble() / candidate.height
        if (aspect in 2.05..3.60) score += 30
        else if (aspect in 1.55..2.05) score += 5

        if (candidate.width in 1000..3000) score += 8
        if (candidate.height in 350..900) score += 8
        if (candidate.width == 1920 && candidate.height == 720) score += 18
        if (candidate.width == 1280 && candidate.height == 480) score += 14
        return score
    }

    fun isLikelyCluster(candidate: Candidate): Boolean {
        val aspect = if (candidate.height > 0) candidate.width.toDouble() / candidate.height else 0.0
        val hinted = nameHints.any(candidate.name.lowercase()::contains)
        return score(candidate) >= 70 && (hinted || aspect >= 2.05)
    }
}
