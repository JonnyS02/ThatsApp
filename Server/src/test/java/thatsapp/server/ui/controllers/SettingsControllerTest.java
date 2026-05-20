package thatsapp.server.ui.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import thatsapp.server.messages.ServerAttentionMessages;

class SettingsControllerTest {

    @Test
    void validatesLimitsAndReportsAllBrokenFields() {
        String message = SettingsController.validateLimits("", "-2", "-1", "abc");

        assertTrue(message.contains(ServerAttentionMessages.fieldCannotBeEmpty("User limit")));
        assertTrue(message.contains(ServerAttentionMessages.fieldMustBeAtLeastNegativeOne("File count limit")));
        assertTrue(message.contains(ServerAttentionMessages.fieldMustBeAtLeastZero("File size limit")));
        assertTrue(message.contains(ServerAttentionMessages.fieldMustBeValidNumber("Message character limit")));
    }

    @Test
    void acceptsValidLimits() {
        assertEquals("", SettingsController.validateLimits("0", "-1", "0", "0"));
    }
}
