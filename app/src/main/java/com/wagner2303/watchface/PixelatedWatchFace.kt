package com.wagner2303.watchface

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.util.Log
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.core.graphics.withRotation
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation

import java.lang.ref.WeakReference
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.*

/**
 * Updates rate in milliseconds for interactive mode. We update once a second to advance the
 * second hand.
 */
private const val INTERACTIVE_UPDATE_RATE_MS = 1000

/**
 * Handler message id for updating the time periodically in interactive mode.
 */
private const val MSG_UPDATE_TIME = 0

private const val HOUR_STROKE_WIDTH = 5f
private const val MINUTE_STROKE_WIDTH = 3f
private const val SECOND_TICK_STROKE_WIDTH = 2f

private const val PIXEL_GRID_SIZE = 33
/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't
 * shown. On devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient
 * mode. The watch face is drawn with less contrast in mute mode.
 *
 *
 * Important Note: Because watch face apps do not have a default Activity in
 * their project, you will need to set your Configurations to
 * "Do not launch Activity" for both the Wear and/or Application modules. If you
 * are unsure how to do this, please review the "Run Starter project" section
 * in the Google Watch Face Code Lab:
 * https://codelabs.developers.google.com/codelabs/watchface/index.html#0
 */
class PixelatedWatchFace : CanvasWatchFaceService() {

    override fun onCreateEngine(): Engine {
        return Engine()
    }

    private class EngineHandler(reference: PixelatedWatchFace.Engine) : Handler() {
        private val weakReference: WeakReference<PixelatedWatchFace.Engine> = WeakReference(reference)

        override fun handleMessage(msg: Message) {
            val engine = weakReference.get()
            if (engine != null) {
                when (msg.what) {
                    MSG_UPDATE_TIME -> engine.handleUpdateTimeMessage()
                }
            }
        }
    }

