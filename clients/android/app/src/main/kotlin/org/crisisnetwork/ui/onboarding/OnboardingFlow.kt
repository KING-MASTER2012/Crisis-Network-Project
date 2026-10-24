package org.crisisnetwork.android.ui.onboarding

/** The steps of the introduction, in order. */
enum class OnboardingStep { Welcome, Identity, Nearby, Background, Done }

/**
 * The rules of the introduction, without any user interface (so that they can be tested): where it
 * starts, which step follows, what must be done before going on, what may be skipped.
 */
object OnboardingFlow {
    val steps: List<OnboardingStep> = OnboardingStep.entries

    /** A person who already has an identity (the process was killed in the middle) does not create a second one. */
    fun firstStep(identityCreated: Boolean): OnboardingStep = if (identityCreated) OnboardingStep.Nearby else OnboardingStep.Welcome

    fun next(step: OnboardingStep): OnboardingStep = steps.getOrElse(step.ordinal + 1) { step }

    fun previous(step: OnboardingStep): OnboardingStep = steps.getOrElse(step.ordinal - 1) { step }

    fun canGoBack(step: OnboardingStep): Boolean = step != OnboardingStep.Welcome

    /** The identity is required; the permissions and the battery explanation are strongly advised but never block. */
    fun canAdvance(step: OnboardingStep, identityCreated: Boolean): Boolean =
        when (step) {
            OnboardingStep.Identity -> identityCreated
            else -> true
        }

    /** Steps with an optional action: the person may go on without doing it. */
    fun canSkip(step: OnboardingStep): Boolean = step == OnboardingStep.Nearby

    /** "Step 2 of 5": the position of [step] counted from 1. */
    fun position(step: OnboardingStep): Int = step.ordinal + 1

    val total: Int get() = steps.size

    /**
     * The safety number as lines of four groups, which is how two people read it to each other.
     * The core returns eight groups of four hexadecimal digits separated by spaces; anything else
     * is returned unchanged.
     */
    fun formatSafetyNumber(raw: String): String {
        val groups = raw.trim().split(' ', '-', '\u00A0').filter { it.isNotEmpty() }
        if (groups.size != 8 || groups.any { it.length != 4 }) return raw
        return groups.chunked(4).joinToString("\n") { it.joinToString("  ") }
    }

    /** The first 16 and the last 8 hexadecimal digits of a node id: enough to recognise it. */
    fun shortNodeId(hex: String): String = if (hex.length <= 28) hex else hex.take(16) + "…" + hex.takeLast(8)
}
