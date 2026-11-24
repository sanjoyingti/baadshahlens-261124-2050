package com.aibuilt.baadshahlens

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.aibuilt.baadshahlens.camera.HardwareKeys
import com.aibuilt.baadshahlens.camera.KeyPhase
import com.aibuilt.baadshahlens.ui.PikaApp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PikaApp()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val volume = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (HardwareKeys.consuming && volume) {
            when (event.action) {
                KeyEvent.ACTION_DOWN ->
                    HardwareKeys.emit(if (event.repeatCount == 0) KeyPhase.DOWN else KeyPhase.REPEAT)
                KeyEvent.ACTION_UP -> HardwareKeys.emit(KeyPhase.UP)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
