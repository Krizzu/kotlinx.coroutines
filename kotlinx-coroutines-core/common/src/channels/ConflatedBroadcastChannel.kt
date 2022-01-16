/*
 * Copyright 2016-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines.channels

import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlinx.coroutines.internal.*
import kotlinx.coroutines.selects.*
import kotlin.native.concurrent.*

/**
 * Broadcasts the most recently sent element (aka [value]) to all [openSubscription] subscribers.
 *
 * Back-to-send sent elements are _conflated_ -- only the most recently sent value is received,
 * while previously sent elements **are lost**.
 * Every subscriber immediately receives the most recently sent element.
 * Sender to this broadcast channel never suspends and [trySend] always succeeds.
 *
 * A secondary constructor can be used to create an instance of this class that already holds a value.
 * This channel is also created by `BroadcastChannel(Channel.CONFLATED)` factory function invocation.
 *
 * In this implementation, [opening][openSubscription] and [closing][ReceiveChannel.cancel] subscription
 * takes linear time in the number of subscribers.
 *
 * **Note: This API is obsolete since 1.5.0.** It will be deprecated with warning in 1.6.0
 * and with error in 1.7.0. It is replaced with [StateFlow][kotlinx.coroutines.flow.StateFlow].
 */
@ObsoleteCoroutinesApi
public class ConflatedBroadcastChannel<E>() : BroadcastChannel<E> {
    /**
     * Creates an instance of this class that already holds a value.
     *
     * It is as a shortcut to creating an instance with a default constructor and
     * immediately sending an element: `ConflatedBroadcastChannel().apply { offer(value) }`.
     */
    public constructor(value: E) : this() {
        _lastElement = value
    }

    /**
     * All operation are protected by this lock.
     */
    private val _lock = ReentrantLock()

    private val _subscribers = atomic<List<ConflatedBufferedChannel<E>>>(emptyList())
    private var _lastElement: Any? = NO_ELEMENT

    private var _isClosed = false
    private var _closeCause: Throwable? = null
    private var _onCloseHandler: Handler? = null

    /**
     * The most recently sent element to this channel.
     *
     * Access to this property throws [IllegalStateException] when this class is constructed without
     * initial value and no value was sent yet or if it was [closed][close] without a cause.
     * It throws the original [close][SendChannel.close] cause exception if the channel has _failed_.
     */
    @Suppress("UNCHECKED_CAST")
    public val value: E get() = _lock.withLock {
        if (_isClosed) {
            throw _closeCause ?: IllegalStateException("This broadcast channel is closed")
        }
        _lastElement.let {
            if (it !== NO_ELEMENT) it as E
            else error("No value")
        }
    }

    /**
     * The most recently sent element to this channel or `null` when this class is constructed without
     * initial value and no value was sent yet or if it was [closed][close].
     */
    public val valueOrNull: E? get() = _lock.withLock {
        if (_isClosed) null
        else if (_lastElement === NO_ELEMENT) null
        else _lastElement as E
    }

    public override val isClosedForSend: Boolean get() = _lock.withLock { _isClosed }

    @Suppress("UNCHECKED_CAST")
    public override fun openSubscription(): ReceiveChannel<E> = _lock.withLock {
        val subscriber = Subscriber()
        _lastElement.let {
            if (it !== NO_ELEMENT) subscriber.trySend(it as E)
        }
        _subscribers.update { it + subscriber }
        subscriber
    }

    @Suppress("UNCHECKED_CAST")
    private fun closeSubscriber(subscriber: Subscriber) = _subscribers.update {
        check(subscriber in it) { "The removing subscriber does not exist" }
        it - subscriber
    }

    @Suppress("UNCHECKED_CAST")
    public override fun close(cause: Throwable?): Boolean = _lock.withLock {
        if (_isClosed) return@withLock false
        _isClosed = true
        _subscribers.value.forEach { it.close(cause) }
        _subscribers.value = emptyList()
        _onCloseHandler?.invoke(cause)
        return@withLock true
    }

    override fun invokeOnClose(handler: Handler): Unit = _lock.withLock {
        if (_onCloseHandler !== null) {
            if (_isClosed) error("Another handler has already registered and successfully invoked: $_onCloseHandler")
            else error("Another handler has already registered: $_onCloseHandler")
        }
        _onCloseHandler = handler
        if (_isClosed) handler.invoke(_closeCause)
    }

    /**
     * @suppress This method has bad semantics when cause is not a [CancellationException]. Use [cancel].
     */
    @Deprecated(level = DeprecationLevel.HIDDEN, message = "Since 1.2.0, binary compatibility with versions <= 1.1.x")
    public override fun cancel(cause: Throwable?): Boolean = close(cause)

    /**
     * Cancels this conflated broadcast channel with an optional cause, same as [close].
     * This function closes the channel with
     * the specified cause (unless it was already closed),
     * and [cancels][ReceiveChannel.cancel] all open subscriptions.
     * A cause can be used to specify an error message or to provide other details on
     * a cancellation reason for debugging purposes.
     */
    public override fun cancel(cause: CancellationException?) {
        close(cause)
    }

    /**
     * Sends the value to all subscribed receives and stores this value as the most recent state for
     * future subscribers. This implementation never suspends.
     * It throws exception if the channel [isClosedForSend] (see [close] for details).
     */
    public override suspend fun send(element: E): Unit = _lock.withLock {
        if (_isClosed) throw _closeCause ?: ClosedSendChannelException(DEFAULT_CLOSE_MESSAGE)
        _lastElement = element
        _subscribers.value.forEach { it.trySend(element) }
    }

    /**
     * Sends the value to all subscribed receives and stores this value as the most recent state for
     * future subscribers. This implementation always returns either successful result
     * or closed with an exception.
     */
    public override fun trySend(element: E): ChannelResult<Unit> = _lock.withLock {
        if (_isClosed) return@withLock ChannelResult.closed(_closeCause ?: ClosedSendChannelException(DEFAULT_CLOSE_MESSAGE))
        _lastElement = element
        _subscribers.value.forEach { it.trySend(element) }
        return@withLock ChannelResult.success(Unit)
    }

    public override val onSend: SelectClause2<E, SendChannel<E>>
        get() = SelectClause2Impl(
            clauseObject = this,
            regFunc = ConflatedBroadcastChannel<*>::registerSelectForSend as RegistrationFunction,
            processResFunc = ConflatedBroadcastChannel<*>::processResultSelectSend as ProcessResultFunction
        )

    @Suppress("UNCHECKED_CAST")
    private fun registerSelectForSend(select: SelectInstance<*>, element: Any?) {
        select.selectInRegistrationPhase(trySend(element as E))
    }

    @Suppress("RedundantNullableReturnType", "UNUSED_PARAMETER", "UNCHECKED_CAST")
    private fun processResultSelectSend(ignoredParam: Any?, selectResult: Any?): Any? {
        (selectResult as ChannelResult<Unit>).onClosed {
            throw it ?: ClosedSendChannelException("Channel has been closed")
        }
        return this
    }

    private inner class Subscriber :
        ConflatedBufferedChannel<E>(
            capacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = null
        ),
        ReceiveChannel<E> {
        override fun onCancel(wasClosed: Boolean) {
            if (wasClosed) closeSubscriber(this)
        }
    }
}

@SharedImmutable
private val NO_ELEMENT = Symbol("NULL")