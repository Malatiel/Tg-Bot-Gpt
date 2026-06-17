package tgbotgpt.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for chat message content.
 * IV is prepended to the ciphertext and stored together as base64.
 */
@Slf4j
@Service
public class EncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    @Value("${encryption.key:}")
    private String keyBase64;

    @Value("${encryption.required:false}")
    private boolean encryptionRequired;

    private SecretKey secretKey;
    private boolean enabled;

    @PostConstruct
    void init() {
        if (keyBase64 == null || keyBase64.isBlank()) {
            enabled = false;
            if (encryptionRequired) {
                throw new IllegalStateException("ENCRYPTION_KEY is required but not set");
            }
            log.warn("ENCRYPTION_KEY not set — chat messages will be stored in plaintext");
            return;
        }

        try {
            byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException("Key must be 32 bytes (256 bits), got " + keyBytes.length);
            }
            secretKey = new SecretKeySpec(keyBytes, "AES");
            enabled = true;
            log.info("Message encryption enabled (AES-256-GCM)");
        } catch (Exception e) {
            enabled = false;
            if (encryptionRequired) {
                throw new IllegalStateException("Invalid ENCRYPTION_KEY", e);
            }
            log.error("Invalid ENCRYPTION_KEY, encryption disabled: {}", e.getMessage());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String encrypt(String plaintext) {
        if (!enabled || plaintext == null) return plaintext;

        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Prepend IV to ciphertext
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            // When encryption is required, never silently downgrade to plaintext —
            // fail the operation so a caller doesn't persist sensitive data in the clear.
            if (encryptionRequired) {
                log.error("Encryption failed and encryption is required; refusing to store plaintext: {}", e.getMessage());
                throw new IllegalStateException("Message encryption failed", e);
            }
            log.error("Encryption failed, storing plaintext: {}", e.getMessage());
            return plaintext;
        }
    }

    public String decrypt(String encrypted) {
        if (!enabled || encrypted == null) return encrypted;

        try {
            byte[] combined = Base64.getDecoder().decode(encrypted);
            if (combined.length < GCM_IV_LENGTH) {
                // Not encrypted data (legacy plaintext)
                return encrypted;
            }

            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);

            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);

            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Not valid base64 — legacy plaintext message
            return encrypted;
        } catch (Exception e) {
            log.warn("Decryption failed (possibly legacy plaintext): {}", e.getMessage());
            return encrypted;
        }
    }
}
