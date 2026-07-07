package tgbotgpt.service.telegram;

import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.model.User;
import org.junit.jupiter.api.Test;
import tgbotgpt.service.BotAdminService;
import tgbotgpt.service.openai.GptService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdminCommandHandlerTest {

    @Test
    void handleRoutesStatsCommand() {
        GptService gptService = mock(GptService.class);
        BotAdminService adminService = mock(BotAdminService.class);
        when(gptService.getAdminStats(99L)).thenReturn("stats");
        AdminCommandHandler handler = new AdminCommandHandler(gptService, adminService);

        String result = handler.handle(update(99L, "/admin stats"));

        assertEquals("stats", result);
    }

    private Update update(Long userId, String text) {
        Update update = mock(Update.class);
        Message message = mock(Message.class);
        User user = mock(User.class);
        when(update.message()).thenReturn(message);
        when(message.text()).thenReturn(text);
        when(message.from()).thenReturn(user);
        when(user.id()).thenReturn(userId);
        return update;
    }
}
