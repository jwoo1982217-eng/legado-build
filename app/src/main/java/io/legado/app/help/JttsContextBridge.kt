package io.legado.app.help

import android.util.Base64
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.utils.MD5Utils
import org.json.JSONArray
import org.json.JSONObject

object JttsContextBridge {

    const val UTTERANCE_PREFIX = "jread_ctx_"
    private const val CHUNK_START = "[[JREAD_CTX_CHUNK_V1]]"
    private const val CHUNK_END = "[[/JREAD_CTX_CHUNK_V1]]"
    private const val POINTER_START = "[[JREAD_PTR_V1]]"
    private const val POINTER_END = "[[/JREAD_PTR_V1]]"
    private const val MAX_PAYLOAD_LENGTH = 2500

    private val sentChapterHashes = linkedMapOf<String, String>()

    data class ChapterContext(
        val sessionId: String,
        val chapterKey: String,
        val contentHash: String,
        val bookName: String,
        val chapterTitle: String,
        val chapterIndex: Int,
        val chapterContentLength: Int,
        val chunks: List<String>,
        val shouldSend: Boolean,
    )

    fun isEnabledForEngine(engine: String?): Boolean {
        return AppConfig.enableJttsNoWebContextBridge &&
                TtsServerDbBridge.isJttsEngine(engine)
    }

