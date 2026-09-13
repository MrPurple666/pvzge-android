// gardendless-android

// Copyright (C) 2026  Caten Hu

// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.

package com.pvzge.gardendless

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var maxRenderEdge: Int = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    var fullscreen: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val child = getChildAt(0) ?: return

        val screenWidth = measuredWidth
        val screenHeight = measuredHeight
        var targetWidth = screenWidth
        var targetHeight = screenHeight

        if (!fullscreen) {
            when {
                screenWidth * MAX_ASPECT_H > screenHeight * MAX_ASPECT_W -> {
                    targetWidth = screenHeight * MAX_ASPECT_W / MAX_ASPECT_H
                }
                screenWidth * MIN_ASPECT_H < screenHeight * MIN_ASPECT_W -> {
                    targetHeight = screenWidth * MIN_ASPECT_H / MIN_ASPECT_W
                }

            }
        }

        val renderSize = RenderSize.fit(targetWidth, targetHeight, maxRenderEdge)
        child.measure(
            MeasureSpec.makeMeasureSpec(renderSize[0], MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(renderSize[1], MeasureSpec.EXACTLY)
        )
        child.pivotX = renderSize[0] / 2f
        child.pivotY = renderSize[1] / 2f
        child.scaleX = if (renderSize[0] > 0) targetWidth.toFloat() / renderSize[0] else 1f
        child.scaleY = if (renderSize[1] > 0) targetHeight.toFloat() / renderSize[1] else 1f
    }

    private companion object {

        const val MAX_ASPECT_W = 171
        const val MAX_ASPECT_H = 90

        const val MIN_ASPECT_W = 160
        const val MIN_ASPECT_H = 100
    }
}
