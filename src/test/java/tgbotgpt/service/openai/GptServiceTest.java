package tgbotgpt.service.openai;

import com.pengrad.telegrambot.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import tgbotgpt.clients.OpenAIApiClient;
import tgbotgpt.model.dto.Choice;
import tgbotgpt.model.dto.Message;
import tgbotgpt.model.dto.Usage;
import tgbotgpt.model.dto.request.ChatRequest;
import tgbotgpt.model.dto.response.ChatResponse;
import tgbotgpt.service.RateLimiter;
import tgbotgpt.service.UserSettingsService;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GptServiceTest {

    @Mock
    private OpenAIApiClient client;
    @Mock
    private Environment env;
    @Mock
    private RateLimiter rateLimiter;
    @Mock
    private UserSettingsService userSettings;

    private GptService gptService;

    @BeforeEach
    void setUp() {
        gptService = new GptService(client, env, rateLimiter, userSettings);
        ReflectionTestUtils.setField(gptService, "maxtokens", 3000);
        ReflectionTestUtils.setField(gptService, "temperature", 0.7);
        ReflectionTestUtils.setField(gptService, "systemprompt", "You are a helpful assistant.");
        ReflectionTestUtils.setField(gptService, "maxMessagePoolSize", 7);
        ReflectionTestUtils.setField(gptService, "presentation", "Hello");
        ReflectionTestUtils.setField(gptService, "whiteList", null);
        ReflectionTestUtils.setField(gptService, "whiteSet", Collections.emptySet());
        ReflectionTestUtils.setField(gptService, "examples", Collections.emptyList());
    }

    @Test
    void shouldReturnResponseOnSendMessage() {
        Update update = createPrivateUpdate(1L, "Hello");
        when(rateLimiter.isAllowed(1L)).thenReturn(true);
        when(userSettings.getModel(1L)).thenReturn("gpt-4o-mini");
        when(client.getCompletion(any(ChatRequest.class))).thenReturn(Mono.just(createResponse("Hi there!")));

        String result = gptService.sendMessage(update);

        assertEquals("Hi there!", result);
        verify(client).getCompletion(any(ChatRequest.class));
    }

    @Test
    void shouldBlockWhenRateLimited() {
        Update update = createPrivateUpdate(1L, "Hello");
        when(rateLimiter.isAllowed(1L)).thenReturn(false);
        when(rateLimiter.getSecondsUntilReset(1L)).thenReturn(30L);

        String result = gptService.sendMessage(update);

        assertTrue(result.contains("Rate limit exceeded"));
        assertTrue(result.contains("30"));
        verify(client, never()).getCompletion(any());
    }

    @Test
    void shouldReturnErrorOnAccessDenied() {
        ReflectionTestUtils.setField(gptService, "whiteSet", java.util.Set.of("alloweduser"));
        Update update = createPrivateUpdate(1L, "Hello");

        String result = gptService.sendMessage(update);

        assertEquals("Sorry, you are not in the access list.", result);
        verify(client, never()).getCompletion(any());
    }

    @Test
    void shouldSetUserModel() {
        when(userSettings.setModel(1L, "gpt-4o")).thenReturn(true);

        String result = gptService.setUserModel(1L, "gpt-4o");

        assertEquals("Model set to: gpt-4o", result);
    }

    @Test
    void shouldRejectInvalidModel() {
        when(userSettings.setModel(1L, "invalid")).thenReturn(false);
        when(userSettings.getAllowedModels()).thenReturn(java.util.Set.of("gpt-4o-mini", "gpt-4o"));

        String result = gptService.setUserModel(1L, "invalid");

        assertTrue(result.contains("Unknown model"));
    }

    @Test
    void shouldHandleApiError() {
        Update update = createPrivateUpdate(1L, "Hello");
        when(rateLimiter.isAllowed(1L)).thenReturn(true);
        when(userSettings.getModel(1L)).thenReturn("gpt-4o-mini");
        when(client.getCompletion(any(ChatRequest.class))).thenReturn(Mono.error(new RuntimeException("API error")));

        String result = gptService.sendMessage(update);

        assertEquals("Sorry, something went wrong.", result);
    }

    @Test
    void shouldResetUserContext() {
        Update update = createPrivateUpdate(1L, "/reset");

        String result = gptService.resetUserContext(update);

        assertTrue(result.contains("reset"));
    }

    @Test
    void shouldTrackTokens() {
        Update update = createPrivateUpdate(1L, "Hello");
        when(rateLimiter.isAllowed(1L)).thenReturn(true);
        when(userSettings.getModel(1L)).thenReturn("gpt-4o-mini");

        ChatResponse response = createResponse("Hi");
        response.getUsage().setTotalTokens(42);
        when(client.getCompletion(any(ChatRequest.class))).thenReturn(Mono.just(response));

        gptService.sendMessage(update);

        assertEquals(42, gptService.getNumTokens());
    }

    @Test
    void shouldUsePerUserModel() {
        Update update = createPrivateUpdate(1L, "Hello");
        when(rateLimiter.isAllowed(1L)).thenReturn(true);
        when(userSettings.getModel(1L)).thenReturn("gpt-4o");
        when(client.getCompletion(any(ChatRequest.class))).thenReturn(Mono.just(createResponse("Hi")));

        gptService.sendMessage(update);

        verify(userSettings).getModel(1L);
        verify(client).getCompletion(argThat(req -> "gpt-4o".equals(req.getModel())));
    }

    private Update createPrivateUpdate(Long userId, String text) {
        Update update = mock(Update.class);
        com.pengrad.telegrambot.model.Message message = mock(com.pengrad.telegrambot.model.Message.class);
        User user = mock(User.class);
        Chat chat = mock(Chat.class);

        lenient().when(update.message()).thenReturn(message);
        lenient().when(message.text()).thenReturn(text);
        lenient().when(message.from()).thenReturn(user);
        lenient().when(message.chat()).thenReturn(chat);
        lenient().when(user.id()).thenReturn(userId);
        lenient().when(user.firstName()).thenReturn("TestUser");
        lenient().when(user.username()).thenReturn("testuser");
        lenient().when(chat.type()).thenReturn(Chat.Type.Private);
        lenient().when(chat.title()).thenReturn(null);

        return update;
    }

    private ChatResponse createResponse(String content) {
        Message msg = new Message();
        msg.setRole("assistant");
        msg.setContent(content);

        Choice choice = new Choice();
        choice.setMessage(msg);

        Usage usage = new Usage();
        usage.setTotalTokens(10);

        ChatResponse response = new ChatResponse();
        response.setChoices(List.of(choice));
        response.setUsage(usage);

        return response;
    }
}
