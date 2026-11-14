package com.foxhole.guard.router.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.foxhole.guard.router.RouterControlCoordinator
import com.foxhole.guard.router.RouterPhoneTransferRequest
import com.foxhole.guard.router.RouterPhoneTransferCatalog
import com.foxhole.guard.router.RouterPhoneTransferResult
import com.foxhole.guard.router.RouterProfileSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class RouterPhonePickerState(private val coordinator: RouterControlCoordinator,
    private val scope: CoroutineScope) {
    var catalog by mutableStateOf<RouterPhoneTransferCatalog?>(null)
        private set
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set
    var loading by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    private var generation = 0L
    private var task: Job? = null
    private var exporter: (suspend (RouterPhoneTransferRequest) -> RouterProfileSelection?)? = null
    val busy: Boolean get() = loading || sending
    val current: Boolean get() = catalog?.let(coordinator::phoneTransferCatalogCurrent) == true

    private suspend fun access(request: ((() -> Unit) -> Unit)) = withTimeout(30_000) {
        val ready = CompletableDeferred<Unit>()
        var live = true
        try {
            request { if (live && scope.isActive) ready.complete(Unit) }
            ready.await()
        } finally { live = false; ready.cancel() }
    }

    fun load(ids: List<RouterPhoneTransferRequest>, export: suspend (RouterPhoneTransferRequest) -> RouterProfileSelection?, request: ((() -> Unit) -> Unit)) {
        cancel()
        val ticket = generation
        exporter = export
        catalog = null; selected = emptySet(); loading = true; failed = false
        task = scope.launch {
            try {
                access(request)
                val result = coordinator.phoneTransferCatalog(ids, export)
                if (ticket == generation) { catalog = result; failed = result == null }
            } catch (_: TimeoutCancellationException) {
                if (ticket == generation) failed = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (ticket == generation) failed = true }
            finally { if (ticket == generation) loading = false }
        }
    }

    fun toggle(id: String) {
        if (busy || !current || catalog?.choices?.none { it.choiceId == id } != false) return
        if (id !in selected && selected.size >= 256) return
        selected = if (id in selected) selected - id else selected + id
    }

    fun save(request: ((() -> Unit) -> Unit), onResult: (RouterPhoneTransferResult) -> Unit) {
        if (busy || !current || selected.isEmpty()) return
        val capture = catalog ?: return
        val export = exporter ?: return
        val ids = selected.toSet()
        val ticket = generation
        sending = true; failed = false
        task = scope.launch {
            try {
                access(request)
                val result = coordinator.sendPhoneProfileChoices(capture, ids, export)
                if (ticket == generation && coordinator.phoneTransferCatalogCurrent(capture)) {
                    selected -= result.confirmedChoiceIds
                    failed = !result.complete
                    onResult(result)
                }
            } catch (_: TimeoutCancellationException) {
                if (ticket == generation) failed = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (ticket == generation) failed = true }
            finally { if (ticket == generation) sending = false }
        }
    }

    fun cancel() {
        generation++; task?.cancel(); task = null
        loading = false; sending = false
    }
}
