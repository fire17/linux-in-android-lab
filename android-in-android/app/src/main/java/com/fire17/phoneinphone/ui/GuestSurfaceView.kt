package com.fire17.phoneinphone.ui

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.fire17.phoneinphone.backend.PhoneBackend
import com.fire17.phoneinphone.backend.TouchAction

/**
 * The guest's screen. Converts host touches to normalized guest coordinates (multi-touch, real
 * pointer ids) and exposes an InputConnection so the HOST soft keyboard types into the guest —
 * committed text (incl. Hebrew) goes to backend.sendText, backspace/enter as key events.
 */
class GuestSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs) {

    var backend: PhoneBackend? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = backend ?: return false
        if (width == 0 || height == 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) requestFocus()
                send(b, e, e.actionIndex, TouchAction.DOWN)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> send(b, e, e.actionIndex, TouchAction.UP)
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) send(b, e, i, TouchAction.MOVE)
            MotionEvent.ACTION_CANCEL -> for (i in 0 until e.pointerCount) send(b, e, i, TouchAction.UP)
        }
        return true
    }

    private fun send(b: PhoneBackend, e: MotionEvent, i: Int, a: TouchAction) {
        val x = e.getX(i) / width
        val y = e.getY(i) / height
        b.sendTouch(a, e.getPointerId(i).toLong(), x, y, e.getPressure(i).coerceIn(0f, 1f).let { if (it == 0f) 1f else it })
    }

    // ---- keyboard (physical keys while focused)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = forwardKey(event) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = forwardKey(event) || super.onKeyUp(keyCode, event)

    private fun forwardKey(event: KeyEvent): Boolean {
        val b = backend ?: return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return false // the shell's own back button handles this
        b.sendKey(event.action, event.keyCode, event.metaState)
        return true
    }

    // ---- soft keyboard
    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                backend?.sendText(text.toString())
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtMost(64)) { tap(KeyEvent.KEYCODE_DEL) }
                repeat(afterLength.coerceAtMost(64)) { tap(KeyEvent.KEYCODE_FORWARD_DEL) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                backend?.sendKey(event.action, event.keyCode, event.metaState)
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                tap(KeyEvent.KEYCODE_ENTER)
                return true
            }

            private fun tap(code: Int) {
                backend?.sendKey(KeyEvent.ACTION_DOWN, code)
                backend?.sendKey(KeyEvent.ACTION_UP, code)
            }
        }
    }
}
