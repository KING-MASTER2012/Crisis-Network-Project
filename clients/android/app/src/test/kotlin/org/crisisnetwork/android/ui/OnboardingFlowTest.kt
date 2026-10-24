package org.crisisnetwork.android.ui

import org.crisisnetwork.android.ui.onboarding.OnboardingFlow
import org.crisisnetwork.android.ui.onboarding.OnboardingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFlowTest {
    @Test
    fun theStepsRunInOrderAndStopAtTheEnds() {
        var step = OnboardingStep.Welcome
        val seen = mutableListOf(step)
        repeat(10) {
            val next = OnboardingFlow.next(step)
            if (next != step) seen += next
            step = next
        }
        assertEquals(listOf(OnboardingStep.Welcome, OnboardingStep.Identity, OnboardingStep.Nearby, OnboardingStep.Background, OnboardingStep.Done), seen)
        assertEquals(OnboardingStep.Done, OnboardingFlow.next(OnboardingStep.Done))
        assertEquals(OnboardingStep.Welcome, OnboardingFlow.previous(OnboardingStep.Welcome))
        assertEquals(OnboardingStep.Identity, OnboardingFlow.previous(OnboardingStep.Nearby))
    }

    @Test
    fun aPersonWithAnIdentityDoesNotCreateASecondOne() {
        assertEquals(OnboardingStep.Welcome, OnboardingFlow.firstStep(identityCreated = false))
        assertEquals(OnboardingStep.Nearby, OnboardingFlow.firstStep(identityCreated = true))
    }

    @Test
    fun onlyTheIdentityBlocksTheWayForward() {
        assertFalse(OnboardingFlow.canAdvance(OnboardingStep.Identity, identityCreated = false))
        assertTrue(OnboardingFlow.canAdvance(OnboardingStep.Identity, identityCreated = true))
        for (step in listOf(OnboardingStep.Welcome, OnboardingStep.Nearby, OnboardingStep.Background, OnboardingStep.Done)) {
            assertTrue("$step must not block", OnboardingFlow.canAdvance(step, identityCreated = false))
        }
    }

    @Test
    fun thePermissionStepMayBeSkippedButTheIdentityMayNot() {
        assertTrue(OnboardingFlow.canSkip(OnboardingStep.Nearby))
        assertFalse(OnboardingFlow.canSkip(OnboardingStep.Identity))
        assertFalse(OnboardingFlow.canSkip(OnboardingStep.Welcome))
    }

    @Test
    fun backIsPossibleFromEveryStepButTheFirst() {
        assertFalse(OnboardingFlow.canGoBack(OnboardingStep.Welcome))
        for (step in OnboardingFlow.steps.drop(1)) assertTrue(OnboardingFlow.canGoBack(step))
    }

    @Test
    fun positionsAreCountedFromOne() {
        assertEquals(1, OnboardingFlow.position(OnboardingStep.Welcome))
        assertEquals(OnboardingFlow.total, OnboardingFlow.position(OnboardingStep.Done))
        assertEquals(5, OnboardingFlow.total)
    }

    @Test
    fun theSafetyNumberIsShownAsTwoLinesOfFourGroups() {
        val raw = "0123 4567 89ab cdef 0123 4567 89ab cdef"
        assertEquals("0123  4567  89ab  cdef\n0123  4567  89ab  cdef", OnboardingFlow.formatSafetyNumber(raw))
        assertEquals("0123  4567  89ab  cdef\n0123  4567  89ab  cdef", OnboardingFlow.formatSafetyNumber("  $raw  "))
    }

    @Test
    fun aSafetyNumberOfAnotherShapeIsLeftAlone() {
        assertEquals("", OnboardingFlow.formatSafetyNumber(""))
        assertEquals("1234 5678", OnboardingFlow.formatSafetyNumber("1234 5678"))
        val oddGroup = "0123 4567 89ab cdef 0123 4567 89ab cde"
        assertEquals(oddGroup, OnboardingFlow.formatSafetyNumber(oddGroup))
    }

    @Test
    fun aLongNodeIdIsShortenedAndAShortOneIsNot() {
        val id = "0123456789abcdef".repeat(4)
        assertEquals("0123456789abcdef…89abcdef", OnboardingFlow.shortNodeId(id))
        assertEquals("abcd", OnboardingFlow.shortNodeId("abcd"))
    }
}
