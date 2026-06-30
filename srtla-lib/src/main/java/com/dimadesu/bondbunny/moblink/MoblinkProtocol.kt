package com.dimadesu.bondbunny.moblink

import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Moblink wire protocol — streamer side.
 *
 * This mirrors the canonical Moblink JSON protocol used by the Moblink Android relay app,
 * Moblin (iOS) and moblink-rust. Bond Bunny acts as the *streamer*: it sends
 * [MessageToRelay] and receives [MessageToStreamer].
 *
 * Messages are externally-tagged single-key objects, e.g. `{"hello":{...}}`. This is achieved
 * by modelling each message as a class of nullable optional fields and omitting nulls during
 * serialization (see [moblinkJson]).
 */
const val MOBLINK_API_VERSION = "1.0"

/** mDNS / NSD service type advertised by streamers and discovered by relays. */
const val MOBLINK_SERVICE_TYPE = "_moblink._tcp"

/** Shared JSON configured to match the Moblink wire format. */
internal val moblinkJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

/** Empty "present" marker object, serialized as `{}`. */
@Serializable
data class Present(val dummy: Boolean? = null)

@Serializable
data class Result(val ok: Present? = null, val wrongPassword: Present? = null)

@Serializable
data class Authentication(val challenge: String, val salt: String)

@Serializable
data class StartTunnelRequest(val address: String, val port: Int)

@Serializable
data class RequestData(
    val startTunnel: StartTunnelRequest? = null,
    val status: Present? = null,
)

@Serializable
data class Hello(val apiVersion: String, val authentication: Authentication)

@Serializable
data class Identified(val result: Result)

@Serializable
data class Request(val id: Int, val data: RequestData)

@Serializable
data class StartTunnelResponse(val port: Int)

@Serializable
enum class ThermalState {
    @SerialName("white") WHITE,
    @SerialName("yellow") YELLOW,
    @SerialName("red") RED,
}

@Serializable
data class StatusResponse(
    val batteryPercentage: Int? = null,
    val thermalState: ThermalState? = null,
)

@Serializable
data class ResponseData(
    val startTunnel: StartTunnelResponse? = null,
    val status: StatusResponse? = null,
)

@Serializable
data class Identify(val id: String, val name: String, val authentication: String)

@Serializable
data class Response(val id: Int, val result: Result, val data: ResponseData? = null)

/** Messages the streamer sends to a relay. */
@Serializable
data class MessageToRelay(
    val hello: Hello? = null,
    val identified: Identified? = null,
    val request: Request? = null,
) {
    fun toJson(): String = moblinkJson.encodeToString(this)
}

/** Messages a relay sends to the streamer. */
@Serializable
data class MessageToStreamer(
    val identify: Identify? = null,
    val response: Response? = null,
) {
    companion object {
        fun fromJson(text: String): MessageToStreamer = moblinkJson.decodeFromString(text)
    }
}

/**
 * Challenge–response authentication hash, identical across all Moblink implementations:
 * `base64(sha256( base64(sha256(password + salt)) + challenge ))`.
 */
fun moblinkCalculateAuthentication(password: String, salt: String, challenge: String): String {
    val first = base64(sha256("$password$salt"))
    return base64(sha256("$first$challenge"))
}

private fun sha256(value: String): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

private fun base64(bytes: ByteArray): String =
    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
