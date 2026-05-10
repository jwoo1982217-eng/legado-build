package io.legado.app.ui.book.read

import android.content.Context
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.TtsServerDbBridge
import io.legado.app.help.audiobook.LocalAudiobookFileGenerator
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudiobookCacheGenerator(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {

    private var job: Job? = null
    private var taskId: String? = null

    fun showGenerateDialog() {
        val book = ReadBook.book ?: run {
            context.toastOnUi("当前没有打开的书籍")
            return
        }
        val currentText = ReadBook.curTextChapter
            ?.takeIf { it.chapter.index == ReadBook.durChapterIndex }
            ?.getContent()
        showGenerateDialog(
            book = book,
            startIndex = ReadBook.durChapterIndex,
            currentSource = ReadBook.bookSource,
            currentChapterText = currentText
        )
    }

    fun showGenerateDialog(
        book: Book,
        startIndex: Int = book.durChapterIndex,
        currentSource: BookSource? = null,
        currentChapterText: String? = null
    ) {
        val chapterCount = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        if (chapterCount <= 0) {
            context.toastOnUi("当前书籍目录为空，无法生成有声书缓存")
            return
        }
        val safeStartIndex = startIndex.coerceIn(0, chapterCount - 1)
        val preloadCount = AppConfig.audioPreDownloadNum.coerceAtLeast(0)
        val submitCount = (chapterCount - safeStartIndex).coerceAtMost(preloadCount + 1)
        val startTitle = appDb.bookChapterDao.getChapter(book.bookUrl, safeStartIndex)?.title.orEmpty()
        val useTtsServer = LocalAudiobookFileGenerator.shouldUseTtsServerBridge()
        val targetDesc = if (useTtsServer) {
            "本次会提交 $submitCount 章给 TTS 缓存工厂。"
        } else {
            "本次会由开源阅读调用当前朗读引擎生成 $submitCount 章整章音频。"
        }
        val statusDesc = if (useTtsServer) {
            "完成状态以 TTS 端实际缓存队列返回为准。"
        } else {
            "完成后会在阅读 App 文件目录生成每章一个完整音频文件。"
        }

        AlertDialog.Builder(context)
            .setTitle("生成有声书缓存")
            .setMessage(
                "书名：${book.name}\n" +
                        "起始章节：第 ${safeStartIndex + 1} 章 ${startTitle}\n" +
                        "生成范围：当前章 + 后面 $preloadCount 章\n" +
                        "$targetDesc\n\n" +
                        statusDesc
            )
            .setPositiveButton("开始生成") { _, _ ->
                start(
                    book = book,
                    startIndex = safeStartIndex,
                    preloadCount = preloadCount,
                    currentSource = currentSource,
                    currentChapterText = currentChapterText
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    fun cancelLocal() {
        job?.cancel()
    }

    private fun start(
        book: Book,
        startIndex: Int,
        preloadCount: Int,
        currentSource: BookSource?,
        currentChapterText: String?
    ) {
        cancelLocal()
        taskId = null

        val statusView = TextView(context).apply {
            setPadding(24.dpToPx(), 16.dpToPx(), 24.dpToPx(), 8.dpToPx())
            textSize = 16f
            text = "正在准备整章正文和缓存窗口..."
        }
        val statusDialog = AlertDialog.Builder(context)
            .setTitle("正在生成有声书音频")
            .setView(statusView)
            .setNegativeButton("取消任务", null)
            .setNeutralButton("后台运行", null)
            .create()
        statusDialog.setOnShowListener {
            statusDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                val runningTaskId = taskId
                cancelLocal()
                if (!runningTaskId.isNullOrBlank()) {
                    coroutineScope.launch(IO) {
                        TtsServerDbBridge.cancelAudiobookGeneration(context, runningTaskId)
                    }
                }
                context.toastOnUi("已请求取消有声书生成任务")
                statusDialog.dismiss()
            }
            statusDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                statusDialog.dismiss()
            }
        }
        statusDialog.show()

        job = coroutineScope.launch {
            try {
                statusView.text = "正在准备第 ${startIndex + 1} 章开始的正文..."
                val fullBook = withContext(IO) {
                    appDb.bookDao.getBook(book.bookUrl) ?: book
                }
                val chapters = collectChapters(
                    book = fullBook,
                    currentSource = currentSource,
                    startIndex = startIndex,
                    preloadCount = preloadCount,
                    currentChapterText = currentChapterText
                )
                if (LocalAudiobookFileGenerator.shouldUseTtsServerBridge()) {
                    statusView.text = "已准备 ${chapters.size} 章正文，正在提交 TTS 缓存工厂..."
                    val submit = withContext(IO) {
                        TtsServerDbBridge.submitAudiobookGeneration(
                            context = context,
                            bookName = fullBook.name,
                            bookUrl = fullBook.bookUrl,
                            author = fullBook.author,
                            origin = fullBook.origin,
                            startChapterIndex = startIndex,
                            preloadCount = preloadCount,
                            chapters = chapters
                        ).getOrThrow()
                    }
                    taskId = submit.taskId
                    if (submit.taskId.isBlank()) {
                        statusView.text = noTaskIdMessage(submit)
                        return@launch
                    }
                    statusView.text = "TTS 已接收 ${submit.acceptedChapters} 章，正在等待缓存进度..."
                    pollStatus(submit.taskId, statusView)
                } else {
                    taskId = null
                    statusView.text = "已准备 ${chapters.size} 章正文，阅读端正在生成整章音频..."
                    val final = withContext(IO) {
                        LocalAudiobookFileGenerator.generate(
                            context = context,
                            bookName = fullBook.name,
                            bookUrl = fullBook.bookUrl,
                            chapters = chapters
                        ) { progress ->
                            withContext(Main) {
                                statusView.text = progress.formatForUser()
                            }
                        }
                    }
                    if (final.isReady) {
                        context.toastOnUi("有声书章节音频已生成")
                    }
                }
            } catch (e: Throwable) {
                statusView.text = "有声书生成提交失败：${e.localizedMessage ?: e.javaClass.simpleName}"
            }
        }
    }

    private suspend fun collectChapters(
        book: Book,
        currentSource: BookSource?,
        startIndex: Int,
        preloadCount: Int,
        currentChapterText: String?
    ): List<TtsServerDbBridge.AudiobookChapter> = withContext(IO) {
        val chapterCount = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        if (chapterCount <= 0) {
            throw NoStackTraceException("当前书籍目录为空")
        }
        val endIndex = (startIndex + preloadCount).coerceAtMost(chapterCount - 1)
        val contentProcessor = ContentProcessor.get(book)
        var source: BookSource? = currentSource
        val chapters = arrayListOf<TtsServerDbBridge.AudiobookChapter>()
        for (index in startIndex..endIndex) {
            ensureActive()
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, index) ?: continue
            var content = if (index == startIndex) {
                currentChapterText?.takeIf { it.isNotBlank() } ?: BookHelp.getContent(book, chapter)
            } else {
                BookHelp.getContent(book, chapter)
            }
            if (content.isNullOrBlank() && !book.isLocal) {
                if (source == null) {
                    source = appDb.bookSourceDao.getBookSource(book.origin)
                }
                val bookSource = source ?: throw NoStackTraceException("未找到书源，无法获取后续章节正文")
                val nextChapterUrl = appDb.bookChapterDao.getChapter(book.bookUrl, index + 1)?.url
                content = WebBook.getContentAwait(
                    bookSource = bookSource,
                    book = book,
                    bookChapter = chapter,
                    nextChapterUrl = nextChapterUrl
                )
            }
            val processed = content
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    contentProcessor.getContent(
                        book = book,
                        chapter = chapter,
                        content = it,
                        includeTitle = false
                    ).toString().trim()
                }
            if (!processed.isNullOrBlank()) {
                chapters.add(
                    TtsServerDbBridge.AudiobookChapter(
                        chapterIndex = index,
                        chapterTitle = chapter.title,
                        chapterText = processed
                    )
                )
            }
        }
        if (chapters.isEmpty()) {
            throw NoStackTraceException("没有拿到可用章节正文")
        }
        chapters
    }

    private suspend fun pollStatus(
        taskId: String,
        statusView: TextView
    ) {
        try {
            repeat(300) {
                delay(2000)
                val status = withContext(IO) {
                    TtsServerDbBridge.queryAudiobookGeneration(context, taskId).getOrThrow()
                }
                statusView.text = status.formatForUser()
                if (status.isFinished) {
                    if (status.status.equals("ready", true) || status.status.equals("completed", true)) {
                        context.toastOnUi("有声书缓存已生成")
                    }
                    return
                }
            }
            statusView.text = "TTS 任务仍在运行，已停止本窗口轮询。\n任务 ID：$taskId"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            statusView.text = buildString {
                append("TTS 已接收任务，但无法查询真实缓存进度。")
                append("\n任务 ID：")
                append(taskId)
                append("\n原因：")
                append(e.localizedMessage ?: e.javaClass.simpleName)
            }
        }
    }

    private fun noTaskIdMessage(submit: TtsServerDbBridge.AudiobookSubmitResult): String {
        return buildString {
            append("TTS 已接收 ${submit.acceptedChapters} 章，但没有返回任务 ID。")
            if (submit.message.isNotBlank()) {
                append("\n")
                append(submit.message)
            }
            append("\n\n无法查询真实缓存进度，请先升级 TTS 端有声书生成接口。")
        }
    }

    private fun TtsServerDbBridge.AudiobookTaskStatus.formatForUser(): String {
        val statusName = when (status.lowercase()) {
            "pending" -> "等待中"
            "analyzing" -> "朗读规则分析中"
            "queue_ready" -> "台词本已生成"
            "caching_audio" -> "正在请求音频缓存"
            "ready", "completed" -> "缓存已完成"
            "failed" -> "生成失败"
            "cancelled", "canceled" -> "已取消"
            else -> status
        }
        return buildString {
            append("状态：")
            append(statusName)
            if (totalChapters > 0) {
                append("\n章节：")
                append(readyChapters)
                append("/")
                append(totalChapters)
                if (failedChapters > 0) {
                    append("，失败 ")
                    append(failedChapters)
                }
            }
            if (totalItems > 0) {
                append("\n句子音频：")
                append(readyItems)
                append("/")
                append(totalItems)
                if (failedItems > 0) {
                    append("，失败 ")
                    append(failedItems)
                }
            }
            if (message.isNotBlank()) {
                append("\n\n")
                append(message)
            }
        }
    }

    private fun LocalAudiobookFileGenerator.Progress.formatForUser(): String {
        val statusName = when (status.lowercase()) {
            "pending" -> "等待中"
            "caching_audio" -> "正在生成整章音频"
            "ready", "completed" -> "音频已完成"
            "failed" -> "生成失败"
            "cancelled", "canceled" -> "已取消"
            else -> status
        }
        return buildString {
            append("状态：")
            append(statusName)
            if (totalChapters > 0) {
                append("\n章节：")
                append(readyChapters)
                append("/")
                append(totalChapters)
                if (failedChapters > 0) {
                    append("，失败 ")
                    append(failedChapters)
                }
            }
            if (totalItems > 0) {
                append("\n音频片段：")
                append(readyItems)
                append("/")
                append(totalItems)
                if (failedItems > 0) {
                    append("，失败 ")
                    append(failedItems)
                }
            }
            if (lastFilePath.isNotBlank()) {
                append("\n最近文件：")
                append(lastFilePath)
            }
            if (message.isNotBlank()) {
                append("\n\n")
                append(message)
            }
        }
    }
}
