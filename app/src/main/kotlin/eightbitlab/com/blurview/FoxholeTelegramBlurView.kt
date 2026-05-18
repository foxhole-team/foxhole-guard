package eightbitlab.com.blurview

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import kotlin.math.ceil

/**
 * Telegram-style API 31+ blur rect: record only the pixels under this chrome
 * into a small RenderNode, then draw that node with RenderEffect.
 */
class FoxholeTelegramBlurView(
    context: Context,
) : View(context) {
    private val targetLocation = IntArray(2)
    private val viewLocation = IntArray(2)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frameClearDrawable = ColorDrawable(Color.TRANSPARENT)
    private val density = resources.displayMetrics.density
    private val blurPaddingPx = (144f * density).toInt()
    private val blurNode: RenderNode? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            RenderNode("FoxholeTelegramBlur")
        } else {
            null
        }

    private var blurTarget: BlurTarget? = null
    private var blurRadius = 40f
    private var inputScale = 1f
    private var saturation = 1.25f

    @ColorInt
    private var overlayColor = Color.TRANSPARENT

    @ColorInt
    private var frameClearColor = Color.TRANSPARENT

    init {
        setWillNotDraw(false)
    }

    fun configure(
        blurTarget: BlurTarget?,
        @ColorInt frameClearColor: Int,
        blurRadius: Float,
        inputScale: Float,
        saturation: Float,
        @ColorInt overlayColor: Int,
    ) {
        if (this.blurTarget !== blurTarget) {
            this.blurTarget = blurTarget
        }
        val resolvedInputScale = inputScale.coerceIn(1f, 4f)
        val needsEffectUpdate =
            this.blurRadius != blurRadius ||
                this.saturation != saturation ||
                this.inputScale != resolvedInputScale
        this.blurRadius = blurRadius
        this.inputScale = resolvedInputScale
        this.saturation = saturation
        this.overlayColor = overlayColor
        this.frameClearColor = frameClearColor
        frameClearDrawable.color = frameClearColor
        if (needsEffectUpdate) {
            updateBlurEffect()
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        blurNode?.discardDisplayList()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val target = blurTarget
        val node = blurNode
        if (target == null || node == null || !canDrawHardwareBlur(target, canvas)) {
            drawFallback(canvas)
            return
        }

        target.getLocationOnScreen(targetLocation)
        getLocationOnScreen(viewLocation)
        val left = viewLocation[0] - targetLocation[0]
        val top = viewLocation[1] - targetLocation[1]
        val paddedWidth = width + (blurPaddingPx * 2)
        val paddedHeight = height + (blurPaddingPx * 2)
        val nodeWidth = ceil(paddedWidth / inputScale).toInt().coerceAtLeast(1)
        val nodeHeight = ceil(paddedHeight / inputScale).toInt().coerceAtLeast(1)

        node.setPosition(0, 0, nodeWidth, nodeHeight)
        val recordingCanvas = node.beginRecording()
        recordingCanvas.scale(1f / inputScale, 1f / inputScale)
        frameClearDrawable.setBounds(0, 0, paddedWidth, paddedHeight)
        frameClearDrawable.draw(recordingCanvas)
        recordingCanvas.translate(blurPaddingPx - left.toFloat(), blurPaddingPx - top.toFloat())
        recordingCanvas.drawRenderNode(target.renderNode)
        node.endRecording()

        val rootSave = canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.translate(-blurPaddingPx.toFloat(), -blurPaddingPx.toFloat())
        canvas.scale(inputScale, inputScale)
        canvas.drawRenderNode(node)
        canvas.restoreToCount(rootSave)

        if (overlayColor != Color.TRANSPARENT) {
            overlayPaint.color = overlayColor
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        }
    }

    private fun drawFallback(canvas: Canvas) {
        if (overlayColor != Color.TRANSPARENT) {
            overlayPaint.color = overlayColor
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        }
    }

    private fun canDrawHardwareBlur(
        target: BlurTarget,
        canvas: Canvas,
    ): Boolean =
        BlurTarget.canUseHardwareRendering &&
            canvas.isHardwareAccelerated &&
            width > 0 &&
            height > 0 &&
            target.width > 0 &&
            target.height > 0

    private fun updateBlurEffect() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            updateBlurEffectApi31()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun updateBlurEffectApi31() {
        val node = blurNode ?: return
        val colorMatrix =
            ColorMatrix().apply {
                setSaturation(saturation)
            }
        val blur =
            RenderEffect.createBlurEffect(
                blurRadius,
                blurRadius,
                Shader.TileMode.CLAMP,
            )
        val colorFilter =
            RenderEffect.createColorFilterEffect(
                ColorMatrixColorFilter(colorMatrix),
            )
        node.setRenderEffect(RenderEffect.createChainEffect(blur, colorFilter))
    }
}
