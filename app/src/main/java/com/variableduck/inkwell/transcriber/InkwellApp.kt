package com.variableduck.inkwell.transcriber

import android.app.Application
import com.variableduck.inkwell.transcriber.share.ShareExport
import com.variableduck.inkwell.transcriber.util.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class InkwellApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        container = AppContainer(this)
        // Old copies made for "Share as .txt file" shouldn't stay on the phone indefinitely.
        container.scope.launch(Dispatchers.IO) { ShareExport.purgeStale(cacheDir) }
    }
}

val android.content.Context.container: AppContainer
    get() = (applicationContext as InkwellApp).container
