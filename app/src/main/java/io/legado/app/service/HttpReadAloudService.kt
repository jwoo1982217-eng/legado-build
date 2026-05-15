package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.script.ScriptException
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.TtsServerDbBridge
import io.legado.app.help.audiobook.LocalAudiobookFileGenerator
import io.legado.app.help.audiobook.ProtectedAudiobookFile
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.InputStreamDataSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.postEvent
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Response
import org.mozilla.javascript.WrappedException
import splitties.init.appCtx
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.math.roundToLong

/**
 * 在线朗读
 */
@SuppressLint("UnsafeOptInUsageError")
class HttpReadAloudService : BaseReadAloudService(),
    Player.Listener {
    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build()
    }

    // 改为外部存储
    private val ttsFolderPath: String by lazy {
        val baseDir = externalCacheDir ?: cacheDir
        baseDir.absolutePath + File.separator + "httpTTS" + File.separator
    }

    private val cache by lazy {
        val baseDir = externalCacheDir ?: cacheDir
        SimpleCache(
            File(baseDir, "httpTTS_cache"),
            LeastRecentlyUsedCacheEvictor(128 * 1024 * 1024),
            StandaloneDatabaseProvider(appCtx)
        )
    }
    private val cacheDataSinkFactory by lazy {
        CacheDataSink.Factory()
            .setCache(cache)
    }
    private val loadErrorHandlingPolicy by lazy {
        CustomLoadErrorHandlingPolicy()
    }
    private var speechRate: Int = AppConfig.speechRatePlay + 5
    private var downloadTask: Coroutine<*>? = null
    private var backgroundPreloadTask: Coroutine<*>? = null
    private var backgroundPreloadWindowKey: String? = null
    private var backgroundPreloadToken: Long = 0L
    private var playIndexJob: Job? = null
    private var chapterAudioMode = false
    private var chapterPlaybackAudio: LocalAudiobookFileGenerator.ChapterPlaybackAudio? = null
    private var skipChapterAudioOnce = false
    private var downloadErrorNo: Int = 0
    private var playErrorNo = 0
    private val downloadTaskActiveLock = Mutex()
    private val speakFileLocksLock = Mutex()
    private val speakFileLocks = mutableMapOf<String, Mutex>()

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadTask?.cancel()
        backgroundPreloadTask?.cancel()
        backgroundPreloadWindowKey = null
        backgroundPreloadToken++
        chapterAudioMode = false
        chapterPlaybackAudio = null
        exoPlayer.release()
        cache.release()
        Coroutine.async {
            removeCacheFile()
        }
    }

    override fun play() {
        pageChanged = false
        exoPlayer.stop()
        chapterAudioMode = false
        chapterPlaybackAudio = null
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
        } else {
            if (skipChapterAudioOnce) {
                skipChapterAudioOnce = false
            } else {
                findCurrentChapterPlaybackAudio()?.let { playback ->
                    playChapterAudio(playback)
                    return
                }
            }
            super.play()
            if (AppConfig.streamReadAloudAudio) {
                downloadAndPlayAudiosStream()
            } else {
                downloadAndPlayAudios()
            }
        }
    }

    override fun playStop() {
        exoPlayer.stop()
        playIndexJob?.cancel()
        chapterAudioMode = false
        chapterPlaybackAudio = null
    }

    private fun updateNextPos() {
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
        } else {
            markChapterFinishedByPlayback()
            nextChapter()
        }
    }

    private fun downloadAndPlayAudios() {
        exoPlayer.clearMediaItems()
        chapterAudioMode = false
        chapterPlaybackAudio = null
        stopBackgroundPreload()
        downloadTask?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                val chapter = textChapter?.chapter
                
                contentList.forEachIndexed { index, content ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    var text = content
                    if (paragraphStartPos > 0 && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    val speakText = normalizedSpeakText(text)
                    val fileName = speakFileName(httpTts, chapter, index, speakText)
                    if (speakText.isEmpty()) {
                        AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$text")
                        ensureSpeakFileCached(httpTts, fileName, speakText)
                    } else {
                        runCatching {
                            ensureSpeakFileCached(httpTts, fileName, speakText)
                        }.onFailure {
                            when (it) {
                                is CancellationException -> Unit
                                else -> pauseReadAloud()
                            }
                            return@execute
                        }
                    }
                    val file = getSpeakFileAsMd5(fileName)
                    val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                    launch(Main) {
                        exoPlayer.addMediaItem(mediaItem)
                    }
                }
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    // 辅助方法：确保能读到文件
    private fun getChapterContent(book: Book, chapter: BookChapter): String? {
        return BookHelp.getContent(book, chapter)
    }

    private fun startBackgroundPreload(
        httpTts: HttpTTS,
        startChapterIndex: Int,
        force: Boolean = false
    ) {
        val book = ReadBook.book ?: return
        if (!AppConfig.audioPreloadEnabled || AppConfig.audioPreDownloadNum <= 0) {
            postEvent(EventBus.AUDIO_PRELOAD_STATUS, AUDIO_PRELOAD_IDLE)
            return
        }
        if (downloadTask?.isActive == true) {
            AppLog.putDebug("当前朗读音频队列生成中，后台预缓存稍后启动。")
            AppConfig.audioPreloadEnabled = false
            postEvent(EventBus.AUDIO_PRELOAD_STATUS, AUDIO_PRELOAD_IDLE)
            return
        }
        val windowKey = listOf(
            book.bookUrl,
            httpTts.id,
            httpTts.url,
            speechRate,
            startChapterIndex,
            AppConfig.audioPreDownloadNum
        ).joinToString("|")
        if (!force && backgroundPreloadWindowKey == windowKey && backgroundPreloadTask != null) {
            return
        }
        backgroundPreloadTask?.cancel()
        backgroundPreloadWindowKey = windowKey
        val taskToken = ++backgroundPreloadToken
        AppConfig.audioPreloadEnabled = true
        postEvent(EventBus.AUDIO_PRELOAD_STATUS, AUDIO_PRELOAD_RUNNING)
        backgroundPreloadTask = execute {
            var completed = false
            runCatching {
                preDownloadAudios(httpTts, book, startChapterIndex)
                completed = true
            }.onFailure {
                if (it !is CancellationException) {
                    AppLog.put("有声书后台预缓存异常: ${it.localizedMessage}", it)
                }
            }.also {
                if (backgroundPreloadWindowKey == windowKey && backgroundPreloadToken == taskToken) {
                    backgroundPreloadWindowKey = null
                    backgroundPreloadTask = null
                    AppConfig.audioPreloadEnabled = false
                    postEvent(
                        EventBus.AUDIO_PRELOAD_STATUS,
                        if (completed) AUDIO_PRELOAD_READY else AUDIO_PRELOAD_IDLE
                    )
                }
            }
        }
    }

    private fun stopBackgroundPreload() {
        backgroundPreloadTask?.cancel()
        backgroundPreloadTask = null
        backgroundPreloadWindowKey = null
        backgroundPreloadToken++
        AppConfig.audioPreloadEnabled = false
        postEvent(EventBus.AUDIO_PRELOAD_STATUS, AUDIO_PRELOAD_IDLE)
    }

    private suspend fun preDownloadAudios(httpTts: HttpTTS, book: Book, startChapterIndex: Int) {
        if (!AppConfig.audioPreloadEnabled) {
            AppLog.putDebug("有声书后台预缓存已暂停。")
            return
        }
        val limit = AppConfig.audioPreDownloadNum.coerceAtLeast(0)
        if (limit <= 0) return
        val startIndex = startChapterIndex.coerceAtLeast(0)
        val endIndex = startIndex + limit - 1
        AppLog.putDebug("有声书后台预缓存启动: ${book.name}, 第${startIndex + 1}章..第${endIndex + 1}章")
        for (targetIndex in startIndex..endIndex) {
            if (!AppConfig.audioPreloadEnabled || AppConfig.audioPreDownloadNum <= 0) {
                AppLog.putDebug("有声书后台预缓存已关闭，停止后续章节请求。")
                return
            }
            currentCoroutineContext().ensureActive()
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, targetIndex) ?: break
            val contentString = getChapterContent(book, chapter)
            if (contentString.isNullOrEmpty()) {
                AppLog.putDebug("有声书后台预缓存跳过未缓存正文: ${chapter.title}")
                continue
            }
            preDownloadChapterAudios(httpTts, chapter, contentString)
        }
        AppLog.putDebug("有声书后台预缓存完成: ${book.name}")
    }

    private suspend fun preDownloadChapterAudios(
        httpTts: HttpTTS,
        chapter: BookChapter,
        contentString: String
    ) {
        val contentList = readAloudSegmentsForCache(chapter, contentString)
        contentList.forEachIndexed { index, content ->
            if (!AppConfig.audioPreloadEnabled || AppConfig.audioPreDownloadNum <= 0) {
                AppLog.putDebug("有声书后台预缓存已关闭，停止当前章节音频请求。")
                return
            }
            currentCoroutineContext().ensureActive()
            val speakText = normalizedSpeakText(content)
            val fileName = speakFileName(httpTts, chapter, index, speakText)
            if (speakText.isEmpty()) {
                ensureSpeakFileCached(httpTts, fileName, speakText)
            } else {
                runCatching {
                    ensureSpeakFileCached(httpTts, fileName, speakText)
                }.onFailure {
                    if (it is CancellationException) throw it
                    AppLog.put("有声书后台预缓存音频失败: ${chapter.title}\n${it.localizedMessage}", it)
                }
            }
        }
    }

    private fun readAloudSegmentsForCache(
        chapter: BookChapter,
        contentString: String
    ): List<String> {
        val currentTextChapter = textChapter
        if (
            currentTextChapter?.chapter?.bookUrl == chapter.bookUrl &&
            currentTextChapter.chapter.index == chapter.index
        ) {
            val currentSegments = currentTextChapter
                .getNeedReadAloud(0, readAloudByPage, 0)
                .split("\n")
                .filter { it.isNotEmpty() }
            if (currentSegments.isNotEmpty()) {
                return currentSegments
            }
        }
        return contentString
            .replace(Regex("[袮꧁]"), " ")
            .split("\n")
            .filter { it.isNotEmpty() }
    }

    private suspend fun ensureSpeakFileCached(
        httpTts: HttpTTS,
        fileName: String,
        speakText: String
    ) {
        val fileLock = speakFileLocksLock.withLock {
            speakFileLocks.getOrPut(fileName) { Mutex() }
        }
        fileLock.withLock {
            if (hasSpeakFile(fileName)) return
            if (speakText.isEmpty()) {
                createSilentSound(fileName)
                return
            }
            val inputStream = getSpeakStream(httpTts, speakText)
            if (inputStream != null) {
                createSpeakFile(fileName, inputStream)
            } else {
                createSilentSound(fileName)
            }
        }
    }

    private fun downloadAndPlayAudiosStream() {
        exoPlayer.clearMediaItems()
        chapterAudioMode = false
        chapterPlaybackAudio = null
        stopBackgroundPreload()
        downloadTask?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                val downloaderChannel = Channel<Downloader>()
                launch {
                    for (downloader in downloaderChannel) {
                        downloader.download(null)
                    }
                }
                contentList.forEachIndexed { index, content ->
                    ensureActive()
                    if (index < nowSpeak) return@forEachIndexed
                    var text = content
                    if (paragraphStartPos > 0 && index == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    val speakText = normalizedSpeakText(text)
                    if (speakText.isEmpty()) {
                        AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$speakText")
                    }
                    val fileName = speakFileName(httpTts, textChapter?.chapter, index, speakText)
                    val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
                    val downloader = createDownloader(dataSourceFactory, fileName)
                    downloaderChannel.send(downloader)
                    val mediaSource = createMediaSource(dataSourceFactory, fileName)
                    launch(Main) {
                        exoPlayer.addMediaSource(mediaSource)
                    }
                }
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private suspend fun preDownloadAudiosStream(
        httpTts: HttpTTS,
        downloaderChannel: Channel<Downloader>
    ) {
        val book = ReadBook.book ?: return
        val currentIdx = ReadBook.durChapterIndex
        if (!AppConfig.audioPreloadEnabled) return
        val limit = AppConfig.audioPreDownloadNum
        if (limit <= 0) return
        
        try {
            for (i in 1..limit) {
                currentCoroutineContext().ensureActive()
                val targetIndex = currentIdx + i
                val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, targetIndex) ?: break
                
                val contentString = getChapterContent(book, chapter)
                if (contentString.isNullOrEmpty()) continue

                val contentList = readAloudSegmentsForCache(chapter, contentString)
                
                contentList.forEachIndexed { index, content ->
                    currentCoroutineContext().ensureActive()
                    val speakText = normalizedSpeakText(content)
                    val fileName = speakFileName(httpTts, chapter, index, speakText)
                    val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
                    val downloader = createDownloader(dataSourceFactory, fileName)
                    downloaderChannel.send(downloader)
                }
            }
        } catch (e: Exception) {
            AppLog.put("听书流式预下载异常: ${e.localizedMessage}", e)
        }
    }

    private fun createDataSourceFactory(
        httpTts: HttpTTS,
        speakText: String
    ): CacheDataSource.Factory {
        val upstreamFactory = DataSource.Factory {
            InputStreamDataSource {
                if (speakText.isEmpty()) {
                    null
                } else {
                    kotlin.runCatching {
                        runBlocking(lifecycleScope.coroutineContext[Job]!!) {
                            getSpeakStream(httpTts, speakText)
                        }
                    }.onFailure {
                        when (it) {
                            is InterruptedException,
                            is CancellationException -> Unit

                            else -> pauseReadAloud()
                        }
                    }.getOrThrow()
                } ?: resources.openRawResource(R.raw.silent_sound)
            }
        }
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
        return factory
    }

    private fun createDownloader(factory: CacheDataSource.Factory, fileName: String): Downloader {
        val uri = fileName.toUri()
        val request = DownloadRequest.Builder(fileName, uri).build()
        return DefaultDownloaderFactory(factory, okHttpClient.dispatcher.executorService)
            .createDownloader(request)
    }

    private fun createMediaSource(factory: DataSource.Factory, fileName: String): MediaSource {
        return DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .createMediaSource(MediaItem.fromUri(fileName))
    }

    private suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String
    ): InputStream? {
        while (true) {
            try {
                val analyzeUrl = AnalyzeUrl(
                    httpTts.url,
                    speakText = speakText,
                    speakSpeed = speechRate,
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
                response.headers["Content-Type"]?.let { contentType ->
                    val contentType = contentType.substringBefore(";")
                    val ct = httpTts.contentType
                    if (contentType == "application/json" || contentType.startsWith("text/")) {
                        throw NoStackTraceException(response.body.string())
                    } else if (ct?.isNotBlank() == true) {
                        if (!contentType.matches(ct.toRegex())) {
                            throw NoStackTraceException(
                                "TTS服务器返回错误：" + response.body.string()
                            )
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                response.body.byteStream().let { stream ->
                    downloadErrorNo = 0
                    return stream
                }
            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> throw e
                    is ScriptException, is WrappedException -> {
                        AppLog.put("js错误\n${e.localizedMessage}", e, true)
                        e.printOnDebug()
                        throw e
                    }

                    is SocketTimeoutException, is ConnectException -> {
                        downloadErrorNo++
                        if (downloadErrorNo > 5) {
                            val msg = "tts超时或连接错误超过5次\n${e.localizedMessage}"
                            AppLog.put(msg, e, true)
                            throw e
                        }
                    }

                    else -> {
                        downloadErrorNo++
                        val msg = "tts下载错误\n${e.localizedMessage}"
                        AppLog.put(msg, e)
                        e.printOnDebug()
                        if (downloadErrorNo > 5) {
                            val msg1 = "TTS服务器连续5次错误，已暂停阅读。"
                            AppLog.put(msg1, e, true)
                            throw e
                        } else {
                            AppLog.put("TTS下载音频出错，使用无声音频代替。\n朗读文本：$speakText")
                            break
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * 生成音频文件名
     */
    private fun speakFileName(
        httpTts: HttpTTS,
        chapter: BookChapter?,
        segmentIndex: Int,
        speakText: String
    ): String {
        return speakFileChapterPrefix(chapter) + "_" +
                segmentIndex.coerceAtLeast(0) + "_" +
                MD5Utils.md5Encode16("${httpTts.id}-|-${httpTts.url}-|-$speechRate") + "_" +
                MD5Utils.md5Encode16(speakText)
    }

    private fun normalizedSpeakText(content: String): String {
        return content
            .replace(Regex("[袮꧁]"), " ")
            .replace(AppPattern.notReadAloudRegex, "")
            .trim()
    }

    private fun speakFileChapterPrefix(chapter: BookChapter?): String {
        val safeChapter = chapter ?: textChapter?.chapter
        val bookKey = MD5Utils.md5Encode16(safeChapter?.bookUrl ?: ReadBook.book?.bookUrl.orEmpty())
        val chapterIndex = safeChapter?.index ?: ReadBook.durChapterIndex
        val titleKey = MD5Utils.md5Encode16(safeChapter?.title.orEmpty())
        return "${bookKey}_${chapterIndex}_${titleKey}"
    }

    private fun createSilentSound(fileName: String) {
        val file = createSpeakFile(fileName)
        file.writeBytes(resources.openRawResource(R.raw.silent_sound).readBytes())
    }

    private fun hasSpeakFile(name: String): Boolean {
        return FileUtils.exist("${ttsFolderPath}$name.mp3")
    }

    private fun getSpeakFileAsMd5(name: String): File {
        return File("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String): File {
        return FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String, inputStream: InputStream) {
        FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3").outputStream().use { out ->
            inputStream.use {
                it.copyTo(out)
            }
        }
    }

    /**
     * 移除缓存文件
     * 如果时间设置为0，则不再保护当前章节，退出即全删。
     */
    private fun removeCacheFile() {
        val keepTime = AppConfig.audioCacheCleanTime
        // 只有当时间大于0时，才需要保护当前章节。如果为0，说明用户想彻底不留缓存。
        val protectCurrentChapter = keepTime > 0
        val currentChapterPrefix = if (protectCurrentChapter) {
            speakFileChapterPrefix(this.textChapter?.chapter)
        } else {
            ""
        }

        FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
            val isSilentSound = it.length() == 2160L

            // 判断逻辑：
            // 1. 如果是无声文件 -> 删
            // 2. 如果保留时间设为0 -> 删 (不管是不是当前章节)
            // 3. 如果保留时间>0 -> 保护当前章节，且只删过期的
            val shouldDelete = if (keepTime == 0L) {
                // 模式：即听即焚 (保留时间0)
                true
            } else {
                // 模式：保留一段时间
                // 条件：(不是当前章节) 且 (时间过期了)
                !it.name.startsWith(currentChapterPrefix) &&
                        (System.currentTimeMillis() - it.lastModified() > keepTime)
            }

            if (shouldDelete || isSilentSound) {
                FileUtils.delete(it.absolutePath)
            }
        }
    }


    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        kotlin.runCatching {
            playIndexJob?.cancel()
            exoPlayer.pause()
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                exoPlayer.play()
                if (chapterAudioMode) {
                    upChapterAudioPlayPos()
                } else {
                    upPlayPos()
                }
            }
        }
    }

    private fun findCurrentChapterPlaybackAudio(): LocalAudiobookFileGenerator.ChapterPlaybackAudio? {
        val book = ReadBook.book ?: return null
        val chapter = textChapter?.chapter ?: return null
        return LocalAudiobookFileGenerator.findChapterPlaybackAudio(
            context = this,
            bookName = book.name,
            chapter = TtsServerDbBridge.AudiobookChapter(
                chapterIndex = chapter.index,
                chapterTitle = chapter.title,
                chapterText = ""
            )
        )
    }

    private fun playChapterAudio(playback: LocalAudiobookFileGenerator.ChapterPlaybackAudio) {
        downloadTask?.cancel()
        playIndexJob?.cancel()
        chapterAudioMode = true
        chapterPlaybackAudio = playback
        super.play()
        val startMs = chapterAudioSeekPosition(playback).coerceAtLeast(0L)
        val mediaSource = createChapterAudioMediaSource(playback)
        exoPlayer.clearMediaItems()
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        exoPlayer.seekTo(startMs)
        AppLog.putDebug("使用整章音频朗读: ${playback.file.absolutePath} seek=$startMs")
    }

    private fun chapterAudioSeekPosition(playback: LocalAudiobookFileGenerator.ChapterPlaybackAudio): Long {
        val item = playback.timeline.firstOrNull { it.index >= nowSpeak }
            ?: playback.timeline.lastOrNull()
            ?: return 0L
        if (paragraphStartPos <= 0) return item.startMs
        val textLength = item.text.length.coerceAtLeast(1)
        val progress = (paragraphStartPos.toFloat() / textLength).coerceIn(0f, 1f)
        return item.startMs + ((item.endMs - item.startMs) * progress).roundToLong()
    }

    private fun createChapterAudioMediaSource(
        playback: LocalAudiobookFileGenerator.ChapterPlaybackAudio
    ): MediaSource {
        return if (
            playback.format == ProtectedAudiobookFile.FORMAT ||
            ProtectedAudiobookFile.isProtectedFile(playback.file)
        ) {
            val factory = DataSource.Factory {
                InputStreamDataSource {
                    ProtectedAudiobookFile.openMp3InputStream(this, playback.file)
                }
            }
            DefaultMediaSourceFactory(this)
                .setDataSourceFactory(factory)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .createMediaSource(MediaItem.fromUri(Uri.fromFile(playback.file)))
        } else {
            DefaultMediaSourceFactory(this)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .createMediaSource(MediaItem.fromUri(Uri.fromFile(playback.file)))
        }
    }

    private fun upChapterAudioPlayPos() {
        playIndexJob?.cancel()
        val textChapter = textChapter ?: return
        val playback = chapterPlaybackAudio ?: return
        playIndexJob = lifecycleScope.launch {
            while (chapterAudioMode && !pause && isActive) {
                val position = exoPlayer.currentPosition.coerceAtLeast(0L)
                val item = playback.timeline.lastOrNull { it.startMs <= position }
                    ?: playback.timeline.firstOrNull()
                if (item != null) {
                    val itemIndex = item.index.coerceIn(0, contentList.lastIndex)
                    val itemDuration = (item.endMs - item.startMs).coerceAtLeast(1L)
                    val itemProgress = ((position - item.startMs).coerceIn(0L, itemDuration)).toFloat() / itemDuration
                    nowSpeak = itemIndex
                    val segmentStart = contentList.take(itemIndex).sumOf { it.length + 1 }
                    val segmentTextLength = contentList.getOrNull(itemIndex)?.length ?: item.text.length
                    readAloudNumber = segmentStart + (segmentTextLength * itemProgress).toInt()
                    syncPageForReadAloudNumber(textChapter)
                    upTtsProgress(readAloudNumber + 1)
                }
                delay(350)
            }
        }
    }

    private fun syncPageForReadAloudNumber(textChapter: TextChapter) {
        while (pageIndex + 1 < textChapter.pageSize &&
            readAloudNumber >= textChapter.getReadLength(pageIndex + 1)
        ) {
            pageIndex++
            ReadBook.moveToNextPage()
        }
        while (pageIndex > 0 &&
            readAloudNumber < textChapter.getReadLength(pageIndex)
        ) {
            pageIndex--
            ReadBook.moveToPrevPage()
        }
    }

    private fun upPlayPos() {
        playIndexJob?.cancel()
        val textChapter = textChapter ?: return
        playIndexJob = lifecycleScope.launch {
            upTtsProgress(readAloudNumber + 1)
            if (exoPlayer.duration <= 0) {
                return@launch
            }
            val speakTextLength = (contentList[nowSpeak].length - paragraphStartPos).coerceAtLeast(0)
            if (speakTextLength <= 0) {
                return@launch
            }
            val sleep = exoPlayer.duration / speakTextLength
            val start = speakTextLength * exoPlayer.currentPosition / exoPlayer.duration
            for (i in start..speakTextLength) {
                if (pageIndex + 1 < textChapter.pageSize
                    && readAloudNumber + i > textChapter.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                    upTtsProgress(readAloudNumber + i.toInt())
                }
                delay(sleep)
            }
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        if (chapterAudioMode) return
        downloadTask?.cancel()
        backgroundPreloadTask?.cancel()
        backgroundPreloadWindowKey = null
        backgroundPreloadToken++
        exoPlayer.stop()
        speechRate = AppConfig.speechRatePlay + 5
        if (AppConfig.streamReadAloudAudio) {
            downloadAndPlayAudiosStream()
        } else {
            downloadAndPlayAudios()
        }
    }

    override fun nextChapter() {
        super.nextChapter()
    }

    protected override fun startAudioPreloadByCommand() {
        if (!pause) {
            AppConfig.audioPreloadEnabled = false
            postEvent(EventBus.AUDIO_PRELOAD_STATUS, AUDIO_PRELOAD_IDLE)
            AppLog.putDebug("正在朗读中，后台预缓存不再并行启动。")
            toastOnUi("正在朗读中，无需启动预缓存")
            return
        }
        ReadAloud.httpTTS?.let {
            startBackgroundPreload(it, ReadBook.durChapterIndex, force = true)
            AppLog.putDebug("已手动启动后台预缓存: ${ReadBook.book?.name.orEmpty()}")
        }
    }

    protected override fun stopAudioPreloadByCommand() {
        stopBackgroundPreload()
        AppLog.putDebug("已手动暂停后台预缓存。")
    }

    companion object {
        const val AUDIO_PRELOAD_IDLE = "idle"
        const val AUDIO_PRELOAD_RUNNING = "running"
        const val AUDIO_PRELOAD_READY = "ready"
    }

    protected override fun playGeneratedChapterByCommand() {
        pageChanged = false
        downloadTask?.cancel()
        backgroundPreloadTask?.cancel()
        backgroundPreloadTask = null
        backgroundPreloadWindowKey = null
        backgroundPreloadToken++
        playIndexJob?.cancel()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        chapterAudioMode = false
        chapterPlaybackAudio = null
        if (!requestFocus()) return
        val playback = findCurrentChapterPlaybackAudio()
        if (playback == null) {
            AppLog.putDebug("本章还没有生成完整章节音频，不启动 TTS。")
            toastOnUi("本章还没有生成完整音频")
            pauseReadAloud(false)
            return
        }
        AppLog.putDebug("播放已生成章节音频，不启动 TTS: ${playback.file.absolutePath}")
        playChapterAudio(playback)
    }

    override fun currentHttpTtsForAudiobookMerge(): HttpTTS? {
        return ReadAloud.httpTTS
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_IDLE -> {
                // 空闲
            }

            Player.STATE_BUFFERING -> {
                // 缓冲中
            }

            Player.STATE_READY -> {
                // 准备好
                if (pause) return
                exoPlayer.play()
                if (chapterAudioMode) {
                    upChapterAudioPlayPos()
                } else {
                    upPlayPos()
                }
            }

            Player.STATE_ENDED -> {
                // 结束
                playErrorNo = 0
                if (chapterAudioMode) {
                    chapterAudioMode = false
                    chapterPlaybackAudio = null
                    playIndexJob?.cancel()
                    exoPlayer.stop()
                    exoPlayer.clearMediaItems()
                    nextChapter()
                    return
                }
                updateNextPos()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }

            else -> {}
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (chapterAudioMode) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
        }
        updateNextPos()
        upPlayPos()
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        if (chapterAudioMode) {
            AppLog.put("整章音频播放失败，回退逐句朗读\n${error.localizedMessage}", error)
            chapterAudioMode = false
            chapterPlaybackAudio = null
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            skipChapterAudioOnce = true
            play()
            return
        }
        AppLog.put("朗读错误\n${contentList[nowSpeak]}", error)
        deleteCurrentSpeakFile()
        playErrorNo++
        if (playErrorNo >= 5) {
            toastOnUi("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})")
            AppLog.put("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})", error)
            pauseReadAloud()
        } else {
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.prepare()
            } else {
                exoPlayer.clearMediaItems()
                updateNextPos()
            }
        }
    }

    private fun deleteCurrentSpeakFile() {
        if (AppConfig.streamReadAloudAudio) {
            return
        }
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val filePath = mediaItem.localConfiguration!!.uri.path!!
        File(filePath).delete()
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<HttpReadAloudService>(actionStr)
    }

    class CustomLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(0) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            return C.TIME_UNSET
        }
    }

}
