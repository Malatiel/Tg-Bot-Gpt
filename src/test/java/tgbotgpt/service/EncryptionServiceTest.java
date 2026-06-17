package tgbotgpt.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class EncryptionServiceTest {

    private EncryptionService createEnabled() {
        EncryptionService service = new EncryptionService();
        // Generate a valid 32-byte key
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        ReflectionTestUtils.setField(service, "keyBase64", Base64.getEncoder().encodeToString(key));
        service.init();
        return service;
    }

    private EncryptionService createDisabled() {
        EncryptionService service = new EncryptionService();
        ReflectionTestUtils.setField(service, "keyBase64", "");
        service.init();
        return service;
    }

    @Test
    void shouldEncryptAndDecryptSuccessfully() {
        EncryptionService service = createEnabled();
        String original = "Hello, this is a secret message!";

        String encrypted = service.encrypt(original);
        assertNotEquals(original, encrypted);

        String decrypted = service.decrypt(encrypted);
        assertEquals(original, decrypted);
    }

    @Test
    void shouldProduceDifferentCiphertextEachTime() {
        EncryptionService service = createEnabled();
        String original = "Same message";

        String encrypted1 = service.encrypt(original);
        String encrypted2 = service.encrypt(original);

        // Different IVs → different ciphertext
        assertNotEquals(encrypted1, encrypted2);

        // Both decrypt to original
        assertEquals(original, service.decrypt(encrypted1));
        assertEquals(original, service.decrypt(encrypted2));
    }

    @Test
    void shouldHandleNullInput() {
        EncryptionService service = createEnabled();

        assertNull(service.encrypt(null));
        assertNull(service.decrypt(null));
    }

    @Test
    void shouldHandleEmptyString() {
        EncryptionService service = createEnabled();

        String encrypted = service.encrypt("");
        String decrypted = service.decrypt(encrypted);
        assertEquals("", decrypted);
    }

    @Test
    void shouldHandleUnicodeContent() {
        EncryptionService service = createEnabled();
        String original = "Привет мир! 你好世界 🌍";

        String encrypted = service.encrypt(original);
        String decrypted = service.decrypt(encrypted);
        assertEquals(original, decrypted);
    }

    @Test
    void shouldHandleLongContent() {
        EncryptionService service = createEnabled();
        String original = "x".repeat(10000);

        String encrypted = service.encrypt(original);
        String decrypted = service.decrypt(encrypted);
        assertEquals(original, decrypted);
    }

    @Test
    void shouldPassthroughWhenDisabled() {
        EncryptionService service = createDisabled();
        assertFalse(service.isEnabled());

        String text = "plaintext message";
        assertEquals(text, service.encrypt(text));
        assertEquals(text, service.decrypt(text));
    }

    @Test
    void shouldBeEnabledWithValidKey() {
        EncryptionService service = createEnabled();
        assertTrue(service.isEnabled());
    }

    @Test
    void shouldDisableOnInvalidKeyLength() {
        EncryptionService service = new EncryptionService();
        // 16-byte key instead of 32
        byte[] shortKey = new byte[16];
        ReflectionTestUtils.setField(service, "keyBase64", Base64.getEncoder().encodeToString(shortKey));
        service.init();

        assertFalse(service.isEnabled());
    }

    @Test
    void shouldDisableOnInvalidBase64() {
        EncryptionService service = new EncryptionService();
        ReflectionTestUtils.setField(service, "keyBase64", "not-valid-base64!!!");
        service.init();

        assertFalse(service.isEnabled());
    }

    @Test
    void shouldFailStartupWhenRequiredKeyIsMissing() {
        EncryptionService service = new EncryptionService();
        ReflectionTestUtils.setField(service, "keyBase64", "");
        ReflectionTestUtils.setField(service, "encryptionRequired", true);

        assertThrows(IllegalStateException.class, service::init);
    }

    @Test
    void shouldFailStartupWhenRequiredKeyIsInvalid() {
        EncryptionService service = new EncryptionService();
        ReflectionTestUtils.setField(service, "keyBase64", "not-valid-base64!!!");
        ReflectionTestUtils.setField(service, "encryptionRequired", true);

        assertThrows(IllegalStateException.class, service::init);
    }

    @Test
    void shouldThrowOnEncryptFailureWhenRequired() {
        EncryptionService service = new EncryptionService();
        // Enabled but with a null key forces cipher init to fail during encrypt().
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "encryptionRequired", true);
        ReflectionTestUtils.setField(service, "secretKey", null);

        assertThrows(IllegalStateException.class, () -> service.encrypt("sensitive"));
    }

    @Test
    void shouldFallbackToPlaintextOnEncryptFailureWhenNotRequired() {
        EncryptionService service = new EncryptionService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "encryptionRequired", false);
        ReflectionTestUtils.setField(service, "secretKey", null);

        assertEquals("sensitive", service.encrypt("sensitive"));
    }

    @Test
    void shouldReturnPlaintextForLegacyUnencryptedData() {
        EncryptionService service = createEnabled();

        // Legacy plaintext that isn't valid base64/ciphertext
        String legacy = "This is old plaintext from before encryption was enabled";
        String result = service.decrypt(legacy);
        assertEquals(legacy, result);
    }

    @Test
    void shouldNotDecryptWithWrongKey() {
        EncryptionService service1 = createEnabled();
        EncryptionService service2 = createEnabled(); // different key

        String encrypted = service1.encrypt("secret");

        // Wrong key should return the encrypted string as-is (fallback)
        String result = service2.decrypt(encrypted);
        // Won't equal the original plaintext
        assertNotEquals("secret", result);
    }
}
