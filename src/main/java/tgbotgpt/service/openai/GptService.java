package tgbotgpt.service.openai;

import com.pengrad.telegrambot.model.Chat;
import com.pengrad.telegrambot.model.Update;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import tgbotgpt.clients.OpenAIApiClient;
import tgbotgpt.model.dto.Message;
import tgbotgpt.model.dto.request.ChatRequest;
import tgbotgpt.model.dto.response.ChatResponse;
import tgbotgpt.model.dto.response.StreamChunk;
import tgbotgpt.service.RateLimiter;
import tgbotgpt.service.UserSettingsService;
import tgbotgpt.utils.MessageLog;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GptService {

    private final OpenAIApiClient client;
    private final Environment env;
    private final RateLimiter rateLimiter;
    private final UserSettingsService userSettings;

    @Value("${openai.maxtokens}")
    private Integer maxtokens;
    @Value("${openai.temperature}")
    private Double temperature;
    @Value("${openai.systemprompt}")
    private String systemprompt;
    @Value("${openai.max.message.pool.size}")
    private Integer maxMessagePoolSize;
    @Value("${bot.presentation}")
    private String presentation;
    @Value("#{'${bot.whitelist:}'.empty ? null : '${bot.whitelist}'.split(',')}")
    private List<String> whiteList;

    private Set<String> whiteSet;
    private List<String> examples;
    private final Map<Long, MessageLog<Message>> userContext = new ConcurrentHashMap<>();
    private final AtomicInteger ntokens = new AtomicInteger(0);

    public GptService(OpenAIApiClient client, Environment env, RateLimiter rateLimiter, UserSettingsService userSettings) {
        this.client = client;
        this.env = env;
        this.rateLimiter = rateLimiter;
        this.userSettings = userSettings;
    }

    public int getNumTokens() {
        return ntokens.get();
    }

    @PostConstruct
    private void init() {
        loadExamples();
        initializeWhitelist();
    }

    private void loadExamples() {
        examples = new ArrayList<>();
        int i = 1;
        while (env.containsProperty("openai.example." + i)) {
            examples.add(env.getProperty("openai.example." + i));
            i++;
        }
    }

    private void initializeWhitelist() {
        if (whiteList != null && !whiteList.isEmpty()) {
            whiteSet = whiteList.stream().map(String::toLowerCase).collect(Collectors.toSet());
        } else {
            whiteSet = Collections.emptySet();
        }
    }

    public String sendMessage(Update update) {
        Long userId = update.message().from().id();

        if (!checkPermission(update)) {
            return "Sorry, you are not in the access list.";
        }

        if (!rateLimiter.isAllowed(userId)) {
            long seconds = rateLimiter.getSecondsUntilReset(userId);
            return String.format("Rate limit exceeded. Try again in %d seconds.", seconds);
        }

        try {
            ChatRequest chatRequest = createChatRequest(update);
            ChatResponse response = client.getCompletion(chatRequest).block();
            ntokens.addAndGet(Objects.requireNonNull(response).getUsage().getTotalTokens());

            if (isPrivate(update)) {
                addAssistantMessage(update, response.getChoices().get(0).getMessage().getContent());
            }

            return response.getChoices().get(0).getMessage().getContent();
        } catch (Exception e) {
            log.error("Error: ", e);
            return "Sorry, something went wrong.";
        }
    }

    /**
     * Sends a message and returns a streaming Flux of text chunks.
     * Each emitted string is the content delta from OpenAI.
     */
    public Flux<String> sendMessageStream(Update update) {
        Long userId = update.message().from().id();

        if (!checkPermission(update)) {
            return Flux.just("Sorry, you are not in the access list.");
        }

        if (!rateLimiter.isAllowed(userId)) {
            long seconds = rateLimiter.getSecondsUntilReset(userId);
            return Flux.just(String.format("Rate limit exceeded. Try again in %d seconds.", seconds));
        }

        try {
            ChatRequest chatRequest = createChatRequest(update);
            StringBuilder fullResponse = new StringBuilder();

            return client.getCompletionStream(chatRequest)
                    .filter(chunk -> chunk.getChoices() != null && !chunk.getChoices().isEmpty())
                    .map(chunk -> {
                        StreamChunk sc = chunk;
                        String content = sc.getChoices().get(0).getDelta() != null
                                ? sc.getChoices().get(0).getDelta().getContent()
                                : null;
                        if (content != null) {
                            fullResponse.append(content);
                        }
                        return content != null ? content : "";
                    })
                    .filter(s -> !s.isEmpty())
                    .doOnComplete(() -> {
                        if (isPrivate(update) && !fullResponse.isEmpty()) {
                            addAssistantMessage(update, fullResponse.toString());
                        }
                    })
                    .doOnError(e -> log.error("Stream error: ", e));
        } catch (Exception e) {
            log.error("Error: ", e);
            return Flux.just("Sorry, something went wrong.");
        }
    }

    public String sendCustomMessage(Update update, String text) {
        try {
            ChatRequest chatRequest = createCustomChatRequest(text);
            ChatResponse response = client.getCompletion(chatRequest).block();
            ntokens.addAndGet(Objects.requireNonNull(response).getUsage().getTotalTokens());
            return response.getChoices().get(0).getMessage().getContent();
        } catch (Exception e) {
            log.error("Error: ", e);
            return "Sorry, something went wrong.";
        }
    }

    public String resetUserContext(Update update) {
        if (isPrivate(update)) {
            userContext.remove(update.message().from().id());
            return "User context has been reset for " + update.message().from().firstName();
        } else {
            return "Nothing to reset in a group chat.";
        }
    }

    public String setUserModel(Long userId, String model) {
        if (userSettings.setModel(userId, model)) {
            return "Model set to: " + model;
        }
        return "Unknown model. Available: " + String.join(", ", userSettings.getAllowedModels());
    }

    public String getUserModel(Long userId) {
        return userSettings.getModel(userId);
    }

    private ChatRequest createChatRequest(Update update) {
        Long userId = update.message().from().id();
        String userModel = userSettings.getModel(userId);

        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setModel(userModel);
        chatRequest.setTemperature(temperature);
        chatRequest.setMaxTokens(maxtokens);

        List<Message> messages = new ArrayList<>();
        Message systemMessage = new Message();
        systemMessage.setRole("system");
        systemMessage.setContent(systemprompt);
        messages.add(systemMessage);

        if (isPrivate(update)) {
            if (!userContext.containsKey(userId)) {
                userContext.put(userId, new MessageLog<>(maxMessagePoolSize));
                if (!examples.isEmpty()) {
                    userContext.get(userId).addAll(getExamples());
                }
            }
            addUserMessage(update);
            messages.addAll(userContext.get(userId));
        } else {
            if (!examples.isEmpty()) {
                messages.addAll(getExamples());
            }
            Message userMessage = new Message();
            userMessage.setRole("user");
            userMessage.setContent(update.message().text());
            messages.add(userMessage);
        }

        chatRequest.setMessages(messages);
        return chatRequest;
    }

    private ChatRequest createCustomChatRequest(String text) {
        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setModel(userSettings.getDefaultModel());
        chatRequest.setTemperature(temperature);
        chatRequest.setMaxTokens(maxtokens);

        List<Message> messages = new ArrayList<>();
        Message systemMessage = new Message();
        systemMessage.setRole("system");
        systemMessage.setContent(systemprompt);
        messages.add(systemMessage);

        Message userMessage = new Message();
        userMessage.setRole("user");
        userMessage.setContent(text);
        messages.add(userMessage);

        chatRequest.setMessages(messages);
        return chatRequest;
    }

    private List<Message> getExamples() {
        return examples.stream().map(this::createMessage).collect(Collectors.toList());
    }

    private Message createMessage(String example) {
        String[] parts = example.split(":", 2);
        String role = parts[0];
        String content = parts[1];

        Message message = new Message();
        message.setRole(role.toLowerCase());
        message.setContent(content);
        return message;
    }

    private void addUserMessage(Update update) {
        Message userMessage = new Message();
        userMessage.setRole("user");
        userMessage.setContent(update.message().text());
        userContext.get(update.message().from().id()).add(userMessage);
    }

    private void addAssistantMessage(Update update, String content) {
        Message assistantMessage = new Message();
        assistantMessage.setRole("assistant");
        assistantMessage.setContent(content);
        userContext.get(update.message().from().id()).add(assistantMessage);
    }

    boolean checkPermission(Update update) {
        if (whiteSet.isEmpty()) {
            return true;
        }

        String userId = String.valueOf(update.message().from().id());
        String username = update.message().from().username() != null ? update.message().from().username().toLowerCase() : "";
        String groupName = update.message().chat().title() != null ? update.message().chat().title().toLowerCase() : "";

        if (whiteSet.contains(userId) || whiteSet.contains(username) || whiteSet.contains(groupName)) {
            return true;
        }

        log.warn("Unauthorized user: id={}, username={}", userId, username);
        return false;
    }

    private boolean isPrivate(Update update) {
        return update.message().chat().type().equals(Chat.Type.Private);
    }
}
