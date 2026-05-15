package io.legado.app.help

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import io.legado.app.constant.AppLog
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.MD5Utils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object JttsChapterContextBridge {

    data class ChapterContextFile(
        val sessionId: String,
        val contentHash: String,
        val chapterContextUri: Uri,
        val file: File,
        val contentLength: Int,
        val segmentCount: Int
    )

    fun contentHash(text: String): String = MD5Utils.md5Encode(text)

    fun sessionId(bookId: String, chapterIndex: Int, contentHash: String): String {
        return MD5Utils.md5Encode("$bookId|$chapterIndex|$contentHash").take(20)
    }

    fun prepareChapterContextUri(
        context: Context,
        bookName: String,
        bookId: String,
        chapter: TtsServerDbBridge.AudiobookChapter
    ): ChapterContextFile {
        val app = context.applicationContext
        val chapterContent = chapter.chapterText
        if (chapterContent.isBlank()) {
            throw NoStackTraceException("当前章节正文为空，无法生成 J.TTS 章节上下文")
        }
        val contentHash = contentHash(chapterContent)
        val sessionId = sessionId(bookId, chapter.chapterIndex, contentHash)
        val segments = buildSegments(chapterContent)
        val json = JSONObject()
            .put("type", "chapter_context")
            .put("sessionId", sessionId)
            .put("bookName", bookName)
            .put("bookId", bookId)
            .put("chapterTitle", chapter.chapterTitle)
            .put("chapterIndex", chapter.chapterIndex)
            .put("chapterContent", chapterContent)
            .put("contentHash", contentHash)
            .put("updatedAt", System.currentTimeMillis())
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

        val dir = File(app.cacheDir, "jtts_chapter_context").also { it.mkdirs() }
        val tmpFile = File(dir, "$sessionId.chapter_context.tmp")
        val outFile = File(dir, "$sessionId.chapter_context.json")
        tmpFile.writeText(json.toString(), Charsets.UTF_8)
        if (outFile.exists()) outFile.delete()
        if (!tmpFile.renameTo(outFile)) {
            tmpFile.copyTo(outFile, overwrite = true)
            tmpFile.delete()
        }

        val uri = FileProvider.getUriForFile(
            app,
            "${app.packageName}.fileProvider",
            outFile
        )
        app.grantUriPermission(
            TtsServerDbBridge.TTS_PACKAGE,
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        AppLog.putDebug(
            "[JRead-JTTS] chapterContextUri ready " +
                    "book=$bookName chapter=${chapter.chapterTitle} " +
                    "len=${chapterContent.length} segments=${segments.size} " +
                    "hash=$contentHash uri=$uri"
        )
        return ChapterContextFile(
            sessionId = sessionId,
            contentHash = contentHash,
            chapterContextUri = uri,
            file = outFile,
            contentLength = chapterContent.length,
            segmentCount = segments.size
        )
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
