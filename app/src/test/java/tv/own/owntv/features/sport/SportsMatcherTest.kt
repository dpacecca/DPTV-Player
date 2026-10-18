package tv.own.owntv.features.sport

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.EpgDao
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/**
 * The matcher's Lume rules, nailed down on the JVM with fake DAOs — no emulator, no Android.
 *
 * The six cases are the ones `docs/sport-channel-matching.md` §9 lists: the two EPG tiers, the
 * nickname alias, the whole-channel-name tier, the one-team regression net and the language filter
 * that must run **before** the limit cut. The reference fixture is the one the doc was written
 * against — Coventry City v Newcastle United.
 *
 * [SportsMatcher] takes no Android-bound dependency: the two DAOs are plain interfaces (faked below)
 * and the live-playlist lookup is the [LiveSourceIdsProvider] seam, handed a one-entry lambda. That
 * is what lets this run as `:app:testStandardDebugUnitTest` without MockK/Robolectric.
 */
class SportsMatcherTest {

    // --- (a) both teams in the EPG title → +10 ------------

    @Test
    fun bothTeamsInTheEpgTitleScoreTen() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(1, "IT: SKY SPORT ARENA", epgKey = "arena")),
            programmes = listOf(programme("arena", title = "Coventry City v Newcastle United")),
        )

        val matches = matcher(store).match(fixture())

        assertEquals(listOf("IT: SKY SPORT ARENA"), matches.map { it.channelName })
        assertEquals(10, matches.single().score)
        assertEquals("Coventry City v Newcastle United", matches.single().epgTitle)
    }

    // --- (b) the teams split across title and description → +3 ------------

    @Test
    fun teamsSplitAcrossTitleAndDescriptionScoreThree() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(2, "DE: SKY SPORT PREMIER LEAGUE", epgKey = "de-premier")),
            programmes = listOf(
                programme(
                    "de-premier",
                    title = "Coventry City im Fokus",
                    description = "Der Gegner an diesem Abend: Newcastle United.",
                ),
            ),
        )

        val matches = matcher(store).match(fixture())

        assertEquals(listOf("DE: SKY SPORT PREMIER LEAGUE"), matches.map { it.channelName })
        assertEquals(3, matches.single().score)
    }

    // --- (c) only one team → no match (news-channel regression net) ------------

    @Test
    fun aProgrammeNamingOnlyOneTeamDoesNotMatch() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(3, "DE: SKY SPORT NEWS", epgKey = "de-news")),
            programmes = listOf(programme("de-news", title = "Newcastle United News")),
        )

        assertTrue(matcher(store).match(fixture()).isEmpty())
    }

    // --- (d) generic title, the other team only as a nickname alias → +3 ------------

    @Test
    fun aNicknameAliasSuppliesTheSecondTeam() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(4, "DE: SKY SPORT TOP EVENT", epgKey = "de-top")),
            programmes = listOf(
                programme(
                    "de-top",
                    title = "Fußball: England, Premier League",
                    description = "Premier League: Coventry City gegen die Magpies.",
                ),
            ),
        )

        val matches = matcher(store).match(fixture())

        assertEquals(listOf("DE: SKY SPORT TOP EVENT"), matches.map { it.channelName })
        assertEquals(3, matches.single().score)

        // Without the alias the feed names only one team and must drop out — this is exactly why the
        // nickname table is load-bearing for generically titled feeds.
        assertTrue(matcher(store, vocab = SportsVocab.EMPTY).match(fixture()).isEmpty())
    }

    // --- (e) the channel's own name names both teams → +4 ------------

    @Test
    fun bothTeamsInTheChannelNameScoreFour() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(5, "AU (STAN 01) | Coventry City v Newcastle United HD"),
            ),
            programmes = emptyList(),
        )

        val matches = matcher(store).match(fixture())

        assertEquals(listOf("AU (STAN 01) | Coventry City v Newcastle United HD"), matches.map { it.channelName })
        assertEquals(4, matches.single().score)
    }

    // --- (f) the language filter runs before the cap ------------

    @Test
    fun theLanguageFilterCutsBeforeTheLimit() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(6, "IT: SKY SPORT ARENA", epgKey = "arena"),
                channel(7, "DE: SKY SPORT PREMIER LEAGUE", epgKey = "de-premier"),
            ),
            programmes = listOf(
                programme("arena", title = "Coventry City v Newcastle United"),
                programme(
                    "de-premier",
                    title = "Fußball: England, Premier League",
                    description = "Coventry City gegen die Magpies.",
                ),
            ),
        )
        val matcher = matcher(store)

        // Without a filter the +10 Italian feed wins the single slot...
        val unfiltered = matcher.match(fixture(), languages = emptySet(), limit = 1)
        assertEquals(listOf("IT: SKY SPORT ARENA"), unfiltered.map { it.channelName })

        // ...but the German feed (score 3) must still be visible to a German viewer, which only works
        // if the filter drops the foreign channel BEFORE `take(limit)`.
        val german = matcher.match(fixture(), languages = setOf(SportLanguage.GERMAN), limit = 1)
        assertEquals(listOf("DE: SKY SPORT PREMIER LEAGUE"), german.map { it.channelName })
    }

    // --- (g) duel: both athletes in the EPG title → +10 ------------

    @Test
    fun duelBothAthletesInTheEpgTitleScoreTen() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(10, "US: PPV 4", epgKey = "ppv4")),
            programmes = listOf(programme("ppv4", title = "Karen Khachanov vs Alex de Minaur")),
        )

        val matches = matcher(store).match(duelFixture())

        assertEquals(listOf("US: PPV 4"), matches.map { it.channelName })
        assertEquals(10, matches.single().score)
        assertEquals("Karen Khachanov vs Alex de Minaur", matches.single().epgTitle)
    }

    // --- (h) duel: athletes split across title and description → +3 ------------

    @Test
    fun duelAthletesSplitAcrossTitleAndDescriptionScoreThree() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(11, "US: PPV 4", epgKey = "ppv4")),
            programmes = listOf(
                programme(
                    "ppv4",
                    title = "Khachanov im Fokus",
                    description = "Der Gegner an diesem Abend: Alex de Minaur.",
                ),
            ),
        )

        val matches = matcher(store).match(duelFixture())

        assertEquals(listOf("US: PPV 4"), matches.map { it.channelName })
        assertEquals(3, matches.single().score)
    }

    // --- (i) duel: a lone surname without tennis context → no match ------------

    @Test
    fun duelSingleAthleteWithoutContextDoesNotMatch() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(12, "DE: SPORT NEWS", epgKey = "de-news")),
            programmes = listOf(programme("de-news", title = "De Minaur News")),
        )

        assertTrue(matcher(store).match(duelFixture()).isEmpty())
    }

    // --- (j) duel: one athlete + tennis context → +2 ------------

    @Test
    fun duelSingleAthleteWithTennisContextScoresTwo() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(13, "EUROSPORT 1", epgKey = "eurosport")),
            programmes = listOf(
                programme(
                    "eurosport",
                    title = "Tennis: ATP Shanghai",
                    description = "U.a. Alex de Minaur im Einsatz.",
                ),
            ),
        )

        val matches = matcher(store).match(duelFixture())

        assertEquals(listOf("EUROSPORT 1"), matches.map { it.channelName })
        assertEquals(2, matches.single().score)
    }

    // --- (k) duel: tournament only → +1 ------------

    @Test
    fun duelTournamentOnlyScoresOne() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(14, "EUROSPORT 1", epgKey = "eurosport")),
            programmes = listOf(
                programme("eurosport", title = "Tennis: Shanghai Masters, Viertelfinale"),
            ),
        )

        val matches = matcher(store).match(duelFixture(tournament = "Shanghai Masters"))

        assertEquals(listOf("EUROSPORT 1"), matches.map { it.channelName })
        assertEquals(1, matches.single().score)
    }

    // --- (l) duel: the league hint matches a tennis-named channel without any guide ------------

    @Test
    fun duelHintMatchesTennisChannelByName() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(15, "US TENNIS PPV")),
            programmes = emptyList(),
        )

        val matches = matcher(store).match(duelFixture(hints = listOf("Tennis")))

        assertEquals(listOf("US TENNIS PPV"), matches.map { it.channelName })
        assertEquals(6, matches.single().score)
    }

    // --- (m) doubles: a "A / B" roster side contributes both players ------------

    @Test
    fun doublesRosterSidesMatchBothPairs() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(16, "US: PPV 5", epgKey = "ppv5")),
            programmes = listOf(
                programme("ppv5", title = "Cash/Glasspool vs Darderi/Etcheverry"),
            ),
        )
        val fixture = duelFixture(
            sideA = "Julian Cash / Lloyd Glasspool",
            sideB = "Luciano Darderi / Tomas Martin Etcheverry",
        )

        val matches = matcher(store).match(fixture)

        assertEquals(listOf("US: PPV 5"), matches.map { it.channelName })
        assertEquals(10, matches.single().score)
    }

    // --- (n) duel: a TBD side still matches through the tournament ------------

    @Test
    fun duelTbdSideKeepsTournamentFallback() = runBlocking {
        val store = FakeStore(
            channels = listOf(channel(17, "EUROSPORT 1", epgKey = "eurosport")),
            programmes = listOf(
                programme("eurosport", title = "Tennis: Shanghai Masters"),
            ),
        )
        val fixture = duelFixture(sideB = "TBD", tournament = "Shanghai Masters")

        val matches = matcher(store).match(fixture)

        assertEquals(listOf("EUROSPORT 1"), matches.map { it.channelName })
        assertEquals(1, matches.single().score)
    }

    // --- (o) duel: the exact per-event channel survives a foreign language filter ------------

    @Test
    fun duelExactChannelSurvivesForeignLanguageFilter() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(20, "NEXT | ALEX DE MINAUR - KAREN KHACHANOV | Tue 13 Oct 06:00 CEST (FR) | FR: MAX PPV 21"),
                channel(21, "DE: SKY SPORT TENNIS"),
            ),
            programmes = emptyList(),
        )
        val fixture = duelFixture(hints = listOf("Tennis"))

        val matches = matcher(store).match(fixture, languages = setOf(SportLanguage.GERMAN))

        assertEquals(
            listOf("DE: SKY SPORT TENNIS", "NEXT | ALEX DE MINAUR - KAREN KHACHANOV | Tue 13 Oct 06:00 CEST (FR) | FR: MAX PPV 21"),
            matches.map { it.channelName },
        )
        assertEquals(listOf(6, 4), matches.map { it.score })
        assertEquals(listOf(false, true), matches.map { it.isExact })
    }

    // --- (p) teams: the exact per-event channel survives a foreign language filter ------------

    @Test
    fun teamExactChannelSurvivesForeignLanguageFilter() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(22, "AU (STAN 01) | Coventry City v Newcastle United HD"),
                channel(23, "DE: SKY SPORT PREMIER LEAGUE", epgKey = "de-premier"),
            ),
            programmes = listOf(
                programme(
                    "de-premier",
                    title = "Fußball: England, Premier League",
                    description = "Coventry City gegen die Magpies.",
                ),
            ),
        )

        val matches = matcher(store).match(fixture(), languages = setOf(SportLanguage.GERMAN))

        assertEquals(
            listOf("AU (STAN 01) | Coventry City v Newcastle United HD", "DE: SKY SPORT PREMIER LEAGUE"),
            matches.map { it.channelName },
        )
        assertEquals(listOf(4, 3), matches.map { it.score })
    }

    // --- fixtures / vocabulary ------------------------------------------------------------------

    // --- v5.2 port: strict channel filter ------------

    @Test
    fun strictFilterKeepsOnlyBothSidesEvidenceForADuel() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(30, "EUROSPORT 1", epgKey = "eurosport"),
                channel(31, "US: PPV 7", epgKey = "ppv7"),
            ),
            programmes = listOf(
                programme("eurosport", title = "Tennis: Shanghai Masters, Viertelfinale"),
                programme("ppv7", title = "Khachanov vs De Minaur"),
            ),
        )
        val fixture = duelFixture(tournament = "Shanghai Masters")

        val loose = matcher(store).match(fixture)
        val strict = matcher(store).match(fixture, strict = true)

        assertEquals(setOf("EUROSPORT 1", "US: PPV 7"), loose.map { it.channelName }.toSet())
        assertEquals(listOf("US: PPV 7"), strict.map { it.channelName })
        assertTrue(strict.single().namesBothSides)
    }

    @Test
    fun strictFilterDropsABroadcastOnlyChannelForATeamGame() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(32, "UK: SKY SPORTS PREMIER LEAGUE"),
                channel(33, "IT: SKY SPORT ARENA", epgKey = "arena"),
            ),
            programmes = listOf(programme("arena", title = "Coventry City v Newcastle United")),
        )
        val fixture = fixture().copy(broadcasts = listOf("Sky Sports Premier League"))

        val loose = matcher(store).match(fixture)
        val strict = matcher(store).match(fixture, strict = true)

        assertTrue("UK: SKY SPORTS PREMIER LEAGUE" in loose.map { it.channelName })
        assertEquals(listOf("IT: SKY SPORT ARENA"), strict.map { it.channelName })
    }

    @Test
    fun strictFilterLeavesAFixtureWithoutTwoSidesAlone() {
        val race = Fixture(
            id = "f1/1", leagueId = "racing.f1", dateMs = Noon, state = FixtureState.UPCOMING,
            statusLine = "14:00", format = FixtureFormat.EVENT, title = "Grand Prix",
            broadcasts = emptyList(), venue = null,
        )
        val hint = ChannelMatch(1, "SKY SPORTS F1", null, null, score = 6)

        assertEquals(listOf(hint), SportsMatcher.strictFilter(listOf(hint), race))
        assertTrue(SportsMatcher.strictFilter(listOf(hint), fixture()).isEmpty())
    }

    // --- v5.2 port: playlist filter ------------

    @Test
    fun playlistFilterSearchesOnlyTheChosenPlaylists() = runBlocking {
        val store = FakeStore(
            channels = listOf(
                channel(40, "IT: SKY SPORT ARENA", epgKey = "arena", sourceId = 1),
                channel(41, "DE: SKY SPORT TOP EVENT", epgKey = "arena", sourceId = 2),
            ),
            programmes = listOf(programme("arena", title = "Coventry City v Newcastle United")),
        )
        val matcher = matcher(store, live = listOf(1L, 2L))

        assertEquals(2, matcher.match(fixture()).size)
        assertEquals(listOf("DE: SKY SPORT TOP EVENT"), matcher.match(fixture(), playlists = setOf(2L)).map { it.channelName })
    }

    @Test
    fun allowedSourcesFallsBackToAllWhenTheChoiceIsStale() {
        assertEquals(listOf(1L, 2L), SportsMatcher.allowedSources(listOf(1L, 2L), emptySet()))
        assertEquals(listOf(2L), SportsMatcher.allowedSources(listOf(1L, 2L), setOf(2L, 9L)))
        assertEquals(listOf(1L, 2L), SportsMatcher.allowedSources(listOf(1L, 2L), setOf(9L)))
    }

    private val vocab = SportsVocab.of(
        aliases = mapOf("Newcastle United" to listOf("Magpies")),
        teamStopwords = setOf("fc", "club"),
        broadcastStopwords = setOf("sky", "sport", "hd", "tv"),
        sportWords = setOf("football", "soccer", "fussball"),
    )

    private fun matcher(
        store: FakeStore,
        vocab: SportsVocab = this.vocab,
        live: List<Long> = listOf(SourceId),
    ) = SportsMatcher(
        channelDao = store.channelDao,
        epgDao = store.epgDao,
        vocab = vocab,
        sources = object : LiveSourceIdsProvider {
            override suspend fun liveSourceIds(): List<Long> = live
        },
    )

    private fun fixture() = Fixture(
        id = "1",
        leagueId = "soccer.eng.1",
        dateMs = Noon,
        state = FixtureState.LIVE,
        statusLine = "20:55",
        home = SportTeam("1", "Coventry City", "COV", null, null, "1", false, "home"),
        away = SportTeam("2", "Newcastle United", "NEW", null, null, "0", false, "away"),
        broadcasts = emptyList(),
        venue = null,
    )

    private fun duelFixture(
        sideA: String = "Karen Khachanov",
        sideB: String = "Alex de Minaur",
        hints: List<String> = emptyList(),
        tournament: String? = null,
    ) = Fixture(
        id = "tennis.atp/1/2",
        leagueId = "tennis.atp",
        dateMs = Noon,
        state = FixtureState.LIVE,
        statusLine = "LIVE",
        format = FixtureFormat.VERSUS,
        entries = listOf(
            FixtureEntry(id = "1", name = sideA, order = 1, detail = null, logoUrl = null),
            FixtureEntry(id = "2", name = sideB, order = 2, detail = null, logoUrl = null),
        ),
        broadcasts = emptyList(),
        channelHints = hints,
        tournament = tournament,
        venue = null,
    )

    private fun channel(id: Long, name: String, epgKey: String? = null, sourceId: Long = SourceId) = ChannelEntity(
        id = id,
        sourceId = sourceId,
        name = name,
        streamUrl = "http://provider.invalid/$id.ts",
        epgChannelId = epgKey,
    )

    private fun programme(key: String, title: String, description: String? = null) = EpgProgrammeEntity(
        sourceId = SourceId,
        epgChannelId = key,
        startMs = Noon,
        stopMs = Noon + 2L * 60L * 60L * 1000L,
        title = title,
        description = description,
    )

    private companion object {
        const val SourceId = 1L

        /** A kickoff time; every programme above sits inside the matcher's t-2h … t+4h window. */
        const val Noon = 1_760_000_000_000L
    }
}

