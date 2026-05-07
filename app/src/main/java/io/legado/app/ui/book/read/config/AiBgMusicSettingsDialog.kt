package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import io.legado.app.R
import io.legado.app.base.BaseBottomSheetDialogFragment
import io.legado.app.model.AiBgMusic
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiBgMusicSettingsDialog : BaseBottomSheetDialogFragment(0) {

    private val callback: Callback? get() = activity as? Callback
    private var promptNameView: TextView? = null
    private data class ModelPreset(
        val provider: String,
        val label: String,
        val baseUrl: String,
        val model: String,
    )

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: android.view.ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return buildView()
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) = Unit

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            isDraggable = false
        }
    }

    @SuppressLint("SetTextI18n")
    private fun buildView(): View {
        val context = requireContext()
        val config = AiBgMusic.config()
        val scrollView = NestedScrollView(context).apply {
            isFillViewport = false
            isNestedScrollingEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx())
        }
        scrollView.addView(root)

        fun label(text: String) {
            root.addView(TextView(context).apply {
                this.text = text
                textSize = 14f
                setPadding(0, 10.dpToPx(), 0, 4.dpToPx())
            })
        }

        fun edit(text: String, hint: String, minLines: Int = 1): EditText {
            return EditText(context).apply {
                setText(text)
                this.hint = hint
                this.minLines = minLines
            }.also(root::addView)
        }

        label("背景音乐文件目录")
        val musicDir = edit(config.musicDir, "选择或粘贴背景音乐文件夹路径 / content:// 目录")
        root.addView(MaterialButton(context).apply {
            text = "选择目录"
            setOnClickListener { callback?.selectAiBgMusicDir() }
        })

        val presets = modelPresets()
        var providerName = presets.firstOrNull {
            it.baseUrl == config.modelUrl && it.model == config.modelName
        }?.provider ?: "自定义"
        var providerPresets = presets.filter { it.provider == providerName }

        label("模型厂牌")
        val providerButton = MaterialButton(context)
        root.addView(providerButton)

        label("模型")
        val modelButton = MaterialButton(context)
        root.addView(modelButton)

        label("AI 模型地址")
        val modelUrl = edit(config.modelUrl, "例如 https://api.openai.com/v1 或完整 /v1/chat/completions")

        label("模型名")
        val modelName = edit(config.modelName, "例如 gpt-4o-mini / deepseek-chat")

        fun refreshPresetButtons() {
            providerButton.text = "厂牌：$providerName"
            val matched = presets.firstOrNull {
                it.provider == providerName && it.baseUrl == modelUrl.text?.toString() && it.model == modelName.text?.toString()
            }
            modelButton.text = if (providerName == "自定义") {
                "模型：自定义"
            } else {
                "模型：${matched?.label ?: modelName.text?.toString()?.ifBlank { "请选择" }}"
            }
        }

        fun applyPreset(preset: ModelPreset) {
            providerName = preset.provider
            providerPresets = presets.filter { it.provider == providerName }
            modelUrl.setText(preset.baseUrl)
            modelName.setText(preset.model)
            refreshPresetButtons()
        }

        providerButton.setOnClickListener {
            val providers = (presets.map { it.provider }.distinct() + "自定义").toTypedArray()
            AlertDialog.Builder(context)
                .setTitle("选择模型厂牌")
                .setItems(providers) { _, which ->
                    providerName = providers[which]
                    providerPresets = presets.filter { it.provider == providerName }
                    if (providerName != "自定义") {
                        providerPresets.firstOrNull()?.let(::applyPreset)
                    } else {
                        refreshPresetButtons()
                    }
                }
                .show()
        }

        modelButton.setOnClickListener {
            if (providerName == "自定义") {
                toastOnUi("自定义模式下直接填写模型地址和模型名")
                return@setOnClickListener
            }
            val names = providerPresets.map { it.label }.toTypedArray()
            AlertDialog.Builder(context)
                .setTitle("选择 $providerName 模型")
                .setItems(names) { _, which ->
                    providerPresets.getOrNull(which)?.let(::applyPreset)
                }
                .show()
        }
        refreshPresetButtons()

        label("密钥")
        val modelKey = edit(config.modelKey, "可选，测试链接会带 Bearer token").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSelection(text?.length ?: 0)
        }

        root.addView(MaterialButton(context).apply {
            text = "自定义接入"
            setOnClickListener {
                showCustomModelDialog(
                    modelUrl.text?.toString().orEmpty(),
                    modelName.text?.toString().orEmpty(),
                    modelKey.text?.toString().orEmpty()
                ) { url, name, key ->
                    providerName = "自定义"
                    providerPresets = emptyList()
                    modelUrl.setText(url)
                    modelName.setText(name)
                    modelKey.setText(key)
                    modelKey.setSelection(modelKey.text?.length ?: 0)
                    refreshPresetButtons()
                }
            }
        })

        root.addView(MaterialButton(context).apply {
            text = "测试链接"
            setOnClickListener {
                saveFromViews(musicDir, modelUrl, modelName, modelKey, null)
                lifecycleScope.launch {
                    val message = withContext(Dispatchers.IO) {
                        AiBgMusic.testModel().getOrElse { e -> "测试失败：${e.localizedMessage}" }
                    }
                    toastOnUi(message)
                }
            }
        })

        label("提示词管理")
        promptNameView = TextView(context).apply {
            text = "当前方案：${AiBgMusic.selectedPromptName}"
            textSize = 15f
        }
        root.addView(promptNameView)
        root.addView(MaterialButton(context).apply {
            text = "管理提示词方案"
            setOnClickListener { showPromptProfilesDialog() }
        })

        label("音乐切换频率")
        val frequencyText = TextView(context)
        root.addView(frequencyText)
        val frequencySlider = Slider(context).apply {
            valueFrom = 0f
            valueTo = 2f
            stepSize = 1f
            value = config.frequency.toFloat()
        }
        fun updateFrequencyText(value: Float) {
            frequencyText.text = when (value.toInt()) {
                AiBgMusic.FREQUENCY_BOOK -> "整本书一种基调音乐反复播放"
                AiBgMusic.FREQUENCY_CHAPTER -> "不同章节切换不同背景音乐"
                else -> "一个场景一个音乐，读到场景边界切换"
            }
        }
        updateFrequencyText(frequencySlider.value)
        frequencySlider.addOnChangeListener { _, value, _ -> updateFrequencyText(value) }
        root.addView(frequencySlider)

        label("场景音乐跨度")
        val scenesPerMusicText = TextView(context)
        root.addView(scenesPerMusicText)
        val scenesPerMusicSlider = Slider(context).apply {
            valueFrom = 1f
            valueTo = 10f
            stepSize = 1f
            value = config.scenesPerMusic.toFloat()
        }
        fun updateScenesPerMusicText(value: Float) {
            scenesPerMusicText.text = if (value.toInt() <= 1) {
                "每 1 个场景切换一次音乐"
            } else {
                "每 ${value.toInt()} 个场景共用一种音乐"
            }
        }
        updateScenesPerMusicText(scenesPerMusicSlider.value)
        scenesPerMusicSlider.addOnChangeListener { _, value, _ -> updateScenesPerMusicText(value) }
        root.addView(scenesPerMusicSlider)

        label("播放列表预生成")
        val preloadWholeBook = CheckBox(context).apply {
            text = "提前分析整本书"
            isChecked = config.preloadWholeBook
        }
        root.addView(preloadWholeBook)
        val preloadText = TextView(context)
        root.addView(preloadText)
        val preloadSlider = Slider(context).apply {
            valueFrom = 1f
            valueTo = 30f
            stepSize = 1f
            value = config.preloadChapters.coerceIn(1, 30).toFloat()
        }
        fun updatePreloadText(value: Float) {
            preloadText.text = "不选整本书时，提前生成 ${value.toInt()} 章播放列表"
        }
        updatePreloadText(preloadSlider.value)
        preloadSlider.addOnChangeListener { _, value, _ -> updatePreloadText(value) }
        root.addView(preloadSlider)

        label("背景音乐音量")
        val volumeText = TextView(context)
        root.addView(volumeText)
        val volumeSlider = Slider(context).apply {
            valueFrom = 0f
            valueTo = 100f
            stepSize = 1f
            value = config.volume.toFloat()
        }
        fun updateVolumeText(value: Float) {
            volumeText.text = "${value.toInt()}%"
        }
        updateVolumeText(volumeSlider.value)
        volumeSlider.addOnChangeListener { _, value, _ -> updateVolumeText(value) }
        root.addView(volumeSlider)

        root.addView(MaterialButton(context).apply {
            text = "保存"
            setOnClickListener {
                saveFromViews(musicDir, modelUrl, modelName, modelKey, frequencySlider to volumeSlider)
                AiBgMusic.scenesPerMusic = scenesPerMusicSlider.value.toInt()
                AiBgMusic.preloadWholeBook = preloadWholeBook.isChecked
                AiBgMusic.preloadChapters = preloadSlider.value.toInt()
                toastOnUi("智能背景音乐设置已保存")
                dismissAllowingStateLoss()
            }
        })

        return scrollView
    }

    private fun saveFromViews(
        musicDir: EditText,
        modelUrl: EditText,
        modelName: EditText,
        modelKey: EditText,
        sliders: Pair<Slider, Slider>?
    ) {
        val old = AiBgMusic.config()
        AiBgMusic.save(
            old.copy(
                musicDir = musicDir.text?.toString().orEmpty().trim().ifBlank { AiBgMusic.musicDir },
                modelUrl = modelUrl.text?.toString().orEmpty(),
                modelName = modelName.text?.toString().orEmpty(),
                modelKey = modelKey.text?.toString().orEmpty(),
                prompts = old.prompts,
                frequency = sliders?.first?.value?.toInt() ?: old.frequency,
                volume = sliders?.second?.value?.toInt() ?: old.volume,
            )
        )
    }

    private fun showCustomModelDialog(
        initUrl: String,
        initName: String,
        initKey: String,
        onApply: (url: String, name: String, key: String) -> Unit
    ) {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx())
        }

        val urlEdit = EditText(context).apply {
            hint = "模型地址，例如 https://open.bigmodel.cn/api/paas/v4"
            setText(initUrl.ifBlank { AiBgMusic.modelUrl })
        }

        val nameEdit = EditText(context).apply {
            hint = "模型名，例如 glm-4.5-flash"
            setText(initName.ifBlank { AiBgMusic.modelName })
        }

        val keyEdit = EditText(context).apply {
            hint = "API Key，可填 sk-xxx 或 Bearer sk-xxx"
            setText(initKey.ifBlank { AiBgMusic.modelKey })
        }

        root.addView(urlEdit)
        root.addView(nameEdit)
        root.addView(keyEdit)

        AlertDialog.Builder(context)
            .setTitle("自定义模型接入")
            .setView(root)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val url = urlEdit.text?.toString()?.trim().orEmpty()
                val name = nameEdit.text?.toString()?.trim().orEmpty()
                val key = keyEdit.text?.toString()?.trim().orEmpty()

                if (url.isBlank() || name.isBlank()) {
                    toastOnUi("模型地址和模型名不能为空")
                    return@setPositiveButton
                }

                onApply(url, name, key)
            }
            .show()
    }

    private fun showPromptProfilesDialog() {
        val context = requireContext()
        val profiles = AiBgMusic.promptProfiles()
        val selectedName = AiBgMusic.selectedPromptName

        val listRoot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
        }

        var dialog: AlertDialog? = null

        fun selectProfile(profile: AiBgMusic.PromptProfile) {
            AiBgMusic.selectedPromptName = profile.name
            promptNameView?.text = "当前方案：${profile.name}"
            toastOnUi("已选择提示词方案")
            dialog?.dismiss()
        }

        profiles.forEach { profile ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 6.dpToPx(), 0, 6.dpToPx())
            }

            val radio = android.widget.RadioButton(context).apply {
                isChecked = profile.name == selectedName
                contentDescription = "选择 ${profile.name}"
                setOnClickListener {
                    selectProfile(profile)
                }
            }

            val title = android.widget.TextView(context).apply {
                text = profile.name
                textSize = 16f
                setPadding(6.dpToPx(), 0, 6.dpToPx(), 0)
                setOnClickListener {
                    dialog?.dismiss()
                    showPromptProfileEditor(profile)
                }
            }

            val edit = android.widget.TextView(context).apply {
                text = "编辑"
                textSize = 14f
                setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
                setOnClickListener {
                    dialog?.dismiss()
                    showPromptProfileEditor(profile)
                }
            }

            row.addView(radio)
            row.addView(
                title,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(edit)

            listRoot.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val scrollView = android.widget.ScrollView(context).apply {
            addView(listRoot)
        }

        dialog = AlertDialog.Builder(context)
            .setTitle("提示词方案")
            .setView(scrollView)
            .setNegativeButton("取消", null)
            .setPositiveButton("新增") { _, _ ->
                showPromptProfileEditor(AiBgMusic.PromptProfile("新提示词", ""))
            }
            .create()

        dialog?.show()
    }

    private fun showPromptProfileEditor(profile: AiBgMusic.PromptProfile) {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dpToPx())
        }
        val nameEdit = EditText(context).apply {
            hint = "方案名称"
            setText(profile.name)
        }
        val promptEdit = EditText(context).apply {
            hint = "提示词内容"
            minLines = 8
            setText(profile.prompt)
        }
        root.addView(nameEdit)
        root.addView(promptEdit)

        AlertDialog.Builder(context)
            .setTitle("编辑提示词方案")
            .setView(root)
            .setPositiveButton("保存并选择") { _, _ ->
                val name = nameEdit.text?.toString()?.trim().orEmpty()
                val prompt = promptEdit.text?.toString().orEmpty()
                if (name.isBlank() || prompt.isBlank()) {
                    toastOnUi("名称和提示词不能为空")
                    return@setPositiveButton
                }
                val profiles = AiBgMusic.promptProfiles().toMutableList()
                val oldIndex = profiles.indexOfFirst { it.name == profile.name }
                val newProfile = AiBgMusic.PromptProfile(name, prompt)
                if (oldIndex >= 0) profiles[oldIndex] = newProfile else profiles.add(newProfile)
                AiBgMusic.savePromptProfiles(profiles)
                AiBgMusic.selectedPromptName = name
                promptNameView?.text = "当前方案：$name"
                toastOnUi("提示词方案已保存并选择")
            }
            .setNeutralButton("删除") { _, _ ->
                val profiles = AiBgMusic.promptProfiles()
                    .filterNot { it.name == profile.name }
                AiBgMusic.savePromptProfiles(profiles)
                promptNameView?.text = "当前方案：${AiBgMusic.selectedPromptName}"
                toastOnUi("提示词方案已删除")
            }
            .setNegativeButton("仅保存") { _, _ ->
                val name = nameEdit.text?.toString()?.trim().orEmpty()
                val prompt = promptEdit.text?.toString().orEmpty()
                if (name.isBlank() || prompt.isBlank()) {
                    toastOnUi("名称和提示词不能为空")
                    return@setNegativeButton
                }

                val wasSelected = AiBgMusic.selectedPromptName == profile.name
                val profiles = AiBgMusic.promptProfiles().toMutableList()
                val oldIndex = profiles.indexOfFirst { it.name == profile.name }
                val newProfile = AiBgMusic.PromptProfile(name, prompt)

                if (oldIndex >= 0) {
                    profiles[oldIndex] = newProfile
                } else {
                    profiles.add(newProfile)
                }

                AiBgMusic.savePromptProfiles(profiles)

                if (wasSelected) {
                    AiBgMusic.selectedPromptName = name
                    promptNameView?.text = "当前方案：$name"
                } else {
                    promptNameView?.text = "当前方案：${AiBgMusic.selectedPromptName}"
                }

                toastOnUi("提示词方案已保存")
            }
            .show()
    }

    interface Callback {
        fun selectAiBgMusicDir()
    }

    private fun modelPresets(): List<ModelPreset> {
        return listOf(
            ModelPreset("DeepSeek", "DeepSeek V4 Flash（快，适合日常场景分析）", "https://api.deepseek.com", "deepseek-v4-flash"),
            ModelPreset("DeepSeek", "DeepSeek V4 Pro（更稳，适合复杂章节）", "https://api.deepseek.com", "deepseek-v4-pro"),
            ModelPreset("智谱", "GLM-4.5-Flash（快，成本低）", "https://open.bigmodel.cn/api/paas/v4", "glm-4.5-flash"),
            ModelPreset("智谱", "GLM-4.5-Air（质量更稳）", "https://open.bigmodel.cn/api/paas/v4", "glm-4.5-air"),
            ModelPreset("美团 LongCat", "LongCat-Flash-Lite（轻量，预生成用）", "https://api.longcat.chat/openai", "LongCat-Flash-Lite"),
            ModelPreset("美团 LongCat", "LongCat-Flash-Chat（通用，稳定）", "https://api.longcat.chat/openai", "LongCat-Flash-Chat"),
            ModelPreset("千问 Qwen", "qwen-flash（轻量，速度优先）", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-flash"),
            ModelPreset("千问 Qwen", "qwen-plus（推荐，结构化更稳）", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        )
    }
}
