package io.legado.app.model

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.help.http.okHttpClient
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max

object AiBgMusic {

    private const val KEY_ENABLED = "ai_bgm_enabled"
    private const val KEY_DIR = "ai_bgm_dir"
    private const val KEY_MODEL_URL = "ai_bgm_model_url"
    private const val KEY_MODEL_NAME = "ai_bgm_model_name"
    private const val KEY_MODEL_KEY = "ai_bgm_model_key"
    private const val KEY_MODEL_PROFILES = "ai_bgm_model_profiles"
    private const val KEY_SELECTED_MODEL_PROFILE = "ai_bgm_selected_model_profile"
    private const val KEY_PROMPTS = "ai_bgm_prompts"
    private const val KEY_PROMPT_PROFILES = "ai_bgm_prompt_profiles"
    private const val KEY_SELECTED_PROMPT = "ai_bgm_selected_prompt"
    private const val KEY_FREQUENCY = "ai_bgm_frequency"
    private const val KEY_SCENES_PER_MUSIC = "ai_bgm_scenes_per_music"
    private const val KEY_PLAYLIST = "ai_bgm_playlist"
    private const val KEY_VOLUME = "ai_bgm_volume"
    private const val KEY_PRELOAD_CHAPTERS = "ai_bgm_preload_chapters"
    private const val KEY_PRELOAD_WHOLE_BOOK = "ai_bgm_preload_whole_book"

    const val FREQUENCY_BOOK = 0
    const val FREQUENCY_CHAPTER = 1
    const val FREQUENCY_SCENE = 2

    data class Config(
        val enabled: Boolean = false,
        val musicDir: String = "",
        val modelUrl: String = "",
        val modelName: String = "",
        val modelKey: String = "",
        val prompts: String = defaultPrompt,
        val frequency: Int = FREQUENCY_SCENE,
        val scenesPerMusic: Int = 1,
        val volume: Int = 35,
        val preloadChapters: Int = 5,
        val preloadWholeBook: Boolean = false,
    )

    data class PlaylistItem(
        val bookName: String = "",
        val chapterTitle: String = "",
        val chapterIndex: Int,
        val sceneIndex: Int = 0,
        val unitType: String = "",
        val start: Int,
        val end: Int,
        val musicName: String = "",
        val musicUri: String = "",
        val reason: String = "",
        val mood: String = "",
        val sourceText: String = "",
        val status: String = STATUS_DONE,
        val statusMessage: String = "",
        val modeKey: String = "",
    )

    data class ChapterAnalysis(
        val bookName: String,
        val chapterTitle: String,
        val chapterIndex: Int,
        val status: String,
        val statusMessage: String,
        val items: List<PlaylistItem> = emptyList(),
        val modeKey: String = "",
        val updatedAt: Long = System.currentTimeMillis(),
    )

    data class PromptProfile(
        val name: String,
        val prompt: String,
    )

    data class ModelProfile(
        val name: String,
        val provider: String = "",
        val modelUrl: String = "",
        val modelName: String = "",
        val modelKey: String = "",
    )

    private data class AiScene(
        val startText: String = "",
        val endText: String = "",
        val mood: String = "",
        val reason: String = "",
        val musicName: String = "",
    )

    private data class PlaylistBuildResult(
        val items: List<PlaylistItem>,
        val statusMessage: String,
        val usedFallback: Boolean = false,
    )

    private val defaultPrompt = """
        根据小说章节内容判断场景氛围，从本地背景音乐文件名中选择最合适的音乐。
        输出时优先匹配情绪、场景、节奏，例如紧张、战斗、安静、温柔、悲伤、悬疑、日常。
    """.trimIndent()

    private var mediaPlayer: MediaPlayer? = null
    private var currentMusicUri: String? = null
    private var currentPlaylist: List<PlaylistItem> = emptyList()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var analyzeJob: Job? = null
    private val analyzingChapterKeys = ConcurrentHashMap.newKeySet<String>()

    const val STATUS_ANALYZING = "analyzing"
    const val STATUS_DONE = "done"
    const val STATUS_WAITING = "waiting"
    const val STATUS_FAILED = "failed"

    var enabled: Boolean
        get() = appCtx.getPrefBoolean(KEY_ENABLED, false)
        set(value) {
            appCtx.putPrefBoolean(KEY_ENABLED, value)
            if (!value) stop()
            postEvent(EventBus.AI_BGM_CHANGED, value)
        }

    var musicDir: String
        get() = appCtx.getPrefString(KEY_DIR).orEmpty()
        set(value) {
            if (value != musicDir) invalidateRuntimePlaylist()
            appCtx.putPrefString(KEY_DIR, value)
        }

    var modelUrl: String
        get() = appCtx.getPrefString(KEY_MODEL_URL).orEmpty()
        set(value) {
            if (value != modelUrl) invalidateRuntimePlaylist()
            appCtx.putPrefString(KEY_MODEL_URL, value)
        }

    var modelName: String
        get() = appCtx.getPrefString(KEY_MODEL_NAME).orEmpty()
        set(value) {
            if (value != modelName) invalidateRuntimePlaylist()
            appCtx.putPrefString(KEY_MODEL_NAME, value)
        }

    var modelKey: String
        get() = appCtx.getPrefString(KEY_MODEL_KEY).orEmpty()
        set(value) {
            if (value != modelKey) invalidateRuntimePlaylist()
            appCtx.putPrefString(KEY_MODEL_KEY, value)
        }

    var prompts: String
        get() = selectedPromptProfile().prompt
        set(value) {
            val name = selectedPromptName()
            val profiles = promptProfiles().toMutableList()
            val index = profiles.indexOfFirst { it.name == name }
            val profile = PromptProfile(name, value.ifBlank { defaultPrompt })
            if (index >= 0) profiles[index] = profile else profiles.add(profile)
            savePromptProfiles(profiles)
            appCtx.putPrefString(KEY_PROMPTS, profile.prompt)
            invalidateRuntimePlaylist()
        }

    var frequency: Int
        get() = appCtx.getPrefInt(KEY_FREQUENCY, FREQUENCY_SCENE)
        set(value) {
            val newValue = value.coerceIn(FREQUENCY_BOOK, FREQUENCY_SCENE)
            if (newValue != frequency) invalidateRuntimePlaylist()
            appCtx.putPrefInt(KEY_FREQUENCY, newValue)
        }

    var scenesPerMusic: Int
        get() = appCtx.getPrefInt(KEY_SCENES_PER_MUSIC, 1)
        set(value) {
            val newValue = value.coerceIn(1, 10)
            if (newValue != scenesPerMusic) invalidateRuntimePlaylist()
            appCtx.putPrefInt(KEY_SCENES_PER_MUSIC, newValue)
        }

