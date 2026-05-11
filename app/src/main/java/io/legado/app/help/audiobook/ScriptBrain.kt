package io.legado.app.help.audiobook

import android.content.Context
import android.os.Environment
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.ReadBook
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Wrapper
import java.io.File

object ScriptBrain {

    data class ScriptCharacter(
        val name: String,
        val gender: String,
        val ageType: String,
        val voiceTag: String,
    )

    data class ScriptLine(
        val index: Int,
        val roleName: String,
        val voiceTag: String,
        val isNarration: Boolean,
        val text: String,
    )

    data class Analysis(
        val bookName: String,
        val chapterIndex: Int,
        val chapterTitle: String,
        val characters: List<ScriptCharacter>,
        val lines: List<ScriptLine>,
        val updatedAt: Long,
        val source: String = "本地规则",
        val error: String = "",
    )

    data class RuleRunResult(
        val analysis: Analysis,
        val rawQueueJson: String,
        val logs: List<String>,
    )

    fun analyzeCurrentChapter(context: Context): Analysis {
        val chapterPayload = currentChapterPayload()
        val importedRule = loadImportedRule(context)
        if (importedRule.isNotBlank()) {
            runCatching {
                return runImportedRule(
                    context = context.applicationContext,
                    payload = chapterPayload,
                    ruleText = importedRule,
                ).analysis
            }.onFailure { error ->
                val analysis = analyze(
                    chapterPayload.bookName,
                    chapterPayload.chapterIndex,
                    chapterPayload.chapterTitle,
                    chapterPayload.chapterText,
                ).copy(
                    source = "本地规则兜底",
                    error = "导入朗读规则运行失败：${error.localizedMessage ?: error.javaClass.simpleName}"
                )
                save(context.applicationContext, analysis)
                return analysis
            }
        }
        val analysis = analyze(
            chapterPayload.bookName,
            chapterPayload.chapterIndex,
            chapterPayload.chapterTitle,
            chapterPayload.chapterText,
        )
        save(context.applicationContext, analysis)
        return analysis
    }

    fun runImportedRuleForCurrentChapter(context: Context): RuleRunResult {
        val rule = loadImportedRule(context)
        if (rule.isBlank()) error("还没有导入朗读规则")
        return runImportedRule(
            context = context.applicationContext,
            payload = currentChapterPayload(),
            ruleText = rule,
        )
    }

    fun saveImportedRule(context: Context, raw: String) {
        val code = extractRuleCode(raw)
        require(code.isNotBlank()) { "朗读规则为空" }
        globalDir(context.applicationContext).mkdirs()
        importedRuleFile(context.applicationContext).writeText(code, Charsets.UTF_8)
    }

    fun loadImportedRule(context: Context): String {
        val file = importedRuleFile(context.applicationContext)
        return file.takeIf { it.exists() }?.readText(Charsets.UTF_8).orEmpty()
    }

    fun clearImportedRule(context: Context) {
        importedRuleFile(context.applicationContext).delete()
    }

    fun hasImportedRule(context: Context): Boolean {
        return loadImportedRule(context).isNotBlank()
    }

    private fun currentChapterPayload(): ChapterPayload {
        val book = ReadBook.book
        val chapter = ReadBook.curTextChapter
        return ChapterPayload(
            bookName = book?.name?.ifBlank { "未命名书籍" } ?: "未命名书籍",
            bookUrl = book?.bookUrl.orEmpty(),
            chapterIndex = chapter?.chapter?.index ?: ReadBook.durChapterIndex,
            chapterTitle = chapter?.let { it.title.ifBlank { it.chapter.title } } ?: "当前章",
            chapterText = chapter?.getNeedReadAloud(0, false, 0).orEmpty(),
        )
    }