/**
 * An in-memory stand-in for the two DAOs, implementing only the handful of queries the matcher
 * issues and returning `null` for everything else (each call site is `runCatching`-guarded, so an
 * un-implemented method is simply "no rows"). Hand-writing all ~70 [ChannelDao] members would bury
 * the test's intent, so a [Proxy] fills the rest.
 */
private class FakeStore(
    private val channels: List<ChannelEntity>,
    private val programmes: List<EpgProgrammeEntity>,
) {
    val channelDao: ChannelDao = proxy(ChannelDao::class.java) { name, args ->
        when (name) {
            "searchList" -> {
                val query = args!![0] as String
                val sources = (args[1] as List<*>).filterIsInstance<Long>()
                channels.filter { it.sourceId in sources && it.name.contains(query, ignoreCase = true) }
            }
            "byEpgKeys" -> {
                val sources = (args!![0] as List<*>).filterIsInstance<Long>()
                val keys = (args[1] as List<*>).filterIsInstance<String>()
                    .map { it.trim().lowercase() }
                channels.filter { channel -> channel.sourceId in sources && channel.epgChannelId?.trim()?.lowercase() in keys }
            }
            "getById" -> channels.firstOrNull { it.id == args!![0] as Long }
            else -> null
        }
    }

    val epgDao: EpgDao = proxy(EpgDao::class.java) { name, args ->
        when (name) {
            // `title LIKE '%query%'`, case-insensitive for ASCII as SQLite's LIKE is.
            "searchTitles" -> matches(args!![0] as String, includeDescription = false)
            // `title LIKE '%query%' OR description LIKE '%query%'`.
            "searchGuide" -> matches(args!![0] as String, includeDescription = true)
            "programmesForChannel" -> {
                val key = (args!![0] as String).trim().lowercase()
                programmes.filter { it.epgChannelId.trim().lowercase() == key }
            }
            else -> null
        }
    }

    private fun matches(query: String, includeDescription: Boolean): List<EpgProgrammeEntity> {
        val needle = query.lowercase()
        return programmes.filter { programme ->
            programme.title.lowercase().contains(needle) ||
                (includeDescription && programme.description?.lowercase()?.contains(needle) == true)
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> proxy(type: Class<T>, handler: (String, Array<Any?>?) -> Any?): T =
    Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
        when (method.name) {
            "toString" -> "Fake${type.simpleName}"
            "hashCode" -> System.identityHashCode(instance)
            "equals" -> instance === args?.firstOrNull()
            else -> handler(method.name, args)
        }
    } as T
