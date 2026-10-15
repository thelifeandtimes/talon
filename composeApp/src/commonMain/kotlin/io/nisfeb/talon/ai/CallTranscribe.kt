package io.nisfeb.talon.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.nisfeb.talon.util.ioDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Cloud speech-to-text for a recorded party line, one speaker's audio
 * at a time.
 *
 * The SFU gives us one audio track per speaker, so each speaker's WAV
 * is transcribed on its own — that is where the speaker attribution
 * comes from, with no diarization model. The caller tags the returned
 * segments with the speaker and merges the speakers by time.
 *
 * OpenAI's `/v1/audio/transcriptions` shape (verbose_json), which Groq
 * and other OpenAI-compatible hosts also serve. The endpoint and key
 * are passed in rather than read from settings so this stays a pure
 * transport with a testable parser.
 *
 * ponytail: cloud, not on-device — the user chose it for cross-platform
 * reach and accepted that call audio leaves the ship (announced to the
 * room + opt-in at the UI). On-device Whisper is the upgrade path.
 */
object CallTranscribe {

    /** One transcribed span: milliseconds from the start of the clip,
     *  and the text. Speaker is filled in by the caller. */
    data class Segment(val startMs: Long, val text: String)

    private val json = Json { ignoreUnknownKeys = true }

    /** A 10-minute chunk of speech can legitimately take a while to
     *  come back, but not forever. */
    private const val REQUEST_TIMEOUT_MS = 180_000L
    private const val SOCKET_TIMEOUT_MS = 60_000L

    /**
     * Transcribe one WAV clip. Returns time-ordered segments.
     *
     * @param endpoint full URL, e.g. https://api.openai.com/v1/audio/transcriptions
     * @param model    e.g. "whisper-1"
     * @throws IllegalStateException on a non-2xx response.
     */
    suspend fun transcribe(
        http: HttpClient,
        endpoint: String,
        apiKey: String,
        model: String,
        wav: ByteArray,
    ): List<Segment> = withContext(ioDispatcher) {
        val res = http.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            // The client leaves requests untimed by default, so a host
            // that accepted the upload and then went quiet left the
            // "Transcribing…" dialog spinning with no way out.
            timeout {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = SOCKET_TIMEOUT_MS
            }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("model", model)
                        append("response_format", "verbose_json")
                        append(
                            "file",
                            wav,
                            Headers.build {
                                append(HttpHeaders.ContentType, "audio/wav")
                                append(HttpHeaders.ContentDisposition, "filename=\"line.wav\"")
                            },
                        )
                    },
                ),
            )
        }
        if (!res.status.isSuccess()) {
            error("transcription failed (HTTP ${res.status.value})")
        }
        parse(res.bodyAsText())
    }

    /**
     * Parse a verbose_json body into segments. Falls back to a single
     * segment carrying the whole `text` when no per-segment timings are
     * present (some hosts omit them). Split out so it is unit-testable
     * without a network call.
     */
    fun parse(body: String): List<Segment> {
        require(body.length <= 8 * 1024 * 1024) { "Transcription response exceeds limit" }
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: error("Invalid transcription JSON")
        require(obj["text"]?.jsonPrimitive?.isString == true) { "Transcription omitted text" }
        val segs = obj["segments"]?.jsonArray
        if (segs != null && segs.isNotEmpty()) {
            return segs.mapNotNull { el ->
                val s = el.jsonObject
                val text = s["text"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (text.isEmpty()) return@mapNotNull null
                val startSec = s["start"]?.jsonPrimitive?.doubleOrNull
                    ?: error("Transcription segment omitted start time")
                require(startSec.isFinite() && startSec >= 0 && startSec <= Long.MAX_VALUE / 1000.0) { "Invalid transcription start time" }
                s["end"]?.let { end ->
                    val endSec = end.jsonPrimitive.doubleOrNull ?: error("Invalid transcription end time")
                    require(endSec.isFinite() && endSec >= startSec) { "Invalid transcription end time" }
                }
                Segment((startSec * 1000).toLong(), text)
            }.sortedBy { it.startMs }
        }
        val whole = obj["text"]?.jsonPrimitive?.content?.trim().orEmpty()
        return if (whole.isEmpty()) emptyList() else listOf(Segment(0, whole))
    }
}
