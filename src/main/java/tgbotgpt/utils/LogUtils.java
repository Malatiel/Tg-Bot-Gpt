package tgbotgpt.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class LogUtils {

    private LogUtils() {
    }

    public static String hashUserId(Long userId) {
        if (userId == null) {
            return "unknown";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.valueOf(userId).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                result.append("%02x".formatted(hash[i]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            return "hash_unavailable";
        }
    }
}
