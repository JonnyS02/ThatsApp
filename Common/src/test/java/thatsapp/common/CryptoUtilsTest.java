package thatsapp.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CryptoUtilsTest {

    @Test
    void buildsAndVerifiesBase64HmacProofs() throws Exception {
        String proof = CryptoUtils.hmacSha256Base64("secret", "data");

        assertFalse(proof.isBlank());
        assertTrue(CryptoUtils.matchesBase64HmacSha256("secret", "data", proof));
        assertFalse(CryptoUtils.matchesBase64HmacSha256("secret", "other", proof));
    }

    @Test
    void blankKeyUsesBlankProof() throws Exception {
        assertTrue(CryptoUtils.hmacSha256Base64("", "data").isBlank());
        assertTrue(CryptoUtils.matchesBase64HmacSha256("", "data", ""));
    }
}
