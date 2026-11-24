package com.aibuilt.baadshahlens

import android.app.Application
import com.aibuilt.baadshahlens.data.AppContainer

class BaadshahApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
