package io.github.wzehirx.bpdf.core.work

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Runs one long operation at a time in [scope] (typically a ViewModel scope), so it survives
 * configuration changes and collector cancellation. Starting a new operation cancels the previous.
 */
class OperationRunner<T>(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<OperationState<T>>(OperationState.Idle)
    val state: StateFlow<OperationState<T>> = _state

    private var job: Job? = null
    private var generation = 0

    fun start(block: suspend (progress: (Float) -> Unit) -> T) {
        job?.cancel()
        val mine = ++generation
        _state.value = OperationState.Running(null)
        job = scope.launch(dispatcher) {
            try {
                val value = block { p ->
                    if (mine == generation) _state.value = OperationState.Running(p.coerceIn(0f, 1f))
                }
                if (mine == generation) _state.value = OperationState.Success(value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (mine == generation) _state.value = OperationState.Failed(e)
            }
        }
    }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        _state.value = OperationState.Idle
    }

    fun reset() = cancel()
}
