package io.grokify.os.wearbridge

/**
 * Shared Wearable Data Layer paths between phone host and wear app.
 * Keep in sync with `io.grokify.os.wear.data.PhoneApiKeySync`.
 */
object WearApiKeyPaths {
    /** DataItem path holding the SpaceXAI inference key. */
    const val DATA_SPACEXAI = "/grokify/api/spacexai"

    /** DataItem path holding the host device token (OTA / API auth). */
    const val DATA_DEVICE_TOKEN = "/grokify/api/device_token"

    /** Wear → phone: please re-push the current key. */
    const val MSG_REQUEST_SPACEXAI = "/grokify/api/request_spacexai"

    /** Phone → wear: one-shot key payload (UTF-8 string). */
    const val MSG_PUSH_SPACEXAI = "/grokify/api/push_spacexai"

    /** Wear → phone: please re-push the device token. */
    const val MSG_REQUEST_DEVICE_TOKEN = "/grokify/api/request_device_token"

    /** Phone → wear: one-shot device token payload (UTF-8 string). */
    const val MSG_PUSH_DEVICE_TOKEN = "/grokify/api/push_device_token"

    const val KEY_VALUE = "value"
    const val KEY_UPDATED_AT = "updated_at"

    /** Phone advertises this; watch discovers via CapabilityClient. */
    const val CAPABILITY_HOST = "grokify_host"

    /** Watch advertises this; phone discovers via CapabilityClient. */
    const val CAPABILITY_WEAR = "grokify_wear"
}
