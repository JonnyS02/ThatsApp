package thatsapp.server.ui.controllers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import thatsapp.server.messages.ServerAttentionMessages;

class MainControllerTest {

    @Test
    void validatesPortField() {
        assertEquals(ServerAttentionMessages.PORT_MUST_NOT_BE_EMPTY, MainController.validatePort(""));
        assertEquals(ServerAttentionMessages.PORT_MUST_BE_A_NUMBER, MainController.validatePort("abc"));
        assertEquals(ServerAttentionMessages.PORT_MUST_BE_BETWEEN_0_AND_65535, MainController.validatePort("70000"));
        assertEquals("", MainController.validatePort("65535"));
    }
}