    inner class Engine : CanvasWatchFaceService.Engine() {

        private lateinit var calendar: Calendar

        private var timeZoneReceiver = false
        private var muteMode: Boolean = false
        private var centerX: Float = 0F
        private var centerY: Float = 0F

        private var secondHandLength: Float = 0F
        private var minuteHandLength: Float = 0F
        private var hourHandLength: Float = 0F

        private var handColor: Int = 0
        private var handHighlightColor: Int = 0

        private lateinit var hourPaint: Paint
        private lateinit var minutePaint: Paint
        private lateinit var secondPaint: Paint
        private lateinit var tickAndCirclePaint: Paint

        private var ambient: Boolean = false
        private var lowBitAmbient: Boolean = false
        private var burnInProtection: Boolean = false

        private var half = 1
        private var pixelSize = 1
        private var scale = 1f

        /* Handler to update the time once a second in interactive mode. */
        private val updateTimeHandler = EngineHandler(this)

        private val zoneReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                calendar.timeZone = TimeZone.getDefault()
                invalidate()
            }
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)

            setWatchFaceStyle(
                WatchFaceStyle.Builder(this@PixelatedWatchFace)
                    .setAcceptsTapEvents(true)
                    .build()
            )

            calendar = Calendar.getInstance()

            initializeWatchFace()
        }

        private fun initializeWatchFace() {
            /* Set defaults for colors */
            handColor = Color.WHITE
            handHighlightColor = Color.RED

            hourPaint = Paint().apply {
                color = handColor
                strokeWidth = HOUR_STROKE_WIDTH
                isAntiAlias = false
                strokeCap = Paint.Cap.ROUND
            }

            minutePaint = Paint().apply {
                color = handColor
                strokeWidth = MINUTE_STROKE_WIDTH
                isAntiAlias = false
                strokeCap = Paint.Cap.ROUND
            }

            secondPaint = Paint().apply {
                color = handHighlightColor
                strokeWidth = SECOND_TICK_STROKE_WIDTH
                isAntiAlias = false
                strokeCap = Paint.Cap.ROUND
            }

            tickAndCirclePaint = Paint().apply {
                color = handColor
                strokeWidth = SECOND_TICK_STROKE_WIDTH
                isAntiAlias = false
                style = Paint.Style.STROKE
            }
        }

        override fun onDestroy() {
            updateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            super.onDestroy()
        }

        override fun onPropertiesChanged(properties: Bundle) {
            super.onPropertiesChanged(properties)
            lowBitAmbient = properties.getBoolean(
                WatchFaceService.PROPERTY_LOW_BIT_AMBIENT, false
            )
            burnInProtection = properties.getBoolean(
                WatchFaceService.PROPERTY_BURN_IN_PROTECTION, false
            )
        }

        override fun onTimeTick() {
            super.onTimeTick()
            invalidate()
        }

        override fun onAmbientModeChanged(inAmbientMode: Boolean) {
            super.onAmbientModeChanged(inAmbientMode)
            ambient = inAmbientMode

            updateWatchHandStyle()

            // Check and trigger whether or not timer should be running (only
            // in active mode).
            updateTimer()
        }

        private fun updateWatchHandStyle() {
            if (ambient) {
                hourPaint.color = Color.WHITE
                minutePaint.color = Color.WHITE
                secondPaint.color = Color.WHITE
                tickAndCirclePaint.color = Color.WHITE
            } else {
                hourPaint.color = handColor
                minutePaint.color = handColor
                secondPaint.color = handHighlightColor
                tickAndCirclePaint.color = handColor
            }
        }

        override fun onInterruptionFilterChanged(interruptionFilter: Int) {
            super.onInterruptionFilterChanged(interruptionFilter)
            val inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE

            /* Dim display in mute mode. */
            if (muteMode != inMuteMode) {
                muteMode = inMuteMode
                hourPaint.alpha = if (inMuteMode) 100 else 255
                minutePaint.alpha = if (inMuteMode) 100 else 255
                secondPaint.alpha = if (inMuteMode) 80 else 255
                invalidate()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)

            /*
             * Find the coordinates of the center point on the screen, and ignore the window
             * insets, so that, on round watches with a "chin", the watch face is centered on the
             * entire screen, not just the usable portion.
             */
            centerX = width / 2f
            centerY = height / 2f

            pixelSize = width / PIXEL_GRID_SIZE
            half = round(width.toFloat() / PIXEL_GRID_SIZE.toFloat() / 2f).toInt()
            scale = width.toFloat() / (pixelSize * PIXEL_GRID_SIZE)
            Log.d("SCALE", scale.toString())

            /*
             * Calculate lengths of different hands based on watch screen size.
             */
            secondHandLength = (centerX * 0.875).toFloat()
            minuteHandLength = (centerX * 0.75).toFloat()
            hourHandLength = (centerX * 0.5).toFloat()
        }

        /**
         * Captures tap event (and tap type). The [WatchFaceService.TAP_TYPE_TAP] case can be
         * used for implementing specific logic to handle the gesture.
         */
        override fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {
            when (tapType) {
                WatchFaceService.TAP_TYPE_TOUCH -> {
                    // The user has started touching the screen.
                }
                WatchFaceService.TAP_TYPE_TOUCH_CANCEL -> {
                    // The user has started a different gesture or otherwise cancelled the tap.
                }
                WatchFaceService.TAP_TYPE_TAP ->
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(applicationContext, R.string.message, Toast.LENGTH_SHORT)
                        .show()
            }
            invalidate()
        }


        override fun onDraw(canvas: Canvas, bounds: Rect) {
            val now = System.currentTimeMillis()
            calendar.timeInMillis = now

            drawBackground(canvas)
            drawWatchFace(canvas)
        }

        private fun drawBackground(canvas: Canvas) {

//            if (ambient && (lowBitAmbient || burnInProtection)) {
                canvas.drawColor(Color.BLACK)
//            } else if (ambient) {
//                canvas.drawBitmap(grayBackgroundBitmap, 0f, 0f, backgroundPaint)
//            } else {
//                canvas.drawBitmap(backgroundBitmap, 0f, 0f, backgroundPaint)
//            }
        }

        private fun drawWatchFace(canvas: Canvas) {
            /*
             * These calculations reflect the rotation in degrees per unit of time, e.g.,
             * 360 / 60 = 6 and 360 / 12 = 30.
             */
            val seconds =
                calendar.get(Calendar.SECOND) + calendar.get(Calendar.MILLISECOND) / 1000.0
            val secondsRad = seconds * PI * 2 / 60

            val minutesRad = calendar.get(Calendar.MINUTE) * PI * 2 / 60

            val hourHandOffset = calendar.get(Calendar.MINUTE) * PI / 360
            val hoursRad = calendar.get(Calendar.HOUR) * PI / 6 + hourHandOffset

            canvas.
                withScale(-1f, -1f, centerX, centerY) {
                withRotation(180f, centerX, centerY) {
                    withTranslation(half.toFloat(), half.toFloat()) {
                        withScale(scale, scale) {
                            plotHand(hoursRad, 10, hourPaint)
                            plotHand(minutesRad, 16, minutePaint)
                            if (!ambient) { plotHand(secondsRad, 16, secondPaint) }
                        }
                    }
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)

            if (visible) {
                registerReceiver()
                /* Update time zone in case it changed while we weren't visible. */
                calendar.timeZone = TimeZone.getDefault()
                invalidate()
            } else {
                unregisterReceiver()
            }

            /* Check and trigger whether or not timer should be running (only in active mode). */
            updateTimer()
        }

        private fun registerReceiver() {
            if (timeZoneReceiver) {
                return
            }
            timeZoneReceiver = true
            val filter = IntentFilter(Intent.ACTION_TIMEZONE_CHANGED)
            this@PixelatedWatchFace.registerReceiver(zoneReceiver, filter)
        }

        private fun unregisterReceiver() {
            if (!timeZoneReceiver) {
                return
            }
            timeZoneReceiver = false
            this@PixelatedWatchFace.unregisterReceiver(zoneReceiver)
        }

        /**
         * Starts/stops the [.updateTimeHandler] timer based on the state of the watch face.
         */
        private fun updateTimer() {
            updateTimeHandler.removeMessages(MSG_UPDATE_TIME)
            if (shouldTimerBeRunning()) {
                updateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME)
            }
        }

        /**
         * Returns whether the [.updateTimeHandler] timer should be running. The timer
         * should only run in active mode.
         */
        private fun shouldTimerBeRunning(): Boolean {
            return isVisible && !ambient
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        fun handleUpdateTimeMessage() {
            invalidate()
            if (shouldTimerBeRunning()) {
                val timeMs = System.currentTimeMillis()
                val delayMs = INTERACTIVE_UPDATE_RATE_MS - timeMs % INTERACTIVE_UPDATE_RATE_MS
                updateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs)
            }
        }

        private fun Canvas.plotHand(rad: Double, size: Int, paint: Paint) {
//            angle = lambda i, count: i * math.pi * 2 / count
            val x = round(sin(rad) * size).toInt() + 16
            val y = round(-1 * cos(rad) * size).toInt() + 16
            plotLine(16, 16, x, y, paint)
        }

        private fun Canvas.plotLine(x0: Int, y0: Int, x1: Int, y1: Int, paint: Paint) {
            if (abs(y1 - y0) < abs(x1 - x0)) {
                if (x0 > x1) {
                    plotLineLow(x1, y1, x0, y0, paint)
                } else {
                    plotLineLow(x0, y0, x1, y1, paint)
                }
            } else {
                if (y0 > y1) {
                    plotLineHigh(x1, y1, x0, y0, paint)
                } else {
                    plotLineHigh(x0, y0, x1, y1, paint)
                }
            }
        }

        private fun Canvas.plotLineHigh(x0: Int, y0: Int, x1: Int, y1: Int, paint: Paint) {
            val xi = if (x1 < x0) -1 else 1
            var delta = 2 * (abs(x1 - x0)) - (y1 - y0)
            var x = x0
            for (y in y0..y1) {
                plot(x, y, paint)
                if (delta > 0){
                    x += xi
                    delta -= 2 * (y1 - y0)
                }
                delta += 2 * (abs(x1 - x0))
            }
        }

        private fun Canvas.plotLineLow(x0: Int, y0: Int, x1: Int, y1: Int, paint: Paint) {
            val yi = if (y1 < y0) -1 else 1
            var delta = 2 * (abs(y1 - y0)) - (x1 - x0)
            var y = y0
            for (x in x0..x1) {
                plot(x, y, paint)
                if (delta > 0){
                    y += yi
                    delta -= 2 * (x1 - x0)
                }
                delta += 2 * (abs(y1 - y0))
            }
        }

        private fun Canvas.plot(x: Int, y: Int, paint: Paint) {
            val rect = Rect(
                x * pixelSize - half,
                y * pixelSize - half,
                x * pixelSize + half,
                y * pixelSize + half
            )
            drawRect(rect, paint)
        }
    }
}


