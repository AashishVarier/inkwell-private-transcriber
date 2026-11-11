package com.variableduck.inkwell.transcriber.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.variableduck.inkwell.transcriber.ui.library.LibraryScreen
import com.variableduck.inkwell.transcriber.ui.models.ModelsScreen
import com.variableduck.inkwell.transcriber.ui.note.NoteScreen
import com.variableduck.inkwell.transcriber.ui.settings.SettingsScreen

/** Screens, encoded as strings so the back stack survives configuration changes. */
object Routes {
    const val LIBRARY = "library"
    const val MODELS = "models"
    const val SETTINGS = "settings"
    fun note(id: String) = "note/$id"
}

class Navigator(private val push: (String) -> Unit, private val pop: () -> Unit) {
    fun open(route: String) = push(route)
    fun back() = pop()
}

@Composable
fun InkwellNavHost() {
    var stack by rememberSaveable { mutableStateOf(arrayListOf(Routes.LIBRARY)) }
    val navigator = Navigator(
        push = { route -> if (stack.last() != route) stack = ArrayList(stack + route) },
        pop = { if (stack.size > 1) stack = ArrayList(stack.dropLast(1)) },
    )
    BackHandler(enabled = stack.size > 1) { navigator.back() }

    val route = stack.last()
    when {
        route == Routes.MODELS -> ModelsScreen(navigator)
        route == Routes.SETTINGS -> SettingsScreen(navigator)
        route.startsWith("note/") -> NoteScreen(route.removePrefix("note/"), navigator)
        else -> LibraryScreen(navigator)
    }
}
