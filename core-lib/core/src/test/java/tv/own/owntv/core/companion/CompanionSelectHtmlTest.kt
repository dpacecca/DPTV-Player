package tv.own.owntv.core.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the [CompanionSelectHtml] level split — which row lands on which level, how children are
 * grouped into tabs, and that escaping and the single-form contract survive. Runs on the JVM
 * because the renderer takes labels instead of a `Context`.
 */
class CompanionSelectHtmlTest {

    private val labels = CompanionSelectHtml.Labels(
        description = "Choose.",
        search = "Search",
        save = "Save selection",
        back = "Back",
        manageAll = "Manage all",
        all = "Select all",
        none = "Clear",
        empty = "Nothing chosen yet.",
    )

    private fun row(
        id: String,
        label: String = id,
        group: String = "",
        selected: Boolean = false,
        parentId: String? = null,
    ) = CompanionSelectItem(id, label, group, selected, parentId)

    private fun body(vararg items: CompanionSelectItem): String =
        CompanionSelectHtml.body("123456", "Sport", items.toList(), labels)

    private fun checkboxes(html: String): List<String> =
        Regex("""name="item" value="([^"]*)"""").findAll(html).map { it.groupValues[1] }.toList()

    @Test
    fun `rows with children become level-one entries, the rest stays on the manage-all level`() {
        val html = body(
            row("soccer.ger.1", "Bundesliga", "Germany", selected = true),
            row("team:ger.1|FCB", "FC Bayern", "Teams", selected = true, parentId = "soccer.ger.1"),
            row("team:ger.1|BVB", "Dortmund", "Teams", parentId = "soccer.ger.1"),
            row("racing.f1", "F1", "Europe", selected = true),
        )

        assertTrue(html.contains("""<a class="row" data-pid="soccer.ger.1" href="#/p/soccer.ger.1">"""))
        // No children, nothing to drill into: no level-1 entry, but still a checkbox on level 3.
        assertFalse(html.contains("""data-pid="racing.f1""""))
        assertTrue(html.contains("""name="item" value="racing.f1""""))
    }

    @Test
    fun `every row is a checkbox exactly once so one submit carries the whole selection`() {
        val items = listOf(
            row("soccer.ger.1", "Bundesliga", "Germany", selected = true),
            row("team:ger.1|FCB", "FC Bayern", "Teams", parentId = "soccer.ger.1"),
            row("player:tennis.atp|1", "Spieler", "Players", parentId = "soccer.ger.1"),
            row("racing.f1", "F1", "Europe"),
        )
        val boxes = checkboxes(body(*items.toTypedArray()))

        assertEquals(items.size, boxes.size)
        assertEquals(items.map { it.id }.sorted(), boxes.sorted())
    }

    @Test
    fun `children keep their ticked state and their parent`() {
        val html = body(
            row("basketball.nba", "NBA", "Americas", selected = true),
            row("team:nba|BOS", "Celtics", "Teams", selected = true, parentId = "basketball.nba"),
        )

        assertTrue(html.contains("""data-pid="basketball.nba" hidden>"""))
        assertTrue(html.contains("""value="team:nba|BOS" checked"""))
    }

    @Test
    fun `child groups become tabs only when there is more than one`() {
        val two = body(
            row("soccer.ger.1", "Bundesliga", "Germany", selected = true),
            row("team:1", "Team", "Teams", parentId = "soccer.ger.1"),
            row("player:1", "Player", "Players", parentId = "soccer.ger.1"),
        )
        assertTrue(two.contains("""<div class="tabs fit">"""))
        assertTrue(two.contains("""data-k="g0">Teams</button>"""))
        assertTrue(two.contains("""data-k="g1">Players</button>"""))

        val one = body(
            row("soccer.ger.1", "Bundesliga", "Germany", selected = true),
            row("team:1", "Team", "Teams", parentId = "soccer.ger.1"),
        )
        assertFalse(one.contains("tabs fit"))
        assertTrue(one.contains("""class="panel active" data-k="g0""""))
    }

    @Test
    fun `labels, ids and the title are escaped`() {
        val html = CompanionSelectHtml.body(
            pin = "1",
            title = "Sport & TV",
            items = listOf(
                row("A&B", """<b>League</b>""", "Grüße & Co"),
                row("child", "Child", "Teams", parentId = "A&B"),
            ),
            labels = labels,
        )

        assertTrue(html.contains("Sport &amp; TV"))
        assertTrue(html.contains("&lt;b&gt;League&lt;/b&gt;"))
        assertTrue(html.contains("""data-pid="A&amp;B" href="#/p/A%26B">"""))
        assertTrue(html.contains("Grüße &amp; Co"))
    }

    @Test
    fun `the empty hint replaces level one when nothing has children`() {
        val html = body(row("racing.f1", "F1", "Europe"))

        assertTrue(html.contains("""id="lv1empty">"""))
        assertFalse(html.contains("""id="lv1empty" hidden>"""))
        assertTrue(html.contains("""id="allcount">0/1</span>"""))
    }

    @Test
    fun `the manage-all row counts the ticked top-level rows`() {
        val html = body(
            row("a", "A", "One", selected = true),
            row("a|1", "A1", "Teams", parentId = "a"),
            row("b", "B", "One"),
            row("c", "C", "Two", selected = true),
        )

        assertTrue(html.contains("""id="allcount">2/3</span>"""))
        assertTrue(html.contains("""id="lv1empty" hidden>"""))
    }
}
