package io.grokify.os.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyDjBanterTextTest {

    private fun words(n: Int): String = (1..n).joinToString(" ") { "word$it" }

    @Test
    fun spokenLine_acceptsLongCustomNewsHandoff() {
        val line = "Quick national desk: the Senate just cleared a stopgap funding bill, " +
            "Florida is still digging out after last night's storms, and grocery prices " +
            "are the talk on every morning show from Denver to Atlanta this week. " +
            "That's the pulse before we change the record. " +
            "Rolling out of Morgan Wallen — here's Fast Car by Luke Combs, " +
            "a cover that still hits like a late-night drive with the windows down " +
            "and a little hope after a heavy news block."
        assertTrue("len=${line.length}", line.length > 400)
        assertTrue(looksLikeSpokenBanter(line))
        assertEquals(line, sanitizeSpokenBanter(line))
    }

    @Test
    fun spokenLine_acceptsSeventyWordRadioCopy() {
        val line = words(70)
        assertTrue(line.length > 400)
        assertTrue("70-word line (${line.length} chars) must stay on-air", looksLikeSpokenBanter(line))
    }

    @Test
    fun spokenLine_keepsLetMeRadioOpener() {
        val line = "Let me slide into Fast Car by Luke Combs after that Wallen cut."
        assertFalse(isProcessNarrationSentence(line))
        assertTrue(looksLikeSpokenBanter(line))
        assertTrue(sanitizeSpokenBanter(line).contains("Let me slide"))
    }

    @Test
    fun spokenLine_keepsNewsVerifyAndToolWording() {
        val line = "Officials are verifying that the storm hit the Gulf overnight, " +
            "and the Fed just rolled out a new tool for inflation. " +
            "Up next, Last Night by Morgan Wallen."
        assertTrue(looksLikeSpokenBanter(line))
        val kept = sanitizeSpokenBanter(line)
        assertTrue(kept.contains("verifying that"))
        assertTrue(kept.contains("tool for inflation"))
        assertTrue(kept.contains("Morgan Wallen"))
    }

    @Test
    fun spokenLine_stripsRealProcessTalkOnly() {
        val leaked = "Checking for a real public tidbit before writing the DJ line. " +
            "Up next, Fast Car by Luke Combs."
        val kept = sanitizeSpokenBanter(leaked)
        assertFalse(kept.contains("public tidbit"))
        assertFalse(kept.contains("writing the DJ line"))
        assertTrue(kept.contains("Fast Car"))
        assertTrue(looksLikeSpokenBanter(kept))
    }

    @Test
    fun spokenLine_rejectsBareJson() {
        assertFalse(looksLikeSpokenBanter("""{"banter":"hi"}"""))
        assertFalse(looksLikeSpokenBanter("```json\n{}\n```"))
    }

    @Test
    fun chooseSpoken_prefersAiOverLocalFallback() {
        val ai = "National desk: Congress is still fighting over that stopgap bill. " +
            "Parking that Wallen cut — here's Fast Car by Luke Combs."
        val local = "Finishing up with some Morgan Wallen. Up next, Luke Combs — here's Fast Car."
        assertEquals(ai, chooseSpokenBanter(ai, local))
    }

    @Test
    fun chooseSpoken_usesLocalOnlyWhenAiIsUnusable() {
        val local = "Finishing up with some Morgan Wallen. Up next, Luke Combs — here's Fast Car."
        assertEquals(local, chooseSpokenBanter(null, local))
        assertEquals(local, chooseSpokenBanter("   ", local))
        assertEquals(local, chooseSpokenBanter("```json\n{}\n```", local))
        assertEquals(local, chooseSpokenBanter("Checking for a real public tidbit.", local))
    }

    @Test
    fun hostAiCompletion_usesTimeoutPartial() {
        val env = org.json.JSONObject()
            .put("ok", false)
            .put("error", "timeout")
            .put("partial", "Parking that Wallen cut. Here's Fast Car by Luke Combs.")
        assertEquals(
            "Parking that Wallen cut. Here's Fast Car by Luke Combs.",
            hostAiCompletionText(env),
        )
    }

    @Test
    fun hostAiCompletion_prefersOkText() {
        val env = org.json.JSONObject()
            .put("ok", true)
            .put("text", "Here's Fast Car by Luke Combs.")
            .put("partial", "ignored")
        assertEquals("Here's Fast Car by Luke Combs.", hostAiCompletionText(env))
    }

    @Test
    fun extractResearchJson_ignoresThinkingBraces() {
        val raw = """
            I'll fill {custom_notes} and {news} after a search.
            Here is the pack:
            {"custom_notes":["Senate cleared a stopgap funding bill this week"],"news":["Gulf coast storms still a national story"]}
        """.trimIndent()
        val json = extractDjJsonObject(raw)
        assertNotNull(json)
        assertEquals(
            "Senate cleared a stopgap funding bill this week",
            json!!.optJSONArray("custom_notes")?.optString(0),
        )
        assertEquals(
            "Gulf coast storms still a national story",
            json.optJSONArray("news")?.optString(0),
        )
    }

    @Test
    fun parseResearchPack_requiresCustomNotesNotJustFocusLabel() {
        val json = org.json.JSONObject(
            """{"facts":["recorded in 2013"],"custom_notes":["Senate cleared a stopgap bill"],"news":["Florida still digging out"]}""",
        )
        val bullets = formatDjResearchBullets(
            json = json,
            angleLabels = "USA news (custom) + Album / song facts",
            customAngleLabels = listOf("USA news"),
            nextAlbum = "Dangerous",
            nextYear = "2021",
        )
        assertTrue(bullets.any { it.startsWith("Custom (USA news):") })
        assertTrue(bullets.any { it.startsWith("News:") })
        assertTrue(researchHasUsableCustomBeat(bullets))
    }

    @Test
    fun researchFocusLineAlone_isNotACustomBeat() {
        val onlyFocus = listOf("Research focus: USA news (custom) + Lyrics & meaning")
        assertFalse(researchHasUsableCustomBeat(onlyFocus))
    }

    @Test
    fun researchPackMissingCustom_isIncompleteWhenCustomRequired() {
        val json = org.json.JSONObject("""{"facts":["recorded in 2013"],"album_facts":["Dangerous, 2021"]}""")
        val bullets = formatDjResearchBullets(
            json = json,
            angleLabels = "USA news (custom)",
            customAngleLabels = listOf("USA news"),
        )
        assertFalse(researchHasUsableCustomBeat(bullets))
        assertTrue(djResearchNeedsCustomRetry(bullets, customAngleLabels = listOf("USA news")))
    }
}
