package ch.stadtlaerm.dsp

import ch.stadtlaerm.dsp.classify.CategoryDef
import ch.stadtlaerm.dsp.classify.CategoryMapper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CategoryMapperTest {
    private val labels = TestSignals.labels()

    @Test
    fun shippedLabelFileIsYamnet521() {
        assertEquals(521, labels.size)
        assertEquals("Speech", labels[0])
        assertEquals("Music", labels[132])
        assertEquals("Motorcycle", labels[320])
        assertEquals("Field recording", labels[520])
        assertEquals(labels.size, labels.toSet().size, "labels must be unique")
    }

    @Test
    fun shippedCategoryJsonOnlyUsesExistingLabels() {
        // Constructing the mapper validates every name against the label file.
        val m = TestSignals.mapper()
        assertEquals(
            listOf("loud_vehicle", "road_traffic", "rail_tram", "aircraft", "construction", "voices", "music"),
            m.categoryIds,
        )
        assertEquals(0.2f, m.threshold)
        assertEquals(0.1f, m.top1Threshold)
        assertEquals("Töff & Poser", m.nameDe("loud_vehicle"))
    }

    @Test
    fun unknownLabelNamesFail() {
        val json = File(TestSignals.assetsDir(), "categories.json").readText()
            .replace("\"Jackhammer\"", "\"Construction\"")
        val ex = assertFailsWith<IllegalArgumentException> { CategoryMapper.fromJson(json, labels) }
        assertTrue(ex.message!!.contains("Construction"))
        assertFailsWith<IllegalArgumentException> {
            CategoryMapper(labels, listOf(CategoryDef("tram", "Tram", listOf("Tram"))))
        }
    }

    private fun scores(vararg pairs: Pair<String, Float>): FloatArray {
        val s = FloatArray(labels.size)
        for ((l, v) in pairs) s[labels.indexOf(l).also { require(it >= 0) { l } }] = v
        return s
    }

    @Test
    fun categoryScoreIsMaxOfMembers() {
        val m = TestSignals.mapper()
        val d = m.decide(scores("Car" to 0.3f, "Truck" to 0.6f, "Bus" to 0.1f))
        assertEquals(0.6f, d.categoryScores["road_traffic"])
        assertEquals("road_traffic", d.dominant)
    }

    @Test
    fun tieBreakPrefersLoudVehicle() {
        val m = TestSignals.mapper()
        val d = m.decide(scores("Motorcycle" to 0.5f, "Car" to 0.5f))
        assertEquals("loud_vehicle", d.dominant)
        // Order in the input must not matter.
        val d2 = m.decide(scores("Vehicle" to 0.7f, "Accelerating, revving, vroom" to 0.7f))
        assertEquals("loud_vehicle", d2.dominant)
        // A clearly higher road_traffic score still wins.
        assertEquals("road_traffic", m.decide(scores("Motorcycle" to 0.4f, "Car" to 0.6f)).dominant)
    }

    @Test
    fun ruleA_top1LabelDecidesFromTop1Threshold() {
        val m = TestSignals.mapper()
        // The owner's quiet highway pass-bys: "Vehicle" is the best label but scores only 0.1–0.3.
        val d = m.decide(scores("Vehicle" to 0.14f, "Speech" to 0.08f, "Wind" to 0.05f))
        assertEquals("road_traffic", d.dominant)
        assertEquals(0.14f, d.dominantScore)
        assertEquals("road_traffic", m.decide(scores("Vehicle" to 0.10f)).dominant) // ≥, not >
        // (a) beats a higher-priority category that is not top-1.
        assertEquals("road_traffic", m.decide(scores("Car" to 0.3f, "Motorcycle" to 0.25f)).dominant)
        // Ties on the top-1 score: loud_vehicle preferred (list order).
        assertEquals("loud_vehicle", m.decide(scores("Motorcycle" to 0.15f, "Car" to 0.15f)).dominant)
    }

    @Test
    fun ruleB_categoryThresholdWhenTop1IsUnmappedOrWeak() {
        val m = TestSignals.mapper()
        // Top-1 is an unmapped label: the best category wins if ≥ 0.2.
        assertEquals("music", m.decide(scores("Music" to 0.25f, "Bird" to 0.9f)).dominant)
        assertEquals("road_traffic", m.decide(scores("Bird" to 0.5f, "Car" to 0.2f)).dominant) // ≥ 0.2
        // Top-1 mapped but below 0.10 cannot happen with a category ≥ 0.2 (that category would be
        // top-1), so (b) only matters when the top-1 label is unmapped.
    }

    @Test
    fun ruleC_otherwiseUnclassified() {
        val m = TestSignals.mapper()
        assertEquals(CategoryMapper.UNCLASSIFIED, m.decide(scores("Bird" to 0.95f)).dominant) // unmapped, no category
        assertEquals(CategoryMapper.UNCLASSIFIED, m.decide(scores("Bird" to 0.6f, "Car" to 0.19f)).dominant)
        assertEquals(CategoryMapper.UNCLASSIFIED, m.decide(scores("Car" to 0.09f, "Speech" to 0.05f)).dominant) // top-1 < 0.10
        assertEquals(CategoryMapper.UNCLASSIFIED, m.decide(FloatArray(labels.size)).dominant)
    }

    @Test
    fun thresholdsComeFromJsonWithLegacyName() {
        val json = File(TestSignals.assetsDir(), "categories.json").readText()
        val custom = CategoryMapper.fromJson(json.replace("\"top1_threshold\": 0.10", "\"top1_threshold\": 0.5"), labels)
        assertEquals(0.5f, custom.top1Threshold)
        assertEquals(CategoryMapper.UNCLASSIFIED, custom.decide(scores("Vehicle" to 0.14f)).dominant)
        val legacy = CategoryMapper.fromJson(json.replace("\"category_threshold\": 0.20", "\"threshold\": 0.3"), labels)
        assertEquals(0.3f, legacy.threshold)
    }

    @Test
    fun topLabels() {
        val m = TestSignals.mapper()
        val top = m.topLabels(scores("Car" to 0.3f, "Truck" to 0.6f, "Bus" to 0.1f, "Speech" to 0.05f), 3)
        assertEquals(listOf("Truck", "Car", "Bus"), top.map { it.label })
    }
}
