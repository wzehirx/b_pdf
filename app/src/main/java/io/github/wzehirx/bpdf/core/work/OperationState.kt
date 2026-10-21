package io.github.wzehirx.bpdf.core.work

sealed interface OperationState<out T> {
    data object Idle : OperationState<Nothing>

    /** [progress] is in 0..1, or null when the operation is indeterminate. */
    data class Running(val progress: Float?) : OperationState<Nothing>

    data class Success<T>(val value: T) : OperationState<T>

    data class Failed(val error: Throwable) : OperationState<Nothing>
}
