package io.legado.app.model

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.legado.app.data.entities.BookSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

object SourceAiAnalyzer {

    data class Decision(
        val url: String,
        val status: String,
        val reason: String
    )

    fun analyzeInvalidSources(sources: List<BookSource>): Result<List<Decision>> = runCatching {
        require(AiBgMusic.modelUrl.isNotBlank()) { "请先在 AI 背景音乐设置里填写模型地址" }
        require(AiBgMusic.modelName.isNotBlank()) { "请先在 AI 背景音乐设置里填写模型名" }

        val sourceItems = sources.map {
            mapOf(
                "name" to it.bookSourceName,
                "url" to it.bookSourceUrl,
                "group" to it.bookSourceGroup.orEmpty(),
                "respondTime" to it.respondTime,
                "comment" to it.bookSourceComment.orEmpty().take(400),
                "hasSearch" to !it.searchUrl.isNullOrBlank(),
                "hasLogin" to !it.loginUrl.isNullOrBlank(),
                "hasExplore" to !it.exploreUrl.isNullOrBlank()
            )
        }
        val userPrompt = """
            你是开源阅读书源维护助手。请根据书源校验分组、响应时间和错误信息，判断哪些书源应该筛为失效。
            只输出 JSON，不要 Markdown，不要解释。
            JSON 格式：{"items":[{"url":"原书源URL","status":"invalid|suspect|ok","reason":"简短原因"}]}

            判断规则：
            - 搜索失效、目录失效、正文失效、发现失效、网站失效、js失效，通常判 invalid。
            - 校验超时、网络波动、需要登录、被风控、偶发 HTTP 错误，优先判 suspect。
            - 没有失效分组、只是需要登录或响应慢，不要判 invalid。
            - url 必须原样复制输入里的 url。

            输入书源：
            ${GSON.toJson(sourceItems)}
        """.trimIndent()
        val body = GSON.toJson(
            mapOf(
                "model" to AiBgMusic.modelName.trim(),
                "messages" to listOf(
                    mapOf("role" to "system", "content" to "你只返回可解析 JSON。"),
                    mapOf("role" to "user", "content" to userPrompt)
                ),
                "temperature" to 0,
                "max_tokens" to 2500
            )
        ).toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(normalizeChatCompletionsUrl(AiBgMusic.modelUrl))
            .apply {
                if (AiBgMusic.modelKey.isNotBlank()) {
                    header("Authorization", normalizeBearerToken(AiBgMusic.modelKey))
                }
            }
            .post(body)
            .build()
        val client = okHttpClient.newBuilder()
            .callTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
        val responseText = client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("AI 请求失败 HTTP ${response.code}，${text.take(300)}")
            }
            text
        }
        parseDecisions(extractChatContent(responseText))
            .filter { decision -> sources.any { it.bookSourceUrl == decision.url } }
    }

    fun localDecisions(sources: List<BookSource>, reasonPrefix: String? = null): List<Decision> {
        return sources.mapNotNull { source ->
            val groups = source.bookSourceGroup.orEmpty()
            val invalidGroups = source.getInvalidGroupNames()
            val status = when {
                invalidGroups.contains("校验超时") -> "suspect"
                invalidGroups.isNotBlank() -> "invalid"
                groups.contains("AI疑似失效") -> "suspect"
                else -> "ok"
            }
            if (status == "ok") return@mapNotNull null
            val reason = listOfNotNull(
                reasonPrefix,
                invalidGroups.ifBlank { null },
                source.bookSourceComment?.lineSequence()?.firstOrNull()
            ).joinToString("；").take(160)
            Decision(source.bookSourceUrl, status, reason.ifBlank { "根据本地校验分组判断" })
        }
    }

    private fun parseDecisions(contentText: String): List<Decision> {
        jsonCandidates(contentText).forEach { candidate ->
            runCatching {
                return jsonElementToDecisions(JsonParser.parseString(candidate))
            }
        }
        return emptyList()
    }

    private fun jsonElementToDecisions(element: JsonElement): List<Decision> {
        val array = when {
            element.isJsonArray -> element.asJsonArray
            element.isJsonObject -> {
                val obj = element.asJsonObject
                when {
                    obj["items"]?.isJsonArray == true -> obj["items"].asJsonArray
                    obj["sources"]?.isJsonArray == true -> obj["sources"].asJsonArray
                    obj["data"]?.isJsonArray == true -> obj["data"].asJsonArray
                    else -> JsonArray().apply { add(element) }
                }
            }
            else -> JsonArray()
        }
        return array.mapNotNull { item ->
            runCatching {
                val obj = item.asJsonObject
                Decision(
                    url = obj.stringValue("url", "bookSourceUrl", "sourceUrl"),
                    status = obj.stringValue("status", "state", "result").lowercase(),
                    reason = obj.stringValue("reason", "why", "message")
                )
            }.getOrNull()
        }.filter { it.url.isNotBlank() && it.status in setOf("invalid", "suspect", "ok") }
    }

    private fun jsonCandidates(raw: String): List<String> {
        val clean = raw.replace("```json", "```", ignoreCase = true).trim()
        val list = arrayListOf<String>()
        if ("```" in clean) {
            clean.split("```")
                .map { it.trim() }
                .filter { it.startsWith("[") || it.startsWith("{") }
                .forEach { list.add(it) }
        }
        list.add(clean)
        val objectStart = clean.indexOf('{')
        val objectEnd = clean.lastIndexOf('}')
        if (objectStart >= 0 && objectEnd > objectStart) {
            list.add(clean.substring(objectStart, objectEnd + 1))
        }
        val arrayStart = clean.indexOf('[')
        val arrayEnd = clean.lastIndexOf(']')
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            list.add(clean.substring(arrayStart, arrayEnd + 1))
        }
        return list.distinct()
    }

    private fun extractChatContent(responseText: String): String {
        return runCatching {
            val choice = JsonParser.parseString(responseText)
                .asJsonObject["choices"].asJsonArray[0]
                .asJsonObject
            val message = choice["message"]?.asJsonObject
            message?.stringValue("content")
                ?.ifBlank { message.stringValue("reasoning_content") }
                .orEmpty()
        }.getOrDefault(responseText)
    }

    private fun com.google.gson.JsonObject.stringValue(vararg keys: String): String {
        keys.forEach { key ->
            val value = get(key)
            if (value != null && value.isJsonPrimitive) return value.asString.orEmpty()
        }
        return ""
    }

    private fun normalizeChatCompletionsUrl(rawUrl: String): String {
        var clean = rawUrl.trim().trimEnd('/')
        if (clean.isBlank()) return clean
        if (!clean.startsWith("http://", ignoreCase = true) &&
            !clean.startsWith("https://", ignoreCase = true)
        ) {
            clean = "https://$clean"
        }
        val lower = clean.lowercase()
        return when {
            lower.endsWith("/chat/completions") -> clean
            lower.endsWith("/responses") -> clean
            lower.matches(Regex(".*/v\\d+(\\.\\d+)?$")) -> "$clean/chat/completions"
            else -> "$clean/v1/chat/completions"
        }
    }

    private fun normalizeBearerToken(rawKey: String): String {
        val key = rawKey.trim()
        return if (key.startsWith("Bearer ", ignoreCase = true)) key else "Bearer $key"
    }
}
