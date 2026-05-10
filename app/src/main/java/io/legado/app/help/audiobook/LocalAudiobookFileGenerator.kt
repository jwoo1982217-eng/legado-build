package io.legado.app.help.audiobook

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.TtsServerDbBridge
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.StringUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object LocalAudiobookFileGenerator {

    data class Progress(
        val status: String,
        val message: String,
        val totalChapters: Int,
        val readyChapters: Int,
        val failedChapters: Int,
        val totalItems: Int,
        val readyItems: Int,
        val failedItems: Int,
        val lastFilePath: String = "",
    ) {
        val isReady: Boolean get() = status == "ready"
    }

    data class ChapterStatus(
        val status: String,
        val path: String,
        val format: String,
        val sizeBytes: Long,
        val totalItems: Int,
        val readyItems: Int,
        val failedItems: Int,
        val error: String,
        val updatedAt: Long,
    )

    fun shouldUseTtsServerBridge(): Boolean {
        return resolveEnginePlan() is EnginePlan.TtsServer
    }

    fun inspectChapterStatus(
        context: Context,
        bookName: String,
        chapter: TtsServerDbBridge.AudiobookChapter
    ): ChapterStatus {
        val dir = chapterDir(context.applicationContext, bookName)
        val baseName = chapterFileBaseName(chapter)
        val manifestFile = File(dir, "$baseName.json")
        if (manifestFile.exists() && manifestFile.length() > 0) {
            return runCatching {
                val json = JSONObject(manifestFile.readText(Charsets.UTF_8))
                val items = json.optJSONArray("items") ?: JSONArray()
                var readyItems = 0
                var failedItems = 0
                for (index in 0 until items.length()) {
                    val itemStatus = items.optJSONObject(index)?.optString("status").orEmpty()
                    when (itemStatus.lowercase()) {
                        "ready", "completed" -> readyItems++
                        "failed" -> failedItems++
                    }
                }
                ChapterStatus(
                    status = json.optString("status").ifBlank { "unknown" },
                    path = json.optString("path"),
                    format = json.optString("format"),
                    sizeBytes = json.optLong("sizeBytes", 0L),
                    totalItems = items.length(),
                    readyItems = readyItems,
                    failedItems = failedItems,
                    error = json.optString("error"),
                    updatedAt = json.optLong("updatedAt", manifestFile.lastModified()),
                )
            }.getOrElse { error ->
                ChapterStatus(
                    status = "failed",
                    path = manifestFile.absolutePath,
                    format = "json",
                    sizeBytes = manifestFile.length(),
                    totalItems = 0,
                    readyItems = 0,
                    failedItems = 0,
                    error = "manifest 解析失败：${error.localizedMessage ?: error.javaClass.simpleName}",
                    updatedAt = manifestFile.lastModified(),
                )
            }
        }

        val audioFile = listOf("mp3", "wav", "audio")
            .asSequence()
            .map { File(dir, "$baseName.$it") }
            .firstOrNull { it.exists() && it.length() > 0 }
        if (audioFile != null) {
            return ChapterStatus(
                status = "ready",
                path = audioFile.absolutePath,
                format = audioFile.extension,
                sizeBytes = audioFile.length(),
                totalItems = 0,
                readyItems = 0,
                failedItems = 0,
                error = "",
                updatedAt = audioFile.lastModified(),
            )
        }

        return ChapterStatus(
            status = "not_generated",
            path = "",
            format = "",
            sizeBytes = 0L,
            totalItems = 0,
            readyItems = 0,
            failedItems = 0,
            error = "",
            updatedAt = 0L,
        )
    }

    suspend fun generate(
        context: Context,
        bookName: String,
        bookUrl: String,
        chapters: List<TtsServerDbBridge.AudiobookChapter>,
        onProgress: suspend (Progress) -> Unit,
    ): Progress {
        val plan = resolveEnginePlan()
        if (plan is EnginePlan.TtsServer) {
            return Progress(
                status = "failed",
                message = "当前是 J.TTS 直通引擎，应交给 TTS 端生成",
                totalChapters = chapters.size,
                readyChapters = 0,
                failedChapters = 0,
                totalItems = 0,
                readyItems = 0,
                failedItems = 0
            )
        }

        val appContext = context.applicationContext
        var readyChapters = 0
        var failedChapters = 0
        var totalItems = 0
        var readyItems = 0
        var failedItems = 0
        var lastFilePath = ""

        suspend fun push(status: String, message: String) {
            onProgress(
                Progress(
                    status = status,
                    message = message,
                    totalChapters = chapters.size,
                    readyChapters = readyChapters,
                    failedChapters = failedChapters,
                    totalItems = totalItems,
                    readyItems = readyItems,
                    failedItems = failedItems,
                    lastFilePath = lastFilePath
                )
            )
        }

        push("pending", "阅读端已接管当前朗读引擎，准备生成整章音频")

        chapters.forEachIndexed { chapterPosition, chapter ->
            currentCoroutineContext().ensureActive()
            val segments = splitSegments(chapter.chapterTitle, chapter.chapterText)
            totalItems += segments.size
            push(
                status = "caching_audio",
                message = "正在生成第 ${chapterPosition + 1}/${chapters.size} 章：${chapter.chapterTitle.ifBlank { chapter.chapterIndex.toString() }}"
            )

            val result = runCatching {
                when (plan) {
                    is EnginePlan.Http -> generateHttpChapter(
                        context = appContext,
                        bookName = bookName,
                        chapter = chapter,
                        segments = segments,
                        httpTts = plan.httpTTS,
                        onItemReady = {
                            readyItems += 1
                            push(
                                status = "caching_audio",
                                message = "正在生成第 ${chapterPosition + 1}/${chapters.size} 章：${chapter.chapterTitle.ifBlank { chapter.chapterIndex.toString() }}"
                            )
                        }
                    )

                    is EnginePlan.System -> generateSystemChapter(
                        context = appContext,
                        bookName = bookName,
                        chapter = chapter,
                        segments = segments,
                        engine = plan.engine,
                        onItemReady = {
                            readyItems += 1
                            push(
                                status = "caching_audio",
                                message = "正在生成第 ${chapterPosition + 1}/${chapters.size} 章：${chapter.chapterTitle.ifBlank { chapter.chapterIndex.toString() }}"
                            )
                        }
                    )

                    EnginePlan.TtsServer -> error("J.TTS 直通引擎应交给 TTS 端生成")
                }
            }

            result.onSuccess { chapterResult ->
                readyChapters += 1
                lastFilePath = chapterResult.file.absolutePath
                writeManifest(
                    context = appContext,
                    bookName = bookName,
                    bookUrl = bookUrl,
                    chapter = chapter,
                    engineName = plan.displayName,
                    status = "ready",
                    file = chapterResult.file,
                    error = "",
                    items = chapterResult.items
                )
                push(
                    status = "caching_audio",
                    message = "已生成第 ${chapterPosition + 1}/${chapters.size} 章：${chapterResult.file.name}"
                )
            }.onFailure {
                failedChapters += 1
                failedItems += segments.size
                writeManifest(
                    context = appContext,
                    bookName = bookName,
                    bookUrl = bookUrl,
                    chapter = chapter,
                    engineName = plan.displayName,
                    status = "failed",
                    file = null,
                    error = it.localizedMessage ?: it.javaClass.simpleName,
                    items = emptyList()
                )
                push(
                    status = "caching_audio",
                    message = "第 ${chapterPosition + 1}/${chapters.size} 章生成失败：${it.localizedMessage ?: it.javaClass.simpleName}"
                )
            }
        }

        return Progress(
            status = if (failedChapters > 0) "failed" else "ready",
            message = if (failedChapters > 0) {
                "整章音频生成完成，但有 $failedChapters 章失败"
            } else {
                "整章音频已生成：$readyChapters/${chapters.size} 章"
            },
            totalChapters = chapters.size,
            readyChapters = readyChapters,
            failedChapters = failedChapters,
            totalItems = totalItems,
            readyItems = readyItems,
            failedItems = failedItems,
            lastFilePath = lastFilePath
        ).also { onProgress(it) }
    }

    suspend fun generateFinishedReadAloudChapter(
        context: Context,
        bookName: String,
        bookUrl: String,
        chapter: TtsServerDbBridge.AudiobookChapter,
        preferredHttpTts: HttpTTS? = null,
    ): ChapterStatus {
        val appContext = context.applicationContext
        val segments = splitSegments(chapter.chapterTitle, chapter.chapterText)
        val result = if (preferredHttpTts != null) {
            generateHttpChapter(
                context = appContext,
                bookName = bookName,
                chapter = chapter,
                segments = segments,
                httpTts = preferredHttpTts,
                onItemReady = {}
            ) to preferredHttpTts.name.ifBlank { "HTTP TTS" }
        } else {
            when (val plan = resolveEnginePlan()) {
                is EnginePlan.Http -> generateHttpChapter(
                    context = appContext,
                    bookName = bookName,
                    chapter = chapter,
                    segments = segments,
                    httpTts = plan.httpTTS,
                    onItemReady = {}
                ) to plan.displayName

                is EnginePlan.System -> generateSystemChapter(
                    context = appContext,
                    bookName = bookName,
                    chapter = chapter,
                    segments = segments,
                    engine = plan.engine,
                    onItemReady = {}
                ) to plan.displayName

                EnginePlan.TtsServer -> error("J.TTS 直通引擎请从朗读服务传入当前 HTTP TTS 缓存")
            }
        }

        writeManifest(
            context = appContext,
            bookName = bookName,
            bookUrl = bookUrl,
            chapter = chapter,
            engineName = result.second,
            status = "ready",
            file = result.first.file,
            error = "",
            items = result.first.items
        )
        return inspectChapterStatus(appContext, bookName, chapter)
    }

    private fun resolveEnginePlan(): EnginePlan {
        val ttsEngine = ReadAloud.ttsEngine
        if (!ttsEngine.isNullOrBlank() && StringUtils.isNumeric(ttsEngine)) {
            val httpTts = appDb.httpTTSDao.get(ttsEngine.toLong())
            if (httpTts != null) return EnginePlan.Http(httpTts)
        }

        val sysEngine = GSON.fromJsonObject<SelectItem<String>>(ttsEngine)
            .getOrNull()
            ?.value

        return when {
            ttsEngine.isNullOrBlank() || sysEngine.isNullOrBlank() || sysEngine == TtsServerDbBridge.TTS_PACKAGE -> {
                EnginePlan.TtsServer
            }

            else -> EnginePlan.System(sysEngine)
        }
    }

    private suspend fun generateHttpChapter(
        context: Context,
        bookName: String,
        chapter: TtsServerDbBridge.AudiobookChapter,
        segments: List<String>,
        httpTts: HttpTTS,
        onItemReady: suspend () -> Unit,
    ): ChapterBuildResult {
        val segmentDir = segmentDir(
            context = context,
            bookName = bookName,
            chapter = chapter,
            engineKey = "http_${httpTts.id}_${httpTts.url.hashCode()}"
        )
        val audioSegments = mutableListOf<AudioSegment>()
        for ((index, segment) in segments.withIndex()) {
            currentCoroutineContext().ensureActive()
            val speakText = segment.replace(AppPattern.notReadAloudRegex, "")
            if (speakText.isBlank()) continue
            audioSegments += obtainHttpSegment(
                context = context,
                segmentDir = segmentDir,
                httpTts = httpTts,
                chapterTitle = chapter.chapterTitle,
                segmentIndex = index,
                sourceText = segment,
                speakText = speakText
            )
            onItemReady()
        }
        if (audioSegments.isEmpty()) error("当前章节没有生成到可用音频片段")

        val outDir = chapterDir(context, bookName)
        val baseName = chapterFileBaseName(chapter)
        val bytesList = audioSegments.map { it.bytes }
        val outFile = when {
            bytesList.all { it.looksLikeMp3() } -> File(outDir, "$baseName.mp3").also {
                it.outputStream().use { output ->
                    bytesList.forEachIndexed { index, bytes ->
                        copyMp3Payload(bytes, output, keepLeadingTags = index == 0)
                    }
                }
            }

            bytesList.all { it.looksLikeWav() } -> File(outDir, "$baseName.wav").also {
                writeMergedWav(bytesList, it)
            }

            else -> File(outDir, "$baseName.audio").also {
                it.outputStream().use { output -> bytesList.forEach { bytes -> output.write(bytes) } }
            }
        }

        if (outFile.length() <= 0) error("整章音频文件为空")
        return ChapterBuildResult(outFile, audioSegments.map { it.toManifestItem() })
    }

    private suspend fun generateSystemChapter(
        context: Context,
        bookName: String,
        chapter: TtsServerDbBridge.AudiobookChapter,
        segments: List<String>,
        engine: String,
        onItemReady: suspend () -> Unit,
    ): ChapterBuildResult {
        val outDir = chapterDir(context, bookName)
        val segmentDir = segmentDir(
            context = context,
            bookName = bookName,
            chapter = chapter,
            engineKey = "system_${engine.hashCode()}"
        )

        val tts = createTextToSpeech(context, engine)
        try {
            val audioSegments = mutableListOf<AudioSegment>()
            withContext(Main) {
                tts.setSpeechRate((AppConfig.ttsSpeechRate + 5) / 10f)
            }

            for ((index, segment) in segments.withIndex()) {
                currentCoroutineContext().ensureActive()
                val speakText = segment.replace(AppPattern.notReadAloudRegex, "")
                if (speakText.isBlank()) continue
                val cached = findSegmentCache(segmentDir, index, segment)
                val file = cached ?: segmentCacheFile(segmentDir, index, segment, "wav")
                if (cached == null) {
                    synthesizeToFile(tts, speakText, file)
                }
                if (!file.exists() || file.length() <= 0) {
                    error("系统 TTS 没有生成可用音频片段")
                }
                audioSegments += AudioSegment(
                    index = index,
                    text = segment,
                    file = file,
                    bytes = file.readBytes(),
                    fromCache = cached != null
                )
                onItemReady()
            }

            if (audioSegments.isEmpty()) error("当前章节没有生成到可用音频片段")
            val outFile = File(outDir, "${chapterFileBaseName(chapter)}.wav")
            writeMergedWav(audioSegments.map { it.bytes }, outFile)
            if (outFile.length() <= 0) error("整章音频文件为空")
            return ChapterBuildResult(outFile, audioSegments.map { it.toManifestItem() })
        } finally {
            withContext(Main) {
                tts.stop()
                tts.shutdown()
            }
        }
    }

    private suspend fun obtainHttpSegment(
        context: Context,
        segmentDir: File,
        httpTts: HttpTTS,
        chapterTitle: String,
        segmentIndex: Int,
        sourceText: String,
        speakText: String,
    ): AudioSegment {
        findSegmentCache(segmentDir, segmentIndex, sourceText)?.let { cached ->
            return AudioSegment(
                index = segmentIndex,
                text = sourceText,
                file = cached,
                bytes = cached.readBytes(),
                fromCache = true
            )
        }

        findReadAloudHttpCache(context, httpTts, chapterTitle, sourceText)?.let { cached ->
            val bytes = cached.readBytes()
            val file = writeSegmentCache(segmentDir, segmentIndex, sourceText, bytes)
            return AudioSegment(
                index = segmentIndex,
                text = sourceText,
                file = file,
                bytes = bytes,
                fromCache = true
            )
        }

        val bytes = requestHttpAudio(httpTts, speakText)
        val file = writeSegmentCache(segmentDir, segmentIndex, sourceText, bytes)
        return AudioSegment(
            index = segmentIndex,
            text = sourceText,
            file = file,
            bytes = bytes,
            fromCache = false
        )
    }

    private suspend fun requestHttpAudio(httpTts: HttpTTS, speakText: String): ByteArray {
        val analyzeUrl = AnalyzeUrl(
            httpTts.url,
            speakText = speakText,
            speakSpeed = AppConfig.speechRatePlay + 5,
            source = httpTts,
            readTimeout = 300 * 1000L,
            coroutineContext = currentCoroutineContext()
        )
        var response = analyzeUrl.getResponseAwait()
        currentCoroutineContext().ensureActive()
        val checkJs = httpTts.loginCheckJs
        if (checkJs?.isNotBlank() == true) {
            response = analyzeUrl.evalJS(checkJs, response) as Response
        }
        response.use {
            it.headers["Content-Type"]?.let { rawType ->
                val contentType = rawType.substringBefore(";")
                val expected = httpTts.contentType
                if (contentType == "application/json" || contentType.startsWith("text/")) {
                    throw NoStackTraceException(it.body.string())
                } else if (expected?.isNotBlank() == true && !contentType.matches(expected.toRegex())) {
                    throw NoStackTraceException("TTS服务器返回错误：" + it.body.string())
                }
            }
            return it.body.bytes()
        }
    }

    private suspend fun createTextToSpeech(context: Context, engine: String): TextToSpeech {
        return withContext(Main) {
            suspendCancellableCoroutine { cont ->
                var tts: TextToSpeech? = null
                val listener = TextToSpeech.OnInitListener { status ->
                    val current = tts
                    if (status == TextToSpeech.SUCCESS && current != null) {
                        cont.resume(current)
                    } else {
                        current?.shutdown()
                        cont.resumeWithException(NoStackTraceException("系统 TTS 初始化失败"))
                    }
                }
                tts = TextToSpeech(context.applicationContext, listener, engine)
                cont.invokeOnCancellation {
                    tts.shutdown()
                }
            }
        }
    }

    private suspend fun synthesizeToFile(tts: TextToSpeech, text: String, file: File) {
        withContext(Main) {
            suspendCancellableCoroutine { cont ->
                val utteranceId = "audiobook-${UUID.randomUUID()}"
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(doneId: String?) {
                        if (doneId == utteranceId && cont.isActive) cont.resume(Unit)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(errorId: String?) {
                        if (errorId == utteranceId && cont.isActive) {
                            cont.resumeWithException(NoStackTraceException("系统 TTS 合成失败"))
                        }
                    }

                    override fun onError(errorId: String?, errorCode: Int) {
                        if (errorId == utteranceId && cont.isActive) {
                            cont.resumeWithException(NoStackTraceException("系统 TTS 合成失败：$errorCode"))
                        }
                    }
                })
                file.parentFile?.mkdirs()
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                }
                val result = tts.synthesizeToFile(text, params, file, utteranceId)
                if (result == TextToSpeech.ERROR && cont.isActive) {
                    cont.resumeWithException(NoStackTraceException("系统 TTS 不支持写入音频文件"))
                }
                cont.invokeOnCancellation {
                    tts.stop()
                }
            }
        }
        if (!file.exists() || file.length() <= 0) {
            throw NoStackTraceException("系统 TTS 合成文件为空")
        }
    }

    private fun splitSegments(chapterTitle: String, text: String): List<String> {
        val raw = text.splitNotBlank("\n")
            .flatMap { paragraph -> paragraph.chunkForTts() }
            .filter { it.any { ch -> ch.isLetterOrDigit() } }
        return raw.ifEmpty { listOf(chapterTitle.ifBlank { text.take(200) }) }
    }

    private fun String.chunkForTts(maxLength: Int = 1800): List<String> {
        if (length <= maxLength) return listOf(this)
        val result = mutableListOf<String>()
        var rest = this
        while (rest.length > maxLength) {
            val window = rest.take(maxLength)
            val splitAt = window.lastIndexOfAny(charArrayOf('。', '！', '？', '.', '!', '?', '；', ';', '，', ','))
                .takeIf { it > maxLength / 3 }
                ?: maxLength
            result += rest.take(splitAt + 1).trim()
            rest = rest.drop(splitAt + 1).trim()
        }
        if (rest.isNotBlank()) result += rest
        return result
    }

    private fun writeMergedWav(chunks: List<ByteArray>, outFile: File) {
        val wavChunks = chunks.map { parseWav(it) }
        val first = wavChunks.firstOrNull() ?: error("没有可合并的 WAV 片段")
        if (wavChunks.any { !it.compatibleWith(first) }) {
            error("音频片段格式不一致，暂无法合并为 WAV")
        }
        val dataSize = wavChunks.sumOf { it.data.size }
        outFile.outputStream().use { output ->
            output.writeWavHeader(
                sampleRate = first.sampleRate,
                channels = first.channels,
                bitsPerSample = first.bitsPerSample,
                dataSize = dataSize
            )
            wavChunks.forEach { output.write(it.data) }
        }
    }

    private fun parseWav(bytes: ByteArray): WavChunk {
        if (!bytes.looksLikeWav()) error("TTS 输出不是 WAV 音频")
        var offset = 12
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var audioFormat = 0
        var data: ByteArray? = null

        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = bytes.leInt(offset + 4).coerceAtLeast(0)
            val start = offset + 8
            val end = (start + size).coerceAtMost(bytes.size)
            when (id) {
                "fmt " -> {
                    audioFormat = bytes.leShort(start)
                    channels = bytes.leShort(start + 2)
                    sampleRate = bytes.leInt(start + 4)
                    bitsPerSample = bytes.leShort(start + 14)
                }

                "data" -> {
                    data = bytes.copyOfRange(start, end)
                }
            }
            offset = end + (size and 1)
        }

        if (audioFormat != 1) error("只支持 PCM WAV，当前格式=$audioFormat")
        val pcm = data ?: error("WAV 缺少 data 块")
        return WavChunk(channels, sampleRate, bitsPerSample, pcm)
    }

    private fun OutputStream.writeWavHeader(
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        dataSize: Int,
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        write(buffer.array())
    }

    private fun copyMp3Payload(bytes: ByteArray, output: OutputStream, keepLeadingTags: Boolean) {
        if (bytes.isEmpty()) return
        val end = bytes.stripTrailingId3v1End()
        var start = if (keepLeadingTags) 0 else bytes.skipLeadingId3v2()
        if (!keepLeadingTags) {
            start = bytes.findMp3FrameStart(start).takeIf { it >= 0 } ?: start
        }
        if (start < end) output.write(bytes, start, end - start)
    }

    private fun ByteArray.looksLikeMp3(): Boolean {
        if (size >= 3 && this[0] == 'I'.code.toByte() && this[1] == 'D'.code.toByte() && this[2] == '3'.code.toByte()) {
            return true
        }
        return findMp3FrameStart(0).let { it in 0..1024 }
    }

    private fun ByteArray.looksLikeWav(): Boolean {
        return size > 12 &&
            String(this, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(this, 8, 4, Charsets.US_ASCII) == "WAVE"
    }

    private fun ByteArray.audioExtension(): String {
        return when {
            looksLikeMp3() -> "mp3"
            looksLikeWav() -> "wav"
            else -> "audio"
        }
    }

    private fun ByteArray.skipLeadingId3v2(): Int {
        if (size < 10) return 0
        if (this[0] != 'I'.code.toByte() || this[1] != 'D'.code.toByte() || this[2] != '3'.code.toByte()) return 0
        val tagSize =
            ((this[6].toInt() and 0x7F) shl 21) or
                ((this[7].toInt() and 0x7F) shl 14) or
                ((this[8].toInt() and 0x7F) shl 7) or
                (this[9].toInt() and 0x7F)
        val hasFooter = (this[5].toInt() and 0x10) != 0
        return (10 + tagSize + if (hasFooter) 10 else 0).coerceAtMost(size)
    }

    private fun ByteArray.stripTrailingId3v1End(): Int {
        if (size < 128) return size
        val start = size - 128
        return if (
            this[start] == 'T'.code.toByte() &&
            this[start + 1] == 'A'.code.toByte() &&
            this[start + 2] == 'G'.code.toByte()
        ) start else size
    }

    private fun ByteArray.findMp3FrameStart(fromIndex: Int): Int {
        val start = fromIndex.coerceAtLeast(0)
        for (i in start until size - 1) {
            if ((this[i].toInt() and 0xFF) == 0xFF && (this[i + 1].toInt() and 0xE0) == 0xE0) {
                return i
            }
        }
        return -1
    }

    private fun ByteArray.leInt(offset: Int): Int {
        return ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun ByteArray.leShort(offset: Int): Int {
        return ByteBuffer.wrap(this, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
    }

    private fun chapterDir(context: Context, bookName: String): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: File(context.filesDir, "audiobook")
        return File(root, "阅读有声书/${bookName.safeFileName().ifBlank { "默认" }}")
            .also { if (!it.exists()) it.mkdirs() }
    }

    private fun segmentDir(
        context: Context,
        bookName: String,
        chapter: TtsServerDbBridge.AudiobookChapter,
        engineKey: String
    ): File {
        val dir = File(
            chapterDir(context, bookName),
            ".segments/${engineKey.safeFileName().ifBlank { "engine" }}/${chapterFileBaseName(chapter)}"
        )
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun segmentCacheBaseName(index: Int, sourceText: String): String {
        return "${index.toString().padStart(4, '0')}_${MD5Utils.md5Encode16(sourceText)}"
    }

    private fun segmentCacheFile(dir: File, index: Int, sourceText: String, extension: String): File {
        return File(dir, "${segmentCacheBaseName(index, sourceText)}.$extension")
    }

    private fun findSegmentCache(dir: File, index: Int, sourceText: String): File? {
        val baseName = segmentCacheBaseName(index, sourceText)
        return listOf("mp3", "wav", "audio")
            .asSequence()
            .map { File(dir, "$baseName.$it") }
            .firstOrNull { it.exists() && it.length() > 0 }
    }

    private fun writeSegmentCache(dir: File, index: Int, sourceText: String, bytes: ByteArray): File {
        if (!dir.exists()) dir.mkdirs()
        val baseName = segmentCacheBaseName(index, sourceText)
        listOf("mp3", "wav", "audio").forEach { File(dir, "$baseName.$it").delete() }
        return File(dir, "$baseName.${bytes.audioExtension()}").also {
            it.writeBytes(bytes)
        }
    }

    private fun findReadAloudHttpCache(
        context: Context,
        httpTts: HttpTTS,
        chapterTitle: String,
        sourceText: String
    ): File? {
        val speechRate = AppConfig.speechRatePlay + 5
        val fileName = MD5Utils.md5Encode16(chapterTitle) + "_" +
            MD5Utils.md5Encode16("${httpTts.url}-|-$speechRate-|-$sourceText")
        val baseDir = context.externalCacheDir ?: context.cacheDir
        return File(baseDir, "httpTTS/$fileName.mp3")
            .takeIf { it.exists() && it.length() > 0 }
    }

    private fun chapterFileBaseName(chapter: TtsServerDbBridge.AudiobookChapter): String {
        return "${chapter.chapterIndex.toString().padStart(4, '0')}_${chapter.chapterTitle.safeFileName().ifBlank { "未命名章节" }}"
    }

    private fun writeManifest(
        context: Context,
        bookName: String,
        bookUrl: String,
        chapter: TtsServerDbBridge.AudiobookChapter,
        engineName: String,
        status: String,
        file: File?,
        error: String,
        items: List<ManifestItem>,
    ) {
        val dir = chapterDir(context, bookName)
        val manifest = JSONObject()
            .put("bookName", bookName)
            .put("bookUrl", bookUrl)
            .put("chapterIndex", chapter.chapterIndex)
            .put("chapterTitle", chapter.chapterTitle)
            .put("engine", engineName)
            .put("status", status)
            .put("path", file?.absolutePath.orEmpty())
            .put("format", file?.extension.orEmpty())
            .put("sizeBytes", file?.length() ?: 0L)
            .put("error", error)
            .put("items", JSONArray().apply {
                items.forEach { item ->
                    put(JSONObject().apply {
                        put("index", item.index)
                        put("text", item.text)
                        put("status", item.status)
                        put("path", item.path)
                        put("format", item.format)
                        put("sizeBytes", item.sizeBytes)
                        put("fromCache", item.fromCache)
                        put("error", item.error)
                    })
                }
            })
            .put("updatedAt", System.currentTimeMillis())
        File(dir, "${chapterFileBaseName(chapter)}.json")
            .writeText(manifest.toString(2), Charsets.UTF_8)
    }

    private fun String.safeFileName(): String {
        return trim()
            .replace(Regex("""[\\/:*?"<>|\n\r\t]"""), "_")
            .replace(Regex("""^\.+"""), "")
            .take(80)
            .trim()
    }

    private sealed class EnginePlan {
        abstract val displayName: String

        data object TtsServer : EnginePlan() {
            override val displayName: String = "J.TTS"
        }

        data class Http(val httpTTS: HttpTTS) : EnginePlan() {
            override val displayName: String = httpTTS.name.ifBlank { "HTTP TTS" }
        }

        data class System(val engine: String) : EnginePlan() {
            override val displayName: String = engine.ifBlank { "系统 TTS" }
        }
    }

    private data class WavChunk(
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val data: ByteArray,
    ) {
        fun compatibleWith(other: WavChunk): Boolean {
            return channels == other.channels &&
                sampleRate == other.sampleRate &&
                bitsPerSample == other.bitsPerSample
        }
    }

    private data class ChapterBuildResult(
        val file: File,
        val items: List<ManifestItem>,
    )

    private data class AudioSegment(
        val index: Int,
        val text: String,
        val file: File,
        val bytes: ByteArray,
        val fromCache: Boolean,
    ) {
        fun toManifestItem(): ManifestItem {
            return ManifestItem(
                index = index,
                text = text,
                status = "ready",
                path = file.absolutePath,
                format = file.extension,
                sizeBytes = file.length(),
                fromCache = fromCache,
                error = ""
            )
        }
    }

    private data class ManifestItem(
        val index: Int,
        val text: String,
        val status: String,
        val path: String,
        val format: String,
        val sizeBytes: Long,
        val fromCache: Boolean,
        val error: String,
    )
}
