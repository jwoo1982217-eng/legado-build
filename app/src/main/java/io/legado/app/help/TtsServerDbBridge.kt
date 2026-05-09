package io.legado.app.help

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import io.legado.app.data.entities.HttpTTS
import org.json.JSONArray
import org.json.JSONObject

object TtsServerDbBridge {

    private const val TAG = "TtsServerDbBridge"

    const val TTS_PACKAGE = "com.github.jing332.tts_server_android.jtts"
    private const val BRIDGE_AUTHORITY = "com.github.jing332.tts_server_android.jtts.legado.bridge"
    private const val ACTION_START = "com.github.jing332.tts_server_android.jtts.action.LEGADO_BRIDGE_START"
    private const val FORWARDER_URL = "http://127.0.0.1:7120/api/tts"
    private const val DIRECT_HTTP_TTS_ID = -72120L
    private const val METHOD_PREPARE_AUDIOBOOK = "prepareAudiobookGeneration"
    private const val METHOD_APPEND_AUDIOBOOK_CHAPTER = "appendAudiobookGenerationChapter"
    private const val METHOD_START_AUDIOBOOK = "startAudiobookGeneration"
    private const val METHOD_SUBMIT_AUDIOBOOK = "submitAudiobookGeneration"
    private const val METHOD_QUERY_AUDIOBOOK = "queryAudiobookGeneration"
    private const val METHOD_CANCEL_AUDIOBOOK = "cancelAudiobookGeneration"

    private val BRIDGE_URI: Uri = Uri.parse("content://$BRIDGE_AUTHORITY")

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

    private fun startByProvider(context: Context): Boolean {
        return try {
            val result: Bundle? = context.contentResolver.call(
                BRIDGE_URI,
                "start",
                null,
                Bundle()
            )
            result?.getBoolean("ok", false) == true
        } catch (e: Throwable) {
            Log.w(TAG, "startByProvider failed: ${e.message}")
            false
        }
    }

    private fun startByService(context: Context): Boolean {
        return try {
            val intent = Intent(ACTION_START).apply {
                setPackage(TTS_PACKAGE)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            Log.i(TAG, "TTS Server DB started by service")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "startByService failed", e)
            false
        }
    }

    private fun callBridge(
        context: Context,
        method: String,
        arg: String?,
        extras: Bundle
    ): Bundle? {
        return try {
            context.contentResolver.call(BRIDGE_URI, method, arg, extras)
        } catch (e: Throwable) {
            Log.w(TAG, "call $method failed: ${e.message}")
            null
        }
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
