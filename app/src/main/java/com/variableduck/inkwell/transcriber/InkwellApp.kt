package com.variableduck.inkwell.transcriber

import android.app.Application
import com.variableduck.inkwell.transcriber.util.Notifications

class InkwellApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        container = AppContainer(this)
    }
}

val android.content.Context.container: AppContainer
    get() = (applicationContext as InkwellApp).container
