package io.legado.app.help

import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.HttpTTS
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

object TtsServerDbBridge {

    private const val TAG = "TtsServerDbBridge"

    const val TTS_PACKAGE = "com.github.jing332.tts_server_android"
    private const val LEGACY_TTS_PACKAGE = "com.github.jing332.tts_server_android.jtts"
    private const val ACTION_START = "com.github.jing332.tts_server_android.action.LEGADO_BRIDGE_START"
    private const val LEGACY_ACTION_START = "com.github.jing332.tts_server_android.jtts.action.LEGADO_BRIDGE_START"
    const val ACTION_EXPORT_CHAPTER_AUDIO = "com.jtts.action.EXPORT_CHAPTER_AUDIO"
    const val ACTION_EXPORT_CHAPTER_AUDIO_RESULT = "com.jtts.action.EXPORT_CHAPTER_AUDIO_RESULT"
    const val ACTION_IMPORT_CHAPTER_CONTEXT = "com.jtts.action.IMPORT_CHAPTER_CONTEXT"
    const val ACTION_IMPORT_CHAPTER_CONTEXT_RESULT = "com.jtts.action.IMPORT_CHAPTER_CONTEXT_RESULT"
    const val ACTION_IMPORT_READING_POINTER = "com.jtts.action.IMPORT_READING_POINTER"
    const val ACTION_IMPORT_READING_POINTER_RESULT = "com.jtts.action.IMPORT_READING_POINTER_RESULT"
    const val ACTION_EXPORT_READER_AUDIO_CACHE = "com.jtts.action.EXPORT_READER_AUDIO_CACHE"
    const val ACTION_EXPORT_READER_AUDIO_CACHE_RESULT = "com.jtts.action.EXPORT_READER_AUDIO_CACHE_RESULT"
    private const val FORWARDER_URL = "http://127.0.0.1:7120/api/tts"
    private const val DIRECT_HTTP_TTS_ID = -72120L
    private const val METHOD_PREPARE_AUDIOBOOK = "prepareAudiobookGeneration"
    private const val METHOD_APPEND_AUDIOBOOK_CHAPTER = "appendAudiobookGenerationChapter"
    private const val METHOD_START_AUDIOBOOK = "startAudiobookGeneration"
    private const val METHOD_SUBMIT_AUDIOBOOK = "submitAudiobookGeneration"
    private const val METHOD_QUERY_AUDIOBOOK = "queryAudiobookGeneration"
    private const val METHOD_CANCEL_AUDIOBOOK = "cancelAudiobookGeneration"
    private const val METHOD_CLEANUP_AUDIOBOOK_CHAPTER_EXPORT = "cleanupAudiobookChapterExport"

    private val BRIDGE_URIS: List<Uri> = listOf(
        Uri.parse("content://$TTS_PACKAGE.legado.bridge"),
        Uri.parse("content://$LEGACY_TTS_PACKAGE.legado.bridge")
    )
    @Volatile
    private var lastImportedContextKey: String? = null
    @Volatile
    private var pendingImportContextKey: String? = null

    data class AudiobookChapter(
        val chapterIndex: Int,
        val chapterTitle: String,
        val chapterText: String
    )

    data class AudiobookSubmitResult(
        val taskId: String,
        val status: String,
        val message: String,
        val acceptedChapters: Int
    )

    data class AudiobookTaskStatus(
        val taskId: String,
        val status: String,
        val message: String,
        val totalChapters: Int,
        val readyChapters: Int,
        val failedChapters: Int,
        val totalItems: Int,
        val readyItems: Int,
        val failedItems: Int
    ) {
        val isFinished: Boolean
            get() = status.equals("ready", true)
                    || status.equals("completed", true)
                    || status.equals("failed", true)
                    || status.equals("cancelled", true)
                    || status.equals("canceled", true)
    }

    data class AudiobookChapterExport(
        val requestId: String,
        val chapterIndex: Int,
        val chapterTitle: String,
        val audioUri: String,
        val timelineUri: String,
        val manifestUri: String,
        val manifestJson: String,
        val timelineJson: String,
        val format: String,
        val audioMimeType: String,
        val durationMs: Long,
        val sizeBytes: Long,
        val contentHash: String,
        val sessionId: String,
        val message: String
    )

    data class ReaderAudioCacheExport(
        val requestId: String,
        val sessionId: String,
        val contentHash: String,
        val manifestJson: String,
        val manifestUri: String,
        val segmentCount: Int,
        val segments: List<ReaderAudioCacheSegment>,
        val message: String
    )

