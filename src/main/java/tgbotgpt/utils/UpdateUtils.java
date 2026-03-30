package tgbotgpt.utils;

import com.pengrad.telegrambot.model.Chat;
import com.pengrad.telegrambot.model.Update;

public final class UpdateUtils {

    private UpdateUtils() {}

    public static boolean isPrivate(Update update) {
        return update.message().chat().type().equals(Chat.Type.Private);
    }
}
