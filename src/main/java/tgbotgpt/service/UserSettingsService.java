package tgbotgpt.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tgbotgpt.model.entity.BotUser;
import tgbotgpt.repository.BotUserRepository;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserSettingsService {

    @Value("${openai.model}")
    private String defaultModel;

    @Value("${openai.systemprompt}")
    private String defaultSystemPrompt;

    @Value("${openai.allowed.models:gpt-5.4-nano,gpt-5.4-mini,gpt-4o-mini,gpt-4o}")
    private String allowedModelsString;

    @Value("${bot.prompt.max.length:500}")
    private int promptMaxLength;

    private Set<String> allowedModels;
    private final BotUserRepository userRepository;

    public UserSettingsService(BotUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @PostConstruct
    private void init() {
        allowedModels = Arrays.stream(allowedModelsString.split(","))
                .map(String::trim)
                .filter(model -> !model.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public String getModel(Long userId) {
        return userRepository.findById(userId)
                .map(BotUser::getSelectedModel)
                .filter(m -> m != null && !m.isBlank())
                .filter(allowedModels::contains)
                .orElse(defaultModel);
    }

    @Transactional
    public boolean setModel(Long userId, String model) {
        if (!allowedModels.contains(model)) {
            return false;
        }
        BotUser user = getOrCreateUser(userId, null, null);
        user.setSelectedModel(model);
        userRepository.save(user);
        return true;
    }

    public String getSystemPrompt(Long userId) {
        return userRepository.findById(userId)
                .map(BotUser::getCustomPrompt)
                .filter(p -> p != null && !p.isBlank())
                .orElse(defaultSystemPrompt);
    }

    @Transactional
    public String setCustomPrompt(Long userId, String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return resetPrompt(userId);
        }

        String sanitized = sanitizePrompt(prompt);
        if (sanitized.isBlank()) {
            return resetPrompt(userId);
        }
        if (sanitized.length() > promptMaxLength) {
            return String.format("Prompt too long. Max %d characters, yours: %d", promptMaxLength, sanitized.length());
        }

        if (containsInjection(sanitized)) {
            log.warn("Prompt injection attempt blocked for user {}", userId);
            return "This prompt contains disallowed patterns.";
        }

        BotUser user = getOrCreateUser(userId, null, null);
        user.setCustomPrompt(sanitized);
        userRepository.save(user);
        return "Custom prompt set.";
    }

    @Transactional
    public String resetPrompt(Long userId) {
        Optional<BotUser> opt = userRepository.findById(userId);
        if (opt.isPresent()) {
            BotUser user = opt.get();
            user.setCustomPrompt(null);
            userRepository.save(user);
        }
        return "Prompt reset to default.";
    }

    @Transactional
    public BotUser getOrCreateUser(Long userId, String username, String firstName) {
        return userRepository.findById(userId).map(user -> {
            if (username != null) user.setUsername(username);
            if (firstName != null) user.setFirstName(firstName);
            return userRepository.save(user);
        }).orElseGet(() -> userRepository.save(new BotUser(userId, username, firstName)));
    }

    @Transactional
    public void recordUsage(Long userId, int tokens) {
        BotUser user = getOrCreateUser(userId, null, null);
        user.addTokens(tokens);
        user.incrementMessages();
        userRepository.save(user);
    }

    @Transactional
    public void recordMessage(Long userId) {
        BotUser user = getOrCreateUser(userId, null, null);
        user.incrementMessages();
        userRepository.save(user);
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public Set<String> getAllowedModels() {
        return allowedModels;
    }

    /**
     * Patterns that indicate prompt injection attempts.
     * These target explicit override/jailbreak patterns, not creative content.
     */
    private static final List<Pattern> INJECTION_PATTERNS = List.of(
            Pattern.compile("ignore\\s+(all\\s+)?(previous|above|prior)\\s+(instructions|prompts|rules)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("disregard\\s+(all\\s+)?(previous|above|prior)\\s+(instructions|prompts|rules)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("forget\\s+(all\\s+)?(previous|above|prior)\\s+(instructions|prompts|rules)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("you\\s+are\\s+now\\s+(DAN|in\\s+developer\\s+mode|unrestricted|jailbroken)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bDAN\\s+mode\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("do\\s+anything\\s+now", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bsystem\\s*:\\s*", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\]\\s*\\}.*\\{\\s*\"role\"\\s*:", Pattern.CASE_INSENSITIVE)
    );

    /**
     * Check if text contains prompt injection patterns.
     * Returns true if suspicious content detected.
     */
    public boolean containsInjection(String text) {
        if (text == null || text.isBlank()) return false;
        return INJECTION_PATTERNS.stream().anyMatch(p -> p.matcher(text).find());
    }

    /**
     * Strip control characters from user-provided prompts.
     */
    private String sanitizePrompt(String prompt) {
        return prompt
                .replaceAll("[\\p{Cntrl}&&[^\n]]", "")
                .trim();
    }

    public int getUserTokens(Long userId) {
        return userRepository.findById(userId)
                .map(BotUser::getTotalTokensUsed)
                .orElse(0);
    }

    public int getUserMessages(Long userId) {
        return userRepository.findById(userId)
                .map(BotUser::getTotalMessages)
                .orElse(0);
    }
}
