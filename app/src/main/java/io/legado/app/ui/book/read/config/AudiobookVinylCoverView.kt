package io.legado.app.ui.book.read.config

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.withSave
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import io.legado.app.help.glide.BlurTransformation
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader

class AudiobookVinylCoverView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4F6F32")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val cropPath = Path()
    private val arcPath = Path()
    private val sleeveRect = RectF()
    private val recordRect = RectF()
    private val centerRect = RectF()
    private val sideClip = RectF()

    private var coverDrawable: Drawable? = null
    private var blurDrawable: Drawable? = null
    private var coverTarget: CustomTarget<Drawable>? = null
    private var blurTarget: CustomTarget<Drawable>? = null
    private var coverPath: String? = null
    private var sourceOrigin: String? = null
    private var bookTitle: String = ""
    private var chapterTitle: String = ""
    private var recordRotation = 0f
    private var rotationAnimator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun bindCover(
        bookTitle: String,
        chapterTitle: String,
        coverPath: String?,
        sourceOrigin: String?
    ) {
        this.bookTitle = bookTitle.ifBlank { "正在听书" }
        this.chapterTitle = chapterTitle.ifBlank { "当前章节" }
        if (this.coverPath == coverPath && this.sourceOrigin == sourceOrigin) {
            invalidate()
            return
        }
        this.coverPath = coverPath
        this.sourceOrigin = sourceOrigin
        loadCoverDrawables()
    }

    fun setPlaying(playing: Boolean) {
        if (playing) {
            startRotation()
        } else {
            stopRotation()
        }
    }

    override fun onDetachedFromWindow() {
        stopRotation()
        clearTargets()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        drawAtmosphere(canvas, viewWidth, viewHeight)

        val sleeveSize = minOf(viewWidth - dp(40f), dp(248f))
        val sleeveLeft = (viewWidth - sleeveSize) / 2f
        val sleeveTop = (viewHeight - sleeveSize) / 2f + dp(2f)
        sleeveRect.set(sleeveLeft, sleeveTop, sleeveLeft + sleeveSize, sleeveTop + sleeveSize)

        val recordRadius = sleeveSize * 0.39f
        val recordCx = sleeveRect.centerX() + dp(4f)
        val recordCy = sleeveRect.centerY() - dp(2f)
        recordRect.set(
            recordCx - recordRadius,
            recordCy - recordRadius,
            recordCx + recordRadius,
            recordCy + recordRadius
        )

        drawSideRecord(canvas, recordCy, recordRadius)
        drawSleeve(canvas)
        drawRecord(canvas, recordCx, recordCy, recordRadius)
        drawSleeveDetails(canvas)
        drawSideOpening(canvas)
    }

    private fun drawAtmosphere(canvas: Canvas, viewWidth: Float, viewHeight: Float) {
        val blur = blurDrawable
        if (blur == null) {
            fillPaint.shader = LinearGradient(
                0f,
                0f,
                viewWidth,
                viewHeight,
                Color.parseColor("#FAF8F3"),
                Color.parseColor("#E8F1E1"),
                Shader.TileMode.CLAMP
            )
            fillPaint.alpha = 255
            canvas.drawRect(0f, 0f, viewWidth, viewHeight, fillPaint)
            fillPaint.shader = null
        } else {
            canvas.withSave {
                fillPaint.alpha = 255
                drawDrawableCrop(canvas, blur, RectF(0f, 0f, viewWidth, viewHeight), alpha = 42)
            }
        }
        fillPaint.color = Color.parseColor("#FAF8F3")
        fillPaint.alpha = 205
        fillPaint.shader = null
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, fillPaint)
        fillPaint.alpha = 255
    }

    private fun drawSideRecord(canvas: Canvas, centerY: Float, radius: Float) {
        sideClip.set(sleeveRect.right - dp(12f), sleeveRect.top + dp(38f), sleeveRect.right + dp(42f), sleeveRect.bottom - dp(38f))
        canvas.withSave {
            clipRect(sideClip)
            drawRecordDisc(canvas, sleeveRect.right - dp(4f), centerY, radius * 0.94f, rotate = true)
        }
    }

    private fun drawSleeve(canvas: Canvas) {
        fillPaint.clearShadowLayer()
        fillPaint.style = Paint.Style.FILL
        fillPaint.shader = LinearGradient(
            sleeveRect.left,
            sleeveRect.top,
            sleeveRect.right,
            sleeveRect.bottom,
            intArrayOf(
                Color.parseColor("#FFFDF7EA"),
                Color.parseColor("#FFF9F4E8"),
                Color.parseColor("#FFE8F1E1")
            ),
            floatArrayOf(0f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )
        fillPaint.setShadowLayer(dp(12f), 0f, dp(5f), Color.argb(34, 65, 73, 56))
        canvas.drawRoundRect(sleeveRect, dp(28f), dp(28f), fillPaint)
        fillPaint.clearShadowLayer()
        fillPaint.shader = null

        strokePaint.color = Color.argb(150, 255, 255, 255)
        strokePaint.strokeWidth = dp(1f)
        canvas.drawRoundRect(sleeveRect, dp(28f), dp(28f), strokePaint)

        strokePaint.color = Color.argb(70, 79, 111, 50)
        strokePaint.strokeWidth = dp(0.8f)
        canvas.drawRoundRect(
            RectF(
                sleeveRect.left + dp(8f),
                sleeveRect.top + dp(8f),
                sleeveRect.right - dp(8f),
                sleeveRect.bottom - dp(8f)
            ),
            dp(22f),
            dp(22f),
            strokePaint
        )
    }

    private fun drawRecord(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        drawRecordDisc(canvas, cx, cy, radius, rotate = true)
    }

    private fun drawRecordDisc(canvas: Canvas, cx: Float, cy: Float, radius: Float, rotate: Boolean) {
        canvas.withSave {
            if (rotate) rotate(recordRotation, cx, cy)
            fillPaint.shader = RadialGradient(
                cx - radius * 0.28f,
                cy - radius * 0.28f,
                radius * 1.2f,
                intArrayOf(
                    Color.parseColor("#3A3A34"),
                    Color.parseColor("#111111"),
                    Color.parseColor("#050505")
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
            fillPaint.alpha = 255
            fillPaint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, radius, fillPaint)
            fillPaint.shader = null

            strokePaint.color = Color.argb(115, 255, 255, 255)
            strokePaint.strokeWidth = dp(0.45f)
            var groove = radius * 0.32f
            while (groove < radius * 0.94f) {
                canvas.drawCircle(cx, cy, groove, strokePaint)
                groove += dp(5.2f)
            }

            strokePaint.color = Color.argb(80, 0, 0, 0)
            strokePaint.strokeWidth = dp(3f)
            canvas.drawCircle(cx, cy, radius * 0.97f, strokePaint)

            val labelRadius = radius * 0.43f
            fillPaint.shader = RadialGradient(
                cx,
                cy,
                labelRadius,
                Color.parseColor("#E8F1E1"),
                Color.parseColor("#4F6F32"),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, labelRadius, fillPaint)
            fillPaint.shader = null

            val coverRadius = labelRadius * 0.86f
            centerRect.set(cx - coverRadius, cy - coverRadius, cx + coverRadius, cy + coverRadius)
            drawCenterCover(canvas, centerRect)
        }
    }

    private fun drawCenterCover(canvas: Canvas, rect: RectF) {
        cropPath.reset()
        cropPath.addOval(rect, Path.Direction.CW)
        canvas.withSave {
            clipPath(cropPath)
            val cover = coverDrawable
            if (cover == null) {
                drawFallbackCenter(canvas, rect)
            } else {
                drawDrawableCrop(canvas, cover, rect, alpha = 255)
            }
        }
        strokePaint.color = Color.argb(210, 250, 248, 243)
        strokePaint.strokeWidth = dp(2f)
        canvas.drawOval(rect, strokePaint)
    }

    private fun drawFallbackCenter(canvas: Canvas, rect: RectF) {
        fillPaint.shader = LinearGradient(
            rect.left,
            rect.top,
            rect.right,
            rect.bottom,
            Color.parseColor("#FAF8F3"),
            Color.parseColor("#E8F1E1"),
            Shader.TileMode.CLAMP
        )
        fillPaint.alpha = 255
        canvas.drawOval(rect, fillPaint)
        fillPaint.shader = null

        textPaint.color = Color.parseColor("#4F6F32")
        textPaint.textSize = sp(8.5f)
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        canvas.drawText(bookTitle.take(7), rect.centerX(), rect.centerY() - dp(2f), textPaint)
        textPaint.color = Color.parseColor("#7D8B73")
        textPaint.textSize = sp(6.8f)
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        canvas.drawText(chapterTitle.take(8), rect.centerX(), rect.centerY() + dp(10f), textPaint)
    }

    private fun drawSleeveDetails(canvas: Canvas) {
        strokePaint.color = Color.argb(58, 79, 111, 50)
        strokePaint.strokeWidth = dp(0.9f)
        canvas.drawArc(
            sleeveRect.left + dp(30f),
            sleeveRect.top + dp(28f),
            sleeveRect.right - dp(30f),
            sleeveRect.bottom - dp(28f),
            210f,
            120f,
            false,
            strokePaint
        )
        canvas.drawArc(
            sleeveRect.left + dp(42f),
            sleeveRect.top + dp(42f),
            sleeveRect.right - dp(42f),
            sleeveRect.bottom - dp(42f),
            38f,
            104f,
            false,
            strokePaint
        )

        textPaint.color = Color.argb(210, 79, 111, 50)
        textPaint.textSize = sp(7.4f)
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        arcPath.reset()
        arcPath.addArc(
            sleeveRect.left + dp(26f),
            sleeveRect.top + dp(25f),
            sleeveRect.right - dp(26f),
            sleeveRect.bottom - dp(25f),
            224f,
            92f
        )
        canvas.drawTextOnPath("AUDIOBOOK · VINYL RECORD", arcPath, dp(10f), 0f, textPaint)

        textPaint.textSize = sp(7f)
        textPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        canvas.drawText("ENJOY READING", sleeveRect.centerX(), sleeveRect.bottom - dp(20f), textPaint)
    }

    private fun drawSideOpening(canvas: Canvas) {
        val notchRadius = dp(18f)
        val notchCx = sleeveRect.right - dp(4f)
        val notchCy = sleeveRect.centerY()
        fillPaint.shader = null
        fillPaint.color = Color.parseColor("#FAF8F3")
        fillPaint.alpha = 240
        canvas.drawCircle(notchCx, notchCy, notchRadius, fillPaint)
        fillPaint.color = Color.parseColor("#4F6F32")
        fillPaint.alpha = 255
        canvas.drawCircle(notchCx + dp(1f), notchCy, dp(10f), fillPaint)

        fillPaint.color = Color.WHITE
        val arrow = Path().apply {
            moveTo(notchCx - dp(2f), notchCy - dp(5f))
            lineTo(notchCx + dp(5f), notchCy)
            lineTo(notchCx - dp(2f), notchCy + dp(5f))
            close()
        }
        canvas.drawPath(arrow, fillPaint)
    }

    private fun drawDrawableCrop(canvas: Canvas, drawable: Drawable, rect: RectF, alpha: Int) {
        val intrinsicWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: rect.width().toInt()
        val intrinsicHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: rect.height().toInt()
        val scale = maxOf(rect.width() / intrinsicWidth, rect.height() / intrinsicHeight)
        val drawWidth = intrinsicWidth * scale
        val drawHeight = intrinsicHeight * scale
        val left = rect.left + (rect.width() - drawWidth) / 2f
        val top = rect.top + (rect.height() - drawHeight) / 2f
        drawable.alpha = alpha
        drawable.setBounds(
            left.toInt(),
            top.toInt(),
            (left + drawWidth).toInt(),
            (top + drawHeight).toInt()
        )
        drawable.draw(canvas)
        drawable.alpha = 255
    }

    private fun loadCoverDrawables() {
        clearTargets()
        coverDrawable = null
        blurDrawable = null
        val path = coverPath
        if (path.isNullOrBlank()) {
            invalidate()
            return
        }
        var options = RequestOptions()
            .set(OkHttpModelLoader.loadOnlyWifiOption, false)
        sourceOrigin?.let {
            options = options.set(OkHttpModelLoader.sourceOriginOption, it)
        }

        coverTarget = object : CustomTarget<Drawable>() {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                coverDrawable = resource.constantState?.newDrawable()?.mutate() ?: resource.mutate()
                invalidate()
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                coverDrawable = null
                invalidate()
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                coverDrawable = null
            }
        }.also {
            ImageLoader.load(context, path)
                .apply(options)
                .centerCrop()
                .into(it)
        }

        blurTarget = object : CustomTarget<Drawable>() {
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                blurDrawable = resource.constantState?.newDrawable()?.mutate() ?: resource.mutate()
                invalidate()
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                blurDrawable = null
                invalidate()
            }

            override fun onLoadCleared(placeholder: Drawable?) {
                blurDrawable = null
            }
        }.also {
            ImageLoader.load(context, path)
                .apply(options)
                .transform(BlurTransformation(25), CenterCrop())
                .into(it)
        }
    }

    private fun clearTargets() {
        coverTarget?.let { runCatching { Glide.with(context).clear(it) } }
        blurTarget?.let { runCatching { Glide.with(context).clear(it) } }
        coverTarget = null
        blurTarget = null
    }

    private fun startRotation() {
        if (rotationAnimator?.isRunning == true) return
        rotationAnimator = ValueAnimator.ofFloat(recordRotation, recordRotation + 360f).apply {
            duration = 26000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                recordRotation = (it.animatedValue as Float) % 360f
                invalidate()
            }
            start()
        }
    }

    private fun stopRotation() {
        rotationAnimator?.cancel()
        rotationAnimator = null
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    private fun sp(value: Float): Float {
        return value * resources.displayMetrics.density * resources.configuration.fontScale
    }
}
