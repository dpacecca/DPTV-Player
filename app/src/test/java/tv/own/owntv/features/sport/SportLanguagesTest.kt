package tv.own.owntv.features.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure language logic behind the Sports-Hub channel filter: country-tag parsing, the
 * country → language mapping and the "never wholly empty" filter fallback. No Android, no DAO.
 */
class SportLanguagesTest {

    // --- countryCodeOf: the tag forms a provider actually writes ---

    @Test
    fun colonBarAndSlashTagsAreRecognised() {
        assertEquals("DE", SportLanguages.countryCodeOf("DE: SKY SPORT BUNDESLIGA 1 HD"))
        assertEquals("UK", SportLanguages.countryCodeOf("UK| SKY SPORTS MAIN EVENT"))
        assertEquals("UK", SportLanguages.countryCodeOf("UK | Sky Sports"))
        assertEquals("FR", SportLanguages.countryCodeOf("FR/CANAL+ SPORT"))
        assertEquals("SG", SportLanguages.countryCodeOf("SG: HUB PREMIER 1"))
    }

    @Test
    fun namesWithoutATagHaveNoCountry() {
        assertNull(SportLanguages.countryCodeOf("Sky Sport Bundesliga 1"))
        // A parenthesised marker is not a country tag: "AU" is not followed by a separator.
        assertNull(SportLanguages.countryCodeOf("AU (STAN 01) | Coventry City v Newcastle United"))
    }

    // --- mapping ---

    @Test
    fun theUsersCountryGroupsMapToTheirLanguage() {
        assertEquals(SportLanguage.GERMAN, SportLanguages.languageOfChannel("DE: SKY SPORT AUSTRIA 1 HD"))
        assertEquals(SportLanguage.GERMAN, SportLanguages.languageOfChannel("AT: SKY SPORT AUSTRIA 1"))
        assertEquals(SportLanguage.GERMAN, SportLanguages.languageOfChannel("CH: SRF ZWEI"))
        assertEquals(SportLanguage.ENGLISH, SportLanguages.languageOfChannel("UK: SKY SPORTS PREMIER LEAGUE"))
        assertEquals(SportLanguage.ENGLISH, SportLanguages.languageOfChannel("US: NBC SPORTS"))
        assertEquals(SportLanguage.ENGLISH, SportLanguages.languageOfChannel("SG: HUB PREMIER 1"))
        assertEquals(SportLanguage.SPANISH, SportLanguages.languageOfChannel("ES: MOVISTAR LALIGA"))
        assertEquals(SportLanguage.PORTUGUESE, SportLanguages.languageOfChannel("PT: SPORT TV 1"))
        assertEquals(SportLanguage.FRENCH, SportLanguages.languageOfChannel("FR: CANAL+ FOOT"))
    }

    @Test
    fun anUnknownCountryMapsToNoLanguage() {
        assertNull(SportLanguages.languageOfChannel("IT: SKY SPORT ARENA"))
        assertNull(SportLanguages.languageOfChannel("AL: SUPER SPORT 3"))
    }

    // --- filter ---

    @Test
    fun anEmptySelectionKeepsEverything() {
        val matches = listOf(match("DE: SKY"), match("IT: SKY"), match("Untagged"))
        assertEquals(matches, SportLanguages.filter(matches, emptySet()))
    }

    @Test
    fun onlyTheSelectedLanguagesSurvive() {
        val matches = listOf(
            match("DE: SKY SPORT AUSTRIA 1 HD"),
            match("UK: SKY SPORTS PREMIER LEAGUE"),
            match("IT: SKY SPORT ARENA"),
            match("AL: SUPER SPORT 3"),
            match("VIP: CANAL+ PREMIER LEAGUE"),
            match("UNPREFIXED PROVIDER FEED"),
        )
        val kept = SportLanguages.filter(matches, setOf(SportLanguage.GERMAN, SportLanguage.ENGLISH))
        assertEquals(
            listOf("DE: SKY SPORT AUSTRIA 1 HD", "UK: SKY SPORTS PREMIER LEAGUE"),
            kept.map { it.channelName },
        )
    }

    @Test
    fun theFilterNeverLeavesTheListEmpty() {
        val matches = listOf(match("IT: SKY SPORT ARENA"), match("AL: SUPER SPORT 3"))
        assertEquals(matches, SportLanguages.filter(matches, setOf(SportLanguage.GERMAN)))
    }

    @Test
    fun exactEventChannelsSurviveInAnotherLanguage() {
        val matches = listOf(
            match("FR: MAX PPV 21", isExact = true),
            match("DE: SKY SPORT TENNIS", isExact = false),
            match("IT: SKY SPORT ARENA", isExact = false),
        )
        val kept = SportLanguages.filter(matches, setOf(SportLanguage.GERMAN))
        assertEquals(
            listOf("FR: MAX PPV 21", "DE: SKY SPORT TENNIS"),
            kept.map { it.channelName },
        )
    }

    private fun match(name: String, isExact: Boolean = false) =
        ChannelMatch(channelId = 1L, channelName = name, logoUrl = null, epgTitle = null, score = 1, isExact = isExact)

    @Test
    fun everyLanguageHasADisplayOrder() {
        assertTrue(SportLanguages.order.containsAll(SportLanguage.values().toList()))
    }
}
