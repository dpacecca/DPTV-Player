package tv.own.owntv.features.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** (v2.6.2) "Record" on a game: how a wish is stored, when it is looked at, and how results merge. */
class SportsRecordPlansTest {

    private val hour = 3_600_000L
    private val kickoff = 100L * 24L * hour
    private val plan = RecordPlan("401", "soccer.eng.1", kickoff, "Arsenal – Liverpool")

    @Test
    fun aPlanSurvivesItsOwnEncoding() {
        assertEquals(plan, RecordPlan.decode(plan.encode()))
        val set = plan.copy(status = RecordPlan.Status.SCHEDULED, channelName = "Sky Sports | Main Event")
        assertEquals(set, RecordPlan.decode(set.encode()))
        assertNull(RecordPlan.decode("garbage"))
    }

    @Test
    fun aWaitingPlanIsTriedOnlyWithinTheGuidesReach() {
        assertEquals(RecordPlanRules.Step.WAIT, RecordPlanRules.step(plan, kickoff - RecordPlanRules.HorizonMs - hour))
        assertEquals(RecordPlanRules.Step.TRY, RecordPlanRules.step(plan, kickoff - 2 * hour))
        assertEquals(RecordPlanRules.Step.TRY, RecordPlanRules.step(plan, kickoff + 10 * 60_000L))
        assertEquals(RecordPlanRules.Step.MISSED, RecordPlanRules.step(plan, kickoff + hour))
        assertEquals(RecordPlanRules.Step.DROP, RecordPlanRules.step(plan, kickoff + RecordPlanRules.KeepAfterMs + 1))
        assertEquals(RecordPlanRules.Step.WAIT, RecordPlanRules.step(plan.copy(status = RecordPlan.Status.SCHEDULED), kickoff - hour))
    }

    @Test
    fun noChannelIsReportedOnlyCloseToKickOff() {
        assertEquals(RecordPlan.Status.WAITING, RecordPlanRules.afterMiss(plan, kickoff - 2 * hour).status)
        assertEquals(RecordPlan.Status.NO_CHANNEL, RecordPlanRules.afterMiss(plan, kickoff - 5 * 60_000L).status)
    }

    @Test
    fun theUsersChangesDuringARunAreKept() {
        val other = RecordPlan("402", "basketball.nba", kickoff, "Lakers – Warriors")
        val added = RecordPlan("403", "football.nfl", kickoff, "Bills – Jets")
        val done = plan.copy(status = RecordPlan.Status.SCHEDULED, channelName = "Sky")
        // 401 ran and was scheduled; 402 was removed by the user meanwhile; 403 was added meanwhile.
        val current = setOf(plan.encode(), added.encode())
        val merged = SportsPrefs.mergeRecordPlans(current, before = listOf(plan, other), after = listOf(done, other))
        assertEquals(setOf(done.encode(), added.encode()), merged)
    }

    @Test
    fun aDroppedPlanGoes() {
        assertEquals(emptySet<String>(), SportsPrefs.mergeRecordPlans(setOf(plan.encode()), listOf(plan), emptyList()))
    }

    @Test
    fun aGameWithoutAGuideProgrammeIsRecordedForItsSportsUsualLength() {
        assertEquals(135L * 60_000L, RecordPlanRules.durationMs(SportGroup.SOCCER))
        assertEquals(120L * 60_000L, RecordPlanRules.durationMs(SportGroup.RUGBY_LEAGUE))
        assertEquals(180L * 60_000L, RecordPlanRules.durationMs(null))
    }
}
