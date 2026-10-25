package tv.own.owntv.core.sync.schedule

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * What one source's change check last found (v2.5.3). [id] is a playlist id, or an EPG source's
 * (negative) id.
 */
data class SourceCheckState(
    val id: Long,
    /** True/false once detected; null before the first answer. */
    val isDptv: Boolean? = null,
    val dptvCheckedAt: Long? = null,
    val dptvRevs: Map<String, String>? = null,
    val validators: Map<String, Validators> = emptyMap(),
    val signal: ChangeSignal? = null,
    val lastCheckAt: Long? = null,
    /** When a check last saw a change (or the server's own refresh time, for DPTV-Server). */
    val lastChangeAt: Long? = null,
    /** HTTP status of a failed check; 0 for no response. */
    val failureCode: Int? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        isDptv?.let { put("dptv", it) }
        dptvCheckedAt?.let { put("dptvAt", it) }
        dptvRevs?.let { revs -> put("revs", JSONObject().apply { revs.forEach { (k, v) -> put(k, v) } }) }
        put("val", JSONObject().apply { validators.forEach { (k, v) -> put(k, v.toJson()) } })
        signal?.let { put("sig", it.name) }
        lastCheckAt?.let { put("checkAt", it) }
        lastChangeAt?.let { put("changeAt", it) }
        failureCode?.let { put("fail", it) }
    }

    companion object {
        fun fromJson(id: Long, o: JSONObject): SourceCheckState = SourceCheckState(
            id = id,
            isDptv = if (o.has("dptv")) o.optBoolean("dptv") else null,
            dptvCheckedAt = o.optLongOrNull("dptvAt"),
            dptvRevs = o.optJSONObject("revs")?.let { r -> r.keys().asSequence().associateWith { r.optString(it) } },
            validators = o.optJSONObject("val")?.let { v ->
                v.keys().asSequence().mapNotNull { k -> Validators.fromJson(v.optJSONObject(k))?.let { k to it } }.toMap()
            }.orEmpty(),
            signal = o.optString("sig").takeIf { it.isNotEmpty() }?.let { runCatching { ChangeSignal.valueOf(it) }.getOrNull() },
            lastCheckAt = o.optLongOrNull("checkAt"),
            lastChangeAt = o.optLongOrNull("changeAt"),
            failureCode = if (o.has("fail")) o.optInt("fail") else null,
        )

        private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
    }
}

/**
 * Device-local bookkeeping for the fixed sync times and the change check (v2.5.3): when each kind
 * last succeeded, which slot was last handled, the VOD throttle, and every source's validators and
 * status line. A plain SharedPreferences file of its own, not the settings DataStore: none of it is a
 * choice the user made, and restoring it on another device would claim checks that never ran there.
 */
class SyncStateStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _checks = MutableStateFlow(loadChecks())

    /** Every source's last check, for the status lines. */
    val checks: StateFlow<Map<Long, SourceCheckState>> = _checks.asStateFlow()

    fun lastSuccessAt(kind: ScheduledKind): Long? = prefs.getLong("ok_${kind.name}", 0L).takeIf { it > 0L }

    fun markSuccess(kind: ScheduledKind, atMs: Long) {
        synchronized(lock) {
            if (atMs > (lastSuccessAt(kind) ?: 0L)) prefs.edit().putLong("ok_${kind.name}", atMs).apply()
        }
    }

    fun handledSlotAt(kind: ScheduledKind): Long? = prefs.getLong("slot_${kind.name}", 0L).takeIf { it > 0L }

    fun markHandled(kind: ScheduledKind, slotMs: Long) {
        prefs.edit().putLong("slot_${kind.name}", slotMs).apply()
    }

    /** When a change last started a sync of [kind] for source [id] — the change throttles. */
    fun lastChangeSyncAt(kind: ScheduledKind, id: Long): Long? = prefs.getLong("chg_${kind.name}_$id", 0L).takeIf { it > 0L }

    fun markChangeSync(kind: ScheduledKind, id: Long, atMs: Long) {
        prefs.edit().putLong("chg_${kind.name}_$id", atMs).apply()
    }

    fun check(id: Long): SourceCheckState? = _checks.value[id]

    fun putCheck(state: SourceCheckState) {
        synchronized(lock) {
            prefs.edit().putString("check_${state.id}", state.toJson().toString()).apply()
            _checks.value = _checks.value + (state.id to state)
        }
    }

    fun removeCheck(id: Long) {
        synchronized(lock) {
            prefs.edit().remove("check_$id").apply()
            _checks.value = _checks.value - id
        }
    }

    private fun loadChecks(): Map<Long, SourceCheckState> =
        prefs.all.mapNotNull { (key, value) ->
            if (!key.startsWith("check_") || value !is String) return@mapNotNull null
            val id = key.removePrefix("check_").toLongOrNull() ?: return@mapNotNull null
            runCatching { id to SourceCheckState.fromJson(id, JSONObject(value)) }.getOrNull()
        }.toMap()

    private companion object {
        const val FILE = "owntv_sync_state"
    }
}