    var volume: Int
        get() = appCtx.getPrefInt(KEY_VOLUME, 35)
        set(value) {
            appCtx.putPrefInt(KEY_VOLUME, value.coerceIn(0, 100))
            updateVolume()
        }

    var preloadChapters: Int
        get() = appCtx.getPrefInt(KEY_PRELOAD_CHAPTERS, 5)
        set(value) = appCtx.putPrefInt(KEY_PRELOAD_CHAPTERS, value.coerceIn(1, 200))

    var preloadWholeBook: Boolean
        get() = appCtx.getPrefBoolean(KEY_PRELOAD_WHOLE_BOOK, false)
        set(value) = appCtx.putPrefBoolean(KEY_PRELOAD_WHOLE_BOOK, value)

    fun config(): Config = Config(
        enabled = enabled,
        musicDir = musicDir,
        modelUrl = modelUrl,
        modelName = modelName,
        modelKey = modelKey,
        prompts = prompts,
        frequency = frequency,
        scenesPerMusic = scenesPerMusic,
        volume = volume,
        preloadChapters = preloadChapters,
        preloadWholeBook = preloadWholeBook,
    )

    fun save(config: Config) {
        enabled = config.enabled
        musicDir = config.musicDir
        modelUrl = config.modelUrl
        modelName = config.modelName
        modelKey = config.modelKey
        prompts = config.prompts
        frequency = config.frequency
        scenesPerMusic = config.scenesPerMusic
        volume = config.volume
        preloadChapters = config.preloadChapters
        preloadWholeBook = config.preloadWholeBook
        updateVolume()
    }

    fun promptProfiles(): List<PromptProfile> {
        val json = appCtx.getPrefString(KEY_PROMPT_PROFILES).orEmpty()
        val profiles = runCatching {
            if (json.isBlank()) emptyList()
            else GSON.fromJson(json, Array<PromptProfile>::class.java).toList()
        }.getOrDefault(emptyList())
            .filter { it.name.isNotBlank() && it.prompt.isNotBlank() }
        return profiles.ifEmpty {
            listOf(PromptProfile("默认", appCtx.getPrefString(KEY_PROMPTS, defaultPrompt) ?: defaultPrompt))
        }
    }

    fun savePromptProfiles(profiles: List<PromptProfile>) {
        val normalized = profiles
            .filter { it.name.isNotBlank() && it.prompt.isNotBlank() }
            .distinctBy { it.name.trim() }
            .ifEmpty { listOf(PromptProfile("默认", defaultPrompt)) }
        appCtx.putPrefString(KEY_PROMPT_PROFILES, GSON.toJson(normalized))
        if (normalized.none { it.name == selectedPromptName() }) {
            selectedPromptName = normalized.first().name
        }
    }

    var selectedPromptName: String
        get() = selectedPromptName()
        set(value) {
            appCtx.putPrefString(KEY_SELECTED_PROMPT, value)
            appCtx.putPrefString(KEY_PROMPTS, selectedPromptProfile(value).prompt)
        }

    fun selectedPromptProfile(name: String = selectedPromptName()): PromptProfile {
        return promptProfiles().firstOrNull { it.name == name } ?: promptProfiles().first()
    }

    private fun selectedPromptName(): String {
        return appCtx.getPrefString(KEY_SELECTED_PROMPT).orEmpty().ifBlank {
            promptProfiles().first().name
        }
    }

    fun modelProfiles(): List<ModelProfile> {
        val json = appCtx.getPrefString(KEY_MODEL_PROFILES).orEmpty()
        val profiles = runCatching {
            if (json.isBlank()) emptyList()
            else GSON.fromJson(json, Array<ModelProfile>::class.java).toList()
        }.getOrDefault(emptyList())
            .filter { it.name.isNotBlank() && it.modelUrl.isNotBlank() && it.modelName.isNotBlank() }

        return profiles.ifEmpty {
            if (modelUrl.isNotBlank() || modelName.isNotBlank() || modelKey.isNotBlank()) {
                listOf(
                    ModelProfile(
                        name = "当前配置",
                        modelUrl = modelUrl,
                        modelName = modelName,
                        modelKey = modelKey,
                    )
                )
            } else {
                emptyList()
            }
        }
    }

    fun saveModelProfiles(profiles: List<ModelProfile>) {
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

        appCtx.putPrefString(KEY_MODEL_PROFILES, GSON.toJson(normalized))
        if (selectedModelProfileName.isNotBlank() && normalized.none { it.name == selectedModelProfileName }) {
            selectedModelProfileName = normalized.firstOrNull()?.name.orEmpty()
        }
    }

    var selectedModelProfileName: String
        get() = appCtx.getPrefString(KEY_SELECTED_MODEL_PROFILE).orEmpty()
        set(value) = appCtx.putPrefString(KEY_SELECTED_MODEL_PROFILE, value)

    fun selectedModelProfile(): ModelProfile? {
        return modelProfiles().firstOrNull { it.name == selectedModelProfileName }
    }

    fun selectModelProfile(profile: ModelProfile) {
        selectedModelProfileName = profile.name
        modelUrl = profile.modelUrl
        modelName = profile.modelName
        modelKey = profile.modelKey
    }

    fun upsertSelectedModelProfile(provider: String = "") {
        val selectedName = selectedModelProfileName
        if (selectedName.isBlank()) return

        val profiles = modelProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.name == selectedName }
        if (index < 0) return

