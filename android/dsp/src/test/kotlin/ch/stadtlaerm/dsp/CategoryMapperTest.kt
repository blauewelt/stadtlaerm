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
    fun thresholdYieldsUnclassified() {
        val m = TestSignals.mapper()
        assertEquals(CategoryMapper.UNCLASSIFIED, m.decide(scores("Car" to 0.15f, "Speech" to 0.1f)).dominant)
        assertEquals(CategoryMapper.UNCLASSIFIED, m.decide(scores("Car" to 0.2f)).dominant) // must be *above* 0.2
        assertEquals(CategoryMapper.UNCLASSIFIED, m.decide(scores("Bird" to 0.95f)).dominant) // unmapped label
        assertEquals("music", m.decide(scores("Music" to 0.25f, "Bird" to 0.9f)).dominant)
    }

    @Test
    fun topLabels() {
        val m = TestSignals.mapper()
        val top = m.topLabels(scores("Car" to 0.3f, "Truck" to 0.6f, "Bus" to 0.1f, "Speech" to 0.05f), 3)
        assertEquals(listOf("Truck", "Car", "Bus"), top.map { it.label })
    }
}
