package com.wcam.app.auto

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

/**
 * Android Auto Session hosting the single backup camera screen.
 */
class WCamCarSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        return WCamCameraScreen(carContext)
    }
}
