package tablekeeper.views

import tablekeeper.core.EdgeRef
import tablekeeper.core.Node
import tablekeeper.modules.GameModules
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ModuleViewsTest {
    private val heart = GameModules.load(System.getProperty("modulesDir") ?: "modules").get("heart")
    private val campaign = UUID.randomUUID()
    private fun node(type: String, code: String, title: String, props: Map<String, Any?> = emptyMap()) =
        Node(UUID.randomUUID(), campaign, type, code, title, "", props, "gm")

    @Test
    fun `characters list their fallouts with type, resistance and description`() {
        val ash = node("heart.pc", "PC1", "Ash", mapOf("player" to "Mia"))
        val arm = node("heart.fallout", "FO1", "Broken arm", mapOf("type" to "Major", "resistance" to "Blood", "description" to "Can't hold a weapon two-handed"))
        val dreams = node("heart.fallout", "FO2", "Nightmares", mapOf("type" to "Minor", "resistance" to "Mind"))
        val other = node("heart.fallout", "FO3", "Someone else's", mapOf("type" to "Minor"))
        val edges = listOf(EdgeRef(ash.id, arm.id, "suffers"), EdgeRef(ash.id, dreams.id, "suffers"))
        val all = listOf(ash, arm, dreams, other)
        val data = ModuleViews.build(heart, heart.view("inspiration")!!, listOf(ash), edges, all)
        val viewNode = data.nodes.single()
        assertEquals("Ash (Mia)", viewNode.display)
        val group = viewNode.linked.single()
        assertEquals("Fallouts", group.label)
        assertEquals(
            listOf("Broken arm: Major · Blood · Can't hold a weapon two-handed", "Nightmares: Minor · Mind"),
            group.items.map { "${it.title}: ${it.summary}" },
        )
    }
}
