package io.github.rhythmcache.dioxamine.adb.shell

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.Log
import com.termux.view.TerminalView

/**
 * Subclass of [TerminalView] that:
 * 1. Strictly clips the Canvas in [onDraw] to prevent drawing outside allocated view bounds.
 * 2. Logs measured view width/height, computed rows/cols, and resize events.
 */
class BoundedTerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TerminalView(context, attrs) {

    companion object {
        private const val TAG = "BoundedTerminalView"
    }

    override fun onDraw(canvas: Canvas) {
        val saveCount = canvas.save()
        try {
            // Strictly clip to current view dimensions
            canvas.clipRect(0, 0, width, height)
            super.onDraw(canvas)
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        Log.d(TAG, "onSizeChanged: measuredWidth=$w, measuredHeight=$h, oldWidth=$oldw, oldHeight=$oldh")
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun updateSize() {
        val viewWidth = width
        val viewHeight = height
        super.updateSize()
        val emu = mEmulator
        if (emu != null) {
            Log.d(
                TAG,
                "updateSize: view=${viewWidth}x${viewHeight}, computedCols=${emu.columns}, computedRows=${emu.rows}"
            )
        }
    }
}