    fun prepareChapterContext(book: Book?, textChapter: TextChapter?): ChapterContext? {
        if (!AppConfig.enableJttsNoWebContextBridge) return null
        val currentBook = book ?: return null
        val currentChapter = textChapter ?: return null
        val chapterContent = currentChapter.getNeedReadAloud(0, pageSplit = false, startPos = 0)
        if (chapterContent.isBlank()) {
            AppLog.putDebug("J.TTS 无 Web 上下文直通：当前章节正文为空，跳过上下文发送。")
            return null
        }

        val chapterIndex = currentChapter.chapter.index
        val chapterTitle = currentChapter.chapter.title.ifBlank { currentChapter.title }
        val contentHash = contentHash(chapterContent)
        val chapterKey = MD5Utils.md5Encode("${currentBook.bookUrl}|$chapterIndex|$chapterTitle")
        val sessionId = MD5Utils.md5Encode("${currentBook.bookUrl}|$chapterIndex|$contentHash")
            .take(20)
        val shouldSend = sentChapterHashes[chapterKey] != contentHash
        val segments = buildSegments(chapterContent)

        val contextJson = JSONObject()
            .put("type", "chapter_context")
            .put("sessionId", sessionId)
            .put("bookName", currentBook.name)
            .put("bookId", currentBook.bookUrl)
            .put("chapterTitle", chapterTitle)
            .put("chapterIndex", chapterIndex)
            .put("chapterContent", chapterContent)
            .put("contentHash", contentHash)
            .put("chapterContentHash", contentHash)
            .put("segments", JSONArray().apply {
                segments.forEach { segment ->
                    put(JSONObject().apply {
                        put("index", segment.index)
                        put("text", segment.text)
                        put("startOffset", segment.startOffset)
                        put("endOffset", segment.endOffset)
                    })
                }
            })
            .put("updatedAt", System.currentTimeMillis())
            .toString()

        val payload = Base64.encodeToString(contextJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val payloadChunks = payload.chunked(MAX_PAYLOAD_LENGTH)
        val chunks = payloadChunks
            .mapIndexed { index, chunk ->
                wrapChunk(
                    sessionId = sessionId,
                    contentHash = contentHash,
                    chunkIndex = index,
                    chunkTotal = payloadChunks.size,
                    payload = chunk
                )
            }

        if (shouldSend) {
            AppLog.putDebug(
                "[JRead-JTTS] chapter context ready " +
                        "book=${currentBook.name} chapter=$chapterTitle " +
                        "len=${chapterContent.length} segments=${segments.size} " +
                        "chunks=${chunks.size} hash=$contentHash"
            )
        } else {
            AppLog.putDebug(
                "[JRead-JTTS] skip duplicate chapter context " +
                        "book=${currentBook.name} chapter=$chapterTitle hash=$contentHash"
            )
        }

        return ChapterContext(
            sessionId = sessionId,
            chapterKey = chapterKey,
            contentHash = contentHash,
            bookName = currentBook.name,
            chapterTitle = chapterTitle,
            chapterIndex = chapterIndex,
            chapterContentLength = chapterContent.length,
            chunks = chunks,
            shouldSend = shouldSend
        )
    }

    fun markContextSent(context: ChapterContext) {
        sentChapterHashes[context.chapterKey] = context.contentHash
        while (sentChapterHashes.size > 12) {
            val firstKey = sentChapterHashes.keys.firstOrNull() ?: break
            sentChapterHashes.remove(firstKey)
        }
        AppLog.putDebug(
            "[JRead-JTTS] all ctx chunks done " +
                    "book=${context.bookName} chapter=${context.chapterTitle} " +
                    "len=${context.chapterContentLength} chunks=${context.chunks.size} " +
                    "hash=${context.contentHash}"
        )
    }

    fun contentHash(text: String): String = MD5Utils.md5Encode(text)

    fun sessionId(bookId: String, chapterIndex: Int, contentHash: String): String {
        return MD5Utils.md5Encode("$bookId|$chapterIndex|$contentHash").take(20)
    }

    fun isContextSent(bookId: String, chapterIndex: Int, chapterTitle: String, contentHash: String): Boolean {
        val chapterKey = MD5Utils.md5Encode("$bookId|$chapterIndex|$chapterTitle")
        return sentChapterHashes[chapterKey] == contentHash
    }

    fun pointerMarker(
        context: ChapterContext?,
        currentText: String,
        startOffset: Int,
        endOffset: Int,
        fallbackChapterIndex: Int
    ): String? {
        if (!AppConfig.enableJttsNoWebContextBridge || context == null) return null
        val json = JSONObject()
            .put("type", "current_pointer")
            .put("sessionId", context.sessionId)
            .put("currentText", currentText)
            .put("startOffset", startOffset.coerceAtLeast(0))
            .put("endOffset", endOffset.coerceAtLeast(startOffset))
            .put("chapterIndex", context.chapterIndex.takeIf { it >= 0 } ?: fallbackChapterIndex)
            .put("updatedAt", System.currentTimeMillis())
        return "$POINTER_START\n$json\n$POINTER_END"
    }

    fun chunkUtteranceId(context: ChapterContext, index: Int): String {
        return "${UTTERANCE_PREFIX}${context.sessionId}_$index"
    }

    fun pointerUtteranceId(context: ChapterContext?, index: Int): String {
        return "${UTTERANCE_PREFIX}${context?.sessionId ?: "unknown"}_ptr_$index"
    }

    fun isBridgeUtterance(utteranceId: String?): Boolean {
        return utteranceId?.startsWith(UTTERANCE_PREFIX) == true
    }

    private fun wrapChunk(
        sessionId: String,
        contentHash: String,
        chunkIndex: Int,
        chunkTotal: Int,
        payload: String
    ): String {
        val json = JSONObject()
            .put("type", "chapter_context_chunk")
            .put("sessionId", sessionId)
            .put("chunkIndex", chunkIndex)
            .put("chunkTotal", chunkTotal)
            .put("encoding", "base64")
            .put("payload", payload)
            .put("contentHash", contentHash)
            .put("chapterContentHash", contentHash)
            .put("updatedAt", System.currentTimeMillis())
        return "$CHUNK_START\n$json\n$CHUNK_END"
    }

    private data class Segment(
        val index: Int,
        val text: String,
        val startOffset: Int,
        val endOffset: Int
    )

    private fun buildSegments(text: String): List<Segment> {
        val result = arrayListOf<Segment>()
        var offset = 0
        text.split('\n').forEach { raw ->
            val startInRaw = raw.indexOfFirst { !it.isWhitespace() }
            val endInRaw = raw.indexOfLast { !it.isWhitespace() }
            if (startInRaw >= 0 && endInRaw >= startInRaw) {
                val start = offset + startInRaw
                val end = offset + endInRaw + 1
                result += Segment(
                    index = result.size,
                    text = raw.substring(startInRaw, endInRaw + 1),
                    startOffset = start,
                    endOffset = end
                )
            }
            offset += raw.length + 1
        }
        return result.ifEmpty {
            listOf(Segment(0, text, 0, text.length))
        }
    }
}
