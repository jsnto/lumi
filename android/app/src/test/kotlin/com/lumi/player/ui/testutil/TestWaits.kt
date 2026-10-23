package com.lumi.player.ui.testutil

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.robolectric.shadows.ShadowLooper

/**
 * The ONE wait-budget policy for the Robolectric UI tests (the flake
 * mechanism's fix): every async convergence in the suite pumps through
 * [idleUntil] / [waitForText] against the SAME generous outer bound
 * ([WAIT_BUDGET_MS]), instead of the per-class private copies the suite
 * used to carry (25 of them, five different budgets) - a load-sensitive
 * gate.
 *
 * The bound is generous on purpose, not a tuning knob: a test class JVM's
 * FIRST test pays the Robolectric/native-graphics cold start (~9-18 s even
 * when green), and full-suite load on a shared host has stretched a warm
 * convergence (a toast after a Save click, a row after an async Room
 * write) to ~16 s. The bound is the measured worst case plus margin:
 * [WAIT_BUDGET_MS]. The loop exits on the first satisfied condition, so a
 * green test costs nothing - the budget only costs the test that is
 * actually waiting.
 *
 * The documented re-drive when a class trips the bound under load: the
 * failure is a retry, not a product regression - the audit measured the
 * tripped tests passing in 0.4-0.7 s each when the class is re-run in
 * isolation (fresh class JVM, no full-suite load). So the gate is: a red
 * [idleUntil] deadline under full-suite load is re-driven as the ISOLATED
 * CLASS RE-RUN (`testDebugUnitTest --tests '<class>'`); the class green in
 * isolation confirms the load-dependent wait, and the red gate degrades to
 * a retry instead of a false failure.
 */
const val WAIT_BUDGET_MS = 120_000L

/**
 * Idles the main looper + compose until [cond] holds or [WAIT_BUDGET_MS]
 * elapses (pumping across real background hops like Room queries). Fails
 * with [message] when the bound trips - see the file KDoc for the
 * isolated re-drive.
 */
fun ComposeTestRule.idleUntil(cond: () -> Boolean, message: String = "the condition to settle") {
    val deadline = System.currentTimeMillis() + WAIT_BUDGET_MS
    while (!cond() && System.currentTimeMillis() < deadline) {
        ShadowLooper.idleMainLooper()
        waitForIdle()
        Thread.sleep(10)
    }
    check(cond()) { "timed out waiting for $message" }
}

/**
 * Waits until a node with [text] exists (pumping the looper across Room
 * hops), asserting it once the bound is reached. [substring] matches the
 * node's merged text by substring (the default is the exact match the
 * other suites use).
 */
fun ComposeTestRule.waitForText(text: String, substring: Boolean = false) {
    val deadline = System.currentTimeMillis() + WAIT_BUDGET_MS
    var last: AssertionError? = null
    while (System.currentTimeMillis() < deadline) {
        try {
            onNodeWithText(text, substring = substring).assertExists()
            return
        } catch (e: AssertionError) {
            last = e
            ShadowLooper.idleMainLooper()
            waitForIdle()
            Thread.sleep(10)
        }
    }
    throw last ?: AssertionError("timed out waiting for text '$text'")
}

/**
 * Waits until a node with [contentDescription] is DISPLAYED (the
 * isDisplayed probe returns false for an absent or clipped node, and
 * throws only for a multiplicity > 1) - the pollable form of
 * [assertIsDisplayed], for actions that must wait for a laid-out node
 * before clicking it.
 */
fun ComposeTestRule.waitForDisplayedContent(contentDescription: String) {
    val deadline = System.currentTimeMillis() + WAIT_BUDGET_MS
    while (System.currentTimeMillis() < deadline) {
        if (onNodeWithContentDescription(contentDescription).isDisplayed()) return
        ShadowLooper.idleMainLooper()
        waitForIdle()
        Thread.sleep(10)
    }
    onNodeWithContentDescription(contentDescription).assertIsDisplayed()
}
