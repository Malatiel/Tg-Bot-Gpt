package tgbotgpt.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.util.Base64;
import java.util.Set;

@Slf4j
@Service
public class ImageService {

    @Value("${bot.image.max.size.mb:10}")
    private int maxSizeMb;

    @Value("#{'${bot.image.allowed.types:image/jpeg,image/png,image/gif,image/webp}'.split(',')}")
    private Set<String> allowedTypes;

    public ImageDownloadResult downloadAndEncode(String fileUrl, String mimeType) {
        if (!isAllowedType(mimeType)) {
            log.warn("Rejected image type: {}", mimeType);
            return ImageDownloadResult.unsupportedType();
        }

        try {
            URI uri = URI.create(fileUrl);
            // Only allow HTTPS URLs from Telegram API
            if (!"https".equals(uri.getScheme())) {
                log.warn("Rejected non-HTTPS image URL");
                return ImageDownloadResult.invalidSource();
            }
            if (!uri.getHost().endsWith("api.telegram.org")) {
                log.warn("Rejected non-Telegram image URL: {}", uri.getHost());
                return ImageDownloadResult.invalidSource();
            }

            try (InputStream in = uri.toURL().openStream()) {
                byte[] data = in.readNBytes(maxSizeMb * 1024 * 1024 + 1);
                if (data.length > maxSizeMb * 1024 * 1024) {
                    log.warn("Image too large: {} bytes", data.length);
                    return ImageDownloadResult.tooLarge();
                }
                return ImageDownloadResult.success(Base64.getEncoder().encodeToString(data));
            }
        } catch (Exception e) {
            log.error("Failed to download image: ", e);
            return ImageDownloadResult.unreadable();
        }
    }

    public boolean isAllowedType(String mimeType) {
        if (mimeType == null) return false;
        return allowedTypes.contains(mimeType.toLowerCase());
    }

    public String guessMimeType(String filePath) {
        if (filePath == null) return "image/jpeg";
        String lower = filePath.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }
}
