package com.variableduck.inkwell.transcriber

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.variableduck.inkwell.transcriber.ui.InkwellNavHost
import com.variableduck.inkwell.transcriber.ui.theme.InkwellTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            InkwellTheme {
                InkwellNavHost()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Resume any transcription that was interrupted (app killed, time limit reached, …).
        container.resumePendingTranscriptions()
    }
}
