package io.grokify.os.apps.companion

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionMovementAgentTest {
    @Test
    fun wantsMotion_detectsWaveAndPose() {
        assertTrue(CompanionMovementAgent.wantsMotion("wave at me"))
        assertTrue(CompanionMovementAgent.wantsMotion("Can you point left?"))
        assertTrue(CompanionMovementAgent.wantsMotion("raise your hand"))
        assertTrue(CompanionMovementAgent.wantsMotion("look at me"))
        assertFalse(CompanionMovementAgent.wantsMotion("what's the weather?"))
        assertFalse(CompanionMovementAgent.wantsMotion(""))
    }

    @Test
    fun parsePlan_directJson() {
        val raw = """
            {"ok":true,"intent":"wave","frames":[
              {"at_ms":0,"right":{"x":0.2,"y":1.1,"z":0.3},"hold_sec":0.5},
              {"at_ms":400,"right":{"x":0.3,"y":1.15,"z":0.35},"hold_sec":0.4}
            ]}
        """.trimIndent()
        val plan = CompanionMovementAgent.parsePlan(raw)
        assertNotNull(plan)
        assertEquals(2, plan!!.optJSONArray("frames")!!.length())
        assertEquals("wave", plan.optString("intent"))
    }

    @Test
    fun parsePlan_fencedAndProse() {
        val raw = """
            Sure, here is the plan:
            ```json
            {"frames":[{"at_ms":0,"left":{"x":0,"y":1,"z":0.2},"hold_sec":0.5}]}
            ```
        """.trimIndent()
        val plan = CompanionMovementAgent.parsePlan(raw)
        assertNotNull(plan)
        assertEquals(1, plan!!.optJSONArray("frames")!!.length())
    }

    @Test
    fun parsePlan_singlePoseShorthand() {
        val plan = CompanionMovementAgent.parsePlan(
            """{"right":{"x":0.1,"y":1.0,"z":0.2},"hold_sec":1.2}""",
        )
        assertNotNull(plan)
        assertEquals(1, plan!!.optJSONArray("frames")!!.length())
        val f0 = plan.optJSONArray("frames")!!.getJSONObject(0)
        assertTrue(f0.has("right"))
    }

    @Test
    fun parsePlan_emptyFails() {
        assertNull(CompanionMovementAgent.parsePlan(""))
        assertNull(CompanionMovementAgent.parsePlan("no json here"))
        assertNull(CompanionMovementAgent.parsePlan("""{"ok":true}"""))
    }

    @Test
    fun compactBodyState_keepsMeasurementsDropsSchema() {
        val full = JSONObject()
            .put("ok", true)
            .put("space", "hips_local")
            .put("loaded", true)
            .put("vr", JSONObject().put("rest", JSONObject().put("left", JSONObject().put("x", 1))))
            .put("arm_reach", JSONObject().put("left", JSONObject().put("max_reach", 0.55)))
            .put(
                "bones",
                JSONObject().put(
                    "leftHand",
                    JSONObject()
                        .put("name", "Left Hand")
                        .put("local", JSONObject().put("x", 0.1))
                        .put("world", JSONObject().put("x", 9)),
                ),
            )
            .put("control_schema", JSONObject().put("examples", "drop_me"))
            .put(
                "environment",
                JSONObject()
                    .put("camera_hips_local", JSONObject().put("z", 1.5))
                    .put("floor_y", 0),
            )
        val c = CompanionMovementAgent.compactBodyState(full)
        assertTrue(c.has("vr"))
        assertTrue(c.has("arm_reach"))
        assertTrue(c.has("bones"))
        assertFalse(c.has("control_schema"))
        val hand = c.getJSONObject("bones").getJSONObject("leftHand")
        assertTrue(hand.has("local"))
        assertFalse(hand.has("world"))
        val env = c.getJSONObject("environment")
        assertTrue(env.has("camera_hips_local"))
        assertFalse(env.has("floor_y"))
    }

    @Test
    fun movementSystemPrompt_mentionsJsonOnly() {
        val p = CompanionMovementAgent.movementSystemPrompt()
        assertTrue(p.contains("JSON"))
        assertTrue(p.contains("frames"))
        assertTrue(p.contains("hips-local") || p.contains("hips_local") || p.contains("wrist"))
    }

    @Test
    fun matchTemplate_wavePointNod() {
        val w = CompanionMovementAgent.matchTemplate("wave left hand", "wave your left hand")
        assertNotNull(w)
        assertEquals("wave", w!!.first)
        assertEquals("left", w.second)

        val p = CompanionMovementAgent.matchTemplate("point at me", "point at me")
        assertNotNull(p)
        assertEquals("point", p!!.first)

        val n = CompanionMovementAgent.matchTemplate("nod yes", "")
        assertNotNull(n)
        assertEquals("nod", n!!.first)

        val novel = CompanionMovementAgent.matchTemplate("hold a tray out in front", "")
        assertNull(novel)
    }

    @Test
    fun matchTemplate_explicitTemplateId() {
        val t = CompanionMovementAgent.matchTemplate("template:wave_right", "")
        assertNotNull(t)
        assertEquals("wave_right", t!!.first)
        assertEquals("right", t.second)
    }
}
