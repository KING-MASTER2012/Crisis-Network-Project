package org.crisisnetwork.android.core.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.crisisnetwork.android.core.runtime.clock.CoreClock
import org.crisisnetwork.android.core.runtime.failure.CoreFailure
import org.crisisnetwork.android.core.runtime.failure.CoreFailureException
import org.crisisnetwork.android.core.runtime.failure.toCoreFailure
import org.crisisnetwork.android.core.runtime.transport.Transport
import org.crisisnetwork.android.core.runtime.transport.TransportEvent
import uniffi.crisis_core.CardDetails
import uniffi.crisis_core.ContactInfo
import uniffi.crisis_core.CoreNotification
import uniffi.crisis_core.CoreOutput
import uniffi.crisis_core.CrisisNode
import uniffi.crisis_core.HistoryPageInfo
import uniffi.crisis_core.NodeStats
import uniffi.crisis_core.PublishOutcome
import uniffi.crisis_core.TransportCommand
import uniffi.crisis_core.TrustLevel

/** Lifecycle of a [NodeRuntime]. */
sealed interface RuntimeState {
    data object Created : RuntimeState

    data object Running : RuntimeState

    data object Stopped : RuntimeState

    /** The node hit a fatal failure and shut itself down. Call [NodeRuntime.stop] to release the rest. */
    data class Failed(val failure: CoreFailure) : RuntimeState
}

/**
 * Runs one [CrisisNode] as a single-threaded actor.
 *
 * * **One coroutine owns the node.** Transport events, publish calls, queries and timer ticks
 *   all go through one mailbox and are executed one after another, so the core never sees
 *   concurrent calls and their order is the order of arrival. Every core call blocks (SQLite),
 *   which is why the actor runs on [dispatcher] (default: the IO dispatcher) and never on the
 *   main thread.
 * * **The core is driven by its own deadline.** Each [CoreOutput] carries `next_timer_ms`; the
 *   actor waits for the next message at most until then, and ticks when the wait runs out (see
 *   [TimerPolicy]). Timing uses [clock], the only time source the core ever sees.
 * * **Outputs are routed**: transport commands go to the [Transport], notifications to
 *   [notifications]. The transport is told to send without blocking.
 * * **Errors are mapped** to [CoreFailure]. Non-fatal ones fail the single call that caused
 *   them (or appear on [failures] for events); a fatal one stops the runtime ([RuntimeState.Failed]).
 *
 * Doze note: a coroutine delay does not wake a sleeping CPU. Keeping the process and the radio
 * alive while the screen is off is the job of the foreground service of phase A3.
 *
 * Create it through [NodeBootstrap.open], call [start], and always call [stop].
 */
