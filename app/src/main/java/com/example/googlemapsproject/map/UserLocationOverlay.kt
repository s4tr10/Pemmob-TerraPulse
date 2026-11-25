package com.example.googlemapsproject.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.example.googlemapsproject.model.GeoPointData
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.abs

class UserLocationOverlay(density: Float) : Overlay() {

    private var currentPoint: GeoPoint? = null
    private var accuracyMeters: Float = 0f
    private var headingDegrees: Float = 0f
    private var isNavigationMode: Boolean = false

    private val screenPoint = Point()
    private val coneRect = RectF()
    private val arrowPath = Path()

    private val dotRadiusPx = 9f * density
    private val borderRadiusPx = 12f * density
    private val haloRadiusPx = 18f * density
    private val coneRadiusPx = 46f * density
    private val navArrowSizePx = 15f * density

    private val accuracyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(38, 13, 148, 136)
        style = Paint.Style.FILL
    }

    private val accuracyStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 13, 148, 136)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 20, 184, 166)
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0F766E")
        style = Paint.Style.FILL
    }

    private val navCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#14B8A6")
        style = Paint.Style.FILL
    }

    private val navBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
    }

    private val conePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun update(
        location: GeoPointData?,
        accuracyMeters: Float?,
        headingDegrees: Float,
        isNavigationMode: Boolean
    ): Boolean {
        val nextPoint = location?.let { GeoPoint(it.lat, it.lon) }
        val nextAcc = (accuracyMeters ?: 0f).coerceIn(0f, 500f)
        val changed = currentPoint?.latitude != nextPoint?.latitude ||
            currentPoint?.longitude != nextPoint?.longitude ||
            abs(this.accuracyMeters - nextAcc) >= 2f ||
            abs(this.headingDegrees - headingDegrees) >= 1.5f ||
            this.isNavigationMode != isNavigationMode

        currentPoint = nextPoint
        this.accuracyMeters = nextAcc
        this.headingDegrees = headingDegrees
        this.isNavigationMode = isNavigationMode
        return changed
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val point = currentPoint ?: return

        mapView.projection.toPixels(point, screenPoint)
        val cx = screenPoint.x.toFloat()
        val cy = screenPoint.y.toFloat()

        // 1. Accuracy ring in true meters
        if (!isNavigationMode && accuracyMeters > 4f) {
            val radiusPx = mapView.projection.metersToEquatorPixels(accuracyMeters)
            if (radiusPx > borderRadiusPx && radiusPx < mapView.width.toFloat()) {
                canvas.drawCircle(cx, cy, radiusPx, accuracyFillPaint)
                canvas.drawCircle(cx, cy, radiusPx, accuracyStrokePaint)
            }
        }

        // 2. Directional heading cone (canvas is rotated with map orientation)
        conePaint.shader = RadialGradient(
            cx,
            cy,
            coneRadiusPx,
            Color.argb(120, 20, 184, 166),
            Color.argb(0, 20, 184, 166),
            Shader.TileMode.CLAMP
        )
        coneRect.set(cx - coneRadiusPx, cy - coneRadiusPx, cx + coneRadiusPx, cy + coneRadiusPx)
        val sweep = 56f
        val startAngle = headingDegrees - 90f - sweep / 2f
        canvas.drawArc(coneRect, startAngle, sweep, true, conePaint)

        // 3. Location puck or navigation chevron
        if (isNavigationMode) {
            canvas.save()
            canvas.rotate(headingDegrees, cx, cy)
            arrowPath.reset()
            arrowPath.moveTo(cx, cy - navArrowSizePx)
            arrowPath.lineTo(cx + navArrowSizePx * 0.78f, cy + navArrowSizePx * 0.75f)
            arrowPath.lineTo(cx, cy + navArrowSizePx * 0.35f)
            arrowPath.lineTo(cx - navArrowSizePx * 0.78f, cy + navArrowSizePx * 0.75f)
            arrowPath.close()
            canvas.drawCircle(cx, cy, haloRadiusPx * 1.15f, haloPaint)
            canvas.drawPath(arrowPath, navCorePaint)
            canvas.drawPath(arrowPath, navBorderPaint)
            canvas.restore()
        } else {
            canvas.drawCircle(cx, cy, haloRadiusPx, haloPaint)
            canvas.drawCircle(cx, cy, borderRadiusPx, borderPaint)
            canvas.drawCircle(cx, cy, dotRadiusPx, corePaint)
        }
    }
}
