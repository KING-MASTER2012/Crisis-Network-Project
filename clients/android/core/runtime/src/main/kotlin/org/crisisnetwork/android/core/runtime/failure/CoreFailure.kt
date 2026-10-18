package org.crisisnetwork.android.core.runtime.failure

import uniffi.crisis_core.CoreException

/**
 * What can go wrong in the runtime, in terms the application can react to. The `detail` texts
 * are for logs, never for users.
 */
sealed class CoreFailure {
    /** `true` when the node cannot continue and the runtime stops itself. */
    open val isFatal: Boolean get() = false

    /** The database file is not a database, or it was written with another key (or none). */
    data object DatabaseUnreadable : CoreFailure()

    /** The database was written by a newer core. Never downgraded. */
    data class SchemaTooNew(val found: Long, val supported: Long) : CoreFailure()

    /** A key was given but this build of the core has no SQLCipher. Data is never stored plain instead. */
    data object EncryptionUnavailable : CoreFailure()

    data object UnknownContact : CoreFailure()

    data object InvalidContactCard : CoreFailure()

    data class InvalidArgument(val detail: String) : CoreFailure()

    /** Bindings and native library (or app and core) disagree about the interface version. */
    data class VersionMismatch(val expected: Long, val actual: Long) : CoreFailure()

    /** Storage, crypto, protocol or mesh error that the caller may survive. */
    data class Other(val detail: String) : CoreFailure()

    /** The core reports it is in an unknown state (an earlier panic). */
    data class InternalError(val detail: String) : CoreFailure() {
        override val isFatal: Boolean get() = true
    }

    /** Anything that is not a core error: a missing native library, a bug in the runtime. */
    data class Unexpected(val cause: Throwable) : CoreFailure() {
        override val isFatal: Boolean get() = true
    }

    /**
     * The identity seed or database key cannot be provided. [resettable] is `true` when the
     * stored secrets exist but cannot be decrypted (for example the Keystore key is gone): the
     * only way forward is an explicit, user-confirmed reset, which loses the identity.
     */
    data class SecretsUnavailable(val reason: String, val resettable: Boolean) : CoreFailure()

    /** The runtime was stopped (or failed) and does not accept calls. */
    data object RuntimeStopped : CoreFailure()
}

/** Carries a [CoreFailure] through coroutine and exception machinery. */
class CoreFailureException(val failure: CoreFailure) : Exception(failure.toString())

/** Maps any throwable of a core call to a [CoreFailure]. */
fun Throwable.toCoreFailure(): CoreFailure = mapThrowable(this)

private fun mapThrowable(t: Throwable): CoreFailure =
    when (t) {
        is CoreFailureException -> t.failure
        is CoreException.WrongKeyOrNotADatabase -> CoreFailure.DatabaseUnreadable
        is CoreException.EncryptionUnavailable -> CoreFailure.EncryptionUnavailable
        is CoreException.SchemaTooNew -> CoreFailure.SchemaTooNew(t.found.toLong(), t.supported.toLong())
        is CoreException.UnknownContact -> CoreFailure.UnknownContact
        is CoreException.InvalidContactCard -> CoreFailure.InvalidContactCard
        is CoreException.InvalidArgument -> CoreFailure.InvalidArgument(t.detail)
        is CoreException.InvalidConfig -> CoreFailure.InvalidArgument(t.detail)
        is CoreException.VersionMismatch -> CoreFailure.VersionMismatch(t.expected.toLong(), t.actual.toLong())
        is CoreException.Internal -> CoreFailure.InternalError(t.detail)
        is CoreException -> CoreFailure.Other(t.message ?: "core error")
        else -> CoreFailure.Unexpected(t)
    }
