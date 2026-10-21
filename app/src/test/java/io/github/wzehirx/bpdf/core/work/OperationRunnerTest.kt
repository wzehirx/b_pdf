package io.github.wzehirx.bpdf.core.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OperationRunnerTest {
    private fun TestScope.runner() =
        OperationRunner<String>(this, StandardTestDispatcher(testScheduler))

    @Test
    fun goesIdleRunningSuccess() = runTest {
        val runner = runner()
        assertEquals(OperationState.Idle, runner.state.value)
        runner.start { "done" }
        assertEquals(OperationState.Running(null), runner.state.value)
        advanceUntilIdle()
        assertEquals(OperationState.Success("done"), runner.state.value)
    }

    private fun TestScope.progressAfter(value: Float): OperationState<String> {
        val r = runner()
        val gate = CompletableDeferred<Unit>()
        r.start { p -> p(value); gate.await(); "x" }
        advanceUntilIdle()
        val seen = r.state.value
        gate.complete(Unit)
        advanceUntilIdle()
        return seen
    }

    @Test
    fun progressIsReported() = runTest {
        assertEquals(OperationState.Running(0.5f), progressAfter(0.5f))
    }

    @Test
    fun progressAboveOneIsCoerced() = runTest {
        assertEquals(OperationState.Running(1f), progressAfter(7f))
    }

    @Test
    fun negativeProgressIsCoercedToZero() = runTest {
        assertEquals(OperationState.Running(0f), progressAfter(-3f))
    }

    @Test
    fun nanProgressIsTreatedAsZero() = runTest {
        assertEquals(OperationState.Running(0f), progressAfter(Float.NaN))
    }

    @Test
    fun staleProgressAfterCancelDoesNotOverwriteIdle() = runTest {
        val runner = runner()
        var report: ((Float) -> Unit)? = null
        runner.start { p -> report = p; CompletableDeferred<Unit>().await(); "x" }
        advanceUntilIdle()
        runner.cancel()
        report!!.invoke(0.9f)
        advanceUntilIdle()
        assertEquals(OperationState.Idle, runner.state.value)
    }

    @Test
    fun exceptionBecomesFailed() = runTest {
        val runner = runner()
        val boom = IllegalStateException("boom")
        runner.start { throw boom }
        advanceUntilIdle()
        assertEquals(OperationState.Failed(boom), runner.state.value)
    }

    @Test
    fun cancelReturnsToIdleAndCancelsBlock() = runTest {
        val runner = runner()
        var cancelled = false
        runner.start {
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                cancelled = true
            }
            "never"
        }
        advanceUntilIdle()
        runner.cancel()
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals(OperationState.Idle, runner.state.value)
    }

    @Test
    fun startWhileRunningCancelsPrevious() = runTest {
        val runner = runner()
        var firstCancelled = false
        runner.start {
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                firstCancelled = true
            }
            "first"
        }
        advanceUntilIdle()
        runner.start { "second" }
        advanceUntilIdle()
        assertTrue(firstCancelled)
        assertEquals(OperationState.Success("second"), runner.state.value)
    }

    @Test
    fun resetReturnsToIdle() = runTest {
        val runner = runner()
        runner.start { "a" }
        advanceUntilIdle()
        runner.reset()
        assertEquals(OperationState.Idle, runner.state.value)
    }

    @Test
    fun survivesCollectorCancellation() = runTest {
        val runner = runner()
        val gate = CompletableDeferred<Unit>()
        val collectorScope = TestScope(testScheduler)
        val collector: Job = collectorScope.launch { runner.state.collect { } }
        runner.start { gate.await(); "ok" }
        advanceUntilIdle()
        collectorScope.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(OperationState.Success("ok"), runner.state.value)
        assertTrue(collector.isCancelled)
    }
}