    data class ReaderAudioCacheSegment(
        val index: Int,
        val order: Int,
        val fileName: String,
        val sizeBytes: Long,
        val audioUri: String,
        val audioMimeType: String,
        val durationMs: Long,
        val lastModified: Long
    )

    fun isJttsEngine(engine: String?): Boolean {
        return engine == TTS_PACKAGE || engine == LEGACY_TTS_PACKAGE
    }

    fun directHttpTts(): HttpTTS {
        return HttpTTS(
            id = DIRECT_HTTP_TTS_ID,
            name = "J.TTS 直连",
            url = "$FORWARDER_URL?engine=$TTS_PACKAGE&text={{java.encodeURI(speakText)}}&rate={{speakSpeed * 2}}&pitch=50",
            contentType = "audio/x-wav",
            concurrentRate = "100"
        )
    }

    fun ensureRunning(context: Context) {
        val app = context.applicationContext

        if (startByProvider(app)) {
            Log.i(TAG, "TTS Server DB started by provider")
            return
        }

        startByService(app)
    }

    fun submitAudiobookGeneration(
        context: Context,
        bookName: String,
        bookUrl: String,
        author: String?,
        origin: String?,
        startChapterIndex: Int,
        preloadCount: Int,
        chapters: List<AudiobookChapter>
    ): Result<AudiobookSubmitResult> {
        if (chapters.isEmpty()) {
            return Result.failure(IllegalStateException("没有可提交的章节正文"))
        }

        val app = context.applicationContext
        ensureRunning(app)

        val meta = Bundle().apply {
            putString("bookName", bookName)
            putString("bookUrl", bookUrl)
            putString("author", author.orEmpty())
            putString("origin", origin.orEmpty())
            putInt("startChapterIndex", startChapterIndex)
            putInt("preloadCount", preloadCount)
            putInt("chapterCount", chapters.size)
            putLong("submittedAt", System.currentTimeMillis())
        }

        val prepare = callBridge(app, METHOD_PREPARE_AUDIOBOOK, null, meta)
        val taskId = prepare?.getString("taskId").orEmpty()
        if (prepare?.getBoolean("ok", false) == true && taskId.isNotBlank()) {
            chapters.forEachIndexed { position, chapter ->
                val appendBundle = Bundle().apply {
                    putString("taskId", taskId)
                    putInt("position", position)
                    putInt("chapterCount", chapters.size)
                    putInt("chapterIndex", chapter.chapterIndex)
                    putString("chapterTitle", chapter.chapterTitle)
                    putString("chapterText", chapter.chapterText)
                }
                val append = callBridge(app, METHOD_APPEND_AUDIOBOOK_CHAPTER, taskId, appendBundle)
                if (append?.getBoolean("ok", false) != true) {
                    val message = append?.bridgeMessage()
                        ?: "TTS 端未接受第 ${chapter.chapterIndex + 1} 章正文"
                    return Result.failure(IllegalStateException(message))
                }
            }
            val started = callBridge(app, METHOD_START_AUDIOBOOK, taskId, meta.apply {
                putString("taskId", taskId)
            })
            return parseSubmitResult(started ?: prepare, chapters.size)
        }

        val oneShot = Bundle(meta).apply {
            putString("chaptersJson", chaptersToJson(chapters))
        }
        return parseSubmitResult(
            callBridge(app, METHOD_SUBMIT_AUDIOBOOK, null, oneShot),
            chapters.size
        )
    }

    fun queryAudiobookGeneration(
        context: Context,
        taskId: String
    ): Result<AudiobookTaskStatus> {
        if (taskId.isBlank()) {
            return Result.failure(IllegalStateException("TTS 端没有返回任务 ID"))
        }
        val bundle = Bundle().apply {
            putString("taskId", taskId)
        }
        val result = callBridge(context.applicationContext, METHOD_QUERY_AUDIOBOOK, taskId, bundle)
            ?: return Result.failure(IllegalStateException("TTS 端未返回有声书生成状态"))
        if (!result.getBoolean("ok", false)) {
            return Result.failure(IllegalStateException(result.bridgeMessage()))
        }
        return Result.success(
            AudiobookTaskStatus(
                taskId = result.getString("taskId").orEmpty().ifBlank { taskId },
                status = result.getString("status").orEmpty().ifBlank { "pending" },
                message = result.bridgeMessage().orEmpty(),
                totalChapters = result.getInt("totalChapters", result.getInt("chapterCount", 0)),
                readyChapters = result.getInt("readyChapters", 0),
                failedChapters = result.getInt("failedChapters", 0),
                totalItems = result.getInt("totalItems", 0),
                readyItems = result.getInt("readyItems", 0),
                failedItems = result.getInt("failedItems", 0)
            )
        )
    }

