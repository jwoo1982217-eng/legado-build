package io.legado.app.ui.book.read.config

//import io.legado.app.lib.theme.bottomBackground
//import io.legado.app.lib.theme.getPrimaryTextColor
import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
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
                toastOnUi("已开启：朗读自然读完一章后自动合成整章音频")
            } else {
                toastOnUi("已关闭读完自动合成")
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
            appendLine()
            if (analysis.characters.isEmpty()) {
                appendLine("当前章暂未识别到明确角色。")
                appendLine("台词本里可能会先显示“角色待定”，后续接入 AI/朗读规则后再自动分配。")
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
        val body = """
            当前是开源阅读端“大脑模块”第一版。

            已启用：
            1. 本地规则拆分旁白和对白。
            2. 尝试从“某某说/问/道/喊”等结构识别角色。
            3. 生成角色表和台词本，并保存到阅读私有目录。

            还没接入：
            1. AI 整章角色分析。
            2. 旧朗读规则 JS 兼容导入。
            3. 从 J.TTS 实时读取完整音色标签库。

            这版先用来验证入口、数据结构和 UI 工作流，后面再把 AI/朗读规则接进来。
        """.trimIndent()
        AlertDialog.Builder(requireContext())
            .setTitle("分析规则")
            .setView(dialogTextView(body))
            .setNegativeButton("重新分析本章") { _, _ ->
                val analysis = ScriptBrain.analyzeCurrentChapter(requireContext())
                toastOnUi("已重新分析：${analysis.lines.size} 行台词")
            }
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
