package tv.own.owntv.features.recordings

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.features.sport.RecordPlan

class DvrPlannedTest {

    private fun rec(id: Long, start: Long, status: RecordingStatus, channel: String = "Sky Sports") = RecordingEntity(
        id = id, profileId = 1, sourceId = 1, channelId = id, channelName = channel, streamUrl = "",
        title = "t$id", programmeStartMs = start, programmeStopMs = start + 100, startMs = start, stopMs = start + 100,
        status = status,
    )

    private fun plan(id: String, kickoff: Long, status: RecordPlan.Status, channel: String? = null) =
        RecordPlan(id, "eng.1", kickoff, "A – B", status, channel)

    @Test
    fun `lists what is recording first, then scheduled recordings and waiting games soonest first`() {
        val list = dvrPlanned(
            recordings = listOf(
                rec(1, 500, RecordingStatus.SCHEDULED),
                rec(2, 100, RecordingStatus.COMPLETED),
                rec(3, 900, RecordingStatus.RECORDING),
                rec(4, 50, RecordingStatus.CANCELLED),
            ),
            plans = listOf(
                plan("w", 300, RecordPlan.Status.WAITING),
                plan("n", 700, RecordPlan.Status.NO_CHANNEL),
                plan("s", 400, RecordPlan.Status.SCHEDULED, "Sky Sports"),
            ),
        )
        assertEquals(listOf("r3", "pw", "r1", "pn"), list.map { it.key })
    }

    @Test
    fun `a recording's Sport plan is the one scheduled on its channel inside its window`() {
        val r = rec(1, 1_000, RecordingStatus.SCHEDULED, channel = "Sky Sports")
        val plans = listOf(
            plan("mine", 1_050, RecordPlan.Status.SCHEDULED, "Sky Sports"),
            plan("other channel", 1_050, RecordPlan.Status.SCHEDULED, "TNT"),
            plan("later", 5_000, RecordPlan.Status.SCHEDULED, "Sky Sports"),
            plan("waiting", 1_050, RecordPlan.Status.WAITING),
        )
        assertEquals(listOf("mine"), sportPlansFor(r, plans).map { it.fixtureId })
    }
}
