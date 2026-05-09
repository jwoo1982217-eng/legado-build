package io.legado.app.ui.book.read.config

//import io.legado.app.lib.theme.bottomBackground
//import io.legado.app.lib.theme.getPrimaryTextColor
import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.slider.Slider
import io.legado.app.R
import io.legado.app.base.BaseBottomSheetDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.databinding.DialogReadAloudBinding
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
        btnSpeakEngine.setOnClickListener {
            SpeakEngineDialog().show(childFragmentManager, "speakEngineDialog")
        }
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
        ivToBackstage.setOnClickListener { callBack?.finish() }
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

        btnAudiobookGenerate.setOnClickListener {
            callBack?.generateAudiobookCache()
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
        binding.btnSpeakEngine.text = getString(R.string.speak_engine) + "：" + speakEngineSummary
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
        fun finish()
    }
}
