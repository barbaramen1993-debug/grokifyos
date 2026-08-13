package io.grokify.os.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpotifyDjPromptsTest {

    private fun customNews(): DjPromptTemplate = DjPromptTemplate(
        id = "custom_research_news",
        kind = DjPromptKind.Research,
        label = "USA news",
        body = "NEWS: USA headlines this week. City: {{CITY}}.",
        enabled = true,
        builtIn = false,
    )

    private fun customBanter(): DjPromptTemplate = DjPromptTemplate(
        id = "custom_banter_sports",
        kind = DjPromptKind.Banter,
        label = "Sports desk",
        body = "BANTER BIT: Drop one real US sports score or headline. City: {{CITY}}.",
        enabled = true,
        builtIn = false,
    )

    @Test
    fun pickBanter_alwaysIncludesEnabledCustom() {
        val all = DjPromptDefaults.all() + customBanter()
        repeat(40) { seed ->
            val picked = pickBanterTemplates(all, Random(seed.toLong()))
            assertTrue(
                "custom banter missing on seed=$seed picked=${picked.map { it.id }}",
                picked.any { it.id == "custom_banter_sports" },
            )
        }
    }

    @Test
    fun pickBanter_disabledCustomNotForced() {
        val off = customBanter().copy(enabled = false)
        val all = DjPromptDefaults.all() + off
        repeat(20) { seed ->
            val picked = pickBanterTemplates(all, Random(seed.toLong()))
            assertTrue(picked.none { it.id == off.id })
        }
    }

    @Test
    fun collectTalkingPoints_includesCustomResearchAndBanter() {
        val all = DjPromptDefaults.all() + customNews() + customBanter()
        val points = collectBanterTalkingPoints(all, Random(7))
        assertTrue(points.any { it.id == "custom_research_news" })
        assertTrue(points.any { it.id == "custom_banter_sports" })
    }

    @Test
    fun talkingPointsBlock_fillsCityAndMarksRequired() {
        val points = listOf(customNews(), customBanter())
        val block = formatBanterTalkingPointsBlock(points, city = "Denver")
        assertTrue(block.contains("REQUIRED ON-AIR TALKING POINTS"))
        assertTrue(block.contains("USA news"))
        assertTrue(block.contains("Sports desk"))
        assertTrue(block.contains("Denver"))
        assertFalse(block.contains("{{CITY}}"))
        assertTrue(block.contains("generic", ignoreCase = true) || block.contains("that was"))
    }

    @Test
    fun banterUserPrompt_requiresTemplatesInsteadOfOnlyHandoff() {
        val points = listOf(customNews(), customBanter())
        val prompt = buildBanterUserPrompt(
            DjBanterUserPromptInput(
                behaviorLabel = "Default",
                listenerName = "Sam",
                city = "Denver",
                genres = listOf("country"),
                prevCleanTitle = "Last Night",
                prevPrimary = "Morgan Wallen",
                prevSource = "LIVE DJ",
                nextCleanTitle = "Fast Car",
                nextPrimary = "Luke Combs",
                nextSource = "LIVE DJ",
                tracksUntilTalk = 0,
                upcoming = listOf("Tennessee Whiskey" to "Chris Stapleton"),
                research = emptyList(),
                talkingPoints = points,
                unhinged = false,
            ),
        )
        assertTrue(prompt.contains("USA news"))
        assertTrue(prompt.contains("Sports desk"))
        assertTrue(prompt.contains("NEWS: USA headlines"))
        assertTrue(prompt.contains("Denver"))
        assertTrue(
            prompt.contains("REQUIRED ON-AIR TALKING POINTS") ||
                prompt.contains("You MUST cover"),
        )
        // Must not collapse to the old hardcoded-only handoff instruction.
        assertFalse(
            "prompt still forces only was/next:\n$prompt",
            prompt.contains("close out the previous vibe, drop one researched beat"),
        )
        // Empty research must not tell the model to do a pure handoff when templates exist.
        assertFalse(
            "empty research overrode templates:\n$prompt",
            prompt.contains("pure handoff"),
        )
    }

    @Test
    fun merge_seedsBanterBitsAndKeepsEditedBanterSystem() {
        val edited = DjPromptDefaults.banterSystem().copy(
            body = "EDITED BANTER SYSTEM: always mention tacos.",
        )
        val merged = mergePromptTemplates(listOf(edited, customBanter()))
        assertTrue(merged.any { it.kind == DjPromptKind.Banter && it.builtIn })
        val sys = merged.first { it.kind == DjPromptKind.BanterSystem }
        assertEquals("EDITED BANTER SYSTEM: always mention tacos.", sys.body)
        assertTrue(merged.any { it.id == "custom_banter_sports" })
    }

    @Test
    fun banterUserPrompt_focusLineIsNotAFakeCustomBeat() {
        val prompt = buildBanterUserPrompt(
            DjBanterUserPromptInput(
                behaviorLabel = "Default",
                listenerName = "Sam",
                city = "Denver",
                prevCleanTitle = "Last Night",
                prevPrimary = "Morgan Wallen",
                nextCleanTitle = "Fast Car",
                nextPrimary = "Luke Combs",
                research = listOf("Research focus: USA news (custom) + Lyrics & meaning"),
                talkingPoints = listOf(customNews()),
            ),
        )
        assertFalse(
            "focus-only pack must not demand a Custom:/News: beat that does not exist:\n$prompt",
            prompt.contains("CUSTOM ANGLE REQUIRED"),
        )
        assertTrue(prompt.contains("REQUIRED ON-AIR TALKING POINTS"))
        assertTrue(prompt.contains("USA news"))
    }

    @Test
    fun appendTalkingPointsToSystem_keepsEditedRules() {
        val system = appendBanterTalkingPointsToSystem(
            "EDITED RULES: whisper only.",
            formatBanterTalkingPointsBlock(listOf(customNews()), city = "Austin"),
        )
        assertTrue(system.startsWith("EDITED RULES: whisper only."))
        assertTrue(system.contains("USA news"))
        assertTrue(system.contains("Austin"))
    }
}