        profiles[index] = profiles[index].copy(
            provider = provider.ifBlank { profiles[index].provider },
            modelUrl = modelUrl,
            modelName = modelName,
            modelKey = modelKey,
        )
        saveModelProfiles(profiles)
    }

    fun testModel(): Result<String> = runCatching {
        val url = modelUrl.trim()
        require(url.isNotBlank()) { "请先填写模型地址" }
        require(modelName.isNotBlank()) { "请先填写模型名" }
        val testUrl = normalizeChatCompletionsUrl(url)
        val body = GSON.toJson(
            mapOf(
                "model" to modelName.trim(),
                "messages" to listOf(
                    mapOf("role" to "user", "content" to "ping")
                ),
                "temperature" to 0,
                "max_tokens" to 8
            )
        ).toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(testUrl)
            .apply {
                if (modelKey.isNotBlank()) {
                    header("Authorization", normalizeBearerToken(modelKey))
                }
            }
            .post(body)
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                "模型连接正常 HTTP ${response.code}"
            } else {
                val message = response.body.string().take(300)
                "模型连接失败 HTTP ${response.code}\n$testUrl\n$message"
            }
        }
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

            // 兼容 OpenAI / 智谱 / 千问兼容模式这类已经带版本号的 Base URL：
            // https://open.bigmodel.cn/api/paas/v4
            // https://dashscope.aliyuncs.com/compatible-mode/v1
            lower.matches(Regex(".*/v\\d+(\\.\\d+)?$")) -> "$clean/chat/completions"

            // 兼容常见 OpenAI-compatible base：
            // https://api.openai.com
            // https://api.deepseek.com
            // https://api.longcat.chat/openai
            else -> "$clean/v1/chat/completions"
        }
    }

    private fun normalizeBearerToken(rawKey: String): String {
        val key = rawKey.trim()
        return if (key.startsWith("Bearer ", ignoreCase = true)) key else "Bearer $key"
    }

    fun preparePlaylist(bookName: String, chapterIndex: Int, chapter: TextChapter?): List<PlaylistItem> {
        if (!enabled || chapter == null) return emptyList()
        chapterPlaylist(bookName, chapterIndex).takeIf { it.isNotEmpty() }?.let {
            currentPlaylist = it
            return it
        }
        markWaiting(
            bookName = bookName,
            chapterTitle = chapter.title,
            chapterIndex = chapterIndex,
            message = "等待整章正文完成后再生成背景音乐。"
        )
        return emptyList()
    }

    private fun buildPlaylist(
        bookName: String,
        chapterIndex: Int,
        chapterTitle: String,
        content: String,
        tracks: List<MusicTrack>
    ): List<PlaylistItem> {
        val units = splitUnits(chapterTitle, content)
        val selectedTracks = arrayListOf<MusicTrack>()
        return units.mapIndexed { index, unit ->
            val trackIndex = if (frequency == FREQUENCY_SCENE) index / scenesPerMusic else index
            val track = selectedTracks.getOrNull(trackIndex) ?: chooseTrack(tracks, unit.text, trackIndex)
                .also { selectedTracks.add(it) }
            val mood = detectMood(unit.text)
            PlaylistItem(
                bookName = bookName,
                chapterTitle = chapterTitle,
                chapterIndex = chapterIndex,
                sceneIndex = index + 1,
                unitType = frequencyLabel(frequency),
                start = unit.start,
                end = unit.end,
                musicName = track.name,
                musicUri = track.uri,
                reason = buildReason(unit.text, track.name, index, trackIndex),
                mood = mood,
                sourceText = unit.text.take(220),
                status = STATUS_DONE,
                statusMessage = "已匹配",
                modeKey = modeKey(),
            )
        }
    }

    fun onReadAloudState(play: Boolean, book: Book?, chapterIndex: Int, chapter: TextChapter?) {
        if (!enabled) return
        if (play) {
            ensureAnalysis(book, chapterIndex, chapter, force = false)
            currentPlaylist = chapterPlaylist(book?.name.orEmpty(), chapterIndex)
            playForPosition(0)
        } else {
            pause()
        }
    }

    fun ensureAnalysis(book: Book?, chapterIndex: Int, chapter: TextChapter?, force: Boolean = false) {
        if (book == null) return
        if (!enabled) {
            saveChapterAnalysis(
                ChapterAnalysis(
                    bookName = book.name,
                    chapterTitle = chapter?.title.orEmpty(),
                    chapterIndex = chapterIndex,
                    status = STATUS_FAILED,
                    statusMessage = "智能背景音乐未开启，请先在设置中打开智能背景音乐总开关并保存。",
                    modeKey = modeKey(),
                )
            )
            return
        }
        val existing = chapterAnalysis(book.name, chapterIndex)
        if (force || existing?.status != STATUS_DONE || existing.modeKey != modeKey()) {
            saveChapterAnalysis(
                ChapterAnalysis(
                    bookName = book.name,
                    chapterTitle = chapter?.title.orEmpty(),
                    chapterIndex = chapterIndex,
                    status = STATUS_ANALYZING,
                    statusMessage = "AI 背景音乐分析已触发，正在排队准备整章内容和音乐列表。",
                    modeKey = modeKey(),
                )
            )
        }
        val count = runCatching {
            if (preloadWholeBook) {
                (appDb.bookChapterDao.getChapterCount(book.bookUrl) - chapterIndex).coerceAtLeast(1)
            } else {
                preloadChapters
            }
        }.getOrElse { e ->
            saveChapterAnalysis(
                ChapterAnalysis(
                    bookName = book.name,
                    chapterTitle = chapter?.title.orEmpty(),
                    chapterIndex = chapterIndex,
                    status = STATUS_FAILED,
                    statusMessage = "读取章节数量失败：${e.localizedMessage.orEmpty()}",
                    modeKey = modeKey(),
                )
            )
            return
        }
        analyzeRange(book, chapterIndex, count, chapter, force)
    }

    fun analyzeRange(
        book: Book,
        startChapterIndex: Int,
        chapterCount: Int,
        currentChapter: TextChapter?,
        force: Boolean = false,
    ) {
        val tracks = runCatching { listMusicFiles() }.getOrElse { e ->
            saveChapterAnalysis(
                ChapterAnalysis(
                    bookName = book.name,
                    chapterTitle = currentChapter?.title.orEmpty(),
                    chapterIndex = startChapterIndex,
                    status = STATUS_FAILED,
                    statusMessage = "读取背景音乐目录失败：${e.localizedMessage.orEmpty()}",
                    modeKey = modeKey(),
                )
            )
            return
        }
        if (tracks.isEmpty()) {
            saveChapterAnalysis(
                ChapterAnalysis(
                    bookName = book.name,
                    chapterTitle = currentChapter?.title.orEmpty(),
                    chapterIndex = startChapterIndex,
                    status = STATUS_FAILED,
                    statusMessage = "未找到背景音乐文件，请先选择背景音乐目录。",
                    modeKey = modeKey(),
                )
            )
            return
        }
        val chapterTotal = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        val endExclusive = (startChapterIndex + chapterCount)
            .coerceAtMost(chapterTotal)
        val indices = if (endExclusive > startChapterIndex) {
            startChapterIndex until endExclusive
        } else {
            startChapterIndex..startChapterIndex
        }

        if (true) {
            saveChapterAnalysis(
                ChapterAnalysis(
                    bookName = book.name,
                    chapterTitle = currentChapter?.title.orEmpty(),
                    chapterIndex = startChapterIndex,
                    status = STATUS_ANALYZING,
                    statusMessage = "AI 背景音乐分析已触发，正在准备整章内容和音乐列表。",
                    modeKey = modeKey(),
                )
            )
        }

        analyzeJob = scope.launch {
            indices.forEach { index ->
                val old = chapterAnalysis(book.name, index)
                if (!force && old?.status == STATUS_DONE && old.modeKey == modeKey()) return@forEach

                val chapterKey = "${book.bookUrl}#$index"
                if (!analyzingChapterKeys.add(chapterKey)) return@forEach
                try {
                    analyzeChapter(book, index, if (index == startChapterIndex) currentChapter else null, tracks)
                } finally {
                    analyzingChapterKeys.remove(chapterKey)
                }
            }
        }
    }

    fun onProgress(position: Int) {
        if (!enabled) return
        playForPosition(position)
    }


    private fun analyzeChapter(
        book: Book,
        chapterIndex: Int,
        currentChapter: TextChapter?,
        tracks: List<MusicTrack>
    ) {
        val dbChapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
        val title = currentChapter?.title ?: dbChapter?.title.orEmpty()
        val candidate = chapterContentCandidate(book, dbChapter, currentChapter)
        val invalidReason = invalidContentReason(candidate.content)
        if (invalidReason != null) {
            val message = "等待有效整章正文后再分析。来源=${candidate.source}，文本长度=${candidate.content.length}，原因=$invalidReason"
            AppLog.putDebug("AI背景音乐：$message")
            markWaiting(book.name, title, chapterIndex, message)
            return
        }

        saveChapterAnalysis(
            ChapterAnalysis(
                bookName = book.name,
                chapterTitle = title,
                chapterIndex = chapterIndex,
                status = STATUS_ANALYZING,
                statusMessage = "AI 正在根据整章内容分析场景音乐。来源=${candidate.source}，文本长度=${candidate.content.length}",
                modeKey = modeKey(),
            )
        )

        val result = buildPlaylistWithAi(book.name, chapterIndex, title, candidate.content, tracks)
        if (result.items.isEmpty()) {
            saveChapterAnalysis(
                ChapterAnalysis(
                    bookName = book.name,
                    chapterTitle = title,
                    chapterIndex = chapterIndex,
                    status = STATUS_WAITING,
                    statusMessage = result.statusMessage,
                    modeKey = modeKey(),
                )
            )
            return
        }

        saveChapterAnalysis(
            ChapterAnalysis(
                bookName = book.name,
                chapterTitle = title,
                chapterIndex = chapterIndex,
                status = STATUS_DONE,
                statusMessage = result.statusMessage,
                items = result.items,
                modeKey = modeKey(),
            )
        )
        if (chapterIndex == ReadBook.durChapterIndex) {
            currentPlaylist = result.items
        }
    }

    private data class ContentCandidate(val source: String, val content: String)

    private fun chapterContentCandidate(
        book: Book,
        dbChapter: io.legado.app.data.entities.BookChapter?,
        currentChapter: TextChapter?
    ): ContentCandidate {
        val fromCurrent = normalizeChapterContent(currentChapter?.getContent().orEmpty())
        if (invalidContentReason(fromCurrent) == null) return ContentCandidate("当前整章排版文本", fromCurrent)

        val fromCache = normalizeChapterContent(
            if (dbChapter != null) BookHelp.getContent(book, dbChapter).orEmpty() else ""
        )
        if (invalidContentReason(fromCache) == null) return ContentCandidate("章节正文缓存", fromCache)

        return when {
            fromCurrent.isNotBlank() -> ContentCandidate("当前整章排版文本", fromCurrent)
            else -> ContentCandidate("章节正文缓存", fromCache)
        }
    }

    private fun normalizeChapterContent(content: String): String {
        return content
            .replace(Regex("\\[\\[(emo|emotion|bgm):[^\\]]+\\]\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[ \\t\\u000B\\f\\r]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun invalidContentReason(content: String): String? {
        val compact = content.replace(Regex("\\s+"), "")
        if (compact.isBlank()) return "空文本"
        if (compact.length < 120) return "文本过短"
        if (compact.matches(Regex("^[\\p{Punct}\\p{IsPunctuation}，。！？；：、（）【】《》“”‘’…—·]+$"))) return "纯标点"
        if (content.matches(Regex("^(\\s|<[^>]+>|\\[\\[[^\\]]+\\]\\])+$"))) return "纯标签"
        return null
    }

    private fun markWaiting(bookName: String, chapterTitle: String, chapterIndex: Int, message: String) {
        saveChapterAnalysis(
            ChapterAnalysis(
                bookName = bookName,
                chapterTitle = chapterTitle,
                chapterIndex = chapterIndex,
                status = STATUS_WAITING,
                statusMessage = message,
                modeKey = modeKey(),
            )
        )
    }

    private fun emptyAnalysisDebug(title: String, bookName: String?): String {
        val trackCount = runCatching { listMusicFiles().size }.getOrElse { -1 }
        val dir = musicDir.trim().ifBlank { "未设置" }
        val url = modelUrl.trim().ifBlank { "未设置" }
        val model = modelName.trim().ifBlank { "未设置" }
        val storedJson = appCtx.getPrefString(KEY_PLAYLIST).orEmpty()
        val storedAnalyses = loadAnalyses()
        val storedSummary = storedAnalyses.takeLast(5).joinToString("；") {
            "${it.bookName.ifBlank { "未知书名" }}#${it.chapterIndex + 1}:${it.status}"
        }.ifBlank { "无" }
        return listOf(
            "$title 暂无记录，但已进入诊断。",
            "bookName=${bookName.orEmpty().ifBlank { "未知" }}",
            "enabled=$enabled",
            "musicDir=$dir",
            "musicFileCount=$trackCount",
            "frequency=$frequency",
            "preloadChapters=$preloadChapters",
            "preloadWholeBook=$preloadWholeBook",
            "modelUrl=$url",
            "modelName=$model",
            "storedJsonLength=${storedJson.length}",
            "storedAnalysisCount=${storedAnalyses.size}",
            "storedRecent=$storedSummary",
            "提示：如果 enabled=false，请先打开智能背景音乐总开关并保存；如果 musicFileCount=0，请重新选择背景音乐目录。"
        ).joinToString("\n")
    }

    fun playlistText(bookName: String? = null): String {
        val analyses = allAnalyses(bookName)
        if (analyses.isEmpty()) return emptyAnalysisDebug("背景音乐播放列表", bookName)
        return analyses.joinToString("\n\n") { analysis ->
            when (analysis.status) {
                STATUS_ANALYZING -> "${analysis.chapterTitle.ifBlank { "第 ${analysis.chapterIndex + 1} 章" }}\n${analysis.statusMessage}"
                STATUS_WAITING, STATUS_FAILED -> "${analysis.chapterTitle.ifBlank { "第 ${analysis.chapterIndex + 1} 章" }}\n${analysis.statusMessage}"
                else -> {
                    val title = analysis.chapterTitle.orEmpty().ifBlank { "第 ${analysis.chapterIndex + 1} 章" }
                    val header = "$title：共 ${analysis.items.size} 个${analysis.items.firstOrNull()?.unitType.orEmpty().ifBlank { "场景" }}"
                    val body = analysis.items.joinToString("\n") {
                        "场景 ${it.sceneIndex}｜${it.mood.orEmpty().ifBlank { "未识别氛围" }}｜${it.musicName.orEmpty().ifBlank { "未匹配音乐" }}"
                    }
                    "$header\n$body"
                }
            }
        }
    }

    fun playlistDetailText(bookName: String? = null): String {
        val analyses = allAnalyses(bookName)
        if (analyses.isEmpty()) return emptyAnalysisDebug("AI 分析详情", bookName)
        return analyses.joinToString("\n\n") { analysis ->
            if (analysis.items.isEmpty()) {
                "${analysis.chapterTitle.orEmpty().ifBlank { "第 ${analysis.chapterIndex + 1} 章" }}\n${analysis.statusMessage.orEmpty()}"
            } else {
                val items = analysis.items.joinToString("\n\n") {
                    "场景 ${it.sceneIndex}\n范围：${it.start}-${it.end}\n氛围：${it.mood.orEmpty().ifBlank { "未识别" }}\n音乐：${it.musicName.orEmpty().ifBlank { "未匹配音乐" }}\n理由：${it.reason.orEmpty().ifBlank { "暂无理由" }}\n片段：${it.sourceText.orEmpty()}"
                }
                "${analysis.chapterTitle.orEmpty().ifBlank { "第 ${analysis.chapterIndex + 1} 章" }}：共 ${analysis.items.size} 个场景\n$items"
            }
        }
    }

    fun chapterPlaylist(bookName: String, chapterIndex: Int): List<PlaylistItem> {
        val analysis = chapterAnalysis(bookName, chapterIndex)
        return if (analysis?.modeKey == modeKey() && analysis.status == STATUS_DONE) {
            analysis.items
        } else {
            emptyList()
        }
    }


    private fun safeSubstring(text: String, start: Int, end: Int): String {
        if (text.isEmpty()) return ""
        val safeStart = start.coerceIn(0, text.length)
        val safeEnd = end.coerceIn(0, text.length)
        return if (safeEnd > safeStart) {
            text.substring(safeStart, safeEnd)
        } else {
            ""
        }
    }

    fun listMusicFiles(): List<MusicTrack> {
        val dir = musicDir.trim()
        if (dir.isBlank()) return emptyList()
        return if (dir.startsWith("content://")) {
            val root = DocumentFile.fromTreeUri(appCtx, Uri.parse(dir)) ?: return emptyList()

            fun collectMusicFiles(file: DocumentFile, out: MutableList<MusicTrack>) {
                file.listFiles().forEach { child ->
                    when {
                        child.isFile && child.name?.isMusicFile() == true -> {
                            out.add(MusicTrack(child.name.orEmpty(), child.uri.toString()))
                        }
                        child.isDirectory -> {
                            collectMusicFiles(child, out)
                        }
                    }
                }
            }

            val tracks = arrayListOf<MusicTrack>()
            collectMusicFiles(root, tracks)
            tracks.sortedBy { it.name }
        } else {
            File(dir).walkTopDown()
                .filter { it.isFile && it.name.isMusicFile() }
                .map { MusicTrack(it.name, Uri.fromFile(it).toString()) }
                .sortedBy { it.name }
                .toList()
        }
    }

    private fun playForPosition(position: Int) {
        val item = currentPlaylist.firstOrNull { position in it.start..max(it.start, it.end) }
            ?: currentPlaylist.firstOrNull()
            ?: return
        if (item.musicUri == currentMusicUri && mediaPlayer?.isPlaying == true) return
        playUri(item.musicUri)
    }

    private fun playUri(uri: String) {
        runCatching {
            mediaPlayer?.release()
            currentMusicUri = uri
            mediaPlayer = MediaPlayer().apply {
                setDataSource(appCtx, Uri.parse(uri))
                isLooping = true
                setOnPreparedListener {
                    updateVolume()
                    it.start()
                }
                prepareAsync()
            }
        }
    }

    private fun pause() {
        runCatching { mediaPlayer?.pause() }
    }

    fun stop() {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
        currentMusicUri = null
    }

    private fun invalidateRuntimePlaylist(stopMusic: Boolean = true) {
        currentPlaylist = emptyList()
        if (stopMusic) {
            stop()
        } else {
            currentMusicUri = null
        }
    }

    private fun updateVolume() {
        val value = volume / 100f
        mediaPlayer?.setVolume(value, value)
    }

    private fun savePlaylist(list: List<PlaylistItem>) {
        val old = loadAnalyses().toMutableList()
        list.groupBy { it.bookName to it.chapterIndex }.forEach { (key, items) ->
            val index = old.indexOfFirst { it.bookName == key.first && it.chapterIndex == key.second }
            val analysis = ChapterAnalysis(
                bookName = key.first,
                chapterTitle = items.firstOrNull()?.chapterTitle.orEmpty(),
                chapterIndex = key.second,
                status = STATUS_DONE,
                statusMessage = "已完成，分成 ${items.size} 个播放单元。",
                items = items,
                modeKey = modeKey(),
            )
            if (index >= 0) old[index] = analysis else old.add(analysis)
        }
        appCtx.putPrefString(KEY_PLAYLIST, GSON.toJson(old.sortedBy { it.chapterIndex }))
    }

    private fun loadPlaylist(): List<PlaylistItem> {
        val key = modeKey()
        return loadAnalyses()
            .filter { it.status == STATUS_DONE && it.modeKey == key }
            .flatMap { it.items }
    }

    private fun saveChapterAnalysis(analysis: ChapterAnalysis) {
        val old = loadAnalyses().toMutableList()
        val index = old.indexOfFirst {
            it.bookName == analysis.bookName && it.chapterIndex == analysis.chapterIndex
        }
        if (index >= 0) old[index] = analysis else old.add(analysis)
        appCtx.putPrefString(KEY_PLAYLIST, GSON.toJson(old.sortedWith(compareBy({ it.bookName }, { it.chapterIndex }))))
    }

    private fun chapterAnalysis(bookName: String, chapterIndex: Int): ChapterAnalysis? {
        return loadAnalyses().firstOrNull { it.bookName == bookName && it.chapterIndex == chapterIndex }
    }

    private fun allAnalyses(bookName: String?): List<ChapterAnalysis> {
        return loadAnalyses()
            .filter { bookName.isNullOrBlank() || it.bookName == bookName }
            .sortedBy { it.chapterIndex }
    }

    private fun loadAnalyses(): List<ChapterAnalysis> {
        val json = appCtx.getPrefString(KEY_PLAYLIST).orEmpty()
        if (json.isBlank()) return emptyList()
        return runCatching {
            val array = JsonParser.parseString(json).asJsonArray
            val first = array.firstOrNull()?.asJsonObject
            if (first?.has("items") == true || first?.has("status") == true) {
                GSON.fromJson(json, Array<ChapterAnalysis>::class.java).toList()
                    .mapNotNull { it.normalizedOrNull() }
            } else {
                val oldItems = GSON.fromJson(json, Array<PlaylistItem>::class.java).toList()
                oldItems.map { it.normalized() }.groupBy { it.bookName to it.chapterIndex }.map { (key, items) ->
                    ChapterAnalysis(
                        bookName = key.first,
                        chapterTitle = items.firstOrNull()?.chapterTitle.orEmpty(),
                        chapterIndex = key.second,
                        status = STATUS_DONE,
                        statusMessage = "已完成，分成 ${items.size} 个播放单元。",
                        items = items,
                        modeKey = items.firstOrNull()?.modeKey ?: modeKey(),
                    )
                }
            }
        }.getOrElse { e ->
            AppLog.putDebug("AI背景音乐：播放列表记录读取失败，已进入诊断。${e.localizedMessage.orEmpty()}")
            emptyList()
        }
    }

    private fun ChapterAnalysis.normalizedOrNull(): ChapterAnalysis? {
        if (bookName.isBlank() && chapterTitle.isBlank()) return null
        val safeItems = items.map { it.normalized() }
        val safeStatus = status.orEmpty().ifBlank {
            if (safeItems.isEmpty()) STATUS_WAITING else STATUS_DONE
        }
        return copy(
            status = safeStatus,
            chapterTitle = chapterTitle.orEmpty(),
            statusMessage = statusMessage.orEmpty().ifBlank {
                if (safeStatus == STATUS_DONE) "已完成，分成 ${safeItems.size} 个播放单元。"
                else "AI 还在分析，请你耐心等待，心急吃不了热豆腐"
            },
            items = safeItems,
            modeKey = modeKey.orEmpty().ifBlank { safeItems.firstOrNull()?.modeKey.orEmpty().ifBlank { modeKey() } },
        )
    }

    private fun PlaylistItem.normalized(): PlaylistItem {
        return copy(
            bookName = bookName.orEmpty(),
            chapterTitle = chapterTitle.orEmpty(),
            unitType = unitType.orEmpty(),
            musicName = musicName.orEmpty(),
            musicUri = musicUri.orEmpty(),
            reason = reason.orEmpty(),
            mood = mood.orEmpty(),
            sourceText = sourceText.orEmpty(),
            status = status.orEmpty().ifBlank { STATUS_DONE },
            statusMessage = statusMessage.orEmpty(),
            modeKey = modeKey.orEmpty(),
        )
    }

    private fun splitUnits(title: String, content: String): List<UnitText> {
        if (content.isBlank()) return listOf(UnitText(0, 0, title))
        return when (frequency) {
            FREQUENCY_BOOK -> listOf(UnitText(0, content.length, content.take(800)))
            FREQUENCY_CHAPTER -> listOf(UnitText(0, content.length, "$title\n${content.take(1200)}"))
            else -> splitScenes(content)
        }
    }

    private fun splitScenes(content: String): List<UnitText> {
        val paragraphs = content.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return listOf(UnitText(0, content.length, content.take(1200)))
        val units = arrayListOf<UnitText>()
        var cursor = 0
        var buffer = StringBuilder()
        var start = 0
        paragraphs.forEach { paragraph ->
            if (buffer.isEmpty()) start = cursor
            buffer.append(paragraph).append('\n')
            cursor += paragraph.length + 1
            if (buffer.length >= 900) {
                units.add(UnitText(start, cursor, buffer.toString()))
                buffer = StringBuilder()
            }
        }
        if (buffer.isNotEmpty()) units.add(UnitText(start, cursor, buffer.toString()))
        return units
    }

    private fun chooseTrack(tracks: List<MusicTrack>, text: String, index: Int): MusicTrack {
        val keyword = sceneKeywords.firstOrNull { text.contains(it.first) }?.second
        return tracks.firstOrNull { track ->
            keyword != null && track.name.contains(keyword, ignoreCase = true)
        } ?: tracks[index % tracks.size]
    }

    private fun buildReason(text: String, musicName: String, sceneIndex: Int, trackIndex: Int): String {
        val hit = sceneKeywords.firstOrNull { text.contains(it.first) }
        val sceneGroup = if (frequency == FREQUENCY_SCENE) {
            "第 ${sceneIndex + 1} 个场景，当前每 $scenesPerMusic 个场景共用一种音乐"
        } else {
            "第 ${trackIndex + 1} 个播放单元"
        }
        return if (hit != null) {
            "$sceneGroup；根据场景关键词「${hit.first}」匹配到 $musicName"
        } else {
            "$sceneGroup；按当前切换频率从音乐库轮换选择 $musicName"
        }
    }

    private fun buildPlaylistWithAi(
        bookName: String,
        chapterIndex: Int,
        chapterTitle: String,
        content: String,
        tracks: List<MusicTrack>
    ): PlaylistBuildResult {
        val aiResult = requestAiScenes(chapterTitle, content, tracks)
        val scenes = aiResult.getOrNull()
        if (scenes.isNullOrEmpty()) {
            val reason = aiResult.exceptionOrNull()?.localizedMessage
                ?: "模型没有返回可解析的 scenes JSON。"
            AppLog.putDebug("AI背景音乐：AI 场景分析失败，使用本地兜底生成播放列表。$reason")
            val fallback = buildPlaylist(bookName, chapterIndex, chapterTitle, content, tracks)
                .map {
                    it.copy(
                        reason = "AI 分析失败，已用本地规则兜底。${it.reason}",
                        statusMessage = "本地规则兜底"
                    )
                }
            return PlaylistBuildResult(
                items = fallback,
                statusMessage = if (fallback.isEmpty()) {
                    "AI 场景分析失败，且本地规则也无法生成播放列表：$reason"
                } else {
                    "AI 场景分析失败，已用本地规则按整章文本生成 ${fallback.size} 个播放单元。原因：$reason"
                },
                usedFallback = true,
            )
        }

        val usedTracks = arrayListOf<MusicTrack>()
        val playlist = scenes.mapIndexed { index, scene ->
            val start = scene.startText.takeIf { it.isNotBlank() }
                ?.let { content.indexOf(it).takeIf { pos -> pos >= 0 } }
                ?: ((content.length * index) / scenes.size)
            val end = scene.endText.takeIf { it.isNotBlank() }
                ?.let { content.indexOf(it, start.coerceAtLeast(0)).takeIf { pos -> pos >= 0 }?.plus(it.length) }
                ?: ((content.length * (index + 1)) / scenes.size)
            val musicGroup = if (frequency == FREQUENCY_SCENE) index / scenesPerMusic else index
            val track = usedTracks.getOrNull(musicGroup) ?: chooseAiTrack(tracks, scene, musicGroup)
                .also { usedTracks.add(it) }
            val safeEnd = if (end > start) end else ((content.length * (index + 1)) / scenes.size)
            PlaylistItem(
                bookName = bookName,
                chapterTitle = chapterTitle,
                chapterIndex = chapterIndex,
                sceneIndex = index + 1,
                unitType = frequencyLabel(frequency),
                start = start.coerceAtLeast(0),
                end = safeEnd.coerceAtLeast(start),
                musicName = track.name,
                musicUri = track.uri,
                reason = scene.reason.ifBlank { "AI 根据场景氛围「${scene.mood}」选择 ${track.name}" },
                mood = scene.mood.ifBlank { detectMood(safeSubstring(content, start, safeEnd)) },
                sourceText = safeSubstring(content, start, safeEnd).take(220),
                status = STATUS_DONE,
                statusMessage = "AI 已匹配",
                modeKey = modeKey(),
            )
        }
        return PlaylistBuildResult(
            items = playlist,
            statusMessage = "AI 已完成，按整章场景分成 ${playlist.size} 个播放单元。",
        )
    }

    private fun requestAiScenes(
        chapterTitle: String,
        content: String,
        tracks: List<MusicTrack>
    ): Result<List<AiScene>> = runCatching {
        require(modelUrl.isNotBlank()) { "请先填写模型地址" }
        require(modelName.isNotBlank()) { "请先填写模型名" }
        val promptTracks = promptCandidateTracks(chapterTitle, content, tracks)
        val trackNames = promptTracks.joinToString("\n") { "- ${it.name}" }
        val modeText = when (frequency) {
            FREQUENCY_BOOK -> "整本书一种基调音乐，当前章节只输出 1 个整体场景。"
            FREQUENCY_CHAPTER -> "每章一种背景音乐，当前章节只输出 1 个整体场景。"
            else -> "按剧情场景切分；当前设置为每 $scenesPerMusic 个场景共用一种音乐。"
        }
        val userPrompt = """
            ${prompts}

            播放模式：$modeText
            章节标题：$chapterTitle

            本地背景音乐文件名候选（共 ${tracks.size} 首，已按章节内容筛选 ${promptTracks.size} 首）：
            $trackNames

            章节正文：
            ${content.take(8000)}

            任务：阅读整章正文，判断这一章有几个剧情场景；每个场景都要从“本地背景音乐文件名”中选择一首最贴合的音乐。
            要求：
            1. 只输出 JSON，不要 Markdown，不要解释。
            2. JSON 可以是数组，或 {"scenes":[...]}。
            3. musicName 必须从上面的文件名中原样复制，不能自己编音乐名。
            4. startText/endText 用正文中真实出现的短句，作为这个场景的边界；找不到可留空。
            5. 至少输出 1 个场景；如果剧情明显变化，请输出多个场景。

            每项格式：
            {"startText":"场景开头短句","endText":"场景结尾短句","mood":"氛围标签","reason":"简短理由","musicName":"音乐文件名"}
        """.trimIndent()
        val body = GSON.toJson(
            mapOf(
                "model" to modelName.trim(),
                "messages" to listOf(
                    mapOf("role" to "system", "content" to "你是小说有声书背景音乐场景分析器，必须输出可解析 JSON。"),
                    mapOf("role" to "user", "content" to userPrompt)
                ),
                "temperature" to 0.2,
                "max_tokens" to 3000
            )
        ).toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(normalizeChatCompletionsUrl(modelUrl))
            .apply {
                if (modelKey.isNotBlank()) header("Authorization", normalizeBearerToken(modelKey))
            }
            .post(body)
            .build()
        val aiClient = okHttpClient.newBuilder()
            .callTimeout(90, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        aiClient.newCall(request).execute().use { response ->
            val responseText = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "模型请求失败 HTTP ${response.code}，${responseText.take(500)}"
                )
            }
            val scenes = parseAiScenes(extractChatContent(responseText))
                .filter { it.musicName.isNotBlank() || it.mood.isNotBlank() }
            if (scenes.isEmpty()) {
                throw IllegalStateException("模型返回内容无法解析为场景 JSON：${responseText.take(500)}")
            }
            scenes
        }
    }

    private fun promptCandidateTracks(
        chapterTitle: String,
        content: String,
        tracks: List<MusicTrack>,
    ): List<MusicTrack> {
        if (tracks.size <= 80) return tracks

        val chapterText = listOf(
            chapterTitle,
            detectMood(content),
            content.take(2400),
            content.takeLast(1200),
        ).joinToString("_")

        val scored = tracks
            .map { it to scoreMusicTrack(it.name, chapterText) }
            .sortedWith(compareByDescending<Pair<MusicTrack, Int>> { it.second }.thenBy { it.first.name })

        val matched = scored
            .filter { it.second > 0 }
            .map { it.first }
            .take(70)

        val fill = tracks
            .filterNot { track -> matched.any { it.name == track.name } }
            .take(80 - matched.size)

        return (matched + fill).take(80)
    }

    private fun parseAiScenes(contentText: String): List<AiScene> {
        jsonCandidates(contentText).forEach { candidate ->
            runCatching {
                return jsonElementToScenes(JsonParser.parseString(candidate))
            }
        }
        return emptyList()
    }

    private fun jsonCandidates(raw: String): List<String> {
        val clean = raw
            .replace("```json", "```", ignoreCase = true)
            .trim()
        val list = arrayListOf<String>()
        if ("```" in clean) {
            clean.split("```")
                .map { it.trim() }
                .filter { it.startsWith("[") || it.startsWith("{") }
                .forEach { list.add(it) }
        }
        list.add(clean)
        val arrayStart = clean.indexOf('[')
        val arrayEnd = clean.lastIndexOf(']')
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            list.add(clean.substring(arrayStart, arrayEnd + 1))
        }
        val objectStart = clean.indexOf('{')
        val objectEnd = clean.lastIndexOf('}')
        if (objectStart >= 0 && objectEnd > objectStart) {
            list.add(clean.substring(objectStart, objectEnd + 1))
        }
        return list.distinct()
    }

    private fun jsonElementToScenes(element: JsonElement): List<AiScene> {
        val array = when {
            element.isJsonArray -> element.asJsonArray
            element.isJsonObject -> {
                val obj = element.asJsonObject
                when {
                    obj["scenes"]?.isJsonArray == true -> obj["scenes"].asJsonArray
                    obj["items"]?.isJsonArray == true -> obj["items"].asJsonArray
                    obj["data"]?.isJsonArray == true -> obj["data"].asJsonArray
                    else -> JsonArray().apply { add(element) }
                }
            }
            else -> JsonArray()
        }
        return array.mapNotNull { item ->
            runCatching {
                val obj = item.asJsonObject
                AiScene(
                    startText = obj.stringValue("startText", "start_text", "start", "begin", "开头", "起始文本", "场景开头"),
                    endText = obj.stringValue("endText", "end_text", "end", "结尾", "结束文本", "场景结尾"),
                    mood = obj.stringValue("mood", "emotion", "atmosphere", "氛围", "情绪", "场景氛围"),
                    reason = obj.stringValue("reason", "why", "理由", "选择理由"),
                    musicName = obj.stringValue(
                        "musicName",
                        "music_name",
                        "musicFile",
                        "music_file",
                        "music",
                        "track",
                        "bgm",
                        "backgroundMusic",
                        "背景音乐",
                        "音乐"
                    )
                )
            }.getOrNull()
        }
    }

    private fun com.google.gson.JsonObject.stringValue(vararg keys: String): String {
        keys.forEach { key ->
            val value = get(key)
            if (value != null && value.isJsonPrimitive) return value.asString.orEmpty()
        }
        return ""
    }

    private fun extractChatContent(responseText: String): String {
        return runCatching {
            JsonParser.parseString(responseText)
                .asJsonObject["choices"].asJsonArray[0]
                .asJsonObject["message"].asJsonObject["content"].asString
        }.getOrDefault(responseText)
    }


    private fun musicFileNameOnly(value: String): String {
        return value.substringAfterLast('/').substringAfterLast('\\').trim()
    }

    private fun stripMusicExtension(value: String): String {
        return musicFileNameOnly(value)
            .replace(Regex("\\.(mp3|wav|m4a|aac|ogg|flac)$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun normalizeMusicMatchKey(value: String): String {
        return stripMusicExtension(value)
            .lowercase()
            .replace(Regex("[\\s\\u3000_\\-·.()（）\\[\\]【】]+"), "")
            .trim()
    }

    private fun extractMusicTags(value: String): List<String> {
        return stripMusicExtension(value)
            .split("_", " ", "　", "-", "·", "/", "\\")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.matches(Regex("^\\d+$")) }
            .filterNot { it.equals("mp3", true) || it.equals("wav", true) || it.equals("m4a", true) || it.equals("aac", true) || it.equals("ogg", true) || it.equals("flac", true) }
            .distinct()
    }

    private fun findRequestedMusicTrack(tracks: List<MusicTrack>, requested: String): MusicTrack? {
        val raw = requested.trim()
        if (raw.isBlank()) return null

        tracks.firstOrNull { it.name == raw || it.uri == raw }?.let { return it }

        val rawFile = musicFileNameOnly(raw)
        tracks.firstOrNull { it.name == rawFile }?.let { return it }

        val key = normalizeMusicMatchKey(raw)
        if (key.isBlank()) return null

        tracks.firstOrNull { normalizeMusicMatchKey(it.name) == key }?.let { return it }

        return tracks.firstOrNull {
            val trackKey = normalizeMusicMatchKey(it.name)
            trackKey.isNotBlank() && (trackKey.contains(key) || key.contains(trackKey))
        }
    }

    private fun scoreMusicTrack(trackName: String, sceneText: String): Int {
        val trackKey = normalizeMusicMatchKey(trackName)
        val sceneKey = normalizeMusicMatchKey(sceneText)
        if (trackKey.isBlank() || sceneKey.isBlank()) return 0

        var score = 0

        // 1. 整体归一化包含关系：适合 AI 返回了部分文件名、漏扩展名、漏空格
        if (trackKey == sceneKey) score += 1000
        if (trackKey.contains(sceneKey) || sceneKey.contains(trackKey)) score += 300

        // 2. 通用标签重叠：不写死任何一套背景音乐标签体系
        val trackTags = extractMusicTags(trackName)
        val sceneTags = extractMusicTags(sceneText)

        trackTags.forEach { tag ->
            val tagKey = normalizeMusicMatchKey(tag)
            if (tagKey.isBlank()) return@forEach

            when {
                sceneTags.any { normalizeMusicMatchKey(it) == tagKey } -> {
                    score += 20 + tagKey.length.coerceAtMost(8)
                }
                sceneKey.contains(tagKey) -> {
                    score += 8 + tagKey.length.coerceAtMost(6)
                }
            }
        }

        // 3. 文件名前缀略加分：适合同一套音乐用统一前缀命名
        val trackMain = stripMusicExtension(trackName)
        val sceneMain = stripMusicExtension(sceneText)
        val commonPrefixLen = trackMain.zip(sceneMain)
            .takeWhile { it.first == it.second }
            .size

        if (commonPrefixLen >= 2) {
            score += commonPrefixLen.coerceAtMost(20)
        }

        return score
    }

    private fun chooseAiTrack(tracks: List<MusicTrack>, scene: AiScene, musicGroup: Int): MusicTrack {
        if (tracks.isEmpty()) return MusicTrack("", "")

        val requested = scene.musicName.trim()
        findRequestedMusicTrack(tracks, requested)?.let { return it }

        if (requested.isNotBlank()) {
            AppLog.putDebug("AI背景音乐：模型返回的音乐名未精确命中，改用宽松标签匹配：$requested")
        }

        val sceneText = listOf(
            scene.musicName,
            scene.mood,
            scene.reason,
            scene.startText,
            scene.endText
        ).joinToString("_")

        val best = tracks
            .map { it to scoreMusicTrack(it.name, sceneText) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first

        if (best != null) return best

        return tracks.getOrElse(musicGroup.mod(tracks.size)) { tracks.first() }
    }

    private fun detectMood(text: String): String {
        val hits = sceneKeywords
            .filter { text.contains(it.first) }
            .map { it.second }
            .distinct()
            .take(3)
        return hits.joinToString("、").ifBlank {
            when {
                text.contains("？") || text.contains("?") -> "对话、疑问"
                text.contains("！") || text.contains("!") -> "情绪、强调"
                else -> "日常、叙事"
            }
        }
    }

    private fun modeKey(): String {
        val safeUrl = runCatching { normalizeChatCompletionsUrl(modelUrl) }.getOrElse { modelUrl.trim() }
        return listOf(
            "frequency=$frequency",
            "scenesPerMusic=$scenesPerMusic",
            "musicDir=${musicDir.trim()}",
            "prompt=${selectedPromptName()}",
            "promptHash=${prompts.hashCode()}",
            "modelUrl=$safeUrl",
            "modelName=${modelName.trim()}",
        ).joinToString("|")
    }

    private fun frequencyLabel(value: Int): String = when (value) {
        FREQUENCY_BOOK -> "整本书"
        FREQUENCY_CHAPTER -> "章节"
        else -> "场景"
    }

    private fun String.isMusicFile(): Boolean {
        return endsWith(".mp3", true) ||
            endsWith(".m4a", true) ||
            endsWith(".aac", true) ||
            endsWith(".wav", true) ||
            endsWith(".ogg", true) ||
            endsWith(".flac", true)
    }

    data class MusicTrack(val name: String, val uri: String)
    private data class UnitText(val start: Int, val end: Int, val text: String)

    private val sceneKeywords = listOf(
        "战" to "战",
        "杀" to "战",
        "剑" to "战",
        "血" to "紧张",
        "逃" to "紧张",
        "惊" to "悬疑",
        "夜" to "夜",
        "雨" to "雨",
        "哭" to "悲",
        "泪" to "悲",
        "笑" to "轻松",
        "温柔" to "温柔",
        "安静" to "安静",
    )
}
