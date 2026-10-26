package com.ryderdavid.voicebar.voice

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Cleanup with GPT-6 Luna through OpenAI's Responses API: the same instructions, the same reply
 * format and the same checks as Claude's ([ClaudeCleanupRequest]), so either can be chosen.
 */
object OpenAICleanupRequest {
    const val LUNA = "gpt-6-luna"

    fun body(cleaned: String, raw: String, model: String, dictionary: String = "", screen: String = "", children: Boolean = false, around: Around? = null, paused: Boolean = false): String = JSONObject()
        .put("model", model)
        .put("instructions", ClaudeCleanupRequest.system(dictionary, screen, children, around))
        .put("input", ClaudeCleanupRequest.user(cleaned, raw, screen, around, paused))
        // a light edit: no reasoning at all ("low" was still slow enough to time out); Luna supports "none"
        .put("reasoning", JSONObject().put("effort", "none"))
        .put("max_output_tokens", 2048)
        .put("store", false)
        .toString()

    /** The reply's text: every output_text of the message items (never assume it's output[0]). */
    fun text(response: String): String? = runCatching {
        val out = JSONObject(response).getJSONArray("output")
        val sb = StringBuilder()
        for (i in 0 until out.length()) {
            val item = out.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val c = content.optJSONObject(j) ?: continue
                if (c.optString("type") == "output_text") sb.append(c.optString("text"))
            }
        }
        sb.toString()
    }.getOrNull()?.ifBlank { null }

    /** The response's status (completed, incomplete…) and, when incomplete, why. */
    fun status(response: String): String? = runCatching {
        val o = JSONObject(response)
        val why = o.optJSONObject("incomplete_details")?.optString("reason")?.ifBlank { null }
        o.optString("status").ifBlank { null }?.let { s -> why?.let { "$s: $it" } ?: s }
    }.getOrNull()

    fun usage(response: String): Pair<Long, Long>? = runCatching {
        val u = JSONObject(response).getJSONObject("usage")
        u.optLong("input_tokens") to u.optLong("output_tokens")
    }.getOrNull()
}

class OpenAICleaner(
    private val apiKey: String,
    private val model: String,
    private val client: OkHttpClient,
    private val baseUrl: String = "https://api.openai.com",
    private val dictionary: () -> String = { "" },
    private val screen: () -> String = { "" },
    private val children: () -> Boolean = { false },
    private val around: () -> Around? = { null },
    private val onUsage: ((model: String, input: Long, output: Long) -> Unit)? = null,
) : Cleaner {
    @Volatile override var problem: String? = null
        private set
    @Volatile override var seam = Infill.Seam()
        private set
    override val service = "ChatGPT"

    override fun clean(raw: String): String? = clean("", raw)
    override fun clean(cleaned: String, raw: String): String? = run(cleaned, raw, paused = false)
    // the screen too: a pause's cleanup is often the final text (nothing said after it)
    override fun cleanAtPause(cleaned: String, raw: String): String? = run(cleaned, raw, paused = true)

    private fun run(cleaned: String, raw: String, paused: Boolean): String? =
        twice(paused, service) { once(cleaned, raw, paused) }

    /** One request: the cleaned text, or null with [problem] set and whether asking again may help. */
    private fun once(cleaned: String, raw: String, paused: Boolean): Pair<String?, Boolean> {
        val aroundNow = around()
        val request = Request.Builder()
            .url("$baseUrl/v1/responses")
            .header("Authorization", "Bearer $apiKey")
            .post(OpenAICleanupRequest.body(cleaned, raw, model, dictionary(), screen(), children(), aroundNow, paused = paused)
                .toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string()
            body?.let { OpenAICleanupRequest.usage(it) }?.let { (i, o) -> onUsage?.invoke(model, i, o) }
            problem = null
            seam = Infill.Seam()
            if (!response.isSuccessful) {
                problem = "ChatGPT answered ${response.code}" + (ClaudeCleanupRequest.errorMessage(body)?.let { ": $it" } ?: "")
                return null to passing(response.code)
            }
            val parsed = OpenAICleanupRequest.text(body ?: "")?.let { ClaudeCleanupRequest.parseText(it) }
                ?.let { ClaudeCleanupRequest.trimAround(it, aroundNow) }?.ifBlank { null }
            when {
                parsed == null -> { problem = "ChatGPT's reply wasn't the cleaned text"; notUsed(body); null to true }
                !ClaudeCleanupRequest.plausible(parsed, cleaned, raw) -> { problem = "ChatGPT's reply was far longer than what you said"; notUsed(body); null to true }
                else -> parsed.also { if (aroundNow != null) seam = Infill.seam(OpenAICleanupRequest.text(body ?: "")) } to false
            }
        }
    }

    /** What the model said instead, for the log: the start of it and the response's status. */
    private fun notUsed(body: String?) = com.ryderdavid.voicebar.AppLog.log("cleanup",
        "ChatGPT's reply, not used (status=${body?.let { OpenAICleanupRequest.status(it) } ?: "?"}): " +
            com.ryderdavid.voicebar.AppLog.excerpt(body?.let { OpenAICleanupRequest.text(it) }, 160))
}