    fun cancelAudiobookGeneration(context: Context, taskId: String): Result<String> {
        if (taskId.isBlank()) {
            return Result.failure(IllegalStateException("TTS 端没有返回任务 ID"))
        }
        val bundle = Bundle().apply {
            putString("taskId", taskId)
        }
        val result = callBridge(context.applicationContext, METHOD_CANCEL_AUDIOBOOK, taskId, bundle)
            ?: return Result.failure(IllegalStateException("TTS 端未返回取消结果"))
        if (!result.getBoolean("ok", false)) {
            return Result.failure(IllegalStateException(result.bridgeMessage()))
        }
        return Result.success(result.bridgeMessage().orEmpty().ifBlank { "已取消有声书生成任务" })
    }

    suspend fun exportAudiobookChapter(
        context: Context,
        bookName: String = "",
        chapter: AudiobookChapter,
        sessionId: String,
        contentHash: String,
        chapterContextUri: Uri,
        chapterContentLength: Int = chapter.chapterText.length,
        segmentsCount: Int = 0,
        preferredFormat: String = "wav",
        onProgress: (Int) -> Unit = {}
    ): Result<AudiobookChapterExport> {
        val app = context.applicationContext
        ensureRunning(app)
        val requestId = "jread_export_${chapter.chapterIndex}_${System.currentTimeMillis()}_${UUID.randomUUID()}"
        AppLog.putDebug(
            "[JRead-JTTS] send export intent requestId=$requestId " +
                    "sessionId=$sessionId hash=$contentHash preferredFormat=$preferredFormat " +
                    "chapterContextUri=$chapterContextUri"
        )
        return withTimeout(30 * 60 * 1000L) {
            suspendCancellableCoroutine { cont ->
                var finished = false
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        if (intent?.action != ACTION_EXPORT_CHAPTER_AUDIO_RESULT) return
                        if (intent.getStringExtra("requestId") != requestId) return
                        val status = intent.getStringExtra("status").orEmpty()
                        val progress = intent.getIntExtra("progress", -1)
                        appendBridgeDebug(
                            buildString {
                                appendLine()
                                appendLine("EXPORT_CHAPTER_AUDIO_RESULT")
                                appendLine("requestId=$requestId")
                                appendLine("status=$status")
                                appendLine("progress=$progress")
                                appendLine("error=${intent.getStringExtra("error").orEmpty()}")
                                appendLine("manifestJson=${intent.getStringExtra("manifestJson").orEmpty()}")
                            }
                        )
                        when (status.lowercase()) {
                            "running" -> {
                                AppLog.putDebug(
                                    "[JRead-JTTS] export running progress=$progress requestId=$requestId"
                                )
                                onProgress(progress)
                            }

                            "done" -> {
                                if (finished) return
                                finished = true
                                unregisterExportReceiver(app, this)
                                val manifestJson = intent.getStringExtra("manifestJson").orEmpty()
                                AppLog.putDebug(
                                    "[JRead-JTTS] export done manifestJson=${manifestJson.take(300)}"
                                )
                                cont.resume(parseExportDone(chapter, requestId, sessionId, contentHash, manifestJson))
                            }

                            "failed" -> {
                                if (finished) return
                                finished = true
                                unregisterExportReceiver(app, this)
                                val error = intent.getStringExtra("error").orEmpty()
                                    .ifBlank { "J.TTS 整章导出失败" }
                                AppLog.putDebug("[JRead-JTTS] export failed requestId=$requestId error=$error")
                                cont.resume(Result.failure(IllegalStateException(error)))
                            }
                        }
                    }
                }
                ContextCompat.registerReceiver(
                    app,
                    receiver,
                    IntentFilter(ACTION_EXPORT_CHAPTER_AUDIO_RESULT),
                    ContextCompat.RECEIVER_EXPORTED
                )
                cont.invokeOnCancellation {
                    unregisterExportReceiver(app, receiver)
                }

                val intent = Intent(ACTION_EXPORT_CHAPTER_AUDIO).apply {
                    setPackage(TTS_PACKAGE)
                    data = chapterContextUri
                    clipData = ClipData.newUri(app.contentResolver, "chapterContext", chapterContextUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    putExtra("method", "exportAudiobookChapter")
                    putExtra("requestId", requestId)
                    putExtra("sessionId", sessionId)
                    putExtra("contentHash", contentHash)
                    putExtra("callerPackage", app.packageName)
                    putExtra("preferredFormat", preferredFormat)
                    putExtra("chapterContextUri", chapterContextUri.toString())
                }
                app.grantUriPermission(
                    TTS_PACKAGE,
                    chapterContextUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                writeExportBridgeDebug(
                    requestId = requestId,
                    sessionId = sessionId,
                    contentHash = contentHash,
                    bookName = bookName,
                    chapter = chapter,
                    chapterContentLength = chapterContentLength,
                    segmentsCount = segmentsCount,
                    chapterContextUri = chapterContextUri,
                    intent = intent,
                    grantUriPermissionCalled = true,
                    startForegroundServiceCalled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                )
                val startResult = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        app.startForegroundService(intent)
                    } else {
                        app.startService(intent)
                    }
                }
                startResult.onFailure {
                    if (!finished) {
                        finished = true
                        unregisterExportReceiver(app, receiver)
                        val error = "J.TTS 导出服务启动失败：${it.localizedMessage ?: it.javaClass.simpleName}"
                        AppLog.putDebug("[JRead-JTTS] export start failed requestId=$requestId error=$error")
                        cont.resume(Result.failure(IllegalStateException(error)))
                    }
                }
            }
        }
    }

    suspend fun exportReaderAudioCache(
        context: Context,
        bookName: String,
        chapter: AudiobookChapter,
        sessionId: String,
        contentHash: String,
        onProgress: (Int) -> Unit = {}
    ): Result<ReaderAudioCacheExport> {
        val app = context.applicationContext
        ensureRunning(app)
        val requestId = "jread_reader_cache_${chapter.chapterIndex}_${System.currentTimeMillis()}_${UUID.randomUUID()}"
        AppLog.putDebug(
            "[JRead-JTTS] send reader audio cache export requestId=$requestId " +
                    "sessionId=$sessionId hash=$contentHash book=$bookName chapter=${chapter.chapterTitle}"
        )
        return withTimeout(10 * 60 * 1000L) {
            suspendCancellableCoroutine { cont ->
                var finished = false
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context?, intent: Intent?) {
                        if (intent?.action != ACTION_EXPORT_READER_AUDIO_CACHE_RESULT) return
                        if (intent.getStringExtra("requestId") != requestId) return
                        val status = intent.getStringExtra("status").orEmpty()
                        val progress = intent.getIntExtra("progress", -1)
                        when (status.lowercase()) {
                            "running" -> {
                                AppLog.putDebug(
                                    "[JRead-JTTS] reader audio cache export running " +
                                            "progress=$progress requestId=$requestId"
                                )
                                onProgress(progress)
                            }

                            "done" -> {
                                if (finished) return
                                finished = true
                                unregisterExportReceiver(app, this)
                                val manifestJson = intent.getStringExtra("manifestJson").orEmpty()
                                AppLog.putDebug(
                                    "[JRead-JTTS] reader audio cache export done " +
                                            "manifestJson=${manifestJson.take(300)}"
                                )
                                cont.resume(
                                    parseReaderAudioCacheDone(
                                        requestId = requestId,
                                        sessionId = sessionId,
                                        contentHash = contentHash,
                                        manifestJson = manifestJson
                                    )
                                )
                            }

                            "failed" -> {
                                if (finished) return
                                finished = true
                                unregisterExportReceiver(app, this)
                                val error = intent.getStringExtra("error").orEmpty()
                                    .ifBlank { "J.TTS 当前句缓存片段导出失败" }
                                AppLog.putDebug(
                                    "[JRead-JTTS] reader audio cache export failed " +
                                            "requestId=$requestId error=$error"
                                )
                                cont.resume(Result.failure(IllegalStateException(error)))
                            }
                        }
                    }
                }
                ContextCompat.registerReceiver(
                    app,
                    receiver,
                    IntentFilter(ACTION_EXPORT_READER_AUDIO_CACHE_RESULT),
                    ContextCompat.RECEIVER_EXPORTED
                )
                cont.invokeOnCancellation {
                    unregisterExportReceiver(app, receiver)
                }

                val intent = Intent(ACTION_EXPORT_READER_AUDIO_CACHE).apply {
                    setPackage(TTS_PACKAGE)
                    putExtra("requestId", requestId)
                    putExtra("sessionId", sessionId)
                    putExtra("contentHash", contentHash)
                    putExtra("bookName", bookName)
                    putExtra("chapterTitle", chapter.chapterTitle)
                    putExtra("chapterIndex", chapter.chapterIndex)
                    putExtra("callerPackage", app.packageName)
                }
                val startResult = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        app.startForegroundService(intent)
                    } else {
                        app.startService(intent)
                    }
                }
                startResult.onFailure {
                    if (!finished) {
                        finished = true
                        unregisterExportReceiver(app, receiver)
                        val error = "J.TTS 当前句缓存导出服务启动失败：${it.localizedMessage ?: it.javaClass.simpleName}"
                        AppLog.putDebug(
                            "[JRead-JTTS] reader audio cache export start failed " +
                                    "requestId=$requestId error=$error"
                        )
                        cont.resume(Result.failure(IllegalStateException(error)))
                    }
                }
            }
        }
    }

    fun importChapterContextAsync(
        context: Context,
        chapter: AudiobookChapter,
        contextFile: JttsChapterContextBridge.ChapterContextFile,
    ) {
        val key = "${contextFile.sessionId}|${contextFile.contentHash}"
        if (lastImportedContextKey == key) {
            AppLog.putDebug(
                "[JRead-JTTS] skip realtime context import, already done " +
                        "sessionId=${contextFile.sessionId} hash=${contextFile.contentHash}"
            )
            return
        }
        if (pendingImportContextKey == key) {
            AppLog.putDebug(
                "[JRead-JTTS] skip realtime context import, pending " +
                        "sessionId=${contextFile.sessionId} hash=${contextFile.contentHash}"
            )
            return
        }
        pendingImportContextKey = key

        val app = context.applicationContext
        val requestId = "jread_import_${chapter.chapterIndex}_${System.currentTimeMillis()}_${UUID.randomUUID()}"
        val intent = Intent(ACTION_IMPORT_CHAPTER_CONTEXT).apply {
            setPackage(TTS_PACKAGE)
            data = contextFile.chapterContextUri
            clipData = ClipData.newUri(app.contentResolver, "chapterContext", contextFile.chapterContextUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("method", "importChapterContext")
            putExtra("requestId", requestId)
            putExtra("sessionId", contextFile.sessionId)
            putExtra("contentHash", contextFile.contentHash)
            putExtra("callerPackage", app.packageName)
            putExtra("chapterContextUri", contextFile.chapterContextUri.toString())
        }
        app.grantUriPermission(
            TTS_PACKAGE,
            contextFile.chapterContextUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        var finished = false
        val mainHandler = Handler(Looper.getMainLooper())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, resultIntent: Intent?) {
                if (resultIntent?.action != ACTION_IMPORT_CHAPTER_CONTEXT_RESULT) return
                if (resultIntent.getStringExtra("requestId") != requestId) return
                if (finished) return
                finished = true
                unregisterExportReceiver(app, this)
                val status = resultIntent.getStringExtra("status").orEmpty()
                val error = resultIntent.getStringExtra("error").orEmpty()
                when (status.lowercase()) {
                    "done" -> {
                        lastImportedContextKey = key
                        pendingImportContextKey = null
                        AppLog.putDebug(
                            "[JRead-JTTS] realtime context import done " +
                                    "sessionId=${contextFile.sessionId} hash=${contextFile.contentHash}"
                        )
                    }

                    "failed" -> {
                        if (pendingImportContextKey == key) pendingImportContextKey = null
                        AppLog.putDebug(
                            "[JRead-JTTS] realtime context import failed " +
                                    "sessionId=${contextFile.sessionId} error=$error"
                        )
                    }

                    else -> {
                        AppLog.putDebug(
                            "[JRead-JTTS] realtime context import result status=$status " +
                                    "sessionId=${contextFile.sessionId}"
                        )
                    }
                }
            }
        }

        runCatching {
            ContextCompat.registerReceiver(
                app,
                receiver,
                IntentFilter(ACTION_IMPORT_CHAPTER_CONTEXT_RESULT),
                ContextCompat.RECEIVER_EXPORTED
            )
            mainHandler.postDelayed({
                if (!finished) {
                    finished = true
                    unregisterExportReceiver(app, receiver)
                    if (pendingImportContextKey == key) pendingImportContextKey = null
                    AppLog.putDebug(
                        "[JRead-JTTS] realtime context import timeout " +
                                "sessionId=${contextFile.sessionId}"
                    )
                }
            }, 120_000L)
            AppLog.putDebug(
                "[JRead-JTTS] send realtime context import requestId=$requestId " +
                        "sessionId=${contextFile.sessionId} hash=${contextFile.contentHash} " +
                        "len=${contextFile.contentLength} segments=${contextFile.segmentCount} " +
                        "chapterContextUri=${contextFile.chapterContextUri}"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        }.onFailure {
            finished = true
            unregisterExportReceiver(app, receiver)
            if (pendingImportContextKey == key) pendingImportContextKey = null
            AppLog.putDebug(
                "[JRead-JTTS] realtime context import start failed " +
                        "sessionId=${contextFile.sessionId} error=${it.localizedMessage ?: it.javaClass.simpleName}"
            )
        }
    }

    fun importReadingPointerAsync(
        context: Context,
        sessionId: String,
        contentHash: String,
        currentText: String,
        startOffset: Int,
        endOffset: Int,
        chapterIndex: Int
    ) {
        if (sessionId.isBlank() || currentText.isBlank()) return
        val app = context.applicationContext
        val requestId = "jread_ptr_${chapterIndex}_${System.currentTimeMillis()}_${UUID.randomUUID()}"
        val intent = Intent(ACTION_IMPORT_READING_POINTER).apply {
            setPackage(TTS_PACKAGE)
            putExtra("method", "importReadingPointer")
            putExtra("requestId", requestId)
            putExtra("sessionId", sessionId)
            putExtra("contentHash", contentHash)
            putExtra("callerPackage", app.packageName)
            putExtra("currentText", currentText)
            putExtra("startOffset", startOffset)
            putExtra("endOffset", endOffset)
            putExtra("chapterIndex", chapterIndex)
            putExtra("updatedAt", System.currentTimeMillis())
        }
        runCatching {
            AppLog.putDebug(
                "[JRead-JTTS] send realtime pointer requestId=$requestId " +
                        "sessionId=$sessionId chapterIndex=$chapterIndex " +
                        "start=$startOffset end=$endOffset textLen=${currentText.length}"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        }.onFailure {
            AppLog.putDebug(
                "[JRead-JTTS] realtime pointer import failed " +
                        "sessionId=$sessionId start=$startOffset end=$endOffset " +
                        "error=${it.localizedMessage ?: it.javaClass.simpleName}"
            )
        }
    }

    fun cleanupAudiobookChapterExport(
        context: Context,
        export: AudiobookChapterExport
    ): Result<String> {
        val bundle = Bundle().apply {
            putString("requestId", export.requestId)
            putInt("chapterIndex", export.chapterIndex)
            putString("audioUri", export.audioUri)
            putString("manifestUri", export.manifestUri)
        }
        val result = callBridge(
            context.applicationContext,
            METHOD_CLEANUP_AUDIOBOOK_CHAPTER_EXPORT,
            export.requestId,
            bundle
        ) ?: return Result.success("TTS 端暂未返回清理结果")
        if (!result.getBoolean("ok", false)) {
            return Result.failure(IllegalStateException(result.bridgeMessage()))
        }
        return Result.success(result.bridgeMessage().orEmpty().ifBlank { "TTS 临时文件已清理" })
    }

    private fun writeExportBridgeDebug(
        requestId: String,
        sessionId: String,
        contentHash: String,
        bookName: String,
        chapter: AudiobookChapter,
        chapterContentLength: Int,
        segmentsCount: Int,
        chapterContextUri: Uri,
        intent: Intent,
        grantUriPermissionCalled: Boolean,
        startForegroundServiceCalled: Boolean
    ) {
        val hasReadFlag = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        val text = buildString {
            appendLine("JRead × J.TTS Bridge Debug")
            appendLine("updatedAt=${System.currentTimeMillis()}")
            appendLine("requestId=$requestId")
            appendLine("sessionId=$sessionId")
            appendLine("contentHash=$contentHash")
            appendLine("bookName=$bookName")
            appendLine("chapterTitle=${chapter.chapterTitle}")
            appendLine("chapterIndex=${chapter.chapterIndex}")
            appendLine("chapterContentLength=$chapterContentLength")
            appendLine("segmentsCount=$segmentsCount")
            appendLine("chapterContextUri=$chapterContextUri")
            appendLine("intentAction=${intent.action.orEmpty()}")
            appendLine("intentPackage=${intent.`package`.orEmpty()}")
            appendLine("hasReadFlag=$hasReadFlag")
            appendLine("hasDataUri=${intent.data == chapterContextUri}")
            appendLine("hasClipData=${intent.clipData != null}")
            appendLine("grantUriPermissionCalled=$grantUriPermissionCalled")
            appendLine("startForegroundServiceCalled=$startForegroundServiceCalled")
        }
        writeBridgeDebug(text, append = false)
    }

    private fun appendBridgeDebug(text: String) {
        writeBridgeDebug(text, append = true)
    }

    private fun writeBridgeDebug(text: String, append: Boolean) {
        runCatching {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "jread_jtts_bridge_debug.txt")
            if (append) {
                file.appendText(text, Charsets.UTF_8)
            } else {
                file.writeText(text, Charsets.UTF_8)
            }
        }.onFailure {
            AppLog.putDebug(
                "[JRead-JTTS] write bridge debug failed: " +
                        (it.localizedMessage ?: it.javaClass.simpleName)
            )
        }
    }

    private fun unregisterExportReceiver(context: Context, receiver: BroadcastReceiver) {
        runCatching {
            context.unregisterReceiver(receiver)
        }
    }

    private fun parseExportDone(
        chapter: AudiobookChapter,
        requestId: String,
        sessionId: String,
        fallbackContentHash: String,
        manifestJson: String
    ): Result<AudiobookChapterExport> {
        val manifest = runCatching {
            if (manifestJson.isBlank()) JSONObject() else JSONObject(manifestJson)
        }.getOrElse {
            return Result.failure(IllegalStateException("J.TTS 导出 manifestJson 解析失败：${it.localizedMessage}"))
        }
        val audioMimeType = manifest.optString("audioMimeType")
        val format = manifest.optString("format").ifBlank {
            when (audioMimeType.lowercase()) {
                "audio/wav", "audio/x-wav" -> "wav"
                "audio/mpeg", "audio/mp3" -> "mp3"
                "audio/mp4", "audio/aac", "audio/m4a" -> "m4a"
                else -> ""
            }
        }
        val audioUri = manifest.optString("audioUri")
        if (audioUri.isBlank()) {
            return Result.failure(IllegalStateException("J.TTS 导出 manifest 缺少 audioUri"))
        }
        return Result.success(
            AudiobookChapterExport(
                requestId = manifest.optString("requestId").ifBlank { requestId },
                chapterIndex = manifest.optInt("chapterIndex", chapter.chapterIndex),
                chapterTitle = manifest.optString("chapterTitle").ifBlank { chapter.chapterTitle },
                audioUri = audioUri,
                timelineUri = manifest.optString("timelineUri"),
                manifestUri = manifest.optString("manifestUri"),
                manifestJson = if (manifest.has("method")) {
                    manifest.toString()
                } else {
                    manifest.put("method", "exportAudiobookChapter").toString()
                },
                timelineJson = manifest.optString("timelineJson"),
                format = format.ifBlank { "audio" },
                audioMimeType = audioMimeType,
                durationMs = manifest.optLong("durationMs", 0L),
                sizeBytes = manifest.optLong("sizeBytes", 0L),
                contentHash = manifest.optString("contentHash").ifBlank { fallbackContentHash },
                sessionId = sessionId,
                message = manifest.optString("message")
            )
        )
    }

    private fun parseReaderAudioCacheDone(
        requestId: String,
        sessionId: String,
        contentHash: String,
        manifestJson: String
    ): Result<ReaderAudioCacheExport> {
        if (manifestJson.isBlank()) {
            return Result.failure(IllegalStateException("J.TTS 当前句缓存导出缺少 manifestJson"))
        }
        val manifest = runCatching {
            JSONObject(manifestJson)
        }.getOrElse {
            return Result.failure(IllegalStateException("J.TTS 当前句缓存 manifestJson 解析失败：${it.localizedMessage}"))
        }
        val rawSegments = manifest.optJSONArray("segments") ?: JSONArray()
        val segments = arrayListOf<ReaderAudioCacheSegment>()
        for (i in 0 until rawSegments.length()) {
            val item = rawSegments.optJSONObject(i) ?: continue
            val index = item.optInt("index", item.optInt("order", i))
            val order = item.optInt("order", index)
            val audioUri = item.optString("audioUri")
            if (audioUri.isBlank()) continue
            segments += ReaderAudioCacheSegment(
                index = index,
                order = order,
                fileName = item.optString("fileName").ifBlank { "segment_${index.toString().padStart(4, '0')}" },
                sizeBytes = item.optLong("sizeBytes", 0L),
                audioUri = audioUri,
                audioMimeType = item.optString("audioMimeType"),
                durationMs = item.optLong("durationMs", 0L),
                lastModified = item.optLong("lastModified", 0L)
            )
        }
        if (segments.isEmpty()) {
            return Result.failure(IllegalStateException("J.TTS 当前句缓存导出没有返回可用音频片段"))
        }
        if (!manifest.has("method")) {
            manifest.put("method", "exportReaderAudioCache")
        }
        return Result.success(
            ReaderAudioCacheExport(
                requestId = manifest.optString("requestId").ifBlank { requestId },
                sessionId = manifest.optString("sessionId").ifBlank { sessionId },
                contentHash = manifest.optString("contentHash").ifBlank { contentHash },
                manifestJson = manifest.toString(),
                manifestUri = manifest.optString("manifestUri"),
                segmentCount = manifest.optInt("segmentCount", segments.size),
                segments = segments,
                message = manifest.optString("message")
            )
        )
    }

    private fun startByProvider(context: Context): Boolean {
        BRIDGE_URIS.forEach { uri ->
            val started = try {
                val result: Bundle? = context.contentResolver.call(
                    uri,
                    "start",
                    null,
                    Bundle()
                )
                result?.getBoolean("ok", false) == true
            } catch (e: Throwable) {
                Log.w(TAG, "startByProvider failed uri=$uri: ${e.message}")
                false
            }
            if (started) return true
        }
        return false
    }

    private fun startByService(context: Context): Boolean {
        listOf(
            ACTION_START to TTS_PACKAGE,
            LEGACY_ACTION_START to LEGACY_TTS_PACKAGE
        ).forEach { (action, packageName) ->
            val started = try {
                val intent = Intent(action).apply {
                    setPackage(packageName)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }

                Log.i(TAG, "TTS Server DB started by service package=$packageName")
                true
            } catch (e: Throwable) {
                Log.w(TAG, "startByService failed package=$packageName: ${e.message}")
                false
            }
            if (started) return true
        }
        return false
    }

    private fun callBridge(
        context: Context,
        method: String,
        arg: String?,
        extras: Bundle
    ): Bundle? {
        BRIDGE_URIS.forEach { uri ->
            val result = try {
                context.contentResolver.call(uri, method, arg, extras)
            } catch (e: Throwable) {
                Log.w(TAG, "call $method failed uri=$uri: ${e.message}")
                null
            }
            if (result != null) return result
        }
        return null
    }

    private fun parseSubmitResult(
        bundle: Bundle?,
        fallbackAcceptedChapters: Int
    ): Result<AudiobookSubmitResult> {
        if (bundle == null) {
            return Result.failure(IllegalStateException("TTS 端暂未支持有声书生成接口"))
        }
        if (!bundle.getBoolean("ok", false)) {
            return Result.failure(IllegalStateException(bundle.bridgeMessage()))
        }
        return Result.success(
            AudiobookSubmitResult(
                taskId = bundle.getString("taskId").orEmpty(),
                status = bundle.getString("status").orEmpty().ifBlank { "pending" },
                message = bundle.bridgeMessage().orEmpty(),
                acceptedChapters = bundle.getInt("acceptedChapters", fallbackAcceptedChapters)
            )
        )
    }

    private fun Bundle.bridgeMessage(): String? {
        return getString("message")
            ?: getString("error")
            ?: getString("reason")
    }

    private fun Bundle.firstString(vararg keys: String): String {
        keys.forEach { key ->
            getString(key)?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    private fun chaptersToJson(chapters: List<AudiobookChapter>): String {
        return JSONArray().apply {
            chapters.forEach { chapter ->
                put(JSONObject().apply {
                    put("chapterIndex", chapter.chapterIndex)
                    put("chapterTitle", chapter.chapterTitle)
                    put("chapterText", chapter.chapterText)
                })
            }
        }.toString()
    }
}
