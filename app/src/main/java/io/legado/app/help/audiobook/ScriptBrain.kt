package io.legado.app.help.audiobook

import android.content.Context
import android.os.Environment
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.ReadBook
import io.legado.app.utils.getPrefString
import io.legado.app.utils.getPrefStringSet
import io.legado.app.utils.putPrefString
import io.legado.app.utils.putPrefStringSet
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

    private data class LockedAnalysisData(
        val characters: List<ScriptCharacter>,
        val lines: List<ScriptLine>,
    )

    data class ImportedRuleInfo(
        val name: String,
        val author: String,
        val version: String,
        val isJson: Boolean,
        val rawLength: Int,
        val codeLength: Int,
    )

    data class SavedRule(
        val id: String,
        val name: String,
        val author: String,
        val version: String,
        val updatedAt: Long,
        val isActive: Boolean,
    )

    data class RoleManagerSnapshot(
        val bookName: String,
        val pluginName: String,
        val pluginAuthor: String,
        val pluginVersion: String,
        val storagePath: String,
        val characters: List<ScriptCharacter>,
        val files: List<String>,
    )

    data class AnalysisModelProfile(
        val name: String,
        val provider: String = "",
        val modelUrl: String = "",
        val modelName: String = "",
        val modelKey: String = "",
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

    fun saveImportedRule(context: Context, raw: String, alsoSaveToLibrary: Boolean = true) {
        val normalized = normalizeImportedRule(raw)
        val code = extractRuleCode(normalized)
        require(code.isNotBlank()) { "朗读规则为空" }
        globalDir(context.applicationContext).mkdirs()
        importedRuleFile(context.applicationContext).writeText(code, Charsets.UTF_8)
        importedRuleJsonFile(context.applicationContext).writeText(normalized, Charsets.UTF_8)
        if (alsoSaveToLibrary) {
            saveRuleToLibrary(context.applicationContext, normalized)
        }
    }

    fun loadImportedRule(context: Context): String {
        val file = importedRuleFile(context.applicationContext)
        return file.takeIf { it.exists() }?.readText(Charsets.UTF_8).orEmpty()
    }

    fun loadImportedRuleRaw(context: Context): String {
        val jsonFile = importedRuleJsonFile(context.applicationContext)
        if (jsonFile.exists()) return jsonFile.readText(Charsets.UTF_8)
        return loadImportedRule(context)
    }

    fun importedRuleInfo(context: Context): ImportedRuleInfo? {
        val raw = loadImportedRuleRaw(context).trim()
        if (raw.isBlank()) return null
        return ruleInfoFromRaw(raw)
    }

    fun savedRules(context: Context): List<SavedRule> {
        val activeRaw = loadImportedRuleRaw(context.applicationContext).trim()
        val dir = ruleLibraryDir(context.applicationContext)
        return dir.listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
            ?.mapNotNull { file ->
                val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()?.trim().orEmpty()
                if (raw.isBlank()) return@mapNotNull null
                val info = ruleInfoFromRaw(raw)
                SavedRule(
                    id = file.nameWithoutExtension,
                    name = info.name,
                    author = info.author,
                    version = info.version,
                    updatedAt = file.lastModified(),
                    isActive = raw == activeRaw,
                )
            }
            ?.sortedWith(compareByDescending<SavedRule> { it.isActive }.thenBy { it.name })
            .orEmpty()
    }

    fun useSavedRule(context: Context, id: String) {
        val file = File(ruleLibraryDir(context.applicationContext), "${id.safeFileName()}.json")
        require(file.exists()) { "规则不存在" }
        saveImportedRule(context.applicationContext, file.readText(Charsets.UTF_8), alsoSaveToLibrary = false)
    }

    fun modelProfiles(context: Context): List<AnalysisModelProfile> {
        val json = context.applicationContext.getPrefString(KEY_MODEL_PROFILES).orEmpty()
        if (json.isBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val profile = AnalysisModelProfile(
                    name = firstText(item, "name"),
                    provider = firstText(item, "provider"),
                    modelUrl = firstText(item, "modelUrl", "baseUrl", "url"),
                    modelName = firstText(item, "modelName", "model"),
                    modelKey = firstText(item, "modelKey", "apiKey", "key"),
                )
                if (profile.name.isNotBlank() && profile.modelUrl.isNotBlank() && profile.modelName.isNotBlank()) {
                    add(profile)
                }
            }
        }.distinctBy { it.name }
    }

    fun saveModelProfiles(context: Context, profiles: List<AnalysisModelProfile>) {
        val normalized = profiles
            .map {
                it.copy(
                    name = it.name.trim(),
                    provider = it.provider.trim(),
                    modelUrl = it.modelUrl.trim(),
                    modelName = it.modelName.trim(),
                    modelKey = it.modelKey.trim(),
                )
            }
            .filter { it.name.isNotBlank() && it.modelUrl.isNotBlank() && it.modelName.isNotBlank() }
            .distinctBy { it.name }
        val array = JSONArray().also { target ->
            normalized.forEach { target.put(it.toScriptModelJson()) }
        }
        context.applicationContext.putPrefString(KEY_MODEL_PROFILES, array.toString())
        val selected = selectedModelProfileName(context.applicationContext)
        if (selected.isNotBlank() && normalized.none { it.name == selected }) {
            saveSelectedModelProfileName(context.applicationContext, normalized.firstOrNull()?.name.orEmpty())
        }
    }

    fun selectedModelProfileName(context: Context): String {
        return context.applicationContext.getPrefString(KEY_SELECTED_MODEL_PROFILE).orEmpty()
    }

    fun saveSelectedModelProfileName(context: Context, name: String) {
        context.applicationContext.putPrefString(KEY_SELECTED_MODEL_PROFILE, name)
    }

    fun selectedModelProfileNames(context: Context): Set<String> {
        return context.applicationContext
            .getPrefStringSet(KEY_SELECTED_MODEL_PROFILES, mutableSetOf())
            ?.toSet()
            .orEmpty()
    }

    fun saveSelectedModelProfileNames(context: Context, names: Set<String>) {
        context.applicationContext.putPrefStringSet(
            KEY_SELECTED_MODEL_PROFILES,
            names.filter { it.isNotBlank() }.toMutableSet()
        )
    }

    fun selectModelProfile(context: Context, profile: AnalysisModelProfile) {
        saveSelectedModelProfileName(context.applicationContext, profile.name)
        saveSelectedModelProfileNames(context.applicationContext, setOf(profile.name))
    }

    fun selectedModelProfile(context: Context): AnalysisModelProfile? {
        return modelProfiles(context.applicationContext)
            .firstOrNull { it.name == selectedModelProfileName(context.applicationContext) }
    }

    fun selectedModelProfiles(context: Context): List<AnalysisModelProfile> {
        val profiles = modelProfiles(context.applicationContext)
        if (profiles.isEmpty()) return emptyList()
        val selectedNames = selectedModelProfileNames(context.applicationContext)
        val selected = profiles.filter { it.name in selectedNames }
        if (selected.isNotEmpty()) return selected
        return selectedModelProfile(context.applicationContext)?.let { listOf(it) } ?: profiles.take(1)
    }

    fun testModel(profile: AnalysisModelProfile): Result<String> = runCatching {
        val url = profile.modelUrl.trim()
        val model = profile.modelName.trim()
        require(url.isNotBlank()) { "请先填写模型地址" }
        require(model.isNotBlank()) { "请先填写模型名" }
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", "ping"))
            )
            .put("temperature", 0)
            .toString()
        val request = Request.Builder()
            .url(normalizeChatCompletionsUrl(url))
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .header("Content-Type", "application/json")
            .apply {
                if (profile.modelKey.isNotBlank()) {
                    header("Authorization", "Bearer ${profile.modelKey.trim()}")
                }
            }
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${text.take(240)}")
            }
            text
        }
    }

    fun builtInRoleManagerInfo(context: Context): ImportedRuleInfo? {
        val raw = runCatching {
            context.applicationContext.assets.open(ROLE_MANAGER_PLUGIN_ASSET).use {
                String(it.readBytes(), Charsets.UTF_8)
            }
        }.getOrNull()?.trim().orEmpty()
        if (raw.isBlank()) return null
        val json = runCatching {
            if (raw.startsWith("[")) JSONArray(raw).optJSONObject(0) else JSONObject(raw)
        }.getOrNull()
        val code = firstText(json, "code", "js", "script", "content", "source")
        return ImportedRuleInfo(
            name = firstText(json, "name", "ruleName", "title", "displayName")
                .ifBlank { "角色管理插件" },
            author = firstText(json, "author", "creator", "user").ifBlank { "未知" },
            version = firstText(json, "version", "versionName", "updateTime").ifBlank { "未标注" },
            isJson = json != null,
            rawLength = raw.length,
            codeLength = code.length,
        )
    }

    fun roleManagerSnapshot(context: Context): RoleManagerSnapshot {
        val analysis = analyzeCurrentChapter(context.applicationContext)
        val dir = scriptDir(context.applicationContext, analysis.bookName).apply { mkdirs() }
        val characters = readRoleManagerCharacters(dir).ifEmpty { analysis.characters }
        val info = builtInRoleManagerInfo(context.applicationContext)
        return RoleManagerSnapshot(
            bookName = analysis.bookName,
            pluginName = info?.name ?: "角色管理插件",
            pluginAuthor = info?.author ?: "未知",
            pluginVersion = info?.version ?: "未标注",
            storagePath = dir.absolutePath,
            characters = characters,
            files = ROLE_MANAGER_FILES
                .map { it.replace("<book>", analysis.bookName.safeFileName()) }
                .filter { File(dir, it).exists() }
        )
    }

    fun clearImportedRule(context: Context) {
        importedRuleFile(context.applicationContext).delete()
        importedRuleJsonFile(context.applicationContext).delete()
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
        val analysisModels = selectedModelProfiles(context)
        val dir = scriptDir(context, payload.bookName).apply { mkdirs() }
        val existingCharacters = readRoleManagerCharacters(dir)
        val payloadJson = JSONObject()
            .put("bookName", payload.bookName)
            .put("bookUrl", payload.bookUrl)
            .put("bookKey", payload.bookUrl)
            .put("chapterIndex", payload.chapterIndex)
            .put("chapterTitle", payload.chapterTitle)
            .put("chapterText", payload.chapterText)
            .put("characterRecords", existingCharacters.toRoleManagerJson())
            .put("voicePool", voicePoolJson())
            .put("analysisModel", analysisModels.firstOrNull()?.toScriptModelJson() ?: JSONObject.NULL)
            .put("analysisModels", analysisModels.toScriptModelJsonArray())

        val bindings = ScriptBindings().apply {
            this["ttsrv"] = CompatTtsrv(context, payload.bookName, logs)
            this["console"] = RuleConsole(logs)
        }
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        RhinoScriptEngine.eval(extractRuleCode(ruleText), scope)
        val rawQueueJson = RhinoScriptEngine.eval(ruleCallJs(payloadJson), scope)?.toString().orEmpty()
        val rawLines = parseAudioQueue(rawQueueJson)
        val returnedCharacters = parseRuleCharacters(rawQueueJson)
        val locked = lockCharactersAndLines(
            existingCharacters = existingCharacters,
            suggestedCharacters = returnedCharacters + charactersFromLines(rawLines),
            rawLines = rawLines,
        )
        val analysis = Analysis(
            bookName = payload.bookName,
            chapterIndex = payload.chapterIndex,
            chapterTitle = payload.chapterTitle,
            characters = locked.characters,
            lines = locked.lines,
            updatedAt = System.currentTimeMillis(),
            source = "导入朗读规则",
        )
        save(context, analysis)
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
                    .ifBlank { if (role == NARRATOR_ROLE) "旁白" else tag.takeIf { it.isUsefulVoiceTag() }.orEmpty() }
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
            voiceTag = "旁白",
            isNarration = true,
            text = text
        )
    }

    private fun parseRuleCharacters(rawQueueJson: String): List<ScriptCharacter> {
        val trimmed = rawQueueJson.trim()
        if (trimmed.isBlank() || trimmed.startsWith("[")) return emptyList()
        val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
        val direct = obj.optJSONArray("characters")
            ?: obj.optJSONArray("roles")
            ?: obj.optJSONArray("roleRecords")
            ?: obj.optJSONArray("characterRecords")
        val fromArray = direct?.let { parseCharacterArray(it) }.orEmpty()
        val roleMap = obj.optJSONObject("roleMap") ?: obj.optJSONObject("charactersMap")
        val fromMap = roleMap?.let { parseCharacterMap(it) }.orEmpty()
        return (fromArray + fromMap)
            .filter { it.name.isNotBlank() && it.name != NARRATOR_ROLE && it.name != UNKNOWN_ROLE }
            .distinctBy { it.name }
    }

    private fun parseCharacterArray(array: JSONArray): List<ScriptCharacter> {
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                parseCharacterObject(item)?.let { add(it) }
            }
        }
    }

    private fun parseCharacterMap(map: JSONObject): List<ScriptCharacter> {
        return buildList {
            val keys = map.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val item = map.optJSONObject(name) ?: continue
                parseCharacterObject(item, fallbackName = name)?.let { add(it) }
            }
        }
    }

    private fun parseCharacterObject(item: JSONObject, fallbackName: String = ""): ScriptCharacter? {
        val name = firstText(item, "name", "roleName", "character", "speaker", "label")
            .ifBlank { fallbackName }
            .trim()
        if (name.isBlank() || name == NARRATOR_ROLE || name == UNKNOWN_ROLE) return null
        val gender = firstText(item, "gender").ifBlank { "待定" }
        val age = firstText(item, "ageType", "age", "genderAge").ifBlank { voicePoolPrefix("", gender) }
        val voice = firstText(item, "voiceTag", "voice", "displayVoice", "actualVoice", "selectedVoice")
        val normalizedVoice = normalizeVoiceTag(voice, age, gender)
        val voiceInfo = inferCharacterFromVoiceTag(normalizedVoice)
        return ScriptCharacter(
            name = name,
            gender = voiceInfo.first.ifBlank { gender },
            ageType = voiceInfo.second.ifBlank { age.toRoleManagerAge(gender) },
            voiceTag = normalizedVoice.ifBlank { "待分配" },
        )
    }

    private fun lockCharactersAndLines(
        existingCharacters: List<ScriptCharacter>,
        suggestedCharacters: List<ScriptCharacter>,
        rawLines: List<ScriptLine>,
    ): LockedAnalysisData {
        val existingByName = existingCharacters
            .filter { it.name.isNotBlank() && it.name != NARRATOR_ROLE && it.name != UNKNOWN_ROLE }
            .associateBy { it.name }
        val occupied = linkedMapOf<String, String>()
        existingCharacters.forEach { character ->
            val voice = normalizeVoiceTag(character.voiceTag, character.ageType, character.gender)
            if (voice.isLockedVoiceTag()) occupied[voice] = character.name
        }

        val finalCharacters = linkedMapOf<String, ScriptCharacter>()
        fun ensureCharacter(roleName: String, suggestion: ScriptCharacter? = null, suggestedVoice: String = ""): ScriptCharacter {
            finalCharacters[roleName]?.let { return it }
            val existing = existingByName[roleName]
            val base = suggestion ?: existing ?: inferCharacter(roleName)
            val preferredVoice = normalizeVoiceTag(
                existing?.voiceTag?.takeIf { it.isLockedVoiceTag() } ?: suggestedVoice.ifBlank { base.voiceTag },
                existing?.ageType ?: base.ageType,
                existing?.gender ?: base.gender,
            )
            val finalVoice = if (preferredVoice.isLockedVoiceTag() && occupied[preferredVoice]?.let { it == roleName } != false) {
                occupied[preferredVoice] = roleName
                preferredVoice
            } else {
                allocateVoiceTag(
                    roleName = roleName,
                    suggestedVoice = preferredVoice,
                    ageType = existing?.ageType ?: base.ageType,
                    gender = existing?.gender ?: base.gender,
                    occupied = occupied,
                )
            }
            val voiceInfo = inferCharacterFromVoiceTag(finalVoice)
            val finalCharacter = ScriptCharacter(
                name = roleName,
                gender = voiceInfo.first.ifBlank { existing?.gender ?: base.gender },
                ageType = voiceInfo.second.ifBlank { existing?.ageType ?: base.ageType.toRoleManagerAge(base.gender) },
                voiceTag = finalVoice,
            )
            finalCharacters[roleName] = finalCharacter
            return finalCharacter
        }

        suggestedCharacters
            .filter { it.name.isNotBlank() && it.name != NARRATOR_ROLE && it.name != UNKNOWN_ROLE }
            .distinctBy { it.name }
            .forEach { ensureCharacter(it.name, suggestion = it, suggestedVoice = it.voiceTag) }

        val finalLines = rawLines.map { line ->
            if (line.isNarration || line.roleName == NARRATOR_ROLE) {
                line.copy(roleName = NARRATOR_ROLE, voiceTag = "旁白", isNarration = true)
            } else {
                val character = ensureCharacter(line.roleName, suggestedVoice = line.voiceTag)
                line.copy(voiceTag = character.voiceTag)
            }
        }
        return LockedAnalysisData(finalCharacters.values.toList(), finalLines)
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
            .filter { it.roleName.isNotBlank() && it.roleName != UNKNOWN_ROLE }
            .groupBy { it.roleName }
            .map { (name, roleLines) ->
                val base = inferCharacter(name)
                val voiceTag = roleLines
                    .map { it.voiceTag }
                    .firstOrNull { it.isNotBlank() && it != "待分配" }
                    .orEmpty()
                val voiceInfo = inferCharacterFromVoiceTag(voiceTag)
                ScriptCharacter(
                    name = name,
                    gender = voiceInfo.first.ifBlank { base.gender },
                    ageType = voiceInfo.second.ifBlank { base.ageType },
                    voiceTag = voiceTag.ifBlank { base.voiceTag },
                )
            }
            .toList()
    }

    private fun inferCharacterFromVoiceTag(voiceTag: String): Pair<String, String> {
        if (voiceTag.isBlank() || voiceTag == "待分配") return "" to ""
        val gender = when {
            voiceTag.contains("女") || voiceTag.contains("幼女") -> "女"
            voiceTag.contains("男") -> "男"
            else -> ""
        }
        val ageType = when {
            voiceTag.contains("女童") || voiceTag.contains("幼女") || voiceTag.contains("女孩") -> "女/女童"
            voiceTag.contains("男童") || voiceTag.contains("男孩") -> "男/男童"
            voiceTag.contains("女老年") -> "女/女老年"
            voiceTag.contains("男老年") -> "男/男老年"
            voiceTag.contains("女中年") -> "女/女中年"
            voiceTag.contains("男中年") -> "男/男中年"
            voiceTag.contains("少女") -> "女/少女"
            voiceTag.contains("少年") -> "男/少年"
            voiceTag.contains("女青年") -> "女/女青年"
            voiceTag.contains("男青年") -> "男/男青年"
            else -> ""
        }
        return gender to ageType
    }

    private fun normalizeVoiceTag(voiceTag: String, ageType: String, gender: String): String {
        val value = voiceTag.trim().replace(Regex("\\s+"), "")
        if (value.isBlank() || value.isGenericVoiceTag()) return ""
        if (value == "旁白" || value.equals("narration", ignoreCase = true)) return "旁白"
        if (value.matches(Regex("^[男女]/.+\\d{2,3}$"))) return value
        if (value.matches(Regex("^[男女]/.+$"))) return "${value}01"
        val short = Regex("^(男童|女童|少年|少女|男青年|女青年|男中年|女中年|男老年|女老年|特殊男|特殊女)(\\d{2,3})?$")
            .matchEntire(value)
        if (short != null) {
            val prefix = voicePoolPrefix(short.groupValues[1], gender)
            val suffix = short.groupValues.getOrNull(2).orEmpty().ifBlank { "01" }
            return "$prefix$suffix"
        }
        val prefix = voicePoolPrefix(ageType, gender)
        return "${prefix}01"
    }

    private fun voicePoolPrefix(ageType: String, gender: String): String {
        val value = ageType.trim().replace(Regex("\\s+"), "")
        var inferredGender = gender.trim()
        if (value.matches(Regex("^男/(男童|少年|男青年|男中年|男老年|特殊)$"))) return value
        if (value.matches(Regex("^女/(女童|少女|女青年|女中年|女老年|特殊)$"))) return value
        if (value.contains("女")) inferredGender = "女"
        if (value.contains("男")) inferredGender = "男"
        return when {
            Regex("老年|老人|老者|老翁|老汉|爷爷|七旬|八旬|六旬|古稀|花甲").containsMatchIn(value) ->
                if (inferredGender == "女") "女/女老年" else "男/男老年"
            Regex("女童|小女孩|女娃|幼女").containsMatchIn(value) -> "女/女童"
            Regex("男童|小男孩|男娃|童").containsMatchIn(value) -> "男/男童"
            Regex("少女|姑娘|丫头|女学生").containsMatchIn(value) -> "女/少女"
            Regex("少年|小伙子|男学生").containsMatchIn(value) -> "男/少年"
            Regex("女中年|中年妇|妇人|妇女").containsMatchIn(value) -> "女/女中年"
            Regex("男中年|中年|壮年|汉子|大汉|管事|掌柜|管家|官员|将军").containsMatchIn(value) ->
                if (inferredGender == "女") "女/女中年" else "男/男中年"
            Regex("女青年|女子|女人").containsMatchIn(value) -> "女/女青年"
            Regex("男青年|男子|男人").containsMatchIn(value) -> "男/男青年"
            Regex("青年|年轻").containsMatchIn(value) -> if (inferredGender == "女") "女/女青年" else "男/男青年"
            value.contains("特殊") -> if (inferredGender == "女") "女/特殊" else "男/特殊"
            inferredGender == "女" -> "女/女青年"
            else -> "男/男青年"
        }
    }

    private fun allocateVoiceTag(
        roleName: String,
        suggestedVoice: String,
        ageType: String,
        gender: String,
        occupied: MutableMap<String, String>,
    ): String {
        val suggested = normalizeVoiceTag(suggestedVoice, ageType, gender)
        if (suggested.isLockedVoiceTag() && occupied[suggested]?.let { it == roleName } != false) {
            occupied[suggested] = roleName
            return suggested
        }
        val prefix = voicePoolPrefix(ageType, gender)
        for (index in 1..voicePoolCapacity(prefix)) {
            val voice = "$prefix${index.toString().padStart(2, '0')}"
            if (!occupied.containsKey(voice)) {
                occupied[voice] = roleName
                return voice
            }
        }
        return suggested.ifBlank { "${prefix}01" }
    }

    private fun voicePoolCapacity(prefix: String): Int {
        return when {
            prefix.contains("青年") -> 200
            prefix.contains("特殊") -> 20
            else -> 100
        }
    }

    private fun String.isLockedVoiceTag(): Boolean {
        return isNotBlank() && !isGenericVoiceTag() && this != "旁白"
    }

    private fun String.isGenericVoiceTag(): Boolean {
        return isBlank()
                || equals("待分配", ignoreCase = true)
                || equals("对话", ignoreCase = true)
                || equals("dialogue", ignoreCase = true)
                || equals("duihua", ignoreCase = true)
                || equals("narration", ignoreCase = true)
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
            childKeywords.any { name.contains(it) } -> if (gender == "女") "女/女童" else "男/男童"
            oldKeywords.any { name.contains(it) } -> if (gender == "女") "女/女老年" else "男/男老年"
            gender == "女" -> "女/女青年"
            else -> "男/男青年"
        }
        val voiceTag = when {
            ageType == "女/女童" -> "女/女童01"
            ageType == "男/男童" -> "男/男童01"
            ageType == "女/女老年" -> "女/女老年01"
            ageType == "男/男老年" -> "男/男老年01"
            ageType == "女/女青年" -> "女/女青年01"
            ageType == "男/男青年" -> "男/男青年01"
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
        syncRoleManagerFiles(dir, analysis)
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

    private fun List<AnalysisModelProfile>.toScriptModelJsonArray(): JSONArray {
        return JSONArray().also { array ->
            forEach { profile -> array.put(profile.toScriptModelJson()) }
        }
    }

    private fun voicePoolJson(): JSONArray {
        val configs = listOf(
            "女/少女" to 100,
            "男/少年" to 100,
            "女/女青年" to 200,
            "男/男青年" to 200,
            "女/女中年" to 100,
            "男/男中年" to 100,
            "女/女老年" to 100,
            "男/男老年" to 100,
            "女/女童" to 100,
            "男/男童" to 100,
            "男/特殊" to 20,
            "女/特殊" to 20,
        )
        return JSONArray().also { array ->
            configs.forEach { (prefix, count) ->
                array.put(
                    JSONObject()
                        .put("prefix", prefix)
                        .put("count", count)
                        .put("example", "${prefix}01")
                )
            }
        }
    }

    private fun AnalysisModelProfile.toScriptModelJson(): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("provider", provider)
            .put("modelUrl", modelUrl)
            .put("baseUrl", modelUrl)
            .put("modelName", modelName)
            .put("model", modelName)
            .put("modelKey", modelKey)
            .put("apiKey", modelKey)
    }

    private fun List<ScriptCharacter>.toRoleManagerJson(): JSONArray {
        return JSONArray().also { array ->
            forEach { character ->
                if (character.name == NARRATOR_ROLE) return@forEach
                array.put(
                    JSONObject()
                        .put("name", character.name)
                        .put("aliases", character.name)
                        .put("gender", character.gender)
                        .put("age", character.ageType.toRoleManagerAge(character.gender))
                        .put("ageType", character.ageType)
                        .put("voice", character.voiceTag)
                        .put("voiceTag", character.voiceTag)
                        .put("usageCount", 100)
                        .put("source", "阅读端大脑")
                )
            }
        }
    }

    private fun syncRoleManagerFiles(dir: File, analysis: Analysis) {
        val bookFileName = "shuming.${analysis.bookName.safeFileName()}.json"
        val mergedCharacters = mergeRoleCharacters(dir, analysis.characters)
        val records = mergedCharacters.toRoleManagerJson()
        File(dir, "characterRecords.json").writeText(records.toString(2), Charsets.UTF_8)
        File(dir, bookFileName).writeText(records.toString(2), Charsets.UTF_8)
        File(dir, "cunfang.txt").writeText(analysis.bookName, Charsets.UTF_8)
        File(dir, "gengxin.json").writeText(
            JSONObject()
                .put("bookName", analysis.bookName)
                .put("chapterIndex", analysis.chapterIndex)
                .put("chapterTitle", analysis.chapterTitle)
                .put("updatedAt", analysis.updatedAt)
                .put("count", mergedCharacters.size)
                .put("source", analysis.source)
                .toString(2),
            Charsets.UTF_8
        )
        File(dir, "liebiao.json").writeText(
            JSONArray().put(analysis.bookName).toString(2),
            Charsets.UTF_8
        )
        File(dir, "fayinren.json").writeText(
            JSONArray().also { array ->
                mergedCharacters
                    .map { it.voiceTag }
                    .filter { it.isNotBlank() && it != "待分配" }
                    .distinct()
                    .forEach { array.put(it) }
            }.toString(2),
            Charsets.UTF_8
        )
    }

    private fun mergeRoleCharacters(dir: File, current: List<ScriptCharacter>): List<ScriptCharacter> {
        val merged = linkedMapOf<String, ScriptCharacter>()
        readRoleManagerCharacters(dir).forEach { character ->
            if (character.name.isNotBlank() && character.name != NARRATOR_ROLE) {
                merged[character.name] = character
            }
        }
        current.forEach { character ->
            if (character.name.isBlank() || character.name == NARRATOR_ROLE) return@forEach
            val old = merged[character.name]
            merged[character.name] = when {
                old == null -> character
                old.voiceTag.isLockedVoiceTag() -> old.copy(
                    gender = old.gender.ifBlank { character.gender },
                    ageType = old.ageType.ifBlank { character.ageType },
                )
                else -> character
            }
        }
        return merged.values.toList()
    }

    private fun readRoleManagerCharacters(dir: File): List<ScriptCharacter> {
        val file = File(dir, "characterRecords.json")
        if (!file.exists()) return emptyList()
        val array = runCatching { JSONArray(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = firstText(item, "name", "roleName", "character")
                if (name.isBlank()) continue
                val gender = firstText(item, "gender").ifBlank { "待定" }
                val age = firstText(item, "ageType", "age").ifBlank { "青年" }
                val voice = firstText(item, "voiceTag", "voice", "displayVoice").ifBlank { "待分配" }
                add(ScriptCharacter(name, gender, age, voice))
            }
        }
    }

    private fun String.toRoleManagerAge(gender: String): String {
        return when {
            matches(Regex("^[男女]/.+")) -> this
            contains("女童") || contains("幼女") || contains("女孩") -> "女/女童"
            contains("男童") || contains("男孩") -> "男/男童"
            contains("女老年") -> "女/女老年"
            contains("男老年") -> "男/男老年"
            contains("女中年") -> "女/女中年"
            contains("男中年") -> "男/男中年"
            contains("少女") -> "女/少女"
            contains("少年") -> "男/少年"
            contains("女青年") -> "女/女青年"
            contains("男青年") -> "男/男青年"
            contains("童") || contains("儿童") -> if (gender == "女") "女/女童" else "男/男童"
            contains("老") -> if (gender == "女") "女/女老年" else "男/男老年"
            contains("中") -> if (gender == "女") "女/女中年" else "男/男中年"
            contains("少") -> if (gender == "女") "女/少女" else "男/少年"
            else -> if (gender == "女") "女/女青年" else "男/男青年"
        }
    }

    private fun ruleInfoFromRaw(raw: String): ImportedRuleInfo {
        val code = extractRuleCode(raw)
        val json = raw.takeIf { it.trim().startsWith("{") }?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }
        return ImportedRuleInfo(
            name = firstText(json, "name", "ruleName", "title", "displayName").ifBlank { "导入朗读规则" },
            author = firstText(json, "author", "creator", "user").ifBlank { "未知" },
            version = firstText(json, "version", "versionName", "updateTime").ifBlank { "未标注" },
            isJson = json != null,
            rawLength = raw.length,
            codeLength = code.length,
        )
    }

    private fun saveRuleToLibrary(context: Context, normalized: String) {
        val info = ruleInfoFromRaw(normalized)
        val id = "${info.name}_${info.author}_${info.version}_${Integer.toHexString(normalized.hashCode())}"
            .safeFileName()
        val dir = ruleLibraryDir(context).apply { mkdirs() }
        File(dir, "$id.json").writeText(normalized, Charsets.UTF_8)
    }

    private fun extractRuleCode(raw: String): String {
        val text = raw.trim()
        if (!text.startsWith("{")) return text
        return runCatching {
            val json = JSONObject(text)
            firstText(json, "code", "js", "script", "content", "source").ifBlank { text }
        }.getOrDefault(text)
    }

    private fun normalizeImportedRule(raw: String): String {
        val text = raw.trim()
        if (text.startsWith("{")) {
            return runCatching { JSONObject(text).toString(2) }.getOrDefault(text)
        }
        return JSONObject()
            .put("name", "手动 JS 朗读规则")
            .put("type", "speech_rule")
            .put("code", text)
            .toString(2)
    }

    private fun importedRuleFile(context: Context): File {
        return File(globalDir(context), "imported_speech_rule.js")
    }

    private fun importedRuleJsonFile(context: Context): File {
        return File(globalDir(context), "imported_speech_rule.json")
    }

    private fun ruleLibraryDir(context: Context): File {
        return File(globalDir(context), "rule-library")
    }

    private fun globalDir(context: Context): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return File(root, "script-brain")
    }

    private fun scriptDir(context: Context, bookName: String): File {
        return File(globalDir(context), bookName.safeFileName())
    }

    private fun normalizeChatCompletionsUrl(url: String): String {
        val normalized = url.trim().trimEnd('/')
        if (normalized.endsWith("/chat/completions", ignoreCase = true)) return normalized
        return "$normalized/chat/completions"
    }

    private const val KEY_MODEL_PROFILES = "script_brain_model_profiles"
    private const val KEY_SELECTED_MODEL_PROFILE = "script_brain_selected_model_profile"
    private const val KEY_SELECTED_MODEL_PROFILES = "script_brain_selected_model_profiles"

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
    private const val ROLE_MANAGER_PLUGIN_ASSET = "defaultData/scriptBrain/role_manager_plugin_v2667.json"
    private val ROLE_MANAGER_FILES = listOf(
        "characterRecords.json",
        "shuming.<book>.json",
        "gengxin.json",
        "cunfang.txt",
        "liebiao.json",
        "fayinren.json",
    )
    private val oldKeywords = listOf("老", "婆", "爷", "伯", "叔", "掌柜", "嬷")
    private const val NARRATOR_ROLE = "旁白"
    private const val UNKNOWN_ROLE = "角色待定"
}
