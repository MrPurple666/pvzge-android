// PvZ2 Gardendless Android Port — Custom WebView for touch-to-mouse conversion
// Copyright (C) 2026  Open Source Gardendless Contributors
// License: GPL-3.0

package com.pvzge.gardendless

import android.content.Context
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import kotlin.math.abs

class MouseGameWebView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : WebView(context, attrs) {

    // #11: Removed dead code (mainHandler, isTouching)
    private var isDragging = false
    private var lastScrollY = 0f
    private var lastScrollX = 0f  // #8: horizontal scroll tracking
    private var touchStartCenterX = 0f
    private var touchStartCenterY = 0f
    private var hasMovedEnough = false
    private val moveThreshold = 20f
    private var maxTouches = 0
    private var touchStartTime = 0L  // #10: long-press detection
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var longPressTriggered = false
    private val longPressThreshold = 400L  // ms
    private val density = context.resources.displayMetrics.density

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.source == InputDevice.SOURCE_MOUSE) {
            return super.dispatchTouchEvent(event)
        }

        val action = event.actionMasked
        val pointerCount = event.pointerCount
        if (pointerCount > maxTouches) maxTouches = pointerCount

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (pointerCount == 1) {
                    touchStartTime = System.currentTimeMillis()  // #10
                    touchStartX = event.x
                    touchStartY = event.y
                    longPressTriggered = false
                    injectJsMouseEvent(event.x, event.y, "mousedown", 0)
                    isDragging = true
                    return super.dispatchTouchEvent(event)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerCount == 2) {
                    isDragging = false
                    hasMovedEnough = false
                    longPressTriggered = true  // #10: cancel long-press on second finger
                    val cx = (event.getX(0) + event.getX(1)) / 2
                    val cy = (event.getY(0) + event.getY(1)) / 2
                    touchStartCenterX = cx
                    touchStartCenterY = cy
                    lastScrollY = cy
                    lastScrollX = cx  // #8
                    injectMouseEventAt(cx, cy, MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (pointerCount == 1 && isDragging) {
                    injectMouseEventAt(event.x, event.y, MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY)
                } else if (pointerCount == 2) {
                    val cx = (event.getX(0) + event.getX(1)) / 2
                    val cy = (event.getY(0) + event.getY(1)) / 2
                    // #8: Track both horizontal and vertical deltas
                    val dx = abs(cx - touchStartCenterX)
                    val dy = abs(cy - touchStartCenterY)
                    val totalDelta = dx + dy

                    if (hasMovedEnough || totalDelta > moveThreshold) {
                        hasMovedEnough = true
                        // Dominant axis determines scroll direction
                        if (dy >= dx) {
                            val scrollDelta = cy - lastScrollY
                            injectScrollEventAt(touchStartCenterX, touchStartCenterY, scrollDelta * 2)
                            lastScrollY = cy
                        } else {
                            val scrollDelta = cx - lastScrollX
                            injectHScrollEventAt(touchStartCenterX, touchStartCenterY, scrollDelta * 2)
                            lastScrollX = cx
                        }
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (action == MotionEvent.ACTION_UP) {
                    if (maxTouches == 1 && isDragging) {
                        // #10: Long-press detection
                        val elapsed = System.currentTimeMillis() - touchStartTime
                        val moved = abs(event.x - touchStartX) + abs(event.y - touchStartY)
                        if (!longPressTriggered && elapsed > longPressThreshold && moved < moveThreshold * 2) {
                            // Long press → right-click
                            injectRightClickAt(touchStartX, touchStartY)
                            // Also fire mouseup for the original mousedown
                            injectJsMouseEvent(event.x, event.y, "mouseup", 0)
                        } else {
                            injectJsMouseEvent(event.x, event.y, "mouseup", 0)
                        }
                        maxTouches = 0
                        isDragging = false
                        hasMovedEnough = false
                        return super.dispatchTouchEvent(event)
                    } else if (maxTouches == 2 && !hasMovedEnough) {
                        injectRightClickAt(touchStartCenterX, touchStartCenterY)
                    }
                    maxTouches = 0
                    isDragging = false
                    hasMovedEnough = false
                }
            }

            // #7: ACTION_CANCEL — reset all state to prevent stuck dragging
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                maxTouches = 0
                hasMovedEnough = false
                longPressTriggered = false
            }
        }
        return true
    }

    private fun injectJsMouseEvent(x: Float, y: Float, type: String, button: Int) {
        val cssX = x / density
        val cssY = y / density
        val js = """
(function() {
    var element = document.getElementById("GameCanvas");
    var ev = new MouseEvent('$type', {
        view: window, bubbles: true, cancelable: true,
        screenX: $x, screenY: $y,
        clientX: $cssX, clientY: $cssY,
        button: $button
    });
    element.dispatchEvent(ev);
})();
        """.trimIndent()
        this.evaluateJavascript(js, null)
    }

    private fun injectMouseEventAt(x: Float, y: Float, action: Int, buttonState: Int) {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
        })
        val ev = MotionEvent.obtain(
            System.currentTimeMillis(), System.currentTimeMillis(), action,
            1, props, coords, 0, buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        super.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun injectScrollEventAt(x: Float, y: Float, delta: Float) {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            setAxisValue(MotionEvent.AXIS_VSCROLL, delta / 15f)
        })
        val ev = MotionEvent.obtain(
            System.currentTimeMillis(), System.currentTimeMillis(), MotionEvent.ACTION_SCROLL,
            1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        super.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    // #8: Horizontal scroll injection
    private fun injectHScrollEventAt(x: Float, y: Float, delta: Float) {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            setAxisValue(MotionEvent.AXIS_HSCROLL, delta / 15f)
        })
        val ev = MotionEvent.obtain(
            System.currentTimeMillis(), System.currentTimeMillis(), MotionEvent.ACTION_SCROLL,
            1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        super.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    // #9: Right-click now moves cursor to tap position first
    private fun injectRightClickAt(x: Float, y: Float) {
        injectMouseEventAt(x, y, MotionEvent.ACTION_MOVE, MotionEvent.BUTTON_PRIMARY)
        injectJsMouseEvent(x, y, "mousedown", 2)
        injectJsMouseEvent(x, y, "mouseup", 2)
    }
}
