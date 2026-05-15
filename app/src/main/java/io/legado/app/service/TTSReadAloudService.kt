package io.legado.app.service

import android.app.PendingIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.JttsContextBridge
import io.legado.app.help.MediaHelp
import io.legado.app.help.TtsServerDbBridge
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * 本地朗读
 */
class TTSReadAloudService : BaseReadAloudService(), TextToSpeech.OnInitListener {

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    private val ttsUtteranceListener = TTSUtteranceListener()
    private var speakJob: Coroutine<*>? = null
    private var currentEngine: String? = null
    private val TAG = "TTSReadAloudService"

    override fun onCreate() {
        super.onCreate()
        initTts()
    }

    override fun onDestroy() {
        super.onDestroy()
        clearTTS()
    }

    @Synchronized
    private fun initTts(forceSystemDefault: Boolean = false) {
        ttsInitFinish = false
        val configuredEngine = GSON.fromJsonObject<SelectItem<String>>(ReadAloud.ttsEngine).getOrNull()?.value
        val engine = when {
            forceSystemDefault -> ""
            configuredEngine.isNullOrBlank() -> TtsServerDbBridge.TTS_PACKAGE
            else -> configuredEngine
        }
        currentEngine = engine
        AppLog.putDebug(
            "[JRead-JTTS] bridge enabled=${JttsContextBridge.isEnabledForEngine(engine)} " +
                    "current engine=$engine"
        )
        if (TtsServerDbBridge.isJttsEngine(engine)) {
            TtsServerDbBridge.ensureRunning(this)
        }
        LogUtils.d(TAG, "initTts engine:$engine")
        textToSpeech = if (engine.isNullOrBlank()) {
            TextToSpeech(this, this)
        } else {
            TextToSpeech(this, this, engine)
        }
        upSpeechRate()
    }

