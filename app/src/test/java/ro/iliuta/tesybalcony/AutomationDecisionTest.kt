package ro.iliuta.tesybalcony

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationDecisionTest {
    @Test fun belowThresholdAndOffStarts() {
        assertTrue(AutomationDecision.shouldStart(9.9, 10.0, false))
    }
    @Test fun atThresholdDoesNotStart() {
        assertFalse(AutomationDecision.shouldStart(10.0, 10.0, false))
    }
    @Test fun aboveThresholdDoesNotStart() {
        assertFalse(AutomationDecision.shouldStart(12.0, 10.0, false))
    }
    @Test fun alreadyOnIsNeverTakenOver() {
        assertFalse(AutomationDecision.shouldStart(5.0, 10.0, true))
    }
    @Test fun unknownTemperatureDoesNotStart() {
        assertFalse(AutomationDecision.shouldStart(null, 10.0, false))
        assertFalse(AutomationDecision.shouldStart(Double.NaN, 10.0, false))
    }
    @Test fun unknownPowerStateDoesNotStart() {
        assertFalse(AutomationDecision.shouldStart(8.0, 10.0, null))
    }
}
