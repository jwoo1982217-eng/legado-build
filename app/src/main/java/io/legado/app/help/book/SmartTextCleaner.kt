package io.legado.app.help.book

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.AiBgMusic
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefBoolean
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import splitties.init.appCtx
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

object SmartTextCleaner {

    private const val MAX_CACHE_SIZE = 48
    private const val LONG_PARAGRAPH_TARGET = 280
    private const val LONG_PARAGRAPH_HARD_LIMIT = 520

    private val cleanCache = object : LinkedHashMap<String, String>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    private val domainRegex = Regex(
        """(?i)(https?://|www\.|[a-z0-9][a-z0-9.-]+\.(com|net|org|cn|cc|top|xyz|vip|info|io|me)(/|\b))"""
    )

    private val obviousAdRegex = Regex(
        """(请收藏本站|收藏本站|加入书签|最新网址|最新域名|永久地址|备用网址|换源|章节报错|本章未完|点击下一页|未完待续.*继续阅读|阅读模式|手机用户请浏览|下载.*app|APP下载|客户端|书友群|QQ群|微信公众|公众号|求月票|求推荐票|投推荐票|笔趣阁|顶点小说|无弹窗|防采集)""",
        RegexOption.IGNORE_CASE
    )

    private val suspiciousAdRegex = Regex(
        """(点击|阅读|收藏|书友|章节|缓存|浏览器|地址|域名|APP|下载|广告|书签|月票|推荐票|公众号|QQ群|微信|网站|小说网|最新|客户端)""",
        RegexOption.IGNORE_CASE
    )

    private val splitPunctuation = setOf('。', '！', '？', '!', '?', '；', ';', '…')
    private val closingPunctuation = setOf('”', '’', '」', '』', '）', ')', '】', ']')
    private val enabled get() = appCtx.getPrefBoolean(PreferKey.smartTextCleanEnable, false)
    private val regexAds get() = appCtx.getPrefBoolean(PreferKey.smartTextCleanRegexAds, true)
    private val splitLongParagraph
        get() = appCtx.getPrefBoolean(PreferKey.smartTextCleanSplitLongParagraph, true)
    private val aiAssist get() = appCtx.getPrefBoolean(PreferKey.smartTextCleanAiAssist, false)

    fun clean(book: Book, chapter: BookChapter, content: String): String {
        if (!enabled || content.isBlank()) return content
        val key = cacheKey(book, chapter, content)
        synchronized(cleanCache) {
            cleanCache[key]?.let { return it }
        }
        val cleaned = runCatching {
            cleanInternal(content)
        }.onFailure {
            AppLog.put("智能正文清洗失败，已回退原文\n${it.localizedMessage}", it)
        }.getOrDefault(content)
        synchronized(cleanCache) {
            cleanCache[key] = cleaned
        }
        return cleaned
    }

    fun clearRuntimeCache(book: Book? = null, chapter: BookChapter? = null) {
        synchronized(cleanCache) {
            if (book == null || chapter == null) {
                cleanCache.clear()
            } else {
                val prefix = cachePrefix(book, chapter)
                cleanCache.keys.filter { it.startsWith(prefix) }.forEach { cleanCache.remove(it) }
            }
        }
    }

