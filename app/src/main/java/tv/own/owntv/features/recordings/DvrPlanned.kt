package tv.own.owntv.features.recordings

import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.model.RecordingStatus
import tv.own.owntv.features.sport.RecordPlan

/**
 * One line of DVR's Scheduled view (v2.9.9): a recording set for later or under way, or a game marked
 * Record in Sport whose channel the guide has not listed yet (or never did). A Sport game that has its
 * channel is an ordinary recording by then, and is listed as one.
 */
internal sealed interface DvrPlanned {
    val key: String
    val atMs: Long

    companion object {
        /** The list key of the recording with this id. */
        fun keyOf(recordingId: Long): String = "r" + recordingId
    }

    data class Recording(val recording: RecordingEntity) : DvrPlanned {
        override val key: String get() = keyOf(recording.id)
        override val atMs: Long get() = recording.startMs
    }

    data class Game(val plan: RecordPlan) : DvrPlanned {
        override val key: String get() = "p" + plan.fixtureId
        override val atMs: Long get() = plan.kickoffMs
    }
}

/** What the Scheduled view lists: what is recording now first, then everything else soonest first. */
internal fun dvrPlanned(recordings: List<RecordingEntity>, plans: List<RecordPlan>): List<DvrPlanned> {
    val recs = recordings
        .filter { it.status == RecordingStatus.SCHEDULED || it.status == RecordingStatus.RECORDING }
        .map { DvrPlanned.Recording(it) }
    val games = plans.filter { it.status != RecordPlan.Status.SCHEDULED }.map { DvrPlanned.Game(it) }
    return (recs + games).sortedWith(
        compareByDescending<DvrPlanned> { (it as? DvrPlanned.Recording)?.recording?.status == RecordingStatus.RECORDING }
            .thenBy { it.atMs },
    )
}

/**
 * The Sport plans a recording came from: scheduled on its channel, with kick-off inside its window.
 * Cancelling the recording drops them too, so the game's Record chip stops saying it is scheduled.
 */
internal fun sportPlansFor(recording: RecordingEntity, plans: List<RecordPlan>): List<RecordPlan> =
    plans.filter {
        it.status == RecordPlan.Status.SCHEDULED &&
            it.channelName == recording.channelName &&
            it.kickoffMs in recording.startMs..recording.stopMs
    }
