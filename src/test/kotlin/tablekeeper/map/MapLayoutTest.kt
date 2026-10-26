package tablekeeper.map

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapLayoutTest {
    // Two landmarks joined by a delve, a minor location on L1, and an unconnected landmark
    private val items = listOf(
        MapLayout.Item("L1"), MapLayout.Item("L2"), MapLayout.Item("D1"),
        MapLayout.Item("M1", small = true), MapLayout.Item("L3"),
    )
    private val edges = listOf("D1" to "L1", "D1" to "L2", "M1" to "L1")
    private fun layout() = MapLayout.layout(items, edges, 1400.0, 990.0, 130.0)
    private fun dist(a: MapLayout.Point, b: MapLayout.Point) = hypot(a.x - b.x, a.y - b.y)

    @Test
    fun `same graph gives the same map`() {
        assertEquals(layout(), layout())
    }

    @Test
    fun `every place is inside the margins`() {
        layout().values.forEach { p ->
            assertTrue(p.x in 129.9..1270.1 && p.y in 129.9..860.1, "$p")
        }
    }

    @Test
    fun `linked places are closer than unlinked ones, and minor locations hug their landmark`() {
        val p = layout()
        assertTrue(dist(p.getValue("D1"), p.getValue("L1")) < dist(p.getValue("L3"), p.getValue("L1")))
        assertTrue(dist(p.getValue("M1"), p.getValue("L1")) < dist(p.getValue("D1"), p.getValue("L1")))
    }

    @Test
    fun `a chain of places is laid out across the page`() {
        val chain = (1..5).map { MapLayout.Item("L$it") }
        val p = MapLayout.layout(chain, (1..4).map { "L$it" to "L${it + 1}" }, 1400.0, 990.0, 130.0).values
        assertTrue(p.maxOf { it.x } - p.minOf { it.x } > p.maxOf { it.y } - p.minOf { it.y })
    }

    @Test
    fun `an unconnected place doesn't squeeze the rest of the map`() {
        val connected = items.filter { it.id != "L3" }
        val without = MapLayout.layout(connected, edges, 1400.0, 990.0, 130.0)
        val with = layout()
        val ratio = dist(with.getValue("D1"), with.getValue("L1")) / dist(without.getValue("D1"), without.getValue("L1"))
        assertTrue(ratio > 0.5, "spacing kept $ratio of its size")
    }

    @Test
    fun `higher tiers are drawn lower, also across unconnected groups`() {
        val tiered = listOf(
            MapLayout.Item("L1", depth = 1.0), MapLayout.Item("D1", depth = 2.0), MapLayout.Item("L2", depth = 3.0),
            MapLayout.Item("M1", small = true), MapLayout.Item("L9", depth = 3.0),  // L9 is on its own
        )
        val p = MapLayout.layout(tiered, listOf("D1" to "L1", "D1" to "L2", "M1" to "L1"), 1400.0, 990.0, 130.0)
        val y = p.mapValues { it.value.y }
        assertTrue(y.getValue("L1") < y.getValue("D1") && y.getValue("D1") < y.getValue("L2"), "$y")
        // Same tier, same band: within the band's half height (scaled to the page) of each other
        assertTrue(kotlin.math.abs(y.getValue("L9") - y.getValue("L2")) < y.getValue("L2") - y.getValue("D1"), "same band: $y")
        // Places sharing a tier keep apart sideways
        val x = p.mapValues { it.value.x }
        assertTrue(kotlin.math.abs(x.getValue("L9") - x.getValue("L2")) > 100, "$x")
    }

    @Test
    fun `a link running through another place curves around it, upwards`() {
        val a = MapLayout.Point(100.0, 500.0); val b = MapLayout.Point(700.0, 500.0)
        val between = MapLayout.Point(400.0, 500.0)
        val c = MapLayout.bend(a, b, listOf(between), 40.0)!!
        // Middle of the quadratic curve: (a + 2c + b) / 4, must clear the place in between, going up
        val midY = (a.y + 2 * c.y + b.y) / 4
        assertTrue(midY < between.y - 40, "curve middle at $midY")
        assertEquals(null, MapLayout.bend(a, b, listOf(MapLayout.Point(400.0, 600.0)), 40.0), "clear line stays straight")
        assertEquals(null, MapLayout.bend(a, b, listOf(MapLayout.Point(720.0, 500.0)), 40.0), "beyond the end doesn't count")
    }

    @Test
    fun `minor locations keep off other places and their labels`() {
        // A landmark with two minor locations, between delves sitting halfway between tiers
        val items = listOf(
            MapLayout.Item("L1", depth = 0.0), MapLayout.Item("L2", depth = 1.0), MapLayout.Item("L3", depth = 2.0),
            MapLayout.Item("D1", depth = 0.5), MapLayout.Item("D2", depth = 1.5),
            MapLayout.Item("M1", small = true), MapLayout.Item("M2", small = true),
        )
        val edges = listOf("D1" to "L1", "D1" to "L2", "D2" to "L2", "D2" to "L3", "M1" to "L2", "M2" to "L2")
        val p = MapLayout.layout(items, edges, 1400.0, 990.0, 130.0)
        // In output pixels a label runs ~30px below its place; nothing else may sit in that band
        for (m in listOf("M1", "M2")) for (other in p.keys - m) {
            val dx = kotlin.math.abs(p.getValue(m).x - p.getValue(other).x)
            val dy = p.getValue(m).y - p.getValue(other).y
            assertTrue(dx > 60 || dy !in -15.0..35.0, "$m sits on $other: ${p.getValue(m)} vs ${p.getValue(other)}")
        }
    }

    @Test
    fun `places on one row keep room for their labels`() {
        // Two linked landmarks on the same tier with long names, which the force layout alone puts too close
        val items = listOf(
            MapLayout.Item("L1", depth = 1.0, labelWidth = 220.0, body = 26.0), MapLayout.Item("L2", depth = 1.0, labelWidth = 200.0, body = 26.0),
            MapLayout.Item("L3", depth = 2.0, labelWidth = 120.0, body = 26.0), MapLayout.Item("D1", depth = 1.5, labelWidth = 80.0, body = 17.0),
            MapLayout.Item("D2", depth = 1.5, labelWidth = 80.0, body = 17.0),
        )
        val p = MapLayout.layout(items, listOf("D1" to "L1", "D1" to "L3", "D2" to "L2", "D2" to "L3"), 1400.0, 990.0, 130.0)
        val gap = kotlin.math.abs(p.getValue("L1").x - p.getValue("L2").x)
        assertTrue(gap >= (220.0 + 200.0) / 2, "labels need ${(220 + 200) / 2}, got $gap")
        p.values.forEach { assertTrue(it.x in 129.9..1270.1, "$it inside the margins") }
    }

    @Test
    fun `landmarks first, then delves between them and minor locations above`() {
        val items = listOf(
            MapLayout.Item("L1", depth = 1.0), MapLayout.Item("L2", depth = 2.0), MapLayout.Item("L3", depth = 2.0),
            MapLayout.Item("D1"), MapLayout.Item("D2"), MapLayout.Item("D3"), MapLayout.Item("Dead"),
            MapLayout.Item("M1", small = true),
        )
        val edges = listOf("D1" to "L1", "D1" to "L2", "D2" to "L1", "D2" to "L2", "D3" to "L2", "D3" to "L3", "Dead" to "L3", "M1" to "L1")
        val between = mapOf("D1" to listOf("L1", "L2"), "D2" to listOf("L1", "L2"), "D3" to listOf("L2", "L3"), "Dead" to listOf("L3"))
        val p = MapLayout.layout(items, edges, 1400.0, 990.0, 130.0, between = between, satellites = mapOf("M1" to "L1"))
        fun mid(a: String, b: String) = MapLayout.Point((p.getValue(a).x + p.getValue(b).x) / 2, (p.getValue(a).y + p.getValue(b).y) / 2)
        // A lone delve sits right between its landmarks (L2 and L3 share a tier, so D3 is on it too)
        assertTrue(dist(p.getValue("D3"), mid("L2", "L3")) < 1.0, "D3 at the middle")
        // Two delves between the same landmarks fan out either side of the middle, at its height on average
        assertTrue(dist(p.getValue("D1"), p.getValue("D2")) > 20.0, "D1 and D2 apart")
        assertEquals(mid("L1", "L2").y, (p.getValue("D1").y + p.getValue("D2").y) / 2, 1.0)
        // A dead end steps out from its landmark, at the same height
        assertEquals(p.getValue("L3").y, p.getValue("Dead").y, 1.0)
        assertTrue(dist(p.getValue("Dead"), p.getValue("L3")) > 20.0)
        // The minor location sits above its landmark, close by
        assertTrue(p.getValue("M1").y < p.getValue("L1").y)
        assertTrue(dist(p.getValue("M1"), p.getValue("L1")) < dist(p.getValue("L1"), p.getValue("L2")))
    }

    @Test
    fun `a single place sits in the middle`() {
        assertEquals(MapLayout.Point(700.0, 495.0), MapLayout.layout(listOf(MapLayout.Item("L1")), emptyList(), 1400.0, 990.0, 130.0)["L1"])
    }

    @Test
    fun `places on a tier line up in the order their delves chain them, whatever ids they have`() {
        // As in a real campaign: tier 2 is a chain Last Orders – Hautausmaa – Ghorryn – Luolasto – Pumppuasema,
        // and Pumppuasema also leads up to a tier 1 temple. Ghorryn must sit between the other two, so both delves
        // are straight; ids seed the start positions, so try many.
        repeat(40) { seed ->
            fun id(name: String) = "$name-$seed"
            val items = listOf(
                MapLayout.Item(id("temple"), depth = 1.0),
                MapLayout.Item(id("lastOrders"), depth = 2.0), MapLayout.Item(id("ghorryn"), depth = 2.0), MapLayout.Item(id("pumppu"), depth = 2.0),
                MapLayout.Item(id("hautausmaa")), MapLayout.Item(id("luolasto")), MapLayout.Item(id("kaivos")),
            )
            val between = mapOf(
                id("hautausmaa") to listOf(id("lastOrders"), id("ghorryn")),
                id("luolasto") to listOf(id("pumppu"), id("ghorryn")),
                id("kaivos") to listOf(id("temple"), id("pumppu")),
            )
            val edges = between.flatMap { (d, ls) -> ls.map { d to it } }
            val x = MapLayout.layout(items, edges, 1400.0, 990.0, 130.0, between = between).mapValues { it.value.x }
            val (lo, g, p) = listOf("lastOrders", "ghorryn", "pumppu").map { x.getValue(id(it)) }
            assertTrue(g in minOf(lo, p)..maxOf(lo, p), "seed $seed: Ghorryn $g between Last Orders $lo and Pumppuasema $p")
        }
    }

    @Test
    fun `a loop of landmarks over two tiers is drawn without lines bending around places`() {
        // A real campaign's map: tier 2 Last Orders, Ghorryn, TBA, Pumppuasema; tier 3 Päätepysäkki; delves join them in a
        // loop (Last Orders–Ghorryn–TBA–Päätepysäkki–Last Orders), and a branch climbs to tiers 1 and 0.
        val tiers = mapOf("derelict" to 0.0, "temple" to 1.0, "redcap" to 1.0, "pumppu" to 2.0, "ghorryn" to 2.0,
            "lastOrders" to 2.0, "tba" to 2.0, "terminus" to 3.0)
        val between = mapOf(
            "portaat" to listOf("temple", "derelict"), "metsa" to listOf("temple", "redcap"), "kaivos" to listOf("pumppu", "temple"),
            "luolasto" to listOf("pumppu", "ghorryn"), "hautausmaa" to listOf("lastOrders", "ghorryn"),
            "rautatie" to listOf("terminus", "lastOrders"), "ghorrynTba" to listOf("tba", "ghorryn"), "tbaTerminus" to listOf("tba", "terminus"),
        )
        val items = tiers.map { (id, t) -> MapLayout.Item(id, depth = t, labelWidth = 100.0, body = 26.0) } +
            between.keys.map { MapLayout.Item(it, labelWidth = 100.0, body = 17.0) }
        val edges = between.flatMap { (d, ls) -> ls.map { d to it } }
        val p = MapLayout.layout(items, edges, 1400.0, 990.0, 130.0, between = between)
        val bent = edges.filter { (a, b) -> MapLayout.bend(p.getValue(a), p.getValue(b), p.filterKeys { it != a && it != b }.values, 40.0) != null }
        assertEquals(emptyList(), bent, "lines running through other places")
    }
}
