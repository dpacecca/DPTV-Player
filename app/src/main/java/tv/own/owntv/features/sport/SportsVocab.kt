package tv.own.owntv.features.sport

import android.content.Context
import android.util.Log
import org.json.JSONObject
import tv.own.owntv.R

/**
 * The Sports Hub matcher's static vocabulary, loaded once from `res/raw/sports_matching.json`:
 * team aliases/exonyms, generic club stop-words, broadcast filler words and sport words.
 *
 * Why a resource and not Kotlin constants: the word lists are data, not UI copy. Keeping them in a
 * `raw` resource means the i18n hardcoded-string gate never sees them, they can be extended without
 * touching code, and the alias table keeps Lume's shape (a folded name → its alternate spellings).
 *
 * The vocabulary is **optional**: [load] degrades to [EMPTY] on any failure (missing/renamed file,
 * malformed JSON), and every consumer treats an empty table as "no alias/stop-word expansion" rather
 * than as an error. Matching must never crash because the resource is absent.
 *
 * @see SportNameMatch for the pure text helpers that consume these sets.
 */
class SportsVocab private constructor(
    private val aliasesByKey: Map<String, List<String>>,
    /** Generic club affixes ("fc", "club") dropped from team tokens. */
    val teamStopwords: Set<String>,
    /** Filler words ("sky", "sport", "hd") dropped when probing broadcast names. */
    val broadcastStopwords: Set<String>,
    /** Words that name a sport inside an EPG `<category>`. */
    val sportWords: Set<String>,
) {

    /**
     * Every alias known for any of [names] (a team's ESPN display and short names), deduped in
     * insertion order. Lookup is accent- and case-insensitive via [foldKey].
     */
    fun aliasesFor(names: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (name in names) {
            aliasesByKey[foldKey(name)]?.let(seen::addAll)
        }
        return seen.toList()
    }

    companion object {
        /** No aliases, no stop-words — matching still works, just without the expansion. */
        val EMPTY = SportsVocab(emptyMap(), emptySet(), emptySet(), emptySet())

        /** Build a vocabulary directly (unit tests, or callers that already hold the data). */
        fun of(
            aliases: Map<String, List<String>> = emptyMap(),
            teamStopwords: Set<String> = emptySet(),
            broadcastStopwords: Set<String> = emptySet(),
            sportWords: Set<String> = emptySet(),
        ): SportsVocab = SportsVocab(
            aliasesByKey = aliases.mapKeys { foldKey(it.key) },
            teamStopwords = teamStopwords,
            broadcastStopwords = broadcastStopwords,
            sportWords = sportWords,
        )

        /** Load `res/raw/sports_matching.json`, degrading to [EMPTY] on any failure. */
        fun load(context: Context): SportsVocab = try {
            val raw = context.resources.openRawResource(R.raw.sports_matching)
                .bufferedReader().use { it.readText() }
            fromJson(JSONObject(raw))
        } catch (t: Throwable) {
            Log.w(LogTag, "sports_matching.json unavailable; alias and stop-word expansion disabled", t)
            EMPTY
        }

        private fun fromJson(root: JSONObject): SportsVocab {
            val aliases = LinkedHashMap<String, List<String>>()
            root.optJSONObject(AliasesKey)?.let { table ->
                for (key in table.keys()) {
                    val array = table.optJSONArray(key) ?: continue
                    val values = ArrayList<String>(array.length())
                    for (i in 0 until array.length()) {
                        array.optString(i).trim().takeIf { it.isNotEmpty() }?.let(values::add)
                    }
                    if (values.isNotEmpty()) aliases[foldKey(key)] = values
                }
            }
            return SportsVocab(
                aliasesByKey = aliases,
                teamStopwords = root.stringSet(TeamStopwordsKey),
                broadcastStopwords = root.stringSet(BroadcastStopwordsKey),
                sportWords = root.stringSet(SportWordsKey),
            )
        }

        private fun JSONObject.stringSet(key: String): Set<String> {
            val array = optJSONArray(key) ?: return emptySet()
            val out = LinkedHashSet<String>(array.length())
            for (i in 0 until array.length()) {
                array.optString(i).trim().takeIf { it.isNotEmpty() }?.let(out::add)
            }
            return out
        }

        /** Accent/case-insensitive key for the alias table — the matcher's normalized form, trimmed. */
        fun foldKey(text: String): String = SportNameMatch.normalize(text).trim()

        private const val LogTag = "SportsHub"
        private const val AliasesKey = "aliases"
        private const val TeamStopwordsKey = "teamStopwords"
        private const val BroadcastStopwordsKey = "broadcastStopwords"
        private const val SportWordsKey = "sportWords"
    }
}
