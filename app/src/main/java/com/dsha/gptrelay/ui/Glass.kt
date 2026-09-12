package com.dsha.gptrelay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * 视觉层：iOS 风「液态玻璃」。
 *
 * 由两部分组成：
 *  - [AuroraBackground]：缓慢流动的极光渐变底，为玻璃面板提供可透出的内容
 *  - [Glass]：半透明 + 细描边 + 高光渐变的玻璃面板
 */
object Glass {

    fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    /** 玻璃面板：半透明填充 + 顶部高光 + 1px 描边 + 大圆角。 */
    fun panel(ctx: Context, radiusDp: Float = 26f, tint: Int = 0x22FFFFFF, stroke: Int = 0x33FFFFFF): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(ctx, radiusDp)
        d.colors = intArrayOf(tint, (tint and 0x00FFFFFF) or 0x0A000000)
        d.gradientType = GradientDrawable.LINEAR_GRADIENT
        d.orientation = GradientDrawable.Orientation.TL_BR
        d.setStroke(dp(ctx, 1f).toInt().coerceAtLeast(1), stroke)
        return d
    }

    /** 主按钮：偏冷的蓝紫渐变，配白描边。 */
    fun primaryButton(ctx: Context, radiusDp: Float = 22f, enabled: Boolean = true): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(ctx, radiusDp)
        if (enabled) {
            d.colors = intArrayOf(Color.parseColor("#7AA2FF"), Color.parseColor("#A98BFF"))
        } else {
            d.colors = intArrayOf(Color.parseColor("#3A4358"), Color.parseColor("#3A4358"))
        }
        d.orientation = GradientDrawable.Orientation.LEFT_RIGHT
        d.setStroke(dp(ctx, 1f).toInt().coerceAtLeast(1), 0x40FFFFFF)
        return d
    }

    /** 消息气泡。mine=true 用蓝紫渐变（右侧），false 用玻璃（左侧）。 */
    fun bubble(ctx: Context, mine: Boolean, radiusDp: Float = 20f): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(ctx, radiusDp)
        if (mine) {
            d.colors = intArrayOf(Color.parseColor("#7AA2FF"), Color.parseColor("#A98BFF"))
            d.orientation = GradientDrawable.Orientation.TL_BR
        } else {
            d.colors = intArrayOf(0x1FFFFFFF, 0x14FFFFFF)
            d.orientation = GradientDrawable.Orientation.TL_BR
        }
        d.setStroke(dp(ctx, 1f).toInt().coerceAtLeast(1), if (mine) 0x33FFFFFF else 0x26FFFFFF)
        return d
    }
}

/** 缓慢流动的极光背景，给玻璃层提供可透视的内容。 */
class AuroraBackground(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private val started = System.currentTimeMillis()

    private val blobs = arrayOf(
        // (相对 x, 相对 y, 半径系数, 颜色)
        floatArrayOf(0.22f, 0.18f, 0.85f) to Color.parseColor("#5B7CFA"),
        floatArrayOf(0.85f, 0.30f, 0.75f) to Color.parseColor("#9B6BFF"),
        floatArrayOf(0.35f, 0.82f, 0.80f) to Color.parseColor("#2FD4C6"),
        floatArrayOf(0.78f, 0.88f, 0.65f) to Color.parseColor("#FF6FA8"),
    )

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        canvas.drawColor(Color.parseColor("#070A14"))

        phase = (System.currentTimeMillis() - started) / 1000f

        for ((i, entry) in blobs.withIndex()) {
            val (geo, color) = entry
            val speed = 0.35f + i * 0.12f
            val cx = (geo[0] + sin(phase * speed + i) * 0.06f) * w
            val cy = (geo[1] + cos(phase * speed * 0.8f + i) * 0.05f) * h
            val radius = geo[2] * maxOf(w, h) * 0.55f

            paint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf((color and 0x00FFFFFF) or 0x59000000, (color and 0x00FFFFFF) or 0x14000000, Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, radius, paint)
        }

        // 顶部一层暗压，保证文字可读
        paint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0x66060A14.toInt(), 0x22060A14, 0x88060A14.toInt()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        postInvalidateOnAnimation()
    }
}
