package org.crisisnetwork.android.core.runtime

import org.crisisnetwork.android.core.runtime.failure.CoreFailure
import org.crisisnetwork.android.core.runtime.failure.CoreFailureException
import org.crisisnetwork.android.core.runtime.failure.toCoreFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreFailureTest {
    @Test
    fun aForeignThrowableIsUnexpectedAndFatal() {
        val cause = IllegalStateException("boom")
        val failure = cause.toCoreFailure()
        assertTrue(failure is CoreFailure.Unexpected)
        assertSame(cause, (failure as CoreFailure.Unexpected).cause)
        assertTrue(failure.isFatal)
    }

    @Test
    fun aWrappedFailureIsUnwrapped() {
        assertSame(CoreFailure.UnknownContact, CoreFailureException(CoreFailure.UnknownContact).toCoreFailure())
    }

    @Test
    fun onlyInternalAndUnexpectedFailuresAreFatal() {
        assertTrue(CoreFailure.InternalError("x").isFatal)
        assertFalse(CoreFailure.DatabaseUnreadable.isFatal)
        assertFalse(CoreFailure.EncryptionUnavailable.isFatal)
        assertFalse(CoreFailure.SchemaTooNew(2, 1).isFatal)
        assertFalse(CoreFailure.InvalidArgument("x").isFatal)
        assertFalse(CoreFailure.RuntimeStopped.isFatal)
        assertFalse(CoreFailure.SecretsUnavailable("x", resettable = true).isFatal)
        assertEquals(CoreFailure.Other("a"), CoreFailure.Other("a"))
    }
}
