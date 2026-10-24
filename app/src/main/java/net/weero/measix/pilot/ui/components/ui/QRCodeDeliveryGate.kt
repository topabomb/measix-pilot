package net.weero.measix.pilot.ui.components.ui

import java.util.concurrent.atomic.AtomicReference

/** A queued frame result belongs to one resumed camera binding, never a later binding. */
internal class QRCodeDeliveryGate {
    private enum class State { OPEN, QUEUED, DELIVERED, CLOSED }
    private val state = AtomicReference(State.OPEN)
    fun queue(): Boolean = state.compareAndSet(State.OPEN, State.QUEUED)
    fun deliver(): Boolean = state.compareAndSet(State.QUEUED, State.DELIVERED)
    fun isClosed(): Boolean = state.get() == State.CLOSED
    fun close() { state.set(State.CLOSED) }
}
