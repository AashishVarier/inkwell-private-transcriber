package com.variableduck.inkwell.transcriber

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.variableduck.inkwell.transcriber.ui.RouteStores
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A note's ViewModel lives while the note is on the back stack, and is cleared when it's closed. */
class RouteStoresTest {

    class Probe : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = Probe() as T
    }

    private fun probe(owner: ViewModelStoreOwner): Probe = ViewModelProvider(owner, factory)[Probe::class.java]

    @Test
    fun closingANoteClearsItsViewModel() {
        val stores = RouteStores()
        val vm = probe(stores.ownerFor("note/a"))

        stores.retainOnly(listOf("library"))

        assertTrue(vm.cleared)
    }

    @Test
    fun aNoteStillOnTheStackKeepsItsViewModel() {
        val stores = RouteStores()
        val vm = probe(stores.ownerFor("note/a"))

        // e.g. Models was opened from the note and then closed again.
        stores.retainOnly(listOf("library", "note/a"))

        assertFalse(vm.cleared)
        assertSame(vm, probe(stores.ownerFor("note/a")))
    }

    @Test
    fun reopeningAClosedNoteStartsFresh() {
        val stores = RouteStores()
        val first = probe(stores.ownerFor("note/a"))
        stores.retainOnly(listOf("library"))

        val second = probe(stores.ownerFor("note/a"))

        assertNotSame(first, second)
        assertFalse(second.cleared)
    }
}
