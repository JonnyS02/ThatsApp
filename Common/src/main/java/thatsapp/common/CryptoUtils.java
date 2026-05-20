package thatsapp.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

public final class CryptoUtils {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private CryptoUtils() {
    }

    public static byte[] hmacSha256(String key, byte[] data) throws GeneralSecurityException {
        if (key == null || key.isBlank()) {
            return new byte[0];
        }
        Mac mac = Mac.getInstance(HMAC_SHA256);
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
        return mac.doFinal(data);
    }

    public static String hmacSha256Base64(String key, String data) throws GeneralSecurityException {
        return Base64.getEncoder().encodeToString(hmacSha256(key, data.getBytes(StandardCharsets.UTF_8)));
    }

    public static boolean matchesBase64HmacSha256(String key, String data, String proof) throws GeneralSecurityException {
        byte[] expected = hmacSha256(key, data.getBytes(StandardCharsets.UTF_8));
        byte[] provided = Base64.getDecoder().decode(proof);
        return MessageDigest.isEqual(expected, provided);
    }
}
