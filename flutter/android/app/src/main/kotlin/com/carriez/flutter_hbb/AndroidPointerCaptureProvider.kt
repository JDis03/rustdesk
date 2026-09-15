package com.carriez.flutter_hbb

import android.app.Activity
import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.flutter.embedding.android.FlutterView
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.O)
class AndroidPointerCaptureProvider(
    private val activity: Activity,
    private val flutterView: FlutterView,
    private val sendEvent: (String, Map<String, Any>) -> Unit,
) : InputManager.InputDeviceListener {
    companion object {
        private const val TAG = "PointerCapture"
        private const val RECAPTURE_DELAY_MS = 500L
        private const val MAX_UNHANDLED_MOUSE_EVENTS = 8
    }

    private val applicationContext = activity.applicationContext
    private val packageManager = applicationContext.packageManager
    private val inputManager =
        applicationContext.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val handler = Handler(Looper.getMainLooper())
    private var enabled = false
    private var listenerRegistered = false
    private var captureActive = false
    private var retryBlocked = false
    private var unhandledMouseEvents = 0
    private val recapture = Runnable { requestCaptureIfPossible() }
    private var previousFocus: View? = null
    private val captureView = CapturedPointerView(activity, flutterView) { event ->
        handleCapturedPointer(event)
    }.apply {
        layoutParams = FrameLayout.LayoutParams(1, 1)
        translationX = -2f
        translationY = -2f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    init {
        (activity.window.decorView as ViewGroup).addView(captureView)
    }

    fun setEnabled(value: Boolean): Boolean {
        handler.removeCallbacks(recapture)
        if (!value) {
            enabled = false
            if (listenerRegistered) {
                inputManager.unregisterInputDeviceListener(this)
                listenerRegistered = false
            }
            releaseCapture()
            Log.d(TAG, "disabled")
            return true
        }

        retryBlocked = false
        unhandledMouseEvents = 0
        enabled = true
        if (!listenerRegistered) {
            inputManager.registerInputDeviceListener(this, handler)
            listenerRegistered = true
        }
        val compatible = hasCompatibleDevice()
        if (compatible) {
            requestCaptureIfPossible()
        }
        Log.d(TAG, "enabled, compatible=$compatible")
        return compatible
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        handler.removeCallbacks(recapture)
        if (hasFocus && enabled && !retryBlocked) {
            handler.postDelayed(recapture, RECAPTURE_DELAY_MS)
        }
    }

    fun onPointerCaptureChanged(active: Boolean) {
        setCaptureActive(active)
    }

    fun destroy() {
        setEnabled(false)
        (captureView.parent as? ViewGroup)?.removeView(captureView)
    }

    fun handleCapturedPointer(event: MotionEvent): Boolean {
        if (!enabled) return false

        val toolType = if (event.pointerCount > 0) event.getToolType(0) else -1
        if (isMouseLike(event)) {
            captureView.requestUnbufferedDispatch(event)
        }

        val mouseRelative = event.source == InputDevice.SOURCE_MOUSE_RELATIVE &&
            event.pointerCount == 1 &&
            toolType == MotionEvent.TOOL_TYPE_MOUSE
        val capturedTouchpad = (captureActive || captureView.hasPointerCapture()) &&
            event.isFromSource(InputDevice.SOURCE_TOUCHPAD) &&
            event.pointerCount > 0
        val recognized = mouseRelative || capturedTouchpad
        if (!recognized) {
            handleUnhandledCapturedEvent(event)
            return false
        }

        setCaptureActive(true)
        val xAxis = if (mouseRelative) MotionEvent.AXIS_X else MotionEvent.AXIS_RELATIVE_X
        val yAxis = if (mouseRelative) MotionEvent.AXIS_Y else MotionEvent.AXIS_RELATIVE_Y
        val dx = sumAxis(event, xAxis)
        val dy = sumAxis(event, yAxis)
        val delivered = runCatching {
            sendEvent(
                "on_captured_pointer",
                mapOf(
                    "dx" to dx,
                    "dy" to dy,
                    "button_state" to event.buttonState,
                    "vscroll" to sumAxis(event, MotionEvent.AXIS_VSCROLL),
                    "hscroll" to sumAxis(event, MotionEvent.AXIS_HSCROLL),
                ),
            )
        }.isSuccess
        if (delivered) {
            unhandledMouseEvents = 0
        } else {
            handleUnhandledCapturedEvent(event)
        }
        return delivered
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        onInputDevicesChanged()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        onInputDevicesChanged()
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        onInputDevicesChanged()
    }

    private fun onInputDevicesChanged() {
        if (!enabled) return
        retryBlocked = false
        unhandledMouseEvents = 0
        if (hasCompatibleDevice()) {
            requestCaptureIfPossible()
        } else {
            releaseCapture()
        }
    }

    private fun requestCaptureIfPossible() {
        if (!enabled || retryBlocked || captureActive || !captureView.hasWindowFocus() ||
            !captureView.isAttachedToWindow || !hasCompatibleDevice()
        ) {
            return
        }
        if (!captureView.hasFocus()) {
            previousFocus = activity.currentFocus
            if (!captureView.requestFocus()) return
        }
        runCatching { captureView.requestPointerCapture() }
            .onFailure { Log.w(TAG, "request failed", it) }
    }

    private fun releaseCapture() {
        handler.removeCallbacks(recapture)
        if (captureView.hasPointerCapture()) {
            runCatching { captureView.releasePointerCapture() }
                .onFailure { Log.w(TAG, "release failed", it) }
        }
        setCaptureActive(false)
        val focus = previousFocus
        previousFocus = null
        if (focus?.isAttachedToWindow == true) {
            focus.requestFocus()
        } else if (flutterView.isAttachedToWindow) {
            flutterView.requestFocus()
        }
    }

    private fun setCaptureActive(active: Boolean) {
        if (captureActive == active) return
        captureActive = active
        runCatching {
            sendEvent("on_pointer_capture_changed", mapOf("active" to active))
        }.onFailure { Log.w(TAG, "state notification failed", it) }
        Log.d(TAG, "active=$active")
    }

    private fun handleUnhandledCapturedEvent(event: MotionEvent) {
        if (!captureActive || !isMouseLike(event)) return
        unhandledMouseEvents++
        if (unhandledMouseEvents < MAX_UNHANDLED_MOUSE_EVENTS) return
        retryBlocked = true
        Log.w(TAG, "unsupported captured mouse events; releasing capture")
        releaseCapture()
    }

    private fun hasCompatibleDevice(): Boolean {
        val allowTouchscreenDevices =
            packageManager.hasSystemFeature("org.chromium.arc.device_management")
        return InputDevice.getDeviceIds().any { deviceId ->
            inputManager.getInputDevice(deviceId)?.let { device ->
                if (device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) &&
                    !allowTouchscreenDevices
                ) {
                    false
                } else {
                    device.supportsSource(InputDevice.SOURCE_MOUSE) ||
                        device.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
                        device.supportsSource(InputDevice.SOURCE_TOUCHPAD)
                }
            } == true
        }
    }

    private fun isMouseLike(event: MotionEvent): Boolean {
        return (event.pointerCount > 0 &&
            event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
            event.isFromSource(InputDevice.SOURCE_TOUCHPAD)
    }

    private fun sumAxis(event: MotionEvent, axis: Int): Double {
        var value = event.getAxisValue(axis).toDouble()
        for (historyIndex in 0 until event.historySize) {
            value += event.getHistoricalAxisValue(axis, 0, historyIndex).toDouble()
        }
        return value
    }
}


@RequiresApi(Build.VERSION_CODES.O)
private class CapturedPointerView(
    context: Context,
    private val keyTarget: View,
    private val pointerHandler: (MotionEvent) -> Boolean,
) : View(context) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCapturedPointerEvent(event: MotionEvent): Boolean {
        return pointerHandler(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return keyTarget.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }
}