    private fun runImportedRule(
        context: Context,
        payload: ChapterPayload,
        ruleText: String,
    ): RuleRunResult {
        val logs = arrayListOf<String>()
        val payloadJson = JSONObject()
            .put("bookName", payload.bookName)
            .put("bookUrl", payload.bookUrl)
            .put("bookKey", payload.bookUrl)
            .put("chapterIndex", payload.chapterIndex)
            .put("chapterTitle", payload.chapterTitle)
            .put("chapterText", payload.chapterText)

        val bindings = ScriptBindings().apply {
            this["ttsrv"] = CompatTtsrv(context, payload.bookName, logs)
            this["console"] = RuleConsole(logs)
        }
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        RhinoScriptEngine.eval(extractRuleCode(ruleText), scope)
        val rawQueueJson = RhinoScriptEngine.eval(ruleCallJs(payloadJson), scope)?.toString().orEmpty()
        val lines = parseAudioQueue(rawQueueJson)
        val analysis = Analysis(
            bookName = payload.bookName,
            chapterIndex = payload.chapterIndex,
            chapterTitle = payload.chapterTitle,
            characters = charactersFromLines(lines),
            lines = lines,
            updatedAt = System.currentTimeMillis(),
            source = "导入朗读规则",
        )
        save(context, analysis)
        val dir = scriptDir(context, payload.bookName).apply { mkdirs() }
        File(dir, "last_audio_queue.json").writeText(rawQueueJson, Charsets.UTF_8)
        File(dir, "last_rule_log.txt").writeText(logs.joinToString("\n"), Charsets.UTF_8)
        return RuleRunResult(analysis, rawQueueJson, logs)
    }

    private fun ruleCallJs(payload: JSONObject): String {
        return """
            (function() {
                var payload = JSON.parse(${JSONObject.quote(payload.toString())});
                var fn = null;
                if (typeof SpeechRuleJS !== 'undefined' && SpeechRuleJS && typeof SpeechRuleJS.prepareChapterAudioQueue === 'function') {
                    fn = function(p) { return SpeechRuleJS.prepareChapterAudioQueue(p); };
                } else if (typeof prepareChapterAudioQueue === 'function') {
                    fn = prepareChapterAudioQueue;
                } else if (typeof __prepareChapterAudioQueue === 'function') {
                    fn = __prepareChapterAudioQueue;
                }
                if (!fn) {
                    throw '未找到 SpeechRuleJS.prepareChapterAudioQueue / prepareChapterAudioQueue';
                }
                var ret = fn(payload);
                if (ret && ret.audioQueue) ret = ret.audioQueue;
                if (ret && ret.queue) ret = ret.queue;
                if (ret && ret.items) ret = ret.items;
                return JSON.stringify(ret || []);
            })();
        """.trimIndent()
    }

    private fun analyze(
        bookName: String,
        chapterIndex: Int,
        chapterTitle: String,
        chapterText: String,
    ): Analysis {
        val lines = buildList {
            val paragraphs = chapterText
                .replace("\r\n", "\n")
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotBlank() }

            paragraphs.forEach { paragraph ->
                appendParagraph(paragraph, size)
            }
        }.mapIndexed { index, line ->
            line.copy(index = index + 1)
        }

