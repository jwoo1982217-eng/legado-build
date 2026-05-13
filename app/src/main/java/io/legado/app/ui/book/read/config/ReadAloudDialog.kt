package io.legado.app.ui.book.read.config

//import io.legado.app.lib.theme.bottomBackground
//import io.legado.app.lib.theme.getPrimaryTextColor
import android.annotation.SuppressLint
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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
    private data class ScriptModelPreset(
        val provider: String,
        val label: String,
        val modelUrl: String,
        val modelName: String,
    )

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
    private val importSpeechRuleJson =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@registerForActivityResult
            val appContext = context?.applicationContext ?: return@registerForActivityResult
            toastOnUi("正在导入完整朗读规则 JSON...")
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = appContext.contentResolver.openInputStream(uri)?.use {
                            String(it.readBytes(), Charsets.UTF_8)
                        }.orEmpty()
                        require(text.isNotBlank()) { "文件内容为空" }
                        ScriptBrain.saveImportedRule(appContext, text)
                        ScriptBrain.importedRuleInfo(appContext)
                    }
                }
                result.onSuccess { info ->
                    toastOnUi("已导入朗读规则：${info?.name.orEmpty().ifBlank { "完整 JSON" }}")
                }.onFailure {
                    toastOnUi("导入失败：${it.localizedMessage ?: it.javaClass.simpleName}")
                }
            }
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
        AppConfig.audiobookConvertMergedToMp3 = true
        cbScriptBrainEnabled.isChecked = AppConfig.scriptBrainEnabled
        upScriptBrainToolsVisible(AppConfig.scriptBrainEnabled)
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
        cbScriptBrainEnabled.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.scriptBrainEnabled = isChecked
            upScriptBrainToolsVisible(isChecked)
            if (isChecked) {
                toastOnUi("内置分析模式已开启")
            } else {
                toastOnUi("内置分析模式已关闭，继续兼容 TTS 端朗读规则")
            }
        }
        btnScriptCharacters.setOnClickListener { showScriptCharacters() }
        btnScriptPreview.setOnClickListener { showScriptPreview() }
        btnScriptRules.setOnClickListener { showScriptRules() }
        btnScriptModelConfig.setOnClickListener { showScriptModelConfig() }
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
                ReadAloud.startAudioPreload(requireContext())
                toastOnUi("后台预缓存已开启，数量按听书预加载数量执行")
            } else {
                ReadAloud.stopAudioPreload(requireContext())
                toastOnUi("后台预缓存已暂停")
            }
        }

        cbAudiobookAutoMerge.setOnCheckedChangeListener { _, isChecked ->
            AppConfig.audiobookAutoMergeAfterRead = isChecked
            AppConfig.audiobookConvertMergedToMp3 = true
            if (isChecked) {
                toastOnUi("生成有声书已开启，完整章节会保存为受保护加密 MP3")
            } else {
                toastOnUi("生成有声书已关闭，只保留句子片段")
            }
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

    private fun upScriptBrainToolsVisible(enabled: Boolean) {
        binding.layoutScriptBrainTools.visibility = if (enabled) View.VISIBLE else View.GONE
        binding.layoutScriptBrainTools.requestLayout()
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

    private fun showScriptModelConfig() {
        val context = requireContext()
        val presets = scriptModelPresets()
        val selectedProfile = ScriptBrain.selectedModelProfile(context)
        val initialPreset = presets.firstOrNull { it.modelName == selectedProfile?.modelName }
            ?: presets.first()
        var currentProvider = selectedProfile?.provider
            ?.ifBlank { initialPreset.provider }
            ?: initialPreset.provider

        fun label(text: String): TextView {
            return TextView(context).apply {
                this.text = text
                textSize = 13f
                setTextColor(Color.rgb(90, 90, 90))
                setPadding(4.dpToPx(), 12.dpToPx(), 4.dpToPx(), 4.dpToPx())
            }
        }

        fun field(hintText: String, value: String, inputTypeValue: Int = InputType.TYPE_CLASS_TEXT): EditText {
            return EditText(context).apply {
                hint = hintText
                setText(value)
                isSingleLine = true
                inputType = inputTypeValue
                setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
            }
        }

        fun actionButton(textValue: String): Button {
            return Button(context).apply {
                text = textValue
                isAllCaps = false
                textSize = 14f
            }
        }

        val providerButton = actionButton("厂牌：$currentProvider")
        val modelButton = actionButton("推荐模型：${initialPreset.label}")
        val modelUrlInput = field(
            "OpenAI 兼容接口地址",
            selectedProfile?.modelUrl.orEmpty().ifBlank { initialPreset.modelUrl },
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        )
        val modelNameInput = field(
            "模型名",
            selectedProfile?.modelName.orEmpty().ifBlank { initialPreset.modelName }
        )
        val modelKeyInput = field(
            "API 密钥",
            selectedProfile?.modelKey.orEmpty(),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        val selectedSummary = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.rgb(100, 100, 100))
            setPadding(4.dpToPx(), 10.dpToPx(), 4.dpToPx(), 10.dpToPx())
        }

        fun updateSelectedSummary() {
            val selected = ScriptBrain.selectedModelProfiles(context)
            selectedSummary.text = if (selected.isEmpty()) {
                "分析规则模型：未选择。保存后可供朗读规则通过 payload.analysisModel / payload.analysisModels 使用。"
            } else {
                "分析规则模型：${selected.joinToString("、") { it.name }}\n" +
                        "当前主模型：${ScriptBrain.selectedModelProfileName(context).ifBlank { "未指定" }}"
            }
        }

        fun applyPreset(preset: ScriptModelPreset) {
            currentProvider = preset.provider
            providerButton.text = "厂牌：${preset.provider}"
            modelButton.text = "推荐模型：${preset.label}"
            modelUrlInput.setText(preset.modelUrl)
            modelNameInput.setText(preset.modelName)
        }

        providerButton.setOnClickListener {
            val providers = presets.map { it.provider }.distinct()
            context.selector("模型厂牌", providers) { _, index ->
                val provider = providers[index]
                val preset = presets.firstOrNull { it.provider == provider } ?: return@selector
                applyPreset(preset)
            }
        }
        modelButton.setOnClickListener {
            val providerPresets = presets.filter { it.provider == currentProvider }
            val labels = providerPresets.map { "${it.label}\n${it.modelName}" }
            context.selector("推荐模型", labels) { _, index ->
                providerPresets.getOrNull(index)?.let(::applyPreset)
            }
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(), 10.dpToPx(), 20.dpToPx(), 8.dpToPx())
            addView(selectedSummary)
            addView(providerButton)
            addView(modelButton)
            addView(label("接口地址"))
            addView(modelUrlInput)
            addView(label("模型名称"))
            addView(modelNameInput)
            addView(label("API 密钥"))
            addView(modelKeyInput)
        }

        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 14.dpToPx(), 0, 0)
        }
        buttonRow.addView(actionButton("保存到模型库").apply {
            layoutParams = LinearLayout.LayoutParams(0, 48.dpToPx(), 1f).apply {
                setMargins(0, 0, 4.dpToPx(), 0)
            }
            setOnClickListener {
                saveScriptModelProfile(
                    currentProvider,
                    modelUrlInput.text?.toString().orEmpty(),
                    modelNameInput.text?.toString().orEmpty(),
                    modelKeyInput.text?.toString().orEmpty(),
                    addToSelection = true,
                )?.let {
                    toastOnUi("已保存模型配置：${it.name}")
                    updateSelectedSummary()
                }
            }
        })
        buttonRow.addView(actionButton("单选/多选").apply {
            layoutParams = LinearLayout.LayoutParams(0, 48.dpToPx(), 1f).apply {
                setMargins(4.dpToPx(), 0, 4.dpToPx(), 0)
            }
            setOnClickListener {
                showScriptModelSelectionDialog {
                    updateSelectedSummary()
                    ScriptBrain.selectedModelProfile(context)?.let { profile ->
                        currentProvider = profile.provider.ifBlank { currentProvider }
                        providerButton.text = "厂牌：$currentProvider"
                        modelButton.text = "推荐模型：${profile.modelName}"
                        modelUrlInput.setText(profile.modelUrl)
                        modelNameInput.setText(profile.modelName)
                        modelKeyInput.setText(profile.modelKey)
                    }
                }
            }
        })
        buttonRow.addView(actionButton("保存并测试").apply {
            layoutParams = LinearLayout.LayoutParams(0, 48.dpToPx(), 1f).apply {
                setMargins(4.dpToPx(), 0, 0, 0)
            }
            setOnClickListener {
                val profile = saveScriptModelProfile(
                    currentProvider,
                    modelUrlInput.text?.toString().orEmpty(),
                    modelNameInput.text?.toString().orEmpty(),
                    modelKeyInput.text?.toString().orEmpty(),
                    addToSelection = true,
                ) ?: return@setOnClickListener
                updateSelectedSummary()
                toastOnUi("正在测试：${profile.modelName}")
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { ScriptBrain.testModel(profile) }
                    result.onSuccess {
                        toastOnUi("测试成功")
                    }.onFailure {
                        toastOnUi("测试失败：${it.localizedMessage ?: it.javaClass.simpleName}")
                    }
                }
            }
        })
        container.addView(buttonRow)
        updateSelectedSummary()

        AlertDialog.Builder(context)
            .setTitle("分析模型配置")
            .setView(ScrollView(context).apply { addView(container) })
            .setPositiveButton("保存并使用") { _, _ ->
                saveScriptModelProfile(
                    currentProvider,
                    modelUrlInput.text?.toString().orEmpty(),
                    modelNameInput.text?.toString().orEmpty(),
                    modelKeyInput.text?.toString().orEmpty(),
                    addToSelection = true,
                    singleSelection = true,
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveScriptModelProfile(
        provider: String,
        modelUrl: String,
        modelName: String,
        modelKey: String,
        addToSelection: Boolean,
        singleSelection: Boolean = false,
    ): ScriptBrain.AnalysisModelProfile? {
        val url = modelUrl.trim()
        val model = modelName.trim()
        if (url.isBlank() || model.isBlank()) {
            toastOnUi("请先选择厂牌/模型，或填写接口地址和模型名")
            return null
        }
        val normalizedProvider = provider.trim().ifBlank { "自定义" }
        val profileName = "$normalizedProvider · $model"
        val profile = ScriptBrain.AnalysisModelProfile(
            name = profileName,
            provider = normalizedProvider,
            modelUrl = url,
            modelName = model,
            modelKey = modelKey.trim(),
        )
        val profiles = ScriptBrain.modelProfiles(requireContext()).toMutableList()
        val index = profiles.indexOfFirst { it.name == profileName }
        if (index >= 0) profiles[index] = profile else profiles.add(profile)
        ScriptBrain.saveModelProfiles(requireContext(), profiles)
        ScriptBrain.saveSelectedModelProfileName(requireContext(), profile.name)
        if (addToSelection) {
            val names = if (singleSelection) {
                setOf(profile.name)
            } else {
                ScriptBrain.selectedModelProfileNames(requireContext()) + profile.name
            }
            ScriptBrain.saveSelectedModelProfileNames(requireContext(), names)
        }
        return profile
    }

    private fun showScriptModelSelectionDialog(onChanged: () -> Unit) {
        val profiles = ScriptBrain.modelProfiles(requireContext())
        if (profiles.isEmpty()) {
            toastOnUi("还没有模型配置，请先保存一个模型")
            return
        }
        val items = listOf("单选模型", "多选模型")
        requireContext().selector("模型配置库", items) { _, index ->
            when (index) {
                0 -> showScriptSingleModelPicker(profiles, onChanged)
                1 -> showScriptMultiModelPicker(profiles, onChanged)
            }
        }
    }

    private fun showScriptSingleModelPicker(
        profiles: List<ScriptBrain.AnalysisModelProfile>,
        onChanged: () -> Unit,
    ) {
        val selectedName = ScriptBrain.selectedModelProfileName(requireContext())
        val labels = profiles.map {
            buildString {
                if (it.name == selectedName) append("✓ ")
                append(it.name)
                append("\n")
                append(it.modelUrl)
            }
        }
        requireContext().selector("单选分析模型", labels) { _, index ->
            val profile = profiles.getOrNull(index) ?: return@selector
            ScriptBrain.selectModelProfile(requireContext(), profile)
            toastOnUi("已选择：${profile.name}")
            onChanged()
        }
    }

    private fun showScriptMultiModelPicker(
        profiles: List<ScriptBrain.AnalysisModelProfile>,
        onChanged: () -> Unit,
    ) {
        val selectedNames = ScriptBrain.selectedModelProfileNames(requireContext()).toMutableSet()
        val selectedName = ScriptBrain.selectedModelProfileName(requireContext())
        if (selectedNames.isEmpty() && selectedName.isNotBlank()) {
            selectedNames.add(selectedName)
        }
        val labels = profiles.map { "${it.name}  ·  ${it.modelName}" }.toTypedArray()
        val checked = profiles.map { it.name in selectedNames }.toBooleanArray()
        AlertDialog.Builder(requireContext())
            .setTitle("多选分析模型")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val name = profiles.getOrNull(which)?.name ?: return@setMultiChoiceItems
                if (isChecked) selectedNames.add(name) else selectedNames.remove(name)
            }
            .setPositiveButton("保存") { _, _ ->
                ScriptBrain.saveSelectedModelProfileNames(requireContext(), selectedNames)
                profiles.firstOrNull { it.name in selectedNames }?.let {
                    ScriptBrain.saveSelectedModelProfileName(requireContext(), it.name)
                }
                toastOnUi(
                    if (selectedNames.isEmpty()) "已清空分析模型选择"
                    else "已选择 ${selectedNames.size} 个分析模型"
                )
                onChanged()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun scriptModelPresets(): List<ScriptModelPreset> = listOf(
        ScriptModelPreset("OpenAI", "GPT-4.1 mini（结构化稳定）", "https://api.openai.com/v1", "gpt-4.1-mini"),
        ScriptModelPreset("OpenAI", "GPT-4o mini（快，成本低）", "https://api.openai.com/v1", "gpt-4o-mini"),
        ScriptModelPreset("DeepSeek", "DeepSeek Chat（通用）", "https://api.deepseek.com", "deepseek-chat"),
        ScriptModelPreset("DeepSeek", "DeepSeek Reasoner（推理更强）", "https://api.deepseek.com", "deepseek-reasoner"),
        ScriptModelPreset("智谱", "GLM-4.5-Air（推荐）", "https://open.bigmodel.cn/api/paas/v4", "glm-4.5-air"),
        ScriptModelPreset("智谱", "GLM-4.5-Flash（快）", "https://open.bigmodel.cn/api/paas/v4", "glm-4.5-flash"),
        ScriptModelPreset("通义千问", "Qwen Plus（结构化稳定）", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        ScriptModelPreset("通义千问", "Qwen Flash（快）", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-flash"),
        ScriptModelPreset("Kimi", "Kimi Latest（长文本友好）", "https://api.moonshot.cn/v1", "kimi-latest"),
        ScriptModelPreset("豆包", "Doubao Seed 1.6 Flash（快）", "https://ark.cn-beijing.volces.com/api/v3", "doubao-seed-1-6-flash"),
        ScriptModelPreset("百度千帆", "ERNIE 4.5 Turbo（通用）", "https://qianfan.baidubce.com/v2", "ernie-4.5-turbo-128k"),
        ScriptModelPreset("腾讯混元", "Hunyuan Turbo（通用）", "https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-turbo-latest"),
        ScriptModelPreset("MiniMax", "MiniMax Text 01（长文本）", "https://api.minimax.chat/v1", "MiniMax-Text-01"),
        ScriptModelPreset("Gemini", "Gemini 2.5 Flash（快）", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
        ScriptModelPreset("OpenRouter", "OpenRouter Auto（统一网关）", "https://openrouter.ai/api/v1", "openrouter/auto"),
        ScriptModelPreset("自定义", "手动填写 OpenAI 兼容模型", "", ""),
    )

    private fun showScriptCharacters() {
        val snapshot = ScriptBrain.roleManagerSnapshot(requireContext())
        AlertDialog.Builder(requireContext())
            .setTitle("角色管理")
            .setView(roleManagerPluginView(snapshot))
            .setNegativeButton("运行当前章") { _, _ -> runScriptRuleForCurrentChapter() }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun roleManagerPluginView(snapshot: ScriptBrain.RoleManagerSnapshot): View {
        val context = requireContext()
        var currentSnapshot = snapshot
        val markedIndices = linkedSetOf<Int>()
        var selectedIndex = -1
        var filterKeyword = ""
        lateinit var openRoleOperations: (Int) -> Unit

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 10.dpToPx())
        }
        val listContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val roleLabel = TextView(context).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(35, 35, 35))
            includeFontPadding = false
        }

        fun addText(text: String, size: Float = 14f, bold: Boolean = false, color: Int = Color.rgb(45, 45, 45)) {
            root.addView(TextView(context).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                if (bold) typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setPadding(2.dpToPx(), 4.dpToPx(), 2.dpToPx(), 4.dpToPx())
            })
        }

        fun button(label: String, color: Int, action: () -> Unit): Button {
            return Button(context).apply {
                text = label
                textSize = 11f
                isAllCaps = false
                minHeight = 0
                minimumHeight = 0
                minWidth = 0
                minimumWidth = 0
                includeFontPadding = false
                setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = 4.dpToPx().toFloat()
                    setColor(color)
                }
                setOnClickListener { action() }
            }
        }

        fun runRoleAction(block: () -> ScriptBrain.RoleManagerActionResult) {
            runCatching { block() }
                .onSuccess { result ->
                    toastOnUi(result.message)
                    currentSnapshot = result.snapshot
                    markedIndices.removeAll { it !in currentSnapshot.characters.indices }
                    if (selectedIndex !in currentSnapshot.characters.indices) {
                        selectedIndex = markedIndices.firstOrNull() ?: -1
                    }
                    renderRoleList(currentSnapshot, listContainer, roleLabel, markedIndices, selectedIndex, filterKeyword) {
                        selectedIndex = it
                        openRoleOperations(it)
                    }
                }
                .onFailure {
                    toastOnUi(it.localizedMessage ?: it.javaClass.simpleName)
                }
        }

        fun addButtonRow(vararg buttons: Button) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 3.dpToPx(), 0, 3.dpToPx())
            }
            buttons.forEach { item ->
                row.addView(item.apply {
                    layoutParams = LinearLayout.LayoutParams(0, 34.dpToPx(), 1f).apply {
                        setMargins(2.dpToPx(), 0, 2.dpToPx(), 0)
                    }
                })
            }
            root.addView(row)
        }

        fun selectedMarks(): Set<Int> {
            return markedIndices.filter { it in currentSnapshot.characters.indices }.toSet()
        }

        fun chooseVoice(title: String, onPick: (String) -> Unit) {
            val voices = ScriptBrain.roleManagerVoiceOptions(context)
            if (voices.isEmpty()) {
                toastOnUi("暂无可用发音人")
                return
            }
            context.selector(title, voices) { _, index ->
                voices.getOrNull(index)?.let(onPick)
            }
        }

        fun chooseTag(title: String, onPick: (String) -> Unit) {
            val tags = ScriptBrain.roleManagerTagOptions()
            context.selector(title, tags) { _, index ->
                tags.getOrNull(index)?.let(onPick)
            }
        }

        fun showAddRoleDialog() {
            val nameEdit = EditText(context).apply {
                hint = "角色名"
                setSingleLine(true)
            }
            val aliasEdit = EditText(context).apply {
                hint = "别名，用 | 分隔，可不填"
                setSingleLine(true)
            }
            val voiceEdit = EditText(context).apply {
                hint = "发音人/标签，例如 男/男青年01，可不填"
                setSingleLine(true)
            }
            AlertDialog.Builder(context)
                .setTitle("新增角色")
                .setView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(18.dpToPx(), 8.dpToPx(), 18.dpToPx(), 0)
                    addView(nameEdit)
                    addView(aliasEdit)
                    addView(voiceEdit)
                })
                .setPositiveButton("新增") { _, _ ->
                    runRoleAction {
                        ScriptBrain.addRoleManagerCharacter(
                            context,
                            nameEdit.text?.toString().orEmpty(),
                            aliasEdit.text?.toString().orEmpty(),
                            voiceEdit.text?.toString().orEmpty(),
                        )
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        fun showEditRoleDialog(index: Int) {
            val character = currentSnapshot.characters.getOrNull(index) ?: return
            val nameEdit = EditText(context).apply {
                setText(character.name)
                hint = "角色名"
                setSingleLine(true)
            }
            val aliasEdit = EditText(context).apply {
                setText(character.aliases)
                hint = "别名，用 | 分隔"
                setSingleLine(true)
            }
            val voiceEdit = EditText(context).apply {
                setText(character.voiceTag)
                hint = "发音人/标签"
                setSingleLine(true)
            }
            AlertDialog.Builder(context)
                .setTitle("编辑角色")
                .setView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(18.dpToPx(), 8.dpToPx(), 18.dpToPx(), 0)
                    addView(nameEdit)
                    addView(aliasEdit)
                    addView(voiceEdit)
                })
                .setPositiveButton("保存") { _, _ ->
                    runRoleAction {
                        ScriptBrain.updateRoleManagerCharacter(
                            context,
                            index,
                            nameEdit.text?.toString().orEmpty(),
                            aliasEdit.text?.toString().orEmpty(),
                            voiceEdit.text?.toString().orEmpty(),
                        )
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        fun showSearchDialog() {
            val editText = EditText(context).apply {
                setText(filterKeyword)
                hint = "搜索角色名或别名，留空显示全部"
                setSingleLine(true)
            }
            AlertDialog.Builder(context)
                .setTitle("搜索角色")
                .setView(editText)
                .setPositiveButton("搜索") { _, _ ->
                    filterKeyword = editText.text?.toString().orEmpty().trim()
                    renderRoleList(currentSnapshot, listContainer, roleLabel, markedIndices, selectedIndex, filterKeyword) {
                        selectedIndex = it
                        openRoleOperations(it)
                    }
                }
                .setNegativeButton("清空") { _, _ ->
                    filterKeyword = ""
                    renderRoleList(currentSnapshot, listContainer, roleLabel, markedIndices, selectedIndex, filterKeyword) {
                        selectedIndex = it
                        openRoleOperations(it)
                    }
                }
                .show()
        }

        fun ensureTargetIndex(): Int {
            if (selectedIndex in currentSnapshot.characters.indices) return selectedIndex
            selectedIndex = markedIndices.firstOrNull() ?: -1
            return selectedIndex
        }

        fun showRoleOperationDialog(index: Int) {
            val character = currentSnapshot.characters.getOrNull(index) ?: return
            val items = listOf("编辑角色", "更换发音人", "固定标签池", "释放角色", "删除角色")
            context.selector("${character.name} 操作", items) { _, which ->
                when (which) {
                    0 -> showEditRoleDialog(index)
                    1 -> chooseVoice("更换发音人") { voice ->
                        runRoleAction { ScriptBrain.fixRoleManagerVoice(context, setOf(index), voice) }
                    }
                    2 -> chooseTag("固定标签池") { tag ->
                        runRoleAction { ScriptBrain.fixRoleManagerTag(context, index, tag) }
                    }
                    3 -> runRoleAction { ScriptBrain.releaseRoleManagerCharacters(context, setOf(index)) }
                    4 -> runRoleAction { ScriptBrain.deleteRoleManagerCharacters(context, setOf(index)) }
                }
            }
        }

        openRoleOperations = { index -> showRoleOperationDialog(index) }

        addText("内置模块：${snapshot.pluginName}  v${snapshot.pluginVersion}", 12f, color = Color.rgb(110, 110, 110))
        addButtonRow(
            button("新增角色", Color.rgb(76, 175, 80)) { showAddRoleDialog() },
            button("搜索", Color.rgb(33, 150, 243)) { showSearchDialog() },
            button("刷新", Color.rgb(96, 125, 139)) {
                currentSnapshot = ScriptBrain.roleManagerSnapshot(context)
                markedIndices.clear()
                selectedIndex = -1
                renderRoleList(currentSnapshot, listContainer, roleLabel, markedIndices, selectedIndex, filterKeyword) {
                    selectedIndex = it
                    showRoleOperationDialog(it)
                }
            },
            button("管理书籍", Color.rgb(255, 152, 0)) {
                showTextDialog(
                    "角色文件",
                    buildString {
                        appendLine("当前书：${currentSnapshot.bookName}")
                        appendLine("目录：${currentSnapshot.storagePath}")
                        appendLine()
                        appendLine("已同步文件：")
                        currentSnapshot.files.forEach { appendLine(it) }
                    }
                )
            }
        )
        addButtonRow(
            button("执行合并", Color.rgb(76, 175, 80)) {
                val target = ensureTargetIndex()
                if (target >= 0) markedIndices.add(target)
                runRoleAction { ScriptBrain.mergeRoleManagerCharacters(context, target, selectedMarks()) }
            },
            button("更换发音人", Color.rgb(156, 39, 176)) {
                chooseVoice("更换发音人") { voice ->
                    runRoleAction { ScriptBrain.fixRoleManagerVoice(context, selectedMarks(), voice) }
                }
            },
            button("释放角色", Color.rgb(33, 150, 243)) {
                runRoleAction { ScriptBrain.releaseRoleManagerCharacters(context, selectedMarks()) }
            },
            button("删除角色", Color.rgb(244, 67, 54)) {
                runRoleAction { ScriptBrain.deleteRoleManagerCharacters(context, selectedMarks()) }
            }
        )
        addButtonRow(
            button("固定当前发音人", Color.rgb(123, 31, 162)) {
                runRoleAction { ScriptBrain.fixRoleManagerCurrentVoice(context, selectedMarks()) }
            },
            button("固定标签池", Color.rgb(103, 58, 183)) {
                val target = ensureTargetIndex()
                chooseTag("固定标签池") { tag ->
                    runRoleAction { ScriptBrain.fixRoleManagerTag(context, target, tag) }
                }
            },
            button("备份", Color.rgb(121, 85, 72)) {
                runRoleAction { ScriptBrain.backupRoleManagerCharacters(context) }
            },
            button("恢复", Color.rgb(0, 150, 136)) {
                runRoleAction { ScriptBrain.restoreRoleManagerCharacters(context) }
            }
        )

        val bookRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 5.dpToPx(), 0, 6.dpToPx())
        }
        bookRow.addView(TextView(context).apply {
            text = snapshot.bookName
            textSize = 14f
            setTextColor(Color.rgb(30, 30, 30))
            includeFontPadding = false
            setPadding(10.dpToPx(), 8.dpToPx(), 10.dpToPx(), 8.dpToPx())
            background = GradientDrawable().apply {
                setColor(Color.rgb(250, 250, 250))
                setStroke(1.dpToPx(), Color.rgb(80, 80, 80))
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        bookRow.addView(button("全选", Color.rgb(96, 125, 139)) {
            if (markedIndices.size >= currentSnapshot.characters.size && currentSnapshot.characters.isNotEmpty()) {
                markedIndices.clear()
            } else {
                markedIndices.clear()
                markedIndices.addAll(currentSnapshot.characters.indices)
            }
            selectedIndex = markedIndices.firstOrNull() ?: -1
            renderRoleList(currentSnapshot, listContainer, roleLabel, markedIndices, selectedIndex, filterKeyword) {
                selectedIndex = it
                showRoleOperationDialog(it)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(70.dpToPx(), 34.dpToPx()).apply {
                setMargins(4.dpToPx(), 0, 0, 0)
            }
        })
        root.addView(bookRow)

        root.addView(roleLabel.apply {
            setPadding(2.dpToPx(), 8.dpToPx(), 2.dpToPx(), 6.dpToPx())
        })
        root.addView(ScrollView(context).apply {
            isFillViewport = false
            addView(listContainer)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                360.dpToPx()
            )
        })
        renderRoleList(currentSnapshot, listContainer, roleLabel, markedIndices, selectedIndex, filterKeyword) {
            selectedIndex = it
            showRoleOperationDialog(it)
        }
        return root
    }

    private fun renderRoleList(
        snapshot: ScriptBrain.RoleManagerSnapshot,
        container: LinearLayout,
        label: TextView,
        markedIndices: MutableSet<Int>,
        selectedIndex: Int,
        filterKeyword: String,
        onLongClick: (Int) -> Unit,
    ) {
        val context = requireContext()
        container.removeAllViews()
        val visibleCharacters = snapshot.characters.withIndex().filter { (_, character) ->
            filterKeyword.isBlank()
                    || character.name.contains(filterKeyword, ignoreCase = true)
                    || character.aliases.contains(filterKeyword, ignoreCase = true)
                    || character.voiceTag.contains(filterKeyword, ignoreCase = true)
        }
        label.text = buildString {
            append("角色列表（已标记 ${markedIndices.size} / ${snapshot.characters.size}）")
            if (filterKeyword.isNotBlank()) append("  搜索：$filterKeyword")
        }
        if (snapshot.characters.isEmpty()) {
            container.addView(TextView(context).apply {
                text = "当前书还没有角色记录。先点“分析规则”运行当前章，分析结果会自动写入这里。"
                textSize = 14f
                setTextColor(Color.rgb(70, 70, 70))
                setPadding(2.dpToPx(), 8.dpToPx(), 2.dpToPx(), 8.dpToPx())
            })
            return
        }
        if (visibleCharacters.isEmpty()) {
            container.addView(TextView(context).apply {
                text = "没有匹配角色。"
                textSize = 14f
                setTextColor(Color.rgb(70, 70, 70))
                setPadding(2.dpToPx(), 8.dpToPx(), 2.dpToPx(), 8.dpToPx())
            })
            return
        }
        visibleCharacters.forEach { indexed ->
            val index = indexed.index
            val character = indexed.value
            val marked = index in markedIndices
            val selected = index == selectedIndex
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(8.dpToPx(), 7.dpToPx(), 6.dpToPx(), 7.dpToPx())
                background = GradientDrawable().apply {
                    cornerRadius = 4.dpToPx().toFloat()
                    setColor(
                        when {
                            selected -> Color.rgb(255, 249, 190)
                            marked -> Color.rgb(245, 248, 255)
                            else -> Color.TRANSPARENT
                        }
                    )
                    if (!selected) setStroke(1.dpToPx(), Color.rgb(232, 232, 232))
                }
            }
            val checkBox = CheckBox(context).apply {
                isChecked = marked
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 4.dpToPx(), 0)
            }
            val textView = TextView(context).apply {
                text = buildString {
                    append(character.name)
                    append("  【")
                    append(character.voiceTag)
                    append("-")
                    append(character.gender)
                    append("-")
                    append(character.ageType)
                    append("】")
                    if (character.aliases.isNotBlank() && character.aliases != character.name) {
                        append("\n别名：")
                        append(character.aliases)
                    }
                }
                textSize = 13f
                setTextColor(if (selected) Color.rgb(210, 90, 30) else Color.rgb(35, 35, 35))
                if (selected) typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            fun toggleMark() {
                if (index in markedIndices) {
                    markedIndices.remove(index)
                } else {
                    markedIndices.add(index)
                }
                renderRoleList(snapshot, container, label, markedIndices, index, filterKeyword, onLongClick)
            }
            checkBox.setOnClickListener { toggleMark() }
            row.setOnClickListener { toggleMark() }
            row.setOnLongClickListener {
                markedIndices.add(index)
                renderRoleList(snapshot, container, label, markedIndices, index, filterKeyword, onLongClick)
                onLongClick(index)
                true
            }
            row.addView(checkBox)
            row.addView(textView)
            container.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 4.dpToPx()
            })
        }
    }

    private fun scriptCharacterListView(analysis: ScriptBrain.Analysis): ScrollView {
        val context = requireContext()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx(), 12.dpToPx(), 20.dpToPx(), 12.dpToPx())
        }

        fun addText(text: String, size: Float = 15f, bold: Boolean = false, color: Int = Color.rgb(45, 45, 45)) {
            container.addView(TextView(context).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                if (bold) typeface = Typeface.DEFAULT_BOLD
                setPadding(0, 4.dpToPx(), 0, 4.dpToPx())
            })
        }

        val info = ScriptBrain.importedRuleInfo(context)
        addText("${analysis.chapterTitle}：角色列表（已标记 ${analysis.characters.size}）", 16f, true)
        addText("来源：${analysis.source}，内置分析模式：${if (AppConfig.scriptBrainEnabled) "开启" else "关闭"}", 13f, color = Color.rgb(100, 100, 100))
        if (info != null) {
            addText("规则：${info.name} / ${if (info.isJson) "完整 JSON" else "JS"}", 13f, color = Color.rgb(100, 100, 100))
        }
        if (analysis.error.isNotBlank()) {
            addText("提示：${analysis.error}", 13f, color = Color.rgb(180, 80, 40))
        }

        if (analysis.characters.isEmpty()) {
            addText("当前章暂未识别到明确角色。可以先在“分析规则”导入完整朗读规则 JSON，再点“运行当前章”。", 15f)
        } else {
            analysis.characters.forEachIndexed { index, character ->
                val selected = index == 0
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(14.dpToPx(), 10.dpToPx(), 10.dpToPx(), 10.dpToPx())
                    background = GradientDrawable().apply {
                        cornerRadius = 4.dpToPx().toFloat()
                        setColor(if (selected) Color.rgb(255, 250, 190) else Color.TRANSPARENT)
                        if (!selected) setStroke(1.dpToPx(), Color.rgb(230, 230, 230))
                    }
                }
                val text = TextView(context).apply {
                    this.text = "${character.name}    【${character.voiceTag}-${character.gender}-${character.ageType}】"
                    textSize = 16f
                    setTextColor(if (selected) Color.rgb(210, 90, 30) else Color.rgb(40, 40, 40))
                    if (selected) typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, 0, 8.dpToPx(), 0)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(text)
                row.addView(RadioButton(context).apply {
                    isChecked = selected
                    isClickable = false
                    isFocusable = false
                })
                container.addView(row)
            }
        }

        addText("分析完成后会写入内置分析目录，并更新这个角色列表。", 13f, color = Color.rgb(110, 110, 110))
        return ScrollView(context).apply { addView(container) }
    }

    private fun showScriptPreview() {
        val analysis = ScriptBrain.analyzeCurrentChapter(requireContext())
        val body = buildString {
            appendLine("${analysis.chapterTitle}")
            appendLine("台词行：${analysis.lines.size}，角色：${analysis.characters.size}")
            appendLine("来源：${analysis.source}")
            appendLine("内置分析模式：${if (AppConfig.scriptBrainEnabled) "开启" else "关闭，仅手动预览"}")
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
        val context = requireContext()
        val modules = ScriptBrain.analysisModules(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(6.dpToPx(), 8.dpToPx(), 6.dpToPx(), 8.dpToPx())
        }
        var dialog: AlertDialog? = null

        fun addText(text: String, size: Float = 14f, bold: Boolean = false) {
            container.addView(TextView(context).apply {
                this.text = text
                textSize = size
                if (bold) typeface = Typeface.DEFAULT_BOLD
                setPadding(4.dpToPx(), 5.dpToPx(), 4.dpToPx(), 5.dpToPx())
            })
        }

        fun Button.compact(label: String, onClick: () -> Unit): Button {
            text = label
            textSize = 12f
            minHeight = 0
            minWidth = 0
            setPadding(4.dpToPx(), 0, 4.dpToPx(), 0)
            setOnClickListener { onClick() }
            return this
        }

        fun addButtonRow(vararg buttons: Button) {
            container.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4.dpToPx(), 0, 4.dpToPx())
                buttons.forEach { button ->
                    addView(button, LinearLayout.LayoutParams(0, 38.dpToPx(), 1f).apply {
                        marginEnd = 4.dpToPx()
                    })
                }
            })
        }

        addText("当前规则：默认多角色分析规则", 15f, true)
        addButtonRow(
            Button(context).compact("运行当前章") {
                dialog?.dismiss()
                runScriptRuleForCurrentChapter()
            },
            Button(context).compact("导入规则") {
                dialog?.dismiss()
                importSpeechRuleJson.launch(arrayOf("application/json", "text/*", "*/*"))
            },
            Button(context).compact("导出规则") {
                showTextDialog("导出分析规则 JSON", ScriptBrain.exportAnalysisRulePackage(context))
            },
        )
        addButtonRow(
            Button(context).compact("添加模块") {
                dialog?.dismiss()
                showEditAnalysisModule(null)
            },
            Button(context).compact("添加正则") {
                dialog?.dismiss()
                val name = "本地正则清理"
                showEditAnalysisModule(
                    ScriptBrain.AnalysisModule(
                        id = "regex_${System.currentTimeMillis()}",
                        name = name,
                        type = "regex",
                        enabled = true,
                        code = ScriptBrain.defaultModuleCode("ad_clean_regex", name),
                    )
                )
            },
            Button(context).compact("规则库") {
                showScriptRuleLibrary()
            },
        )
        addButtonRow(
            Button(context).compact("说明") {
                showScriptRuleHelp()
            },
        )

        addText("模块列表：", 15f, true)
        modules.forEachIndexed { index, module ->
            val moduleRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 5.dpToPx(), 0, 2.dpToPx())
            }
            moduleRow.addView(CheckBox(context).apply {
                text = "开"
                textSize = 12f
                isChecked = module.enabled
                includeFontPadding = false
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 2.dpToPx(), 0)
                setOnClickListener {
                    ScriptBrain.setAnalysisModuleEnabled(context, module.id, isChecked)
                    dialog?.dismiss()
                    showScriptRules()
                }
            }, LinearLayout.LayoutParams(54.dpToPx(), LinearLayout.LayoutParams.WRAP_CONTENT))
            moduleRow.addView(CheckBox(context).apply {
                text = "AI"
                textSize = 12f
                isChecked = module.aiEnabled
                includeFontPadding = false
                minWidth = 0
                minimumWidth = 0
                setPadding(0, 0, 2.dpToPx(), 0)
                setOnClickListener {
                    ScriptBrain.upsertAnalysisModule(context, module.copy(aiEnabled = isChecked))
                    dialog?.dismiss()
                    showScriptRules()
                }
            }, LinearLayout.LayoutParams(56.dpToPx(), LinearLayout.LayoutParams.WRAP_CONTENT))
            moduleRow.addView(TextView(context).apply {
                text = "${(index + 1).toString().padStart(2, '0')} ${module.name}"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
                setPadding(4.dpToPx(), 7.dpToPx(), 4.dpToPx(), 4.dpToPx())
                setTextColor(if (module.enabled) Color.rgb(35, 35, 35) else Color.rgb(130, 130, 130))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            container.addView(moduleRow)
            addButtonRow(
                Button(context).compact("编辑") {
                    dialog?.dismiss()
                    showEditAnalysisModule(module)
                },
                Button(context).compact("测试") {
                    dialog?.dismiss()
                    testAnalysisModule(module)
                },
                Button(context).compact("上移") {
                    ScriptBrain.moveAnalysisModule(context, module.id, -1)
                    dialog?.dismiss()
                    showScriptRules()
                },
                Button(context).compact("下移") {
                    ScriptBrain.moveAnalysisModule(context, module.id, 1)
                    dialog?.dismiss()
                    showScriptRules()
                },
                Button(context).compact("删除") {
                    ScriptBrain.deleteAnalysisModule(context, module.id)
                    dialog?.dismiss()
                    showScriptRules()
                },
            )
        }

        if (ScriptBrain.hasImportedRule(context)) {
            addText("旧 TTS 规则兼容：旧规则可能和阅读端分析不完全匹配，建议只用于对照测试。", 13f)
            addButtonRow(
                Button(context).compact("运行旧规则") {
                    dialog?.dismiss()
                    runImportedSpeechRuleForCurrentChapter()
                },
                Button(context).compact("查看旧规则") {
                    showImportedScriptRule()
                },
                Button(context).compact("清空旧规则") {
                    clearImportedScriptRule()
                },
            )
        }

        dialog = AlertDialog.Builder(context)
            .setTitle("分析中心")
            .setView(ScrollView(context).apply { addView(container) })
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog?.show()
    }

    private fun showEditAnalysisModule(module: ScriptBrain.AnalysisModule?) {
        val context = requireContext()
        val defaultId = module?.id ?: "module_${System.currentTimeMillis()}"
        val defaultName = module?.name.orEmpty()
        val enabledCheck = CheckBox(context).apply {
            text = "启用这个模块"
            isChecked = module?.enabled ?: true
            textSize = 14f
        }
        val aiCheck = CheckBox(context).apply {
            text = "允许这个模块调用 AI"
            isChecked = module?.aiEnabled ?: false
            textSize = 14f
        }
        val nameEdit = EditText(context).apply {
            setText(defaultName)
            hint = "模块名称，例如：说话人归属"
            setSingleLine(true)
        }
        val helpText = TextView(context).apply {
            text = """
                JS 入口固定为 function run(ctx)。
                ctx 里有整章正文、台词本 lines、角色表 characters、日志 logs。
                ctx.aiEnabled 表示这个模块是否允许调用 AI；需要联网模型时先判断它。
                保存后可点“测试当前章”，结果会写入台词本和角色表。
            """.trimIndent()
            textSize = 12f
            setTextColor(Color.rgb(100, 100, 100))
            setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
        }
        val codeEdit = EditText(context).apply {
            setText(module?.code.orEmpty().ifBlank {
                ScriptBrain.defaultModuleCode(defaultId, defaultName.ifBlank { "新建模块" })
            })
            hint = "在这里编辑 JS 模块代码"
            minLines = 14
            maxLines = 24
            typeface = Typeface.MONOSPACE
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setHorizontallyScrolling(false)
            setPadding(10.dpToPx(), 10.dpToPx(), 10.dpToPx(), 10.dpToPx())
            background = GradientDrawable().apply {
                cornerRadius = 8.dpToPx().toFloat()
                setColor(Color.rgb(248, 248, 248))
                setStroke(1.dpToPx(), Color.rgb(210, 210, 210))
            }
        }
        val templateButton = Button(context).apply {
            text = "填入默认 JS 模板"
            setOnClickListener {
                val name = nameEdit.text?.toString().orEmpty().trim().ifBlank { "新建模块" }
                codeEdit.setText(ScriptBrain.defaultModuleCode(defaultId, name))
            }
        }
        val testButton = Button(context).apply {
            text = "保存并测试当前章"
            setOnClickListener {
                val saved = saveAnalysisModuleFromEditor(
                    module = module,
                    id = defaultId,
                    enabled = enabledCheck.isChecked,
                    aiEnabled = aiCheck.isChecked,
                    name = nameEdit.text?.toString().orEmpty(),
                    code = codeEdit.text?.toString().orEmpty(),
                    showToast = false,
                ) ?: return@setOnClickListener
                toastOnUi("模块已保存，开始测试")
                testAnalysisModule(saved)
            }
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 10.dpToPx(), 16.dpToPx(), 10.dpToPx())
            addView(enabledCheck)
            addView(aiCheck)
            addView(nameEdit)
            addView(helpText)
            addView(templateButton)
            addView(codeEdit)
            addView(testButton)
        }
        AlertDialog.Builder(context)
            .setTitle(if (module == null) "JS 模块编辑器" else "JS 模块：${module.name}")
            .setView(ScrollView(context).apply { addView(view) })
            .setPositiveButton("保存") { _, _ ->
                saveAnalysisModuleFromEditor(
                    module = module,
                    id = defaultId,
                    enabled = enabledCheck.isChecked,
                    aiEnabled = aiCheck.isChecked,
                    name = nameEdit.text?.toString().orEmpty(),
                    code = codeEdit.text?.toString().orEmpty(),
                    showToast = true,
                )
                showScriptRules()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveAnalysisModuleFromEditor(
        module: ScriptBrain.AnalysisModule?,
        id: String,
        enabled: Boolean,
        aiEnabled: Boolean,
        name: String,
        code: String,
        showToast: Boolean,
    ): ScriptBrain.AnalysisModule? {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            toastOnUi("模块名称不能为空")
            return null
        }
        val saved = ScriptBrain.AnalysisModule(
            id = module?.id ?: id,
            name = trimmedName,
            type = module?.type ?: "js",
            enabled = enabled,
            aiEnabled = aiEnabled,
            code = code.ifBlank { ScriptBrain.defaultModuleCode(module?.id ?: id, trimmedName) },
        )
        ScriptBrain.upsertAnalysisModule(requireContext(), saved)
        if (showToast) toastOnUi("模块已保存")
        return saved
    }

    private fun testAnalysisModule(module: ScriptBrain.AnalysisModule) {
        val appContext = requireContext().applicationContext
        toastOnUi("正在测试模块：${module.name}")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ScriptBrain.runAnalysisModulesForCurrentChapter(
                        context = appContext,
                        stopAtModuleId = module.id,
                    )
                }
            }
            if (!isAdded) return@launch
            result.onSuccess { runResult ->
                showTextDialog(
                    "模块测试：${module.name}",
                    buildString {
                        appendLine("已运行到当前模块为止。")
                        appendLine("台词：${runResult.analysis.lines.size} 行")
                        appendLine("角色：${runResult.analysis.characters.size} 个")
                        appendLine()
                        appendLine("结果已写入角色表和台词本，方便直接对照。")
                        appendModuleReports(runResult.moduleReports)
                        appendLine()
                        appendLine("日志：")
                        runResult.logs.forEach { appendLine(it) }
                    }
                )
            }.onFailure {
                showTextDialog("模块测试失败：${module.name}", it.localizedMessage ?: it.stackTraceToString())
            }
        }
    }

    private fun showScriptRuleLibrary() {
        val rules = ScriptBrain.savedRules(requireContext())
        if (rules.isEmpty()) {
            toastOnUi("规则库还没有规则，请先导入完整朗读规则 JSON")
            return
        }
        val labels = rules.map { rule ->
            buildString {
                if (rule.isActive) append("✓ ")
                append(rule.name)
                append("  v")
                append(rule.version)
                if (rule.author.isNotBlank() && rule.author != "未知") {
                    append(" / ")
                    append(rule.author)
                }
            }
        }
        context?.selector("朗读规则库", labels) { _, index ->
            val rule = rules.getOrNull(index) ?: return@selector
            runCatching {
                ScriptBrain.useSavedRule(requireContext(), rule.id)
            }.onSuccess {
                toastOnUi("已启用规则：${rule.name}")
            }.onFailure {
                toastOnUi("启用失败：${it.localizedMessage ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun showPasteScriptRuleDialog() {
        val editText = EditText(requireContext()).apply {
            setText(ScriptBrain.loadImportedRuleRaw(requireContext()))
            hint = "粘贴完整朗读规则 JSON，或临时粘贴 JS 代码"
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
                    val info = ScriptBrain.importedRuleInfo(requireContext())
                    toastOnUi("朗读规则已保存：${info?.name.orEmpty().ifBlank { "导入规则" }}")
                }.onFailure {
                    toastOnUi("保存失败：${it.localizedMessage ?: it.javaClass.simpleName}")
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runScriptRuleForCurrentChapter() {
        val appContext = requireContext().applicationContext
        toastOnUi("正在运行内置分析模块...")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ScriptBrain.runAnalysisModulesForCurrentChapter(appContext) }
            }
            if (!isAdded) return@launch
            result.onSuccess { runResult ->
                showTextDialog("内置分析运行结果", formatRuleRunResult(runResult, "结果已写入角色表和台词本。"))
            }.onFailure {
                showTextDialog(
                    "内置分析运行失败",
                    it.localizedMessage ?: it.stackTraceToString()
                )
            }
        }
    }

    private fun runImportedSpeechRuleForCurrentChapter() {
        if (!ScriptBrain.hasImportedRule(requireContext())) {
            toastOnUi("请先导入旧 TTS 朗读规则")
            return
        }
        val appContext = requireContext().applicationContext
        toastOnUi("正在运行旧 TTS 朗读规则...")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ScriptBrain.runImportedRuleForCurrentChapter(appContext) }
            }
            if (!isAdded) return@launch
            result.onSuccess { runResult ->
                showTextDialog("旧规则运行结果", formatRuleRunResult(runResult, "旧规则结果已保存，可用于对照。"))
            }.onFailure {
                showTextDialog(
                    "旧规则运行失败",
                    it.localizedMessage ?: it.stackTraceToString()
                )
            }
        }
    }

    private fun formatRuleRunResult(runResult: ScriptBrain.RuleRunResult, note: String): String {
        val analysis = runResult.analysis
        return buildString {
            appendLine("${analysis.chapterTitle}")
            appendLine("运行完成：${analysis.lines.size} 行台词，${analysis.characters.size} 个角色")
            appendLine(note)
            appendModuleReports(runResult.moduleReports)
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
    }

    private fun StringBuilder.appendModuleReports(reports: List<ScriptBrain.ModuleRunReport>) {
        if (reports.isEmpty()) return
        appendLine()
        appendLine("模块诊断：")
        reports.forEach { report ->
            val statusText = when (report.status) {
                "ok" -> "成功"
                "failed" -> "失败"
                "skipped" -> "跳过"
                else -> report.status
            }
            appendLine(
                "${report.index.toString().padStart(2, '0')} ${report.moduleName}：$statusText"
            )
            appendLine(
                "    正文 ${report.beforeTextLength} -> ${report.afterTextLength} 字，" +
                        "台词 ${report.beforeLineCount} -> ${report.afterLineCount} 行，" +
                        "角色 ${report.beforeCharacterCount} -> ${report.afterCharacterCount} 个"
            )
            if (report.message.isNotBlank() && report.message != "运行成功") {
                appendLine("    原因：${report.message}")
            }
            if (report.sampleLines.isNotEmpty()) {
                appendLine("    样例：")
                report.sampleLines.forEach { appendLine("      $it") }
            }
        }
    }

    private fun showImportedScriptRule() {
        val rule = ScriptBrain.loadImportedRuleRaw(requireContext())
        if (rule.isBlank()) {
            toastOnUi("还没有导入朗读规则")
            return
        }
        val info = ScriptBrain.importedRuleInfo(requireContext())
        showTextDialog(
            "已导入朗读规则",
            buildString {
                if (info != null) {
                    appendLine("名称：${info.name}")
                    appendLine("作者：${info.author}")
                    appendLine("版本：${info.version}")
                    appendLine("格式：${if (info.isJson) "完整 JSON" else "JS 代码"}")
                    appendLine("代码长度：${info.codeLength} 字符")
                    appendLine("原始长度：${info.rawLength} 字符")
                } else {
                    appendLine("长度：${rule.length} 字符")
                }
                appendLine()
                append(rule.take(12000))
            } +
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
            当前是开源阅读端“内置分析模式”第一版。

            已启用：
            1. 可以粘贴导入完整旧 TTS 朗读规则 JSON，也兼容临时粘贴 JS。
            2. 可以从手机文件直接选择完整朗读规则 JSON 导入。
            3. 导入后的规则会进入规则库，可以点选启用。
            4. 可以运行当前章，调用 SpeechRuleJS.prepareChapterAudioQueue。
            5. 运行结果会转换成台词本，并更新角色表。
            6. 兼容 ttsrv.readTxtFile/writeTxtFile/httpGet/httpPost 的基础能力。

            当前限制：
            1. 角色管理插件已作为内置模块接入，当前先复刻主要角色管理界面和文件格式。
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
