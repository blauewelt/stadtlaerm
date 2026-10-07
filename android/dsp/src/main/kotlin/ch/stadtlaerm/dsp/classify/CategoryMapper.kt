package ch.stadtlaerm.dsp.classify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A project category: a named group of raw classifier labels. */
data class CategoryDef(val id: String, val nameDe: String, val members: List<String>)

data class LabelScore(val label: String, val score: Float)

data class CategoryDecision(
    /** Category id, or [CategoryMapper.UNCLASSIFIED]. */
    val dominant: String,
    val dominantScore: Float,
    /** Score per category id (same order as [CategoryMapper.categories]). */
    val categoryScores: Map<String, Float>,
)

/**
 * Maps raw AudioSet scores to project categories.
 *
 * - category score = max of its member label scores
 * - dominant:
 *   (a) if the top-1 AudioSet label belongs to a category and its score ≥ [top1Threshold], that
 *       category (quiet, distant sources often score only 0.1–0.3 even when "Vehicle" is clearly
 *       the best label);
 *   (b) otherwise the category with the highest score if that score ≥ [threshold];
 *   (c) otherwise "unclassified".
 * - ties: categories earlier in the list win (list order = priority; loud_vehicle comes first),
 *   also between labels sharing the top-1 score in (a). With [tieMargin] > 0, an earlier category
 *   also wins in (b) if it is within the margin of a later one.
 *
 * Every member name must exist exactly in the label list, otherwise construction fails.
 */
class CategoryMapper(
    labels: List<String>,
    val categories: List<CategoryDef>,
    /** Category threshold for rule (b) (`category_threshold` in the JSON). */
    val threshold: Float = DEFAULT_CATEGORY_THRESHOLD,
    val tieMargin: Float = 0.0f,
    /** Top-1 label threshold for rule (a) (`top1_threshold` in the JSON). */
    val top1Threshold: Float = DEFAULT_TOP1_THRESHOLD,
    val fallbackNameDe: String = "Unklassifiziert",
) {
    companion object {
        const val UNCLASSIFIED = "unclassified"
        const val DEFAULT_CATEGORY_THRESHOLD = 0.2f
        const val DEFAULT_TOP1_THRESHOLD = 0.1f

        fun fromJson(json: String, labels: List<String>): CategoryMapper {
            val root = Json.parseToJsonElement(json).jsonObject
            val cats = root["categories"]!!.jsonArray.map { el ->
                val o: JsonObject = el.jsonObject
                CategoryDef(
                    id = o["id"]!!.jsonPrimitive.content,
                    nameDe = o["name_de"]!!.jsonPrimitive.content,
                    members = o["audioset"]!!.jsonArray.map { it.jsonPrimitive.content },
                )
            }
            val fallback = root["fallback"]?.jsonObject
            return CategoryMapper(
                labels = labels,
                categories = cats,
                // "threshold" is the pre-0.3.0 name of category_threshold.
                threshold = ((root["category_threshold"] ?: root["threshold"])?.jsonPrimitive?.doubleOrNull
                    ?: DEFAULT_CATEGORY_THRESHOLD.toDouble()).toFloat(),
                tieMargin = (root["tie_margin"]?.jsonPrimitive?.doubleOrNull ?: 0.0).toFloat(),
                top1Threshold = (root["top1_threshold"]?.jsonPrimitive?.doubleOrNull ?: DEFAULT_TOP1_THRESHOLD.toDouble()).toFloat(),
                fallbackNameDe = fallback?.get("name_de")?.jsonPrimitive?.content ?: "Unklassifiziert",
            )
        }

        /** Parses a plain label file (one display name per line). */
        fun parseLabels(text: String): List<String> =
            text.split('\n').map { it.trimEnd('\r') }.let { lines ->
                if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
            }
    }

    val labels: List<String> = labels.toList()
    private val labelIndex: Map<String, Int> = labels.withIndex().associate { it.value to it.index }
    private val memberIdx: Array<IntArray>
    /** Category index of each label, −1 if unmapped. */
    private val labelCategory: IntArray

    init {
        val unknown = categories.flatMap { c -> c.members.filter { it !in labelIndex }.map { "${c.id}: '$it'" } }
        require(unknown.isEmpty()) { "Unknown AudioSet label names in category mapping: $unknown" }
        val ids = categories.map { it.id }
        require(ids.toSet().size == ids.size) { "Duplicate category ids: $ids" }
        require(UNCLASSIFIED !in ids)
        memberIdx = Array(categories.size) { c -> categories[c].members.map { labelIndex.getValue(it) }.toIntArray() }
        labelCategory = IntArray(labels.size) { -1 }
        // A label listed in several categories belongs to the earliest (highest priority).
        for (c in categories.indices.reversed()) for (i in memberIdx[c]) labelCategory[i] = c
    }


    val categoryIds: List<String> get() = categories.map { it.id }

    /** All bucket ids used for time shares: the categories plus "unclassified". */
    val bucketIds: List<String> get() = categoryIds + UNCLASSIFIED

    fun nameDe(id: String): String = categories.firstOrNull { it.id == id }?.nameDe ?: fallbackNameDe

    fun categoryScores(scores: FloatArray): FloatArray {
        require(scores.size == labels.size) { "expected ${labels.size} scores, got ${scores.size}" }
        return FloatArray(categories.size) { c ->
            var m = 0f
            for (i in memberIdx[c]) if (scores[i] > m) m = scores[i]
            m
        }
    }

    fun decide(scores: FloatArray): CategoryDecision {
        val cs = categoryScores(scores)
        val map = LinkedHashMap<String, Float>()
        categories.forEachIndexed { i, c -> map[c.id] = cs[i] }
        // (a) The top-1 label decides if it is mapped and scores ≥ top1Threshold.
        var top = Float.NEGATIVE_INFINITY
        for (s in scores) if (s > top) top = s
        if (top >= top1Threshold) {
            var cat = -1
            for (i in scores.indices) {
                if (scores[i] == top && labelCategory[i] >= 0 && (cat < 0 || labelCategory[i] < cat)) cat = labelCategory[i]
            }
            if (cat >= 0) return CategoryDecision(categories[cat].id, top, map)
        }
        // (b) Otherwise the best category if it reaches the category threshold.
        var best = -1
        for (c in cs.indices) {
            if (best < 0) { best = c; continue }
            // Earlier (higher-priority) category keeps the lead on ties / within the margin.
            if (cs[c] > cs[best] + tieMargin) best = c
        }
        return if (best >= 0 && cs[best] >= threshold) {
            CategoryDecision(categories[best].id, cs[best], map)
        } else {
            CategoryDecision(UNCLASSIFIED, if (best >= 0) cs[best] else 0f, map)
        }
    }

    fun topLabels(scores: FloatArray, k: Int = 3): List<LabelScore> =
        scores.indices.sortedByDescending { scores[it] }.take(k).map { LabelScore(labels[it], scores[it]) }
}