        return Analysis(
            bookName = bookName,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            characters = charactersFromLines(lines),
            lines = lines,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun parseAudioQueue(rawQueueJson: String): List<ScriptLine> {
        val trimmed = rawQueueJson.trim()
        if (trimmed.isBlank()) return emptyList()
        val array = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            val obj = JSONObject(trimmed)
            obj.optJSONArray("audioQueue")
                ?: obj.optJSONArray("queue")
                ?: obj.optJSONArray("items")
                ?: JSONArray()
        }
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val text = firstText(item, "text", "content", "sentence", "line")
                if (text.isBlank()) continue
                val tag = firstText(item, "tag", "role", "type")
                val characterInfo = item.optJSONObject("characterInfo")
                val rawRole = firstText(item, "roleName", "displayRoleName", "speaker", "character", "name")
                    .ifBlank { firstText(characterInfo, "name", "roleName", "speaker") }
                    .ifBlank { tag }
                val role = normalizeRoleName(rawRole, tag)
                val voice = firstText(item, "voice", "voiceTag", "displayVoice", "actualVoice", "sourceVoice")
                    .ifBlank { nestedVoice(item) }
                    .ifBlank { if (role == NARRATOR_ROLE) "旁白01" else tag.takeIf { it.isUsefulVoiceTag() }.orEmpty() }
                    .ifBlank { "待分配" }
                add(
                    ScriptLine(
                        index = size + 1,
                        roleName = role,
                        voiceTag = voice,
                        isNarration = role == NARRATOR_ROLE || tag.equals("narration", ignoreCase = true),
                        text = text
                    )
                )
            }
        }
    }

    private fun MutableList<ScriptLine>.appendParagraph(paragraph: String, startIndex: Int) {
        var cursor = 0
        var lastSpeaker: String? = null
        quoteRegex.findAll(paragraph).forEach { match ->
            val before = paragraph.substring(cursor, match.range.first).trim()
            if (before.isNotBlank()) {
                add(narration(startIndex + size + 1, before))
                inferSpeaker(before)?.let { lastSpeaker = it }
            }
            val dialogue = match.groupValues[1].trim()
            if (dialogue.isNotBlank()) {
                val speaker = inferSpeaker(before) ?: lastSpeaker ?: UNKNOWN_ROLE
                add(dialogue(startIndex + size + 1, speaker, dialogue))
            }
            cursor = match.range.last + 1
        }
        val tail = paragraph.substring(cursor).trim()
        if (tail.isNotBlank()) {
            add(narration(startIndex + size + 1, tail))
        }
    }

    private fun narration(index: Int, text: String): ScriptLine {
        return ScriptLine(
            index = index,
            roleName = NARRATOR_ROLE,
            voiceTag = "旁白01",
            isNarration = true,
            text = text
        )
    }

    private fun dialogue(index: Int, speaker: String, text: String): ScriptLine {
        val character = inferCharacter(speaker)
        return ScriptLine(
            index = index,
            roleName = speaker,
            voiceTag = character.voiceTag,
            isNarration = false,
            text = text
        )
    }

    private fun charactersFromLines(lines: List<ScriptLine>): List<ScriptCharacter> {
        return lines
            .asSequence()
            .filterNot { it.isNarration }
            .map { it.roleName }
            .filter { it.isNotBlank() && it != UNKNOWN_ROLE }
            .distinct()
            .map { inferCharacter(it) }
            .toList()
    }

    private fun normalizeRoleName(role: String, tag: String): String {
        val value = role.trim()
        if (value.equals("narration", ignoreCase = true) || value == "旁白") return NARRATOR_ROLE
        if (value.equals("duihua", ignoreCase = true) || value.equals("dialogue", ignoreCase = true)) {
            return UNKNOWN_ROLE
        }
        if (value.isNotBlank()) return value
        if (tag.equals("narration", ignoreCase = true)) return NARRATOR_ROLE
        return UNKNOWN_ROLE
    }

    private fun nestedVoice(item: JSONObject): String {
        val config = item.optJSONObject("config") ?: return ""
        val source = config.optJSONObject("source") ?: return ""
        return firstText(source, "voice", "voiceTag", "name")
    }

    private fun firstText(json: JSONObject?, vararg keys: String): String {
        if (json == null) return ""
        for (key in keys) {
            val value = json.opt(key)
            if (value != null && value != JSONObject.NULL) {
                val text = value.toString().trim()
                if (text.isNotBlank()) return text
            }
        }
        return ""
    }

    private fun String.isUsefulVoiceTag(): Boolean {
        return isNotBlank()
                && !equals("narration", ignoreCase = true)
                && !equals("duihua", ignoreCase = true)
                && !equals("dialogue", ignoreCase = true)
    }

    private fun inferSpeaker(beforeQuote: String): String? {
        if (beforeQuote.isBlank()) return null
        val normalized = beforeQuote
            .replace(Regex("[，。！？、；：\\s]+$"), "")
            .takeLast(24)
        speakerRegex.find(normalized)?.let { match ->
            val name = match.groupValues[1].trim()
            if (name.isLikelySpeakerName()) return name
        }
        return null
    }

    private fun String.isLikelySpeakerName(): Boolean {
        if (length !in 1..8) return false
        return this !in pronouns
    }

    private fun inferCharacter(name: String): ScriptCharacter {
        if (name == UNKNOWN_ROLE) {
            return ScriptCharacter(name, "待定", "待定", "待分配")
        }
        val gender = when {
            femaleKeywords.any { name.contains(it) } -> "女"
            maleKeywords.any { name.contains(it) } -> "男"
            else -> "待定"
        }
        val ageType = when {
            childKeywords.any { name.contains(it) } -> "儿童"
            oldKeywords.any { name.contains(it) } -> "老年"
            else -> "青年"
        }
        val voiceTag = when {
            gender == "女" && ageType == "儿童" -> "女童01"
            gender == "男" && ageType == "儿童" -> "男童01"
            gender == "女" && ageType == "老年" -> "老年女01"
            gender == "男" && ageType == "老年" -> "老年男01"
            gender == "女" -> "女青年01"
            gender == "男" -> "男青年01"
            else -> "待分配"
        }
        return ScriptCharacter(name, gender, ageType, voiceTag)
    }

    private fun save(context: Context, analysis: Analysis) {
        val dir = scriptDir(context, analysis.bookName)
        dir.mkdirs()
        File(dir, "characters.json").writeText(analysis.characters.toJson().toString(2), Charsets.UTF_8)
        File(dir, "${analysis.chapterIndex}_${analysis.chapterTitle.safeFileName()}.json")
            .writeText(analysis.toJson().toString(2), Charsets.UTF_8)
    }

    private fun Analysis.toJson(): JSONObject {
        return JSONObject()
            .put("bookName", bookName)
            .put("chapterIndex", chapterIndex)
            .put("chapterTitle", chapterTitle)
            .put("updatedAt", updatedAt)
            .put("source", source)
            .put("error", error)
            .put("characters", characters.toJson())
            .put("lines", JSONArray().also { array ->
                lines.forEach { line ->
                    array.put(
                        JSONObject()
                            .put("index", line.index)
                            .put("roleName", line.roleName)
                            .put("voiceTag", line.voiceTag)
                            .put("isNarration", line.isNarration)
                            .put("text", line.text)
                    )
                }
            })
    }

    private fun List<ScriptCharacter>.toJson(): JSONArray {
        return JSONArray().also { array ->
            forEach { character ->
                array.put(
                    JSONObject()
                        .put("name", character.name)
                        .put("gender", character.gender)
                        .put("ageType", character.ageType)
                        .put("voiceTag", character.voiceTag)
                )
            }
        }
    }

    private fun extractRuleCode(raw: String): String {
        val text = raw.trim()
        if (!text.startsWith("{")) return text
        return runCatching {
            val json = JSONObject(text)
            json.optString("code").ifBlank { text }
        }.getOrDefault(text)
    }

    private fun importedRuleFile(context: Context): File {
        return File(globalDir(context), "imported_speech_rule.js")
    }

    private fun globalDir(context: Context): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return File(root, "script-brain")
    }

    private fun scriptDir(context: Context, bookName: String): File {
        return File(globalDir(context), bookName.safeFileName())
    }

    private fun String.safeFileName(): String {
        return replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_").take(80).ifBlank { "未命名" }
    }

    private data class ChapterPayload(
        val bookName: String,
        val bookUrl: String,
        val chapterIndex: Int,
        val chapterTitle: String,
        val chapterText: String,
    )

    class RuleConsole(private val logs: MutableList<String>) {
        fun log(vararg messages: Any?) {
            logs.add("log: ${messages.joinToString(" ") { it.orEmptyText() }}")
        }

        fun warn(vararg messages: Any?) {
            logs.add("warn: ${messages.joinToString(" ") { it.orEmptyText() }}")
        }

        fun error(vararg messages: Any?) {
            logs.add("error: ${messages.joinToString(" ") { it.orEmptyText() }}")
        }
    }

    class CompatTtsrv(
        private val context: Context,
        private val bookName: String,
        private val logs: MutableList<String>,
    ) {
        val tts = TtsCompat()

        fun readTxtFile(fileName: String?): String {
            val file = resolveFile(fileName)
            return file.takeIf { it.exists() }?.readText(Charsets.UTF_8).orEmpty()
        }

        fun writeTxtFile(fileName: String?, content: String?) {
            val file = resolveFile(fileName)
            file.parentFile?.mkdirs()
            file.writeText(content.orEmpty(), Charsets.UTF_8)
            logs.add("writeTxtFile: ${file.name}")
        }

        fun httpGet(url: String?): CompatResponse {
            val request = Request.Builder().url(url.orEmpty()).get().build()
            return okHttpClient.newCall(request).execute().use { response ->
                CompatResponse(response.code, response.body.string())
            }
        }

        fun httpPost(url: String?, body: String?): CompatResponse {
            return httpPost(url, body, null)
        }

        fun httpPost(url: String?, body: String?, headers: Any?): CompatResponse {
            val headersMap = headerMap(headers)
            val mediaType = headersMap["Content-Type"]?.toMediaTypeOrNull()
                ?: "application/json; charset=utf-8".toMediaTypeOrNull()
            val builder = Request.Builder()
                .url(url.orEmpty())
                .post(body.orEmpty().toRequestBody(mediaType))
            headersMap.forEach { (name, value) ->
                if (!name.equals("Content-Type", ignoreCase = true)) {
                    builder.addHeader(name, value)
                }
            }
            return okHttpClient.newCall(builder.build()).execute().use { response ->
                CompatResponse(response.code, response.body.string())
            }
        }

        private fun resolveFile(fileName: String?): File {
            val safeName = fileName.orEmpty()
                .replace("\\", "/")
                .split("/")
                .filter { it.isNotBlank() && it != "." && it != ".." }
                .joinToString("/")
                .ifBlank { "empty.txt" }
            val dir = scriptDir(context, bookName)
            return File(dir, safeName)
        }

        private fun headerMap(headers: Any?): Map<String, String> {
            if (headers == null) return emptyMap()
            val unwrapped = if (headers is Wrapper) headers.unwrap() else headers
            if (unwrapped is Map<*, *>) {
                return unwrapped.mapNotNull { (key, value) ->
                    val name = key?.toString().orEmpty()
                    val text = value?.toString().orEmpty()
                    if (name.isBlank()) null else name to text
                }.toMap()
            }
            if (unwrapped is Scriptable) {
                return unwrapped.ids.mapNotNull { id ->
                    val name = id?.toString().orEmpty()
                    val value = ScriptableObject.getProperty(unwrapped, name)
                    val text = value?.toString().orEmpty()
                    if (name.isBlank() || text == "undefined") null else name to text
                }.toMap()
            }
            return emptyMap()
        }
    }

    class TtsCompat {
        val data: MutableMap<String, Any?> = linkedMapOf()
    }

    class CompatResponse(private val codeValue: Int, private val text: String) {
        fun code(): Int = codeValue
        fun body(): CompatBody = CompatBody(text)
    }

    class CompatBody(private val text: String) {
        fun string(): String = text
    }

    private fun Any?.orEmptyText(): String {
        return this?.toString().orEmpty()
    }

    private val quoteRegex = Regex("[“「『](.*?)[”」』]")
    private val speakerRegex = Regex("""([\u4e00-\u9fa5A-Za-z0-9·]{1,8})(?:轻声|低声|大声|冷冷|淡淡|笑着|皱眉|急忙|连忙|忍不住|忽然|沉声|柔声|小声)?(?:说道|说|问道|问|道|喊道|喊|叫道|叫|笑道|怒道|喝道|答道|回答|嘀咕|喃喃|开口)$""")
    private val pronouns = setOf("我", "你", "他", "她", "它", "我们", "你们", "他们", "她们", "众人", "男人", "女人", "少年", "少女")
    private val femaleKeywords = listOf("女", "娘", "妹", "姐", "姑", "婆", "姨", "母", "月", "雪", "柔", "婉")
    private val maleKeywords = listOf("男", "哥", "叔", "伯", "父", "爷", "掌柜", "凡", "轩", "辰", "峰")
    private val childKeywords = listOf("小", "童", "孩", "娃")
    private val oldKeywords = listOf("老", "婆", "爷", "伯", "叔", "掌柜", "嬷")
    private const val NARRATOR_ROLE = "旁白"
    private const val UNKNOWN_ROLE = "角色待定"
}
