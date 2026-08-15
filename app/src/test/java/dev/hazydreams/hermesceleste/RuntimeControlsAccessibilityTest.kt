package dev.hazydreams.hermesceleste

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeControlsAccessibilityTest {
    @Test
    fun focusRestoresOnlyWhenThePickerTransitionsFromOpenToClosed() {
        assertTrue(shouldRestoreRuntimeControlsFocus(wasPickerOpen = true, pickerOpen = false))
        assertFalse(shouldRestoreRuntimeControlsFocus(wasPickerOpen = false, pickerOpen = false))
        assertFalse(shouldRestoreRuntimeControlsFocus(wasPickerOpen = false, pickerOpen = true))
        assertFalse(shouldRestoreRuntimeControlsFocus(wasPickerOpen = true, pickerOpen = true))
    }

    @Test
    fun operationAnnouncementsCoverApplyingQueuedAndUnknownOutcomes() {
        assertEquals(
            "Applying",
            RuntimeControlsUiState(operation = RuntimeControlsOperation.Applying).operationAnnouncement,
        )
        assertEquals(
            "Queued for next response",
            RuntimeControlsUiState(operation = RuntimeControlsOperation.Queued).operationAnnouncement,
        )
        assertEquals(
            "Unknown result; reconnect to verify",
            RuntimeControlsUiState(operation = RuntimeControlsOperation.Unknown).operationAnnouncement,
        )
    }
}
