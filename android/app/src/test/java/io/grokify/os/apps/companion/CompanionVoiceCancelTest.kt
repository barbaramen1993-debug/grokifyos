package io.grokify.os.apps.companion

import io.grokify.os.apps.GrokAssistantVoiceClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: xAI returns "Cancellation failed: no active response found" when
 * response.cancel races the server's auto-cancel on barge-in. Companion must
 * treat that as benign (same as Grok Assistant).
 */
class CompanionVoiceCancelTest {
    @Test
    fun benignCancelError_detectsXaiNoActiveResponse() {
        val raw =
            """{"message":"Cancellation failed: no active response found","type":"invalid_request_error"}"""
        assertTrue(GrokAssistantVoiceClient.isBenignRealtimeCancelError(raw))
        assertTrue(
            GrokAssistantVoiceClient.isBenignRealtimeCancelError(
                "Cancellation failed: no active response found",
            ),
        )
        assertTrue(
            GrokAssistantVoiceClient.isBenignRealtimeCancelError(
                "response_cancel_not_active",
            ),
        )
        assertFalse(GrokAssistantVoiceClient.isBenignRealtimeCancelError("rate limit exceeded"))
        assertFalse(GrokAssistantVoiceClient.isBenignRealtimeCancelError("connection failed"))
    }
}
