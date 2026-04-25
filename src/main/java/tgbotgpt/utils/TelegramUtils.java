package tgbotgpt.utils;

import java.util.ArrayList;
import java.util.List;

public final class TelegramUtils {

    public static final int MAX_MESSAGE_LENGTH = 4096;

    private TelegramUtils() {}

    /**
     * Splits text into chunks that fit Telegram's message limit.
     * Tries to split on newlines first, then on spaces, then hard-cuts.
     */
    public static List<String> splitMessage(String text) {
        List<String> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return parts;
        }

        while (text.length() > MAX_MESSAGE_LENGTH) {
            int splitAt = text.lastIndexOf('\n', MAX_MESSAGE_LENGTH);
            if (splitAt <= 0) {
                splitAt = text.lastIndexOf(' ', MAX_MESSAGE_LENGTH);
            }
            if (splitAt <= 0) {
                splitAt = MAX_MESSAGE_LENGTH;
            }
            parts.add(text.substring(0, splitAt));
            text = text.substring(splitAt).stripLeading();
        }

        if (!text.isEmpty()) {
            parts.add(text);
        }

        return parts;
    }

    public static String fitMessage(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.length() <= MAX_MESSAGE_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_MESSAGE_LENGTH);
    }
}
