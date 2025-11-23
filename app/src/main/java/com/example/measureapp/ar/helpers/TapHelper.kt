package com.example.measureapp.ar.helpers

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.util.concurrent.ArrayBlockingQueue

/**
 * Thread-safe tap queue used by the ARCore samples.
 * Converts touch events into a queue that the renderer can poll each frame.
 */
class TapHelper(context: Context) : GestureDetector.SimpleOnGestureListener(), View.OnTouchListener {

    private val gestureDetector = GestureDetector(context, this)
    private val motionEventQueue = ArrayBlockingQueue<MotionEvent>(16)

    override fun onTouch(view: View?, event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event)
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        motionEventQueue.offer(MotionEvent.obtain(e))
        return true
    }

    override fun onDown(e: MotionEvent): Boolean {
        return true
    }

    fun poll(): MotionEvent? {
        return motionEventQueue.poll()
    }
}
