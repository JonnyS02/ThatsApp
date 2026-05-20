package thatsapp.client.ui.controllers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import thatsapp.client.messages.ClientAttentionMessages;

class ConfigControllerTest {

    @Test
    void validatesDesktopConfiguration() {
        String message = ConfigController.validateDesktopConfig("", "70000", "short");

        assertTrue(message.contains(ClientAttentionMessages.HOST_IP_CANNOT_BE_EMPTY));
        assertTrue(message.contains(ClientAttentionMessages.PORT_MUST_BE_BETWEEN_1_AND_65535));
        assertTrue(message.contains(ClientAttentionMessages.ROOM_SECRET_MIN_16_WHEN_PROVIDED));
    }

    @Test
    void validatesWebConfiguration() {
        String message = ConfigController.validateWebConfig("invalid", "session/part", "short");

        assertTrue(message.contains(ClientAttentionMessages.INVALID_URL));
        assertTrue(message.contains(ClientAttentionMessages.SESSION_NAME_MUST_NOT_CONTAIN_SLASH));
        assertTrue(message.contains(ClientAttentionMessages.ROOM_SECRET_MIN_16_WHEN_PROVIDED));
    }

    @Test
    void acceptsValidHostsAndUrls() {
        assertTrue(ConfigController.isValidIpAddress("localhost"));
        assertTrue(ConfigController.isValidIpAddress("192.168.1.10"));
        assertTrue(ConfigController.isValidIpAddress("2001:db8::1"));
        assertFalse(ConfigController.isValidIpAddress("invalid host"));
        assertTrue(ConfigController.isValidUrl("https://example.com/chat"));
        assertFalse(ConfigController.isValidUrl("example"));
    }
}