class NodeRuntime(
    private val node: CrisisNode,
    private val transport: Transport,
    private val clock: CoreClock,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val timer = TimerPolicy()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mailbox = Channel<Message>(Channel.UNLIMITED)

    private val stateFlow = MutableStateFlow<RuntimeState>(RuntimeState.Created)
    private val notificationFlow = MutableSharedFlow<CoreNotification>(extraBufferCapacity = NOTIFICATION_BUFFER)
    private val failureFlow =
        MutableSharedFlow<CoreFailure>(extraBufferCapacity = FAILURE_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private var actorJob: Job? = null
    private var collectorJob: Job? = null

    val state: StateFlow<RuntimeState> = stateFlow.asStateFlow()

    /** Things the user interface should show. Not replayed: read the history for what happened earlier. */
    val notifications: SharedFlow<CoreNotification> = notificationFlow.asSharedFlow()

    /** Failures of transport events and of the runtime itself (failures of a call are thrown to its caller). */
    val failures: SharedFlow<CoreFailure> = failureFlow.asSharedFlow()

    /** This node's address (32 bytes). */
    val nodeId: ByteArray = node.nodeId()

    /** This node's shareable contact card (128 bytes). */
    val contactCard: ByteArray = node.contactCard()

    val safetyNumber: String = node.safetyNumber()

    // ----- lifecycle ------------------------------------------------------------------------

    /** Starts the transport and the actor. Calls made before are queued and run first. */
    @Synchronized
    fun start() {
        check(stateFlow.value == RuntimeState.Created && actorJob == null) { "the runtime was already started" }
        collectorJob =
            scope.launch {
                transport.events.collect { event -> mailbox.trySend(Message.Link(event)) }
            }
        try {
            transport.start()
        } catch (t: Throwable) {
            collectorJob?.cancel()
            stateFlow.value = RuntimeState.Failed(CoreFailure.Unexpected(t))
            node.close()
            throw t
        }
        stateFlow.value = RuntimeState.Running
        actorJob = scope.launch { runActor() }
    }

    /**
     * Stops the actor after it has handled what is already queued, stops the transport, closes
     * the node and persists the clock. Safe to call more than once and in any state.
     */
    suspend fun stop() {
        val job =
            synchronized(this) {
                val running = actorJob
                if (running == null && stateFlow.value == RuntimeState.Created) {
                    stateFlow.value = RuntimeState.Stopped
                    node.close()
                }
                running
            }
        mailbox.close()
        job?.join()
        scope.cancel()
    }

    // ----- publishing -----------------------------------------------------------------------

    /** Publishes a public announcement. [lifetimeSecs] `null` uses the default lifetime. */
    suspend fun publishBroadcast(payload: ByteArray, lifetimeSecs: UInt? = null): PublishOutcome =
        execute { n, now ->
            val outcome =
                if (lifetimeSecs == null) {
                    n.publishBroadcast(now, payload)
                } else {
                    n.publishBroadcastWithLifetime(now, payload, lifetimeSecs)
                }
            Step(outcome, outcome.output)
        }

    /** Publishes a message encrypted for [to]; the contact must have been added. */
    suspend fun publishDirect(to: ByteArray, plaintext: ByteArray): PublishOutcome =
        execute { n, now ->
            val outcome = n.publishDirect(now, to, plaintext)
            Step(outcome, outcome.output)
        }

    /** Publishes a message encrypted for the owner of [card] without saving the card. */
    suspend fun publishDirectToCard(card: ByteArray, plaintext: ByteArray): PublishOutcome =
        execute { n, now ->
            val outcome = n.publishDirectToCard(now, card, plaintext)
            Step(outcome, outcome.output)
        }

    // ----- contacts and reading -------------------------------------------------------------

    suspend fun addContact(card: ByteArray): CardDetails = query { n, now -> n.addContact(now, card) }

    suspend fun contacts(limit: UInt): List<ContactInfo> = query { n, _ -> n.contacts(limit) }

    suspend fun setTrust(nodeId: ByteArray, trust: TrustLevel) = query { n, now -> n.setTrust(now, nodeId, trust) }

    suspend fun history(afterSeq: ULong, limit: UInt): HistoryPageInfo =
        query { n, now -> n.history(afterSeq, limit, now) }

    suspend fun connectedPeers(): List<ULong> = query { n, _ -> n.connectedPeers() }

    suspend fun stats(): NodeStats = query { n, _ -> n.stats() }

    // ----- the actor ------------------------------------------------------------------------

    private class Step<T>(val value: T, val output: CoreOutput?)

    private sealed interface Message {
        class Link(val event: TransportEvent) : Message

        /** [run] executes on the actor; [abort] completes the caller when the runtime stops first. */
        class Call(val run: suspend () -> Unit, val abort: (Throwable) -> Unit) : Message
    }

    /** A failure that stops the runtime. */
    private class FatalFailure(val failure: CoreFailure) : Exception()

    private suspend fun <T> query(block: (CrisisNode, ULong) -> T): T = execute { n, now -> Step(block(n, now), null) }

    private suspend fun <T> execute(block: (CrisisNode, ULong) -> Step<T>): T {
        val reply = CompletableDeferred<T>()
        val message =
            Message.Call(
                run = { runCall(reply, block) },
                abort = { cause -> reply.completeExceptionally(cause) },
            )
        if (mailbox.trySend(message).isFailure) throw CoreFailureException(CoreFailure.RuntimeStopped)
        return reply.await()
    }

    private suspend fun <T> runCall(reply: CompletableDeferred<T>, block: (CrisisNode, ULong) -> Step<T>) {
        try {
            val step = block(node, nowForCore())
            step.output?.let { applyOutput(it) }
            reply.complete(step.value)
        } catch (e: CancellationException) {
            reply.cancel(e)
            throw e
        } catch (t: Throwable) {
            val failure = t.toCoreFailure()
            reply.completeExceptionally(CoreFailureException(failure))
            if (failure.isFatal) throw FatalFailure(failure)
        }
    }

    private suspend fun runActor() {
        var outcome: RuntimeState = RuntimeState.Stopped
        try {
            tick()
            while (true) {
                val received = withTimeoutOrNull(timer.waitMs(clock.nowMs())) { mailbox.receiveCatching() }
                when {
                    received == null -> tick()
                    received.isClosed -> break
                    else -> handle(received.getOrThrow())
                }
            }
        } catch (e: FatalFailure) {
            outcome = RuntimeState.Failed(e.failure)
            failureFlow.tryEmit(e.failure)
        } finally {
            withContext(NonCancellable) { shutdown(outcome) }
        }
    }

    private suspend fun handle(message: Message) {
        when (message) {
            is Message.Call -> message.run()
            is Message.Link -> onLink(message.event)
        }
    }

    private suspend fun onLink(event: TransportEvent) {
        when (event) {
            is TransportEvent.PeerSeen ->
                stepEvent { now -> node.peerSeen(now, event.peer, event.linkMtu.coerceAtLeast(0).toUInt()) }
            is TransportEvent.PeerLost -> stepEvent { now -> node.peerLost(now, event.peer) }
            is TransportEvent.PacketReceived -> stepEvent { now -> node.packetReceived(now, event.peer, event.bytes) }
        }
    }

    private suspend fun tick() {
        timer.onTick(clock.nowMs())
        stepEvent { now -> node.tick(now) }
    }

    /** Runs one core call made for an event, applies its output, and reports a failure instead of throwing. */
    private suspend fun stepEvent(block: (ULong) -> CoreOutput) {
        try {
            applyOutput(block(nowForCore()))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val failure = t.toCoreFailure()
            failureFlow.tryEmit(failure)
            if (failure.isFatal) throw FatalFailure(failure)
        }
    }

    private suspend fun applyOutput(output: CoreOutput) {
        for (command in output.transport) {
            when (command) {
                is TransportCommand.SendPacket -> guarded { transport.send(command.peer, command.bytes) }
                is TransportCommand.Disconnect -> guarded { transport.disconnect(command.peer) }
            }
        }
        for (notification in output.notifications) notificationFlow.emit(notification)
        timer.onOutput(output.nextTimerMs?.toLongSaturated())
    }

    /** A transport that throws must not take the node down: report it and carry on. */
    private inline fun guarded(action: () -> Unit) {
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failureFlow.tryEmit(CoreFailure.Other("transport failure: ${t.message}"))
        }
    }

    private suspend fun shutdown(outcome: RuntimeState) {
        collectorJob?.cancel()
        guarded { transport.stop() }
        mailbox.close()
        while (true) {
            val pending = mailbox.tryReceive().getOrNull() ?: break
            if (pending is Message.Call) pending.abort(CoreFailureException(CoreFailure.RuntimeStopped))
        }
        guarded { node.close() }
        guarded { clock.flush() }
        stateFlow.value = outcome
    }

    private fun nowForCore(): ULong = clock.nowMs().coerceAtLeast(0L).toULong()

    private fun ULong.toLongSaturated(): Long = if (this > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else toLong()

    private companion object {
        const val NOTIFICATION_BUFFER = 256
        const val FAILURE_BUFFER = 16
    }
}
