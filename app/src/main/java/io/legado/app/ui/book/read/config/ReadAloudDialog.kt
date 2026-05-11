package io.legado.app.ui.book.read.config

//import io.legado.app.lib.theme.bottomBackground
//import io.legado.app.lib.theme.getPrimaryTextColor
import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import io.legado.app.R
import io.legado.app.base.BaseBottomSheetDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.databinding.DialogReadAloudBinding
import io.legado.app.help.audiobook.ScriptBrain
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.selector
import io.legado.app.model.AiBgMusic
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.StringUtils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.observeEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class ReadAloudDialog : BaseBottomSheetDialogFragment(R.layout.dialog_read_aloud),
    SpeakEngineDialog.CallBack {
    private val callBack: CallBack? get() = activity as? CallBack
    private val binding by viewBinding(DialogReadAloudBinding::bind)
    private val speakEngineSummary: String
        get() {
            val ttsEngine = ReadAloud.ttsEngine
                ?: return getString(R.string.system_tts)
            if (StringUtils.isNumeric(ttsEngine)) {
                return appDb.httpTTSDao.getName(ttsEngine.toLong())
                    ?: getString(R.string.system_tts)
            }
            return GSON.fromJsonObject<SelectItem<String>>(ttsEngine).getOrNull()?.title
                ?: getString(R.string.system_tts)
        }

    override fun onStart() {
        super.onStart()
//        dialog?.window?.run {
//            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
//            setBackgroundDrawableResource(R.color.background)
//            decorView.setPadding(0, 0, 0, 0)
//            val attr = attributes
//            attr.dimAmount = 0.0f
//            attr.gravity = Gravity.BOTTOM
//            attributes = attr
//            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
//        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as ReadBookActivity).bottomDialog--
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        val bottomDialog = (activity as ReadBookActivity).bottomDialog++
        if (bottomDialog > 0) {
            dismiss()
            return
        }
        //val bg = requireContext().bottomBackground
        //val isLight = ColorUtils.isColorLight(bg)
        //val textColor = requireContext().getPrimaryTextColor(isLight)
        binding.run {
//            rootView.setBackgroundColor(bg)
//            tvPre.setTextColor(textColor)
//            tvNext.setTextColor(textColor)
//            ivPlayPrev.setColorFilter(textColor)
//            ivPlayPause.setColorFilter(textColor)
//            ivPlayNext.setColorFilter(textColor)
//            ivStop.setColorFilter(textColor)
//            ivTimer.setColorFilter(textColor)
//            tvTimer.setTextColor(textColor)
//            ivTtsSpeechReduce.setColorFilter(textColor)
//            tvTtsSpeed.setTextColor(textColor)
//            tvTtsSpeedValue.setTextColor(textColor)
//            ivTtsSpeechAdd.setColorFilter(textColor)
//            ivCatalog.setColorFilter(textColor)
//            tvCatalog.setTextColor(textColor)
//            ivMainMenu.setColorFilter(textColor)
//            tvMainMenu.setTextColor(textColor)
//            ivToBackstage.setColorFilter(textColor)
//            tvToBackstage.setTextColor(textColor)
//            ivSetting.setColorFilter(textColor)
//            tvSetting.setTextColor(textColor)
//            cbTtsFollowSys.setTextColor(textColor)
        }
        initData()
        initEvent()
    }

    private fun initData() = binding.run {
        upPlayState()
        upTimerText(BaseReadAloudService.timeMinute)
        cbTtsFollowSys.isChecked = requireContext().getPrefBoolean("ttsFollowSys", true)
        cbAiBgm.isChecked = AiBgMusic.enabled
        cbAudioPreload.isChecked = AppConfig.audioPreloadEnabled && AppConfig.audioPreDownloadNum > 0
        cbAudiobookAutoMerge.isChecked = AppConfig.audiobookAutoMergeAfterRead
        upSpeakEngineSummary()
        upTtsSpeechRateEnabled(!cbTtsFollowSys.isChecked)
        upSeekTimer()
    }

    private fun initEvent() = binding.run {
        ivMainMenu.setOnClickListener {
            callBack?.showMenuBar()
            dismissAllowingStateLoss()
        }
        ivSetting.setOnClickListener {
            ReadAloudConfigDialog().show(childFragmentManager, "readAloudConfigDialog")
        }
        btnSpeakEngineSetting.setOnClickListener {
            SpeakEngineDialog().show(childFragmentManager, "speakEngineDialog")
        }
        btnAiBgmConfig.setOnClickListener {
            showAiBgMusicPlaybackConfig()
        }
        btnScriptCharacters.setOnClickListener { showScriptCharacters() }
        btnScriptPreview.setOnClickListener { showScriptPreview() }
        btnScriptRules.setOnClickListener { showScriptRules() }
        tvPre.setOnClickListener { ReadBook.moveToPrevChapter(upContent = true, toLast = false) }
        tvNext.setOnClickListener { ReadBook.moveToNextChapter(true) }
        ivStop.setOnClickListener {
            ReadAloud.stop(requireContext())
            dismissAllowingStateLoss()
        }
        ivPlayPause.setOnClickListener { callBack?.onClickReadAloud() }
        ivPlayPrev.setOnClickListener { ReadAloud.prevParagraph(requireContext()) }
        ivPlayNext.setOnClickListener { ReadAloud.nextParagraph(requireContext()) }
        ivCatalog.setOnClickListener { callBack?.openChapterList() }
        ivToBackstage.setOnClickListener {
            callBack?.returnToBookshelf()
            dismissAllowingStateLoss()
        }
        cbTtsFollowSys.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.ttsFlowSys = isChecked
            upTtsSpeechRateEnabled(!isChecked)
            upTtsSpeechRate()
        }
        cbAiBgm.setOnCheckedChangeListener { _, isChecked ->
            AiBgMusic.enabled = isChecked
            if (isChecked) {
                toastOnUi("智能背景音乐已开启")
            } else {
                toastOnUi("智能背景音乐已关闭")
            }
        }

        cbAudioPreload.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && AppConfig.audioPreDownloadNum <= 0) {
                cbAudioPreload.isChecked = false
                toastOnUi("请先在设置里把听书预加载数量设为大于 0")
                return@setOnCheckedChangeListener
            }
            AppConfig.audioPreloadEnabled = isChecked
            if (isChecked) {
                toastOnUi("后台预缓存已开启，数量按听书预加载数量执行")
            } else {
                toastOnUi("后台预缓存已暂停")
            }
        }

        cbAudiobookAutoMerge.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.audiobookAutoMergeAfterRead = isChecked
            if (isChecked) {
                toastOnUi("已开启：读完章节后自动生成受保护MP3")
            } else {
                toastOnUi("已关闭受保护MP3自动生成")
            }
        }

        btnAudiobookGenerate.setOnClickListener {
            callBack?.generateAudiobookCache()
            dismissAllowingStateLoss()
        }
        btnAudiobookStatus.setOnClickListener {
            callBack?.showAudiobookCacheStatus()
            dismissAllowingStateLoss()
        }

        ivTimer.setOnClickListener {
            AppConfig.ttsTimer = seekTimer.value.toInt()
            toastOnUi("保存设定时间成功！")
        }

        // 设置初始值
        seekTtsSpeechRate.value = AppConfig.ttsSpeechRate.toFloat()
        seekTimer.value = if (BaseReadAloudService.timeMinute > 0)
            BaseReadAloudService.timeMinute.toFloat()
        else AppConfig.ttsTimer.toFloat()

        // 减速按钮逻辑
        ivTtsSpeechReduce.setOnClickListener {
            val newValue = (seekTtsSpeechRate.value - 1).coerceAtLeast(seekTtsSpeechRate.valueFrom)
            seekTtsSpeechRate.value = newValue
            AppConfig.ttsSpeechRate = newValue.toInt()
            upTtsSpeechRateText(newValue.toInt())
            upTtsSpeechRate()
        }

        // 加速按钮逻辑
        ivTtsSpeechAdd.setOnClickListener {
            val newValue = (seekTtsSpeechRate.value + 1).coerceAtMost(seekTtsSpeechRate.valueTo)
            seekTtsSpeechRate.value = newValue
            AppConfig.ttsSpeechRate = newValue.toInt()
            upTtsSpeechRateText(newValue.toInt())
            upTtsSpeechRate()
        }

        btnTimer.setOnClickListener {
            val times = intArrayOf(0, 5, 10, 15, 30, 60, 90, 180)
            val timeKeys = times.map { "$it 分钟" }
            context?.selector("设定时间", timeKeys) { _, index ->
                ReadAloud.setTimer(requireContext(), times[index])
                upTimerText(times[index])
            }
        }

        //设置保存的默认值
        seekTtsSpeechRate.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                upTtsSpeechRateText(value.toInt())
            }
        }

        seekTtsSpeechRate.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                AppConfig.ttsSpeechRate = slider.value.toInt()
                upTtsSpeechRate()
            }
        })

        seekTimer.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                upTimerText(value.toInt())
            }
        }

        seekTimer.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                ReadAloud.setTimer(requireContext(), slider.value.toInt())
            }
        })

    }

    private fun upTtsSpeechRateEnabled(enabled: Boolean) {
        binding.run {
            upTtsSpeechRateText(AppConfig.ttsSpeechRate)
            tvTtsSpeedValue.visible(enabled)
            seekTtsSpeechRate.isEnabled = enabled
            ivTtsSpeechReduce.isEnabled = enabled
            ivTtsSpeechAdd.isEnabled = enabled
        }
    }

    private fun upPlayState() {
        if (!BaseReadAloudService.pause) {
            binding.ivPlayPause.icon =
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_pause)
            binding.ivPlayPause.contentDescription = getString(R.string.pause)
        } else {
            binding.ivPlayPause.icon =
                ContextCompat.getDrawable(requireContext(), R.drawable.ic_play)
            binding.ivPlayPause.contentDescription = getString(R.string.audio_play)
        }

        // val bg = requireContext().bottomBackground
        // val isLight = ColorUtils.isColorLight(bg)
        // val textColor = requireContext().getPrimaryTextColor(isLight)
        // binding.ivPlayPause.iconTint = ColorStateList.valueOf(textColor)
    }

    private fun upSeekTimer() {
        binding.seekTimer.post {
            binding.seekTimer.value = if (BaseReadAloudService.timeMinute > 0) {
                BaseReadAloudService.timeMinute.toFloat()
            } else {
                AppConfig.ttsTimer.toFloat()
            }
        }
    }

    private fun upTimerText(timeMinute: Int) {
        if (timeMinute < 0) {
            binding.btnTimer.text = requireContext().getString(R.string.timer_m, 0)
        } else {
            binding.btnTimer.text = requireContext().getString(R.string.timer_m, timeMinute)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun upTtsSpeechRateText(value: Int) {
        binding.tvTtsSpeedValue.text = value.toString()
    }

    override fun upSpeakEngineSummary() {
        binding.btnSpeakEngineSetting.contentDescription =
            getString(R.string.speak_engine) + "：" + speakEngineSummary
    }

    private fun showAiBgMusicPlaybackConfig() {
        val items = listOf("设置", "频率", "播放列表", "AI 分析", "重新分析")
        context?.selector("背景音乐播放配置", items) { _, index ->
            when (index) {
                0 -> callBack?.openAiBgMusicSettings()
                1 -> callBack?.showAiBgMusicFrequency()
                2 -> callBack?.showAiBgMusicPlaylist()
                3 -> callBack?.showAiBgMusicAnalysis()
                4 -> callBack?.reanalyzeAiBgMusic()
            }
        }
    }

    private fun showScriptCharacters() {
        val analysis = ScriptBrain.analyzeCurrentChapter(requireContext())
        val body = buildString {
            appendLine("${analysis.chapterTitle}：共 ${analysis.characters.size} 个角色")
            appendLine("来源：${analysis.source}")
            if (analysis.error.isNotBlank()) {
                appendLine("提示：${analysis.error}")
            }
            appendLine()
            if (analysis.characters.isEmpty()) {
                appendLine("当前章暂未识别到明确角色。")
                appendLine("台词本里可能会先显示“角色待定”，可先导入旧朗读规则再运行。")
            } else {
                analysis.characters.forEachIndexed { index, character ->
                    appendLine("${index + 1}. ${character.name}")
                    appendLine("   ${character.gender} / ${character.ageType} / ${character.voiceTag}")
                }
            }
        }
        showTextDialog("角色表", body)
    }

    private fun showScriptPreview() {
        val analysis = ScriptBrain.analyzeCurrentChapter(requireContext())
        val body = buildString {
            appendLine("${analysis.chapterTitle}")
            appendLine("台词行：${analysis.lines.size}，角色：${analysis.characters.size}")
            appendLine("来源：${analysis.source}")
            if (analysis.error.isNotBlank()) {
                appendLine("提示：${analysis.error}")
            }
            appendLine()
            analysis.lines.take(120).forEach { line ->
                appendLine("${line.index.toString().padStart(2, '0')}  ${line.roleName}  ${line.voiceTag}")
                appendLine("    ${line.text.take(120)}")
            }
            if (analysis.lines.size > 120) {
                appendLine()
                appendLine("已显示前 120 行，其余已保存到本地分析文件。")
            }
        }
        showTextDialog("台词本", body)
    }

    private fun showScriptRules() {
        val imported = ScriptBrain.hasImportedRule(requireContext())
        val items = mutableListOf(
            if (imported) "粘贴/替换朗读规则" else "粘贴导入朗读规则",
            "运行当前章",
            "查看已导入规则",
            "清空已导入规则",
            "说明"
        )
        context?.selector("分析规则", items) { _, index ->
            when (index) {
                0 -> showPasteScriptRuleDialog()
                1 -> runScriptRuleForCurrentChapter()
                2 -> showImportedScriptRule()
                3 -> clearImportedScriptRule()
                4 -> showScriptRuleHelp()
            }
        }
    }

    private fun showPasteScriptRuleDialog() {
        val editText = EditText(requireContext()).apply {
            setText(ScriptBrain.loadImportedRule(requireContext()))
            hint = "粘贴旧 TTS 朗读规则 JS，或包含 code 字段的规则 JSON"
            minLines = 10
            maxLines = 18
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(false)
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
        }
        AlertDialog.Builder(requireContext())
            .setTitle("导入朗读规则")
            .setView(ScrollView(requireContext()).apply { addView(editText) })
            .setPositiveButton("保存") { _, _ ->
                runCatching {
                    ScriptBrain.saveImportedRule(requireContext(), editText.text?.toString().orEmpty())
                    toastOnUi("朗读规则已保存")
                }.onFailure {
                    toastOnUi("保存失败：${it.localizedMessage ?: it.javaClass.simpleName}")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runScriptRuleForCurrentChapter() {
        if (!ScriptBrain.hasImportedRule(requireContext())) {
            toastOnUi("请先导入朗读规则")
            return
        }
        val appContext = requireContext().applicationContext
        toastOnUi("正在运行朗读规则...")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ScriptBrain.runImportedRuleForCurrentChapter(appContext) }
            }
            if (!isAdded) return@launch
            result.onSuccess { runResult ->
                val analysis = runResult.analysis
                val body = buildString {
                    appendLine("${analysis.chapterTitle}")
                    appendLine("运行完成：${analysis.lines.size} 行台词，${analysis.characters.size} 个角色")
                    appendLine()
                    analysis.lines.take(120).forEach { line ->
                        appendLine("${line.index.toString().padStart(2, '0')}  ${line.roleName}  ${line.voiceTag}")
                        appendLine("    ${line.text.take(120)}")
                    }
                    if (runResult.logs.isNotEmpty()) {
                        appendLine()
                        appendLine("日志：")
                        runResult.logs.takeLast(30).forEach { appendLine(it) }
                    }
                    if (analysis.lines.size > 120) {
                        appendLine()
                        appendLine("已显示前 120 行，完整结果已保存。")
                    }
                }
                showTextDialog("朗读规则运行结果", body)
            }.onFailure {
                showTextDialog(
                    "朗读规则运行失败",
                    it.localizedMessage ?: it.stackTraceToString()
                )
            }
        }
    }

    private fun showImportedScriptRule() {
        val rule = ScriptBrain.loadImportedRule(requireContext())
        if (rule.isBlank()) {
            toastOnUi("还没有导入朗读规则")
            return
        }
        showTextDialog(
            "已导入朗读规则",
            "长度：${rule.length} 字符\n\n" + rule.take(12000) +
                    if (rule.length > 12000) "\n\n已截断显示前 12000 字符。" else ""
        )
    }

    private fun clearImportedScriptRule() {
        AlertDialog.Builder(requireContext())
            .setTitle("清空朗读规则")
            .setMessage("清空后会退回本地简易规则。")
            .setPositiveButton("清空") { _, _ ->
                ScriptBrain.clearImportedRule(requireContext())
                toastOnUi("已清空朗读规则")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showScriptRuleHelp() {
        val body = """
            当前是开源阅读端“大脑模块”第一版。

            已启用：
            1. 可以粘贴导入旧 TTS 朗读规则 JS。
            2. 可以运行当前章，调用 SpeechRuleJS.prepareChapterAudioQueue。
            3. 运行结果会转换成台词本，后续缓存/请求音频应以这份队列为准。
            4. 兼容 ttsrv.readTxtFile/writeTxtFile/httpGet/httpPost 的基础能力。

            当前限制：
            1. 角色管理插件 UI 原样嵌入还没做。
            2. 旧规则里如果依赖 TTS 私有 Android UI API，可能还会失败。
            3. 从 J.TTS 实时读取完整音色标签库还没接。

            如果未导入规则，台词本会退回本地简易规则。
        """.trimIndent()
        AlertDialog.Builder(requireContext())
            .setTitle("分析规则")
            .setView(dialogTextView(body))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showTextDialog(title: String, body: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(dialogTextView(body))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun dialogTextView(body: String): ScrollView {
        val textView = TextView(requireContext()).apply {
            text = body
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(20.dpToPx(), 12.dpToPx(), 20.dpToPx(), 12.dpToPx())
        }
        return ScrollView(requireContext()).apply {
            addView(textView)
        }
    }

    private fun upTtsSpeechRate() {
        ReadAloud.upTtsSpeechRate(requireContext())
        if (!BaseReadAloudService.pause) {
            ReadAloud.pause(requireContext())
            ReadAloud.resume(requireContext())
        }
    }

    override fun observeLiveBus() {
        observeEvent<Int>(EventBus.ALOUD_STATE) { upPlayState() }
        observeEvent<Int>(EventBus.READ_ALOUD_DS) {
            val value = it.coerceIn(binding.seekTimer.valueFrom.toInt(), binding.seekTimer.valueTo.toInt())
            binding.seekTimer.value = value.toFloat()
        }
    }

    interface CallBack {
        fun showMenuBar()
        fun openChapterList()
        fun onClickReadAloud()
        fun generateAudiobookCache()
        fun showAudiobookCacheStatus()
        fun openAiBgMusicSettings()
        fun showAiBgMusicFrequency()
        fun showAiBgMusicPlaylist()
        fun showAiBgMusicAnalysis()
        fun reanalyzeAiBgMusic()
        fun returnToBookshelf()
    }
}
