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

    // All state writes and generation checks happen under [lock], so a stale (cancelled or
    // superseded) job can never overwrite a newer state, e.g. Idle after cancel().
    private val lock = Any()
    private var job: Job? = null
    private var generation = 0

    private fun setIfCurrent(mine: Int, value: OperationState<T>) {
        synchronized(lock) { if (mine == generation) _state.value = value }
    }

    fun start(block: suspend (progress: (Float) -> Unit) -> T) {
        synchronized(lock) {
            job?.cancel()
            val mine = ++generation
            _state.value = OperationState.Running(null)
            job = scope.launch(dispatcher) {
                try {
                    // Progress is clamped to 0..1; NaN is treated as 0.
                    val value = block { p ->
                        setIfCurrent(mine, OperationState.Running(if (p.isNaN()) 0f else p.coerceIn(0f, 1f)))
                    }
                    setIfCurrent(mine, OperationState.Success(value))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    setIfCurrent(mine, OperationState.Failed(e))
                }
            }
        }
    }

    fun cancel() {
        synchronized(lock) {
            generation++
            job?.cancel()
            job = null
            _state.value = OperationState.Idle
        }
    }

    fun reset() = cancel()
}