    private fun cleanInternal(content: String): String {
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        val keptLines = normalized.lineSequence()
            .map { it.trim { ch -> ch.code <= 0x20 || ch == '　' } }
            .filter { it.isNotBlank() }
            .filterNot { regexAds && isObviousAdLine(it) }
            .toList()

        val aiRemoveIndexes = if (aiAssist) {
            aiAdLineIndexes(keptLines)
        } else {
            emptySet()
        }

        return keptLines
            .filterIndexed { index, _ -> index !in aiRemoveIndexes }
            .flatMap { line ->
                if (splitLongParagraph) splitLongParagraph(line) else listOf(line)
            }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun isObviousAdLine(line: String): Boolean {
        val compact = line.replace(" ", "").replace("　", "")
        if (domainRegex.containsMatchIn(compact)) return true
        if (compact.length <= 160 && obviousAdRegex.containsMatchIn(compact)) return true
        return compact.length <= 80 && compact.count { it == ':' || it == '：' } >= 2 &&
            suspiciousAdRegex.containsMatchIn(compact)
    }

    private fun isSuspiciousAdLine(line: String): Boolean {
        if (line.length !in 6..220) return false
        if (line.count { it == '。' || it == '！' || it == '？' } >= 3) return false
        return suspiciousAdRegex.containsMatchIn(line) || domainRegex.containsMatchIn(line)
    }

    private fun splitLongParagraph(line: String): List<String> {
        if (line.length <= LONG_PARAGRAPH_HARD_LIMIT) return listOf(line)
        val result = arrayListOf<String>()
        val builder = StringBuilder()
        line.forEachIndexed { index, ch ->
            builder.append(ch)
            val previous = line.getOrNull(index - 1)
            val canSplit = ch in splitPunctuation ||
                (ch in closingPunctuation && previous != null && previous in splitPunctuation)
            if (builder.length >= LONG_PARAGRAPH_TARGET && canSplit) {
                result.add(builder.toString().trim())
                builder.clear()
            } else if (builder.length >= LONG_PARAGRAPH_HARD_LIMIT) {
                result.add(builder.toString().trim())
                builder.clear()
            }
        }
        if (builder.isNotBlank()) {
            result.add(builder.toString().trim())
        }
        return result.filter { it.isNotBlank() }.ifEmpty { listOf(line) }
    }

    private fun aiAdLineIndexes(lines: List<String>): Set<Int> {
        val candidates = lines.mapIndexedNotNull { index, line ->
            if (isSuspiciousAdLine(line)) index to line.take(240) else null
        }.take(24)
        if (candidates.isEmpty()) return emptySet()
        if (AiBgMusic.modelUrl.isBlank() || AiBgMusic.modelName.isBlank()) return emptySet()
        return runCatching {
            requestAiAdLineIndexes(candidates)
        }.onFailure {
            AppLog.put("智能正文清洗 AI 辅助失败，已继续使用本地规则\n${it.localizedMessage}", it)
        }.getOrDefault(emptySet())
    }

    private fun requestAiAdLineIndexes(candidates: List<Pair<Int, String>>): Set<Int> {
        val userPrompt = """
            你是小说正文广告判断助手。下面是从章节里筛出来的疑似广告短段。
            只判断是否应删除，不要改写正文。
            只输出 JSON，不要 Markdown，不要解释。
            JSON 格式：{"remove":[候选 id 数字]}

            删除标准：
            - 明显网站广告、书源广告、APP下载、域名地址、求收藏月票、QQ群/公众号推广，可以删除。
            - 小说剧情、人物台词、作者正常叙事，不要删除。
            - 拿不准就保留。

            候选：
            ${GSON.toJson(candidates.map { mapOf("id" to it.first, "text" to it.second) })}
        """.trimIndent()
        val body = GSON.toJson(
            mapOf(
                "model" to AiBgMusic.modelName.trim(),
                "messages" to listOf(
                    mapOf("role" to "system", "content" to "你只返回可解析 JSON。"),
                    mapOf("role" to "user", "content" to userPrompt)
                ),
                "temperature" to 0,
                "max_tokens" to 500
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
            .callTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
        val responseText = client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("AI 请求失败 HTTP ${response.code}，${text.take(300)}")
            }
            text
        }
        val candidateIds = candidates.map { it.first }.toSet()
        return parseRemoveIndexes(extractChatContent(responseText))
            .filter { it in candidateIds }
            .toSet()
    }

    private fun parseRemoveIndexes(contentText: String): List<Int> {
        jsonCandidates(contentText).forEach { candidate ->
            runCatching {
                return jsonElementToIndexes(JsonParser.parseString(candidate))
            }
        }
        return emptyList()
    }

    private fun jsonElementToIndexes(element: JsonElement): List<Int> {
        return when {
            element.isJsonArray -> element.asJsonArray.mapNotNull { it.asIntOrNull() }
            element.isJsonObject -> {
                val obj = element.asJsonObject
                when {
                    obj["remove"]?.isJsonArray == true -> obj["remove"].asJsonArray
                    obj["delete"]?.isJsonArray == true -> obj["delete"].asJsonArray
                    obj["items"]?.isJsonArray == true -> obj["items"].asJsonArray
                    else -> JsonArray()
                }.mapNotNull { it.asIntOrNull() }
            }
            else -> emptyList()
        }
    }

    private fun JsonElement.asIntOrNull(): Int? {
        return runCatching {
            when {
                isJsonPrimitive && asJsonPrimitive.isNumber -> asInt
                isJsonPrimitive && asJsonPrimitive.isString -> asString.toIntOrNull()
                isJsonObject -> asJsonObject["id"]?.asIntOrNull()
                else -> null
            }
        }.getOrNull()
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

    private fun cacheKey(book: Book, chapter: BookChapter, content: String): String {
        return "${cachePrefix(book, chapter)}:${content.hashCode()}:${configKey()}"
    }

    private fun cachePrefix(book: Book, chapter: BookChapter): String {
        return "${book.bookUrl.hashCode()}:${book.origin.hashCode()}:${chapter.index}:"
    }

    private fun configKey(): String {
        return listOf(
            regexAds,
            splitLongParagraph,
            aiAssist,
            AiBgMusic.modelUrl,
            AiBgMusic.modelName
        ).joinToString("|")
    }
}