    @Synchronized
    fun clearTTS() {
        textToSpeech?.runCatching {
            stop()
            shutdown()
        }
        textToSpeech = null
        ttsInitFinish = false
        currentEngine = null
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let {
                it.setOnUtteranceProgressListener(ttsUtteranceListener)
                ttsInitFinish = true
                play()
            }
        } else {
            if (TtsServerDbBridge.isJttsEngine(currentEngine)) {
                AppLog.putDebug("TTS Server 初始化失败，临时回退系统默认引擎")
                clearTTS()
                initTts(forceSystemDefault = true)
                return
            }
            toastOnUi(R.string.tts_init_failed)
        }
    }

    @Synchronized
    override fun play() {
        if (!ttsInitFinish) return
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
            return
        }
        super.play()
        MediaHelp.playSilentSound(this@TTSReadAloudService)
        speakJob?.cancel()
        speakJob = execute {
            LogUtils.d(TAG, "朗读列表大小 ${contentList.size}")
            LogUtils.d(TAG, "朗读页数 ${textChapter?.pageSize}")
            val tts = textToSpeech ?: throw NoStackTraceException("tts is null")
            val contentList = contentList
            val jttsContext = if (JttsContextBridge.isEnabledForEngine(currentEngine)) {
                JttsContextBridge.prepareChapterContext(ReadBook.book, textChapter)
            } else {
                null
            }
            var nextQueueMode = TextToSpeech.QUEUE_FLUSH
            var queuedReadOffset = readAloudNumber
            var isAddedText = false
            for (i in nowSpeak until contentList.size) {
                ensureActive()
                val rawText = contentList[i]
                val firstOffset = if (i == nowSpeak) paragraphStartPos else 0
                var text = contentList[i]
                if (firstOffset > 0) {
                    text = text.substring(firstOffset)
                }
                if (text.matches(AppPattern.notReadAloudRegex)) {
                    queuedReadOffset += rawText.length + 1 - firstOffset
                    continue
                }
                if (!isAddedText) {
                    if (jttsContext?.shouldSend == true) {
                        val sent = sendJttsContextChunks(tts, jttsContext, nextQueueMode)
                        if (sent) {
                            JttsContextBridge.markContextSent(jttsContext)
                            nextQueueMode = TextToSpeech.QUEUE_ADD
                        }
                    }
                }
                val startOffset = queuedReadOffset
                val endOffset = startOffset + text.length
                if (jttsContext != null) {
                    val pointerSent = sendJttsPointer(
                        tts = tts,
                        context = jttsContext,
                        text = text,
                        startOffset = startOffset,
                        endOffset = endOffset,
                        utteranceIndex = i,
                        queueMode = nextQueueMode
                    )
                    if (pointerSent) {
                        nextQueueMode = TextToSpeech.QUEUE_ADD
                    }
                }
                if (!isAddedText) {
                    val result = tts.runCatching {
                        AppLog.putDebug("[JRead-JTTS] speak body after pointer textLen=${text.length}")
                        speak(text, nextQueueMode, null, AppConst.APP_TAG + i)
                    }.getOrElse {
                        AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                        TextToSpeech.ERROR
                    }
                    if (result == TextToSpeech.ERROR) {
                        AppLog.put("tts出错 尝试重新初始化")
                        clearTTS()
                        initTts()
                        return@execute
                    }
                } else {
                    val result = tts.runCatching {
                        speak(text, TextToSpeech.QUEUE_ADD, null, AppConst.APP_TAG + i)
                    }.getOrElse {
                        AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                        TextToSpeech.ERROR
                    }
                    if (result == TextToSpeech.ERROR) {
                        AppLog.put("tts朗读出错:$text")
                    }
                }
                nextQueueMode = TextToSpeech.QUEUE_ADD
                queuedReadOffset += rawText.length + 1 - firstOffset
                isAddedText = true
            }
            LogUtils.d(TAG, "朗读内容添加完成")
            if (!isAddedText) {
                playStop()
                delay(1000)
                nextChapter()
            }
        }.onError {
            AppLog.put("tts朗读出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun sendJttsContextChunks(
        tts: TextToSpeech,
        context: JttsContextBridge.ChapterContext,
        firstQueueMode: Int
    ): Boolean {
        var queueMode = firstQueueMode
                for ((index, chunk) in context.chunks.withIndex()) {
            val result = tts.runCatching {
                speak(
                    chunk,
                    queueMode,
                    null,
                    JttsContextBridge.chunkUtteranceId(context, index)
                )
            }.getOrElse {
                AppLog.putDebug("J.TTS 无 Web 上下文直通：分片发送异常 ${it.localizedMessage.orEmpty()}")
                TextToSpeech.ERROR
            }
            if (result == TextToSpeech.ERROR) {
                AppLog.putDebug(
                    "J.TTS 无 Web 上下文直通：分片发送失败 " +
                            "book=${context.bookName}, chapter=${context.chapterTitle}, index=$index"
                )
                return false
            }
            AppLog.putDebug(
                "[JRead-JTTS] send ctx chunk ${index + 1}/${context.chunks.size} " +
                        "utteranceId=${JttsContextBridge.chunkUtteranceId(context, index)}"
            )
            queueMode = TextToSpeech.QUEUE_ADD
        }
        return true
    }

    private fun sendJttsPointer(
        tts: TextToSpeech,
        context: JttsContextBridge.ChapterContext,
        text: String,
        startOffset: Int,
        endOffset: Int,
        utteranceIndex: Int,
        queueMode: Int
    ): Boolean {
        val marker = JttsContextBridge.pointerMarker(
            context = context,
            currentText = text,
            startOffset = startOffset,
            endOffset = endOffset,
            fallbackChapterIndex = textChapter?.chapter?.index ?: -1
        ) ?: return false
        val result = tts.runCatching {
            speak(
                marker,
                queueMode,
                null,
                JttsContextBridge.pointerUtteranceId(context, utteranceIndex)
            )
        }.getOrElse {
            AppLog.putDebug("J.TTS 无 Web 指针发送异常 ${it.localizedMessage.orEmpty()}")
            TextToSpeech.ERROR
        }
        if (result == TextToSpeech.ERROR) {
            AppLog.putDebug(
                "J.TTS 无 Web 指针发送失败 " +
                        "chapter=${context.chapterTitle}, start=$startOffset, end=$endOffset"
            )
            return false
        }
        AppLog.putDebug(
            "[JRead-JTTS] send pointer sessionId=${context.sessionId} " +
                    "start=$startOffset end=$endOffset textLen=${text.length}"
        )
        return true
    }

    override fun playStop() {
        textToSpeech?.runCatching {
            stop()
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        if (AppConfig.ttsFlowSys) {
            if (reset) {
                clearTTS()
                initTts()
            }
        } else {
            val speechRate = (AppConfig.ttsSpeechRate + 5) / 10f
            textToSpeech?.setSpeechRate(speechRate)
        }
    }

    /**
     * 暂停朗读
     */
    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        speakJob?.cancel()
        textToSpeech?.runCatching {
            stop()
        }
    }

    /**
     * 恢复朗读
     */
    override fun resumeReadAloud() {
        super.resumeReadAloud()
        play()
    }

    /**
     * 朗读监听
     */
    private inner class TTSUtteranceListener : UtteranceProgressListener() {

        private val TAG = "TTSUtteranceListener"

        override fun onStart(s: String) {
            if (JttsContextBridge.isBridgeUtterance(s)) {
                LogUtils.d(TAG, "onStart bridge utteranceId:$s")
                return
            }
            LogUtils.d(TAG, "onStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$s")
            textChapter?.let {
                if (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex)) {
                    nextParagraph()
                }
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber + 1 > it.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                }
                upTtsProgress(readAloudNumber + 1)
            }
        }

        override fun onDone(s: String) {
            if (JttsContextBridge.isBridgeUtterance(s)) {
                LogUtils.d(TAG, "onDone bridge utteranceId:$s")
                return
            }
            LogUtils.d(TAG, "onDone utteranceId:$s")
            nextParagraph()
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            super.onRangeStart(utteranceId, start, end, frame)
            if (JttsContextBridge.isBridgeUtterance(utteranceId)) {
                LogUtils.d(TAG, "onRangeStart bridge utteranceId:$utteranceId start:$start end:$end")
                return
            }
            val msg =
                "onRangeStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId start:$start end:$end frame:$frame"
            LogUtils.d(TAG, msg)
            textChapter?.let {
                if (pageIndex + 1 < it.pageSize
                    && readAloudNumber + start > it.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                    upTtsProgress(readAloudNumber + start)
                }
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (JttsContextBridge.isBridgeUtterance(utteranceId)) {
                LogUtils.d(TAG, "onError bridge utteranceId:$utteranceId errorCode:$errorCode")
                return
            }
            LogUtils.d(
                TAG,
                "onError nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId errorCode:$errorCode"
            )
            nextParagraph()
        }

        private fun nextParagraph() {
            //跳过全标点段落
            do {
                readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
                paragraphStartPos = 0
                nowSpeak++
                if (nowSpeak >= contentList.size) {
                    markChapterFinishedByPlayback()
                    nextChapter()
                    return
                }
            } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String) {
            if (JttsContextBridge.isBridgeUtterance(s)) {
                LogUtils.d(TAG, "onError bridge utteranceId:$s")
                return
            }
            LogUtils.d(TAG, "onError nowSpeak:$nowSpeak pageIndex:$pageIndex s:$s")
            nextParagraph()
        }

    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }

}
