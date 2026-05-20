package thatsapp.client.ui.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import thatsapp.client.messages.ClientAttentionMessages;

class LoginControllerTest {

    @Test
    void returnsValidationMessagesForMissingOrMismatchedPasswords() {
        String missing = LoginController.validatePasswordMessage("", "");
        String mismatch = LoginController.validatePasswordMessage("secret", "other");

        assertTrue(missing.contains(ClientAttentionMessages.PLEASE_ENTER_PASSWORD));
        assertTrue(missing.contains(ClientAttentionMessages.PLEASE_REPEAT_PASSWORD));
        assertEquals(ClientAttentionMessages.PASSWORDS_DO_NOT_MATCH, mismatch);
    }

    @Test
    void acceptsMatchingPasswords() {
        assertEquals("", LoginController.validatePasswordMessage("secret", "secret"));
    }
}
