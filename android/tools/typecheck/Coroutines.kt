@file:Suppress("unused", "UNUSED_PARAMETER")
package kotlinx.coroutines
import kotlin.coroutines.Continuation
interface CoroutineScope
interface CancellableContinuation<in T> : Continuation<T> {
    val isActive: Boolean
    fun invokeOnCancellation(handler: (cause: Throwable?) -> Unit)
}
suspend inline fun <T> suspendCancellableCoroutine(crossinline block: (CancellableContinuation<T>) -> Unit): T = TODO()
suspend fun <T> withTimeoutOrNull(timeMillis: Long, block: suspend CoroutineScope.() -> T): T? = TODO()
fun <T> runBlocking(block: suspend CoroutineScope.() -> T): T = TODO()

// Used by LockService to read steps off the main thread (real types: CompletableJob, CoroutineDispatcher, Job).
fun SupervisorJob(): kotlin.coroutines.CoroutineContext = TODO()
object Dispatchers { val Default: kotlin.coroutines.CoroutineContext = TODO() }
fun CoroutineScope(context: kotlin.coroutines.CoroutineContext): CoroutineScope = TODO()
fun CoroutineScope.launch(block: suspend CoroutineScope.() -> Unit): Any = TODO()
fun CoroutineScope.cancel(): Unit = TODO()
