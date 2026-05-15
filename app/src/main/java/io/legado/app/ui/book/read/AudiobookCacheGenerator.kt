package io.legado.app.ui.book.read

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.JttsChapterContextBridge
import io.legado.app.help.TtsServerDbBridge
import io.legado.app.help.audiobook.LocalAudiobookFileGenerator
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    fun showChapterStatusDialog() {
        val book = ReadBook.book ?: run {
            context.toastOnUi("当前没有打开的书籍")
            return
        }
        val chapterCount = appDb.bookChapterDao.getChapterCount(book.bookUrl)
        if (chapterCount <= 0) {
            context.toastOnUi("当前书籍目录为空，无法查询有声书状态")
            return
        }
        val currentIndex = ReadBook.durChapterIndex.coerceIn(0, chapterCount - 1)
        val safeStartIndex = 0
        val preloadCount = AppConfig.audioPreDownloadNum.coerceAtLeast(0)
        val submitCount = chapterCount
        val statusContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 16.dpToPx(), 24.dpToPx(), 8.dpToPx())
        }
        val statusDialog = AlertDialog.Builder(context)
            .setTitle("章节列表")
            .setView(ScrollView(context).apply { addView(statusContainer) })
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton("刷新", null)
            .create()

        fun refresh() {
            renderStatusLoading(statusContainer)
            coroutineScope.launch {
                runCatching {
                    withContext(IO) {
                        buildChapterStatusData(
                            book = book,
                            startIndex = safeStartIndex,
                            submitCount = submitCount,
                            preloadCount = preloadCount,
                            currentIndex = currentIndex
                        )
                    }
                }.onSuccess { data ->
                    renderChapterStatus(
                        container = statusContainer,
                        book = book,
                        data = data,
                        refresh = { refresh() }
                    )
                }.onFailure { error ->
                    renderStatusMessage(
                        container = statusContainer,
                        message = "章节列表查询失败：${error.localizedMessage ?: error.javaClass.simpleName}"
                    )
                }
            }
        }

        statusDialog.setOnShowListener {
            statusDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                refresh()
            }
        }
        statusDialog.show()
        refresh()
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
        val previewChapters = collectChapterPreview(
            bookUrl = book.bookUrl,
            startIndex = safeStartIndex,
            submitCount = submitCount
        )
        val useTtsServer = LocalAudiobookFileGenerator.shouldUseTtsServerBridge()
        val modeDesc = if (useTtsServer) {
            "生成模式：J.TTS 缓存工厂"
        } else {
            "生成模式：开源阅读本地章节音频"
        }
        val targetDesc = if (useTtsServer) {
            "工作方式：把 $submitCount 章正文提交给 TTS，由 TTS 分析台词本、请求句子音频、生成章节缓存。"
        } else {
            "工作方式：开源阅读优先复用朗读缓存，缺失时调用当前朗读引擎，把每句音频保存到章节目录；开启生成有声书后再生成完整章节音频。"
        }
        val statusDesc = if (useTtsServer) {
            "完成判断：以 TTS 端实际缓存队列结果为准。"
        } else {
            "保存位置：阅读 App 文件目录 / Music / 阅读有声书。当前设置：生成有声书=${if (AppConfig.audiobookAutoMergeAfterRead) "开" else "关"}，完整章节音频统一保存为受保护加密 MP3，仅本 App 可播放。"
        }

        AlertDialog.Builder(context)
            .setTitle("生成有声书缓存")
            .setMessage(
                "书名：${book.name}\n" +
                        "起始章节：第 ${safeStartIndex + 1} 章 ${startTitle}\n" +
                        "生成范围：当前章 + 后面 $preloadCount 章\n" +
                        "$modeDesc\n" +
                        "$targetDesc\n\n" +
                        statusDesc +
                        "\n\n本次章节队列：\n" +
                        formatChapterQueue(previewChapters)
            )
            .setPositiveButton("开始生成缓存") { _, _ ->
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

    private fun buildChapterStatusData(
        book: Book,
        startIndex: Int,
        submitCount: Int,
        preloadCount: Int,
        currentIndex: Int
    ): ChapterStatusData {
        val chapters = collectChapterPreview(
            bookUrl = book.bookUrl,
            startIndex = startIndex,
            submitCount = submitCount
        )
        if (chapters.isEmpty()) {
            return ChapterStatusData(
                header = "当前缓存窗口没有可查询章节。",
                rows = emptyList(),
                footer = ""
            )
        }

        val snapshots = chapters.map { chapter ->
            chapter to LocalAudiobookFileGenerator.inspectChapterStatus(
                context = context,
                bookName = book.name,
                chapter = TtsServerDbBridge.AudiobookChapter(
                    chapterIndex = chapter.chapterIndex,
                    chapterTitle = chapter.title,
                    chapterText = ""
                )
            )
        }
        val displaySnapshots = snapshots.map { (chapter, status) ->
            chapter to status.toReadableChapterStatus(chapter.chapterIndex, currentIndex)
        }
        val readyCount = displaySnapshots.count { (_, status) -> status.status.isReadyStatus() }
        val failedCount = displaySnapshots.count { (_, status) -> status.status.lowercase() == "failed" }
        val rows = displaySnapshots.map { (chapter, status) ->
            ChapterStatusRow(
                chapter = chapter.copy(state = status.toChapterQueueState()),
                status = status,
                canDelete = status.status.lowercase() != "not_generated"
            )
        }
        val useTtsServer = LocalAudiobookFileGenerator.shouldUseTtsServerBridge()

        return ChapterStatusData(
            header = buildString {
                append(
                    if (useTtsServer) {
                        "生成模式：J.TTS 直连 + 阅读端整章音频"
                    } else {
                        "生成模式：开源阅读本地章节音频"
                    }
                )
                append("\n查询范围：全书 ")
                append(chapters.size)
                append(" 章")
                append("\n生成窗口：后台预缓存/朗读默认提交当前章 + 后面 ")
                append(preloadCount)
                append(" 章")
                append("\n查询对象：句子片段 + 完整章节音频")
                append("\n已完成章节：")
                append(readyCount)
                append("/")
                append(chapters.size)
                if (failedCount > 0) {
                    append("，失败 ")
                    append(failedCount)
                }
            },
            rows = rows,
            footer = buildString {
                if (useTtsServer) {
                    append("J.TTS 缓存工厂状态：\n")
                    append(buildTtsServerStatusSummary(chapters))
                }
            }
        )
    }

    private fun renderStatusLoading(container: LinearLayout) {
        renderStatusMessage(container, "正在查询章节生成状态...")
    }

    private fun renderStatusMessage(container: LinearLayout, message: String) {
        container.removeAllViews()
        container.addView(TextView(context).apply {
            textSize = 16f
            text = message
        })
    }

    private fun renderChapterStatus(
        container: LinearLayout,
        book: Book,
        data: ChapterStatusData,
        refresh: () -> Unit
    ) {
        container.removeAllViews()
        container.addView(TextView(context).apply {
            textSize = 16f
            text = data.header
        })
        container.addView(Button(context).apply {
            text = "清空本书整章音频"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12.dpToPx()
                bottomMargin = 4.dpToPx()
            }
            setOnClickListener {
                confirmClearBookMergedAudio(book, refresh)
            }
        })
        if (data.rows.isNotEmpty()) {
            container.addView(TextView(context).apply {
                textSize = 15f
                text = "\n章节详情："
            })
        }
        data.rows.forEach { row ->
            container.addView(chapterStatusRowView(book, row, refresh))
            container.addView(View(context).apply {
                setBackgroundColor(0x1F000000)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1
                )
            })
        }
        if (data.footer.isNotBlank()) {
            container.addView(TextView(context).apply {
                textSize = 15f
                text = "\n${data.footer}"
            })
        }
    }

    private fun chapterStatusRowView(
        book: Book,
        row: ChapterStatusRow,
        refresh: () -> Unit
    ): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 10.dpToPx(), 0, 10.dpToPx())
            addView(TextView(context).apply {
                textSize = 15f
                text = formatChapterStatusDetail(row.chapter, row.status)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
            if (row.canDelete) {
                addView(Button(context).apply {
                    text = "删除"
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.END
                        topMargin = 6.dpToPx()
                    }
                    setOnClickListener {
                        confirmDeleteChapterCache(book, row.chapter, refresh)
                    }
                })
            }
        }
    }

    private fun confirmDeleteChapterCache(
        book: Book,
        chapter: ChapterUiState,
        refresh: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle("删除本章有声书缓存")
            .setMessage(
                "只删除第 ${chapter.chapterIndex + 1} 章「${chapter.title.ifBlank { "未命名章节" }}」的整章音频、manifest 和分句片段，其他章节不受影响。"
            )
            .setPositiveButton("删除") { _, _ ->
                coroutineScope.launch {
                    val success = withContext(IO) {
                        LocalAudiobookFileGenerator.clearChapterAudioCache(
                            context = context,
                            bookName = book.name,
                            chapter = TtsServerDbBridge.AudiobookChapter(
                                chapterIndex = chapter.chapterIndex,
                                chapterTitle = chapter.title,
                                chapterText = ""
                            )
                        )
                    }
                    context.toastOnUi(
                        if (success) {
                            "已删除第 ${chapter.chapterIndex + 1} 章有声书缓存"
                        } else {
                            "第 ${chapter.chapterIndex + 1} 章缓存可能未完全删除"
                        }
                    )
                    postEvent(EventBus.AUDIOBOOK_CACHE_CHANGED, true)
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmClearBookMergedAudio(
        book: Book,
        refresh: () -> Unit
    ) {
        AlertDialog.Builder(context)
            .setTitle("清空本书整章音频")
            .setMessage(
                "只删除《${book.name}》已经合成好的完整章节音频，并把章节状态回退到片段已缓存/未生成；已经缓存的一句一句音频片段会尽量保留，方便重新合成。"
            )
            .setPositiveButton("清空") { _, _ ->
                coroutineScope.launch {
                    val success = withContext(IO) {
                        LocalAudiobookFileGenerator.clearBookMergedChapterAudio(
                            context = context,
                            bookName = book.name
                        )
                    }
                    context.toastOnUi(
                        if (success) {
                            "已清空本书整章音频"
                        } else {
                            "部分整章音频可能未删除完整"
                        }
                    )
                    postEvent(EventBus.AUDIOBOOK_CACHE_CHANGED, true)
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun buildTtsServerStatusSummary(chapters: List<ChapterUiState>): String {
        val runningTaskId = taskId
        if (!runningTaskId.isNullOrBlank()) {
            return runCatching {
                val status = TtsServerDbBridge.queryAudiobookGeneration(context, runningTaskId)
                    .getOrThrow()
                val states = chapters.map { it.copy() }.toMutableList()
                markChapterProgress(
                    chapters = states,
                    readyCount = status.readyChapters,
                    failedCount = status.failedChapters,
                    running = status.status.lowercase() !in setOf(
                        "ready",
                        "completed",
                        "failed",
                        "cancelled",
                        "canceled"
                    )
                )
                buildString {
                    append("TTS 任务：")
                    append(status.status.toChapterState())
                    if (status.totalChapters > 0) {
                        append("\n章节缓存：")
                        append(status.readyChapters)
                        append("/")
                        append(status.totalChapters)
                        if (status.failedChapters > 0) {
                            append("，失败 ")
                            append(status.failedChapters)
                        }
                    }
                    if (status.totalItems > 0) {
                        append("\n句子音频：")
                        append(status.readyItems)
                        append("/")
                        append(status.totalItems)
                        if (status.failedItems > 0) {
                            append("，失败 ")
                            append(status.failedItems)
                        }
                    }
                    if (status.message.isNotBlank()) {
                        append("\n")
                        append(status.message)
                    }
                    append("\n\nTTS 端章节队列：\n")
                    append(formatChapterQueue(states))
                }
            }.getOrElse { error ->
                "TTS 端状态查询失败：${error.localizedMessage ?: error.javaClass.simpleName}"
            }
        }

        return "当前没有正在追踪的 J.TTS 任务 ID。\n" +
                "上面的章节队列仍会显示开源阅读端已经缓存的句子片段和完整章节音频。"
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
        postEvent(EventBus.AUDIOBOOK_CACHE_STATUS, "running")

        val statusView = TextView(context).apply {
            setPadding(24.dpToPx(), 16.dpToPx(), 24.dpToPx(), 8.dpToPx())
            textSize = 16f
            text = "正在准备整章正文和缓存窗口..."
        }
        val statusScrollView = ScrollView(context).apply {
            addView(statusView)
        }
        val statusDialog = AlertDialog.Builder(context)
            .setTitle("正在生成章节音频")
            .setView(statusScrollView)
            .setNegativeButton("取消任务", null)
            .setNeutralButton("后台运行", null)
            .create()
        statusDialog.setOnShowListener {
            statusDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                val runningTaskId = taskId
                cancelLocal()
                postEvent(EventBus.AUDIOBOOK_CACHE_STATUS, "idle")
                if (!runningTaskId.isNullOrBlank()) {
                    coroutineScope.launch(IO) {
                        TtsServerDbBridge.cancelAudiobookGeneration(context, runningTaskId)
                    }
                }
                context.toastOnUi("已请求取消章节音频生成任务")
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
                val chapterStates = chapters.map {
                    ChapterUiState(
                        chapterIndex = it.chapterIndex,
                        title = it.chapterTitle,
                        state = "等待生成"
                    )
                }.toMutableList()
                if (LocalAudiobookFileGenerator.shouldUseTtsServerBridge()) {
                    if (AppConfig.enableJttsAudiobookExportBridge) {
                        taskId = null
                        markChapterProgress(chapterStates, readyCount = 0, failedCount = 0, running = true)
                        val imported = importTtsServerExports(
                            bookName = fullBook.name,
                            bookUrl = fullBook.bookUrl,
                            chapters = chapters,
                            statusView = statusView,
                            chapterStates = chapterStates
                        )
                        if (imported) {
                            context.toastOnUi("有声书缓存已导入 J阅读")
                            postEvent(EventBus.AUDIOBOOK_CACHE_STATUS, "ready")
                            postEvent(EventBus.AUDIOBOOK_CACHE_CHANGED, true)
                        } else {
                            postEvent(EventBus.AUDIOBOOK_CACHE_STATUS, "idle")
                        }
                    } else {
                        statusView.text = formatGenerationStatus(
                            header = "已准备 ${chapters.size} 章正文，正在提交 TTS 缓存工厂...",
                            chapters = chapterStates,
                            footer = ""
                        )
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
                            statusView.text = formatGenerationStatus(
                                header = "TTS 已接收任务，但没有返回任务 ID。",
                                chapters = chapterStates,
                                footer = noTaskIdMessage(submit)
                            )
                            return@launch
                        }
                        markChapterProgress(chapterStates, readyCount = 0, failedCount = 0, running = true)
                        statusView.text = formatGenerationStatus(
                            header = "TTS 已接收 ${submit.acceptedChapters} 章，正在等待缓存进度...",
                            chapters = chapterStates,
                            footer = "任务 ID：${submit.taskId}"
                        )
                        pollStatus(
                            taskId = submit.taskId,
                            bookName = fullBook.name,
                            bookUrl = fullBook.bookUrl,
                            chapters = chapters,
                            statusView = statusView,
                            chapterStates = chapterStates
                        )
                    }
                } else {
                    taskId = null
                    markChapterProgress(chapterStates, readyCount = 0, failedCount = 0, running = true)
                    statusView.text = formatGenerationStatus(
                        header = "已准备 ${chapters.size} 章正文，阅读端正在生成章节音频...",
                        chapters = chapterStates,
                        footer = ""
                    )
                    val final = withContext(IO) {
                        LocalAudiobookFileGenerator.generate(
                            context = context,
                            bookName = fullBook.name,
                            bookUrl = fullBook.bookUrl,
                            chapters = chapters
                        ) { progress ->
                            withContext(Main) {
                                markChapterProgress(
                                    chapters = chapterStates,
                                    readyCount = progress.readyChapters,
                                    failedCount = progress.failedChapters,
                                    running = !progress.isReady && progress.status != "failed"
                                )
                                statusView.text = progress.formatForUser(chapterStates)
                            }
                        }
                    }
                    if (final.isReady) {
                        context.toastOnUi("章节音频已生成")
                        postEvent(EventBus.AUDIOBOOK_CACHE_STATUS, "ready")
                        postEvent(EventBus.AUDIOBOOK_CACHE_CHANGED, true)
                    }
                }
            } catch (e: Throwable) {
                postEvent(EventBus.AUDIOBOOK_CACHE_STATUS, "idle")
                statusView.text = "章节音频生成提交失败：${e.localizedMessage ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun collectChapterPreview(
        bookUrl: String,
        startIndex: Int,
        submitCount: Int
    ): List<ChapterUiState> {
        return (startIndex until startIndex + submitCount).mapNotNull { index ->
            appDb.bookChapterDao.getChapter(bookUrl, index)?.let {
                ChapterUiState(
                    chapterIndex = index,
                    title = it.title,
                    state = "待生成"
                )
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

    private fun formatChapterStatusDetail(
        chapter: ChapterUiState,
        status: LocalAudiobookFileGenerator.ChapterStatus
    ): String {
        return buildString {
            append((chapter.chapterIndex + 1).toString().padStart(2, '0'))
            append(". 第 ")
            append(chapter.chapterIndex + 1)
            append(" 章 ")
            append(chapter.title.ifBlank { "未命名章节" })
            append("\n状态：")
            append(status.status.ifBlank { "unknown" })
            append("（")
            append(status.status.toChapterState())
            append("）")
            if (status.format.isNotBlank()) {
                append("，格式：")
                append(status.format.toChapterFormatName())
            }
            if (status.sizeBytes > 0) {
                append("，大小：")
                append(status.sizeBytes.formatFileSize())
            }
            if (status.totalItems > 0) {
                append("\n音频片段：")
                append(status.readyItems)
                append("/")
                append(status.totalItems)
                if (status.failedItems > 0) {
                    append("，失败 ")
                    append(status.failedItems)
                }
            }
            if (status.path.isNotBlank() && !status.status.isReadyStatus()) {
                append("\n文件：")
                append(status.path)
            }
            if (status.updatedAt > 0) {
                append("\n更新时间：")
                append(status.updatedAt.formatTime())
            }
            if (status.error.isNotBlank()) {
                append("\n原因：")
                append(status.error)
            }
        }
    }

    private suspend fun pollStatus(
        taskId: String,
        bookName: String,
        bookUrl: String,
        chapters: List<TtsServerDbBridge.AudiobookChapter>,
        statusView: TextView,
        chapterStates: MutableList<ChapterUiState>
    ) {
        try {
            repeat(300) {
                delay(2000)
                val status = withContext(IO) {
                    TtsServerDbBridge.queryAudiobookGeneration(context, taskId).getOrThrow()
                }
                markChapterProgress(
                    chapters = chapterStates,
                    readyCount = status.readyChapters,
                    failedCount = status.failedChapters,
                    running = !status.isFinished
                )
                statusView.text = status.formatForUser(chapterStates)
                if (status.isFinished) {
                    if (status.status.equals("ready", true) || status.status.equals("completed", true)) {
                        val imported = importTtsServerExports(
                            bookName = bookName,
                            bookUrl = bookUrl,
                            chapters = chapters,
                            statusView = statusView,
                            chapterStates = chapterStates
                        )
                        if (imported) {
                            context.toastOnUi("有声书缓存已导入 J阅读")
                            postEvent(EventBus.AUDIOBOOK_CACHE_STATUS, "ready")
                            postEvent(EventBus.AUDIOBOOK_CACHE_CHANGED, true)
                        } else {
                            postEvent(EventBus.AUDIOBOOK_CACHE_STATUS, "idle")
                        }
                    } else {
                        postEvent(EventBus.AUDIOBOOK_CACHE_STATUS, "idle")
                    }
                    return
                }
            }
            statusView.text = formatGenerationStatus(
                header = "TTS 任务仍在运行，已停止本窗口轮询。",
                chapters = chapterStates,
                footer = "任务 ID：$taskId"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            statusView.text = formatGenerationStatus(
                header = "TTS 已接收任务，但无法查询真实缓存进度。",
                chapters = chapterStates,
                footer = "任务 ID：$taskId\n原因：${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        }
    }

    private suspend fun importTtsServerExports(
        bookName: String,
        bookUrl: String,
        chapters: List<TtsServerDbBridge.AudiobookChapter>,
        statusView: TextView,
        chapterStates: MutableList<ChapterUiState>
    ): Boolean {
        var readyCount = 0
        var failedCount = 0
        statusView.text = formatGenerationStatus(
            header = "正在调用 J.TTS 原生整章导出服务...",
            chapters = chapterStates,
            footer = "导出格式：wav"
        )
        chapters.forEachIndexed { index, chapter ->
            val contextFile = runCatching {
                withContext(IO) {
                    JttsChapterContextBridge.prepareChapterContextUri(
                        context = context,
                        bookName = bookName,
                        bookId = bookUrl,
                        chapter = chapter
                    )
                }
            }.getOrElse { error ->
                failedCount += 1
                markChapterProgress(chapterStates, readyCount, failedCount, running = index < chapters.lastIndex)
                statusView.text = formatGenerationStatus(
                    header = "第 ${chapter.chapterIndex + 1} 章上下文文件生成失败",
                    chapters = chapterStates,
                    footer = "原因：${error.localizedMessage ?: error.javaClass.simpleName}"
                )
                return@forEachIndexed
            }
            val export = runCatching {
                withContext(IO) {
                    TtsServerDbBridge.exportAudiobookChapter(
                        context = context,
                        chapter = chapter,
                        sessionId = contextFile.sessionId,
                        contentHash = contextFile.contentHash,
                        chapterContextUri = contextFile.chapterContextUri,
                        preferredFormat = "wav",
                        onProgress = { progress ->
                            statusView.post {
                                statusView.text = formatGenerationStatus(
                                    header = "J.TTS 正在导出第 ${chapter.chapterIndex + 1} 章：$progress%",
                                    chapters = chapterStates,
                                    footer = "sessionId=${contextFile.sessionId}\nhash=${contextFile.contentHash}"
                                )
                            }
                        }
                    ).getOrThrow()
                }
            }.getOrElse { error ->
                failedCount += 1
                markChapterProgress(chapterStates, readyCount, failedCount, running = index < chapters.lastIndex)
                statusView.text = formatGenerationStatus(
                    header = "第 ${chapter.chapterIndex + 1} 章导出失败",
                    chapters = chapterStates,
                    footer = "原因：${error.localizedMessage ?: error.javaClass.simpleName}"
                )
                return@forEachIndexed
            }

            val imported = runCatching {
                withContext(IO) {
                    LocalAudiobookFileGenerator.importTtsServerExport(
                        context = context,
                        bookName = bookName,
                        bookUrl = bookUrl,
                        chapter = chapter,
                        export = export
                    )
                }
            }
            withContext(IO) {
                TtsServerDbBridge.cleanupAudiobookChapterExport(context, export)
                    .onFailure { /* 清理失败不影响 J阅读已导入的加密缓存 */ }
            }
            imported
                .onSuccess {
                    readyCount += 1
                    markChapterProgress(
                        chapters = chapterStates,
                        readyCount = readyCount,
                        failedCount = failedCount,
                        running = index < chapters.lastIndex
                    )
                    statusView.text = formatGenerationStatus(
                        header = "正在导入 J.TTS 整章音频：$readyCount/${chapters.size}",
                        chapters = chapterStates,
                        footer = "最近导入：第 ${chapter.chapterIndex + 1} 章 ${chapter.chapterTitle.ifBlank { "未命名章节" }}"
                    )
                }
                .onFailure { error ->
                    failedCount += 1
                    markChapterProgress(
                        chapters = chapterStates,
                        readyCount = readyCount,
                        failedCount = failedCount,
                        running = index < chapters.lastIndex
                    )
                    statusView.text = formatGenerationStatus(
                        header = "第 ${chapter.chapterIndex + 1} 章导入失败",
                        chapters = chapterStates,
                        footer = "原因：${error.localizedMessage ?: error.javaClass.simpleName}"
                    )
                }
        }
        statusView.text = formatGenerationStatus(
            header = if (failedCount == 0) {
                "J.TTS 整章音频已全部导入 J阅读有声书缓存"
            } else {
                "J.TTS 整章音频导入完成，但有 $failedCount 章失败"
            },
            chapters = chapterStates,
            footer = "成功：$readyCount/${chapters.size}"
        )
        return chapters.isNotEmpty() && readyCount == chapters.size && failedCount == 0
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

    private fun TtsServerDbBridge.AudiobookTaskStatus.formatForUser(
        chapters: List<ChapterUiState>
    ): String {
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
        return formatGenerationStatus(
            header = "状态：$statusName",
            chapters = chapters,
            footer = buildString {
            if (totalChapters > 0) {
                append("章节：")
                append(readyChapters)
                append("/")
                append(totalChapters)
                if (failedChapters > 0) {
                    append("，失败 ")
                    append(failedChapters)
                }
            }
            if (totalItems > 0) {
                if (isNotEmpty()) append("\n")
                append("句子音频：")
                append(readyItems)
                append("/")
                append(totalItems)
                if (failedItems > 0) {
                    append("，失败 ")
                    append(failedItems)
                }
            }
            if (message.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(message)
            }
        })
    }

    private fun LocalAudiobookFileGenerator.Progress.formatForUser(
        chapters: List<ChapterUiState>
    ): String {
        val statusName = when (status.lowercase()) {
            "pending" -> "等待中"
            "caching_audio" -> "正在生成章节音频"
            "ready", "completed" -> "音频已完成"
            "failed" -> "生成失败"
            "cancelled", "canceled" -> "已取消"
            else -> status
        }
        return formatGenerationStatus(
            header = "状态：$statusName",
            chapters = chapters,
            footer = buildString {
            if (totalChapters > 0) {
                append("章节：")
                append(readyChapters)
                append("/")
                append(totalChapters)
                if (failedChapters > 0) {
                    append("，失败 ")
                    append(failedChapters)
                }
            }
            if (totalItems > 0) {
                if (isNotEmpty()) append("\n")
                append("音频片段：")
                append(readyItems)
                append("/")
                append(totalItems)
                if (failedItems > 0) {
                    append("，失败 ")
                    append(failedItems)
                }
            }
            if (lastFilePath.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append("最近文件：")
                append(lastFilePath)
            }
            if (message.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(message)
            }
        })
    }

    private fun String.isReadyStatus(): Boolean {
        return lowercase() in setOf("ready", "completed", "segments_ready")
    }

    private fun String.toChapterState(): String {
        return when (lowercase()) {
            "ready", "completed" -> "已生成"
            "segments_ready" -> "片段已缓存"
            "failed" -> "生成失败"
            "pending" -> "等待生成"
            "preparing" -> "预备生成"
            "caching_audio" -> "生成中"
            "cancelled", "canceled" -> "已取消"
            "not_generated" -> "未生成"
            else -> ifBlank { "未知" }
        }
    }

    private fun LocalAudiobookFileGenerator.ChapterStatus.toChapterQueueState(): String {
        if (status.isReadyStatus()) {
            return when (format.lowercase()) {
                "protected_mp3" -> "已生成受保护 MP3"
                "jreadmp3" -> "已生成受保护 MP3"
                "mp3" -> "已生成 MP3"
                "wav" -> "已生成 WAV"
                "audio" -> "已生成音频"
                else -> "已生成"
            }
        }
        if (status.lowercase() == "segments_ready") return "片段已缓存"
        return status.toChapterState()
    }

    private fun LocalAudiobookFileGenerator.ChapterStatus.toReadableChapterStatus(
        chapterIndex: Int,
        currentIndex: Int
    ): LocalAudiobookFileGenerator.ChapterStatus {
        val rawStatus = status.lowercase()
        val isCurrentChapter = chapterIndex == currentIndex
        val hasPartialSegments = totalItems > 0 && readyItems > 0 && !status.isReadyStatus()
        return if (rawStatus == "failed" && (isCurrentChapter || hasPartialSegments)) {
            copy(
                status = "preparing",
                error = "",
                path = ""
            )
        } else {
            this
        }
    }

    private fun String.toChapterFormatName(): String {
        return when (lowercase()) {
            "protected_mp3", "jreadmp3" -> "受保护 MP3"
            else -> uppercase()
        }
    }

    private fun Long.formatFileSize(): String {
        if (this < 1024L) return "${this}B"
        val units = listOf("KB", "MB", "GB")
        var value = this.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return String.format(Locale.US, "%.1f%s", value, units[unitIndex])
    }

    private fun Long.formatTime(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(this))
    }

    private data class ChapterUiState(
        val chapterIndex: Int,
        val title: String,
        var state: String
    )

    private data class ChapterStatusRow(
        val chapter: ChapterUiState,
        val status: LocalAudiobookFileGenerator.ChapterStatus,
        val canDelete: Boolean
    )

    private data class ChapterStatusData(
        val header: String,
        val rows: List<ChapterStatusRow>,
        val footer: String
    )

    private fun markChapterProgress(
        chapters: MutableList<ChapterUiState>,
        readyCount: Int,
        failedCount: Int,
        running: Boolean
    ) {
        val safeReady = readyCount.coerceIn(0, chapters.size)
        val safeFailed = failedCount.coerceIn(0, chapters.size - safeReady)
        chapters.forEachIndexed { index, chapter ->
            chapter.state = when {
                index < safeReady -> "已生成"
                index < safeReady + safeFailed -> "生成失败"
                running && index == safeReady + safeFailed -> "生成中"
                else -> "等待生成"
            }
        }
    }

    private fun formatGenerationStatus(
        header: String,
        chapters: List<ChapterUiState>,
        footer: String
    ): String {
        return buildString {
            append(header)
            if (chapters.isNotEmpty()) {
                append("\n\n章节队列：\n")
                append(formatChapterQueue(chapters))
            }
            if (footer.isNotBlank()) {
                append("\n\n")
                append(footer)
            }
        }
    }

    private fun formatChapterQueue(chapters: List<ChapterUiState>): String {
        return chapters.joinToString(separator = "\n") { chapter ->
            val number = (chapter.chapterIndex + 1).toString().padStart(2, '0')
            val title = chapter.title.ifBlank { "未命名章节" }
            "$number. 第 ${chapter.chapterIndex + 1} 章 $title：${chapter.state}"
        }
    }
}
