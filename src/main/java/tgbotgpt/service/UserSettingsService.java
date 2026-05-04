package tgbotgpt.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tgbotgpt.model.entity.BotUser;
import tgbotgpt.repository.BotUserRepository;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    @Value("${billing.default.plan:free}")
    private String defaultBillingPlan;

    @Value("${billing.free.monthly.tokens:50000}")
    private int freeMonthlyTokens;

    @Value("${billing.free.monthly.messages:100}")
    private int freeMonthlyMessages;

    @Value("${billing.pro.monthly.tokens:1000000}")
    private int proMonthlyTokens;

    @Value("${billing.pro.monthly.messages:2000}")
    private int proMonthlyMessages;

    @Value("${billing.pro.default.days:30}")
    private int proDefaultDays;

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
            ensureBillingDefaults(user);
            if (username != null) user.setUsername(username);
            if (firstName != null) user.setFirstName(firstName);
            return userRepository.save(user);
        }).orElseGet(() -> {
            BotUser user = new BotUser(userId, username, firstName);
            user.setBillingPlan(normalizePlan(defaultBillingPlan));
            user.setUsagePeriod(currentPeriod());
            return userRepository.save(user);
        });
    }

    @Transactional
    public void recordUsage(Long userId, int tokens) {
        BotUser user = getOrCreateUser(userId, null, null);
        ensureCurrentBillingPeriod(user);
        user.addTokens(tokens);
        user.addPeriodTokens(tokens);
        user.incrementMessages();
        user.incrementPeriodMessages();
        userRepository.save(user);
    }

    @Transactional
    public void recordMessage(Long userId) {
        BotUser user = getOrCreateUser(userId, null, null);
        ensureCurrentBillingPeriod(user);
        user.incrementMessages();
        user.incrementPeriodMessages();
        userRepository.save(user);
    }

    @Transactional
    public boolean setBillingPlan(Long userId, String plan) {
        return setBillingPlan(userId, plan, proDefaultDays);
    }

    @Transactional
    public boolean setBillingPlan(Long userId, String plan, int days) {
        String normalized = plan == null ? "" : plan.trim().toLowerCase(Locale.ROOT);
        if (!isKnownPlan(normalized)) {
            return false;
        }
        BotUser user = getOrCreateUser(userId, null, null);
        ensureCurrentBillingPeriod(user);
        applyBillingPlan(user, normalized, days, false);
        userRepository.save(user);
        return true;
    }

    @Transactional
    public boolean extendProPlan(Long userId, int days) {
        if (days <= 0) {
            return false;
        }
        BotUser user = getOrCreateUser(userId, null, null);
        ensureCurrentBillingPeriod(user);
        applyBillingPlan(user, "pro", days, true);
        userRepository.save(user);
        return true;
    }

    @Transactional
    public boolean downgradeToFree(Long userId) {
        BotUser user = getOrCreateUser(userId, null, null);
        ensureCurrentBillingPeriod(user);
        applyBillingPlan(user, "free", 0, false);
        userRepository.save(user);
        return true;
    }

    @Transactional
    public UsageStatus getUsageStatus(Long userId, boolean owner) {
        BotUser user = getOrCreateUser(userId, null, null);
        ensureCurrentBillingPeriod(user);
        userRepository.save(user);
        String plan = effectivePlan(user, owner);
        return new UsageStatus(
                plan,
                user.getUsagePeriod(),
                user.getTotalTokensUsed(),
                user.getTotalMessages(),
                user.getPeriodTokensUsed(),
                user.getPeriodMessages(),
                tokenLimit(plan),
                messageLimit(plan),
                user.getPlanExpiresAt()
        );
    }

    @Transactional
    public Optional<String> checkUsageLimit(Long userId, boolean owner) {
        UsageStatus status = getUsageStatus(userId, owner);
        if (status.unlimited()) {
            return Optional.empty();
        }
        boolean tokensExceeded = status.tokenLimit() >= 0 && status.periodTokensUsed() >= status.tokenLimit();
        boolean messagesExceeded = status.messageLimit() >= 0 && status.periodMessages() >= status.messageLimit();
        if (!tokensExceeded && !messagesExceeded) {
            return Optional.empty();
        }
        return Optional.of("""
                Monthly limit reached for plan %s.
                Used this month: %d/%s tokens, %d/%s messages.
                Use /plan to see available plans or /upgrade to request Pro.
                """.formatted(
                status.plan().toUpperCase(Locale.ROOT),
                status.periodTokensUsed(),
                formatLimit(status.tokenLimit()),
                status.periodMessages(),
                formatLimit(status.messageLimit())
        ).strip());
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

    public Set<String> getAvailableBillingPlans() {
        return Set.of("free", "pro", "owner");
    }

    public int getMonthlyTokenLimit(String plan) {
        return tokenLimit(plan);
    }

    public int getMonthlyMessageLimit(String plan) {
        return messageLimit(plan);
    }

    public List<AdminUserSummary> getRecentUsers(int limit) {
        int actualLimit = Math.max(1, limit);
        Comparator<BotUser> byLastActive = Comparator.comparing(
                BotUser::getLastActive,
                Comparator.nullsLast(Comparator.naturalOrder())
        );
        return userRepository.findAll().stream()
                .sorted(byLastActive.reversed())
                .limit(actualLimit)
                .map(user -> new AdminUserSummary(
                        user.getTelegramId(),
                        user.getUsername(),
                        normalizePlan(user.getBillingPlan()),
                        user.getPlanExpiresAt(),
                        user.getUsagePeriod(),
                        user.getPeriodTokensUsed(),
                        user.getPeriodMessages(),
                        user.getTotalTokensUsed(),
                        user.getTotalMessages()
                ))
                .toList();
    }

    @Scheduled(cron = "${billing.expiration.cleanup.cron:0 15 3 * * *}")
    @Transactional
    public void cleanupExpiredPlans() {
        int downgraded = downgradeExpiredPlans();
        if (downgraded > 0) {
            log.info("Downgraded {} expired Pro users to Free", downgraded);
        }
    }

    @Transactional
    public int downgradeExpiredPlans() {
        List<BotUser> expired = userRepository.findByBillingPlanAndPlanExpiresAtBefore("pro", LocalDateTime.now());
        expired.forEach(user -> applyBillingPlan(user, "free", 0, false));
        userRepository.saveAll(expired);
        return expired.size();
    }

    private void ensureBillingDefaults(BotUser user) {
        if (user.getBillingPlan() == null || user.getBillingPlan().isBlank()) {
            user.setBillingPlan(normalizePlan(defaultBillingPlan));
        } else {
            user.setBillingPlan(normalizePlan(user.getBillingPlan()));
        }
        if ("pro".equals(user.getBillingPlan()) && isExpired(user.getPlanExpiresAt())) {
            user.setBillingPlan("free");
            user.setPlanExpiresAt(null);
        }
        if ("pro".equals(user.getBillingPlan()) && user.getPlanExpiresAt() == null) {
            user.setPlanExpiresAt(LocalDateTime.now().plusDays(Math.max(1, proDefaultDays)));
        }
        if (!"pro".equals(user.getBillingPlan())) {
            user.setPlanExpiresAt(null);
        }
        if (user.getUsagePeriod() == null || user.getUsagePeriod().isBlank()) {
            user.setUsagePeriod(currentPeriod());
        }
    }

    private void ensureCurrentBillingPeriod(BotUser user) {
        ensureBillingDefaults(user);
        String current = currentPeriod();
        if (!current.equals(user.getUsagePeriod())) {
            user.resetBillingPeriod(current);
        }
    }

    private String effectivePlan(BotUser user, boolean owner) {
        if (owner) {
            return "owner";
        }
        return normalizePlan(user.getBillingPlan());
    }

    private String normalizePlan(String plan) {
        String normalized = plan == null ? "" : plan.trim().toLowerCase(Locale.ROOT);
        if (!isKnownPlan(normalized)) {
            return "free";
        }
        return normalized;
    }

    private boolean isKnownPlan(String plan) {
        return "free".equals(plan) || "pro".equals(plan) || "owner".equals(plan);
    }

    private void applyBillingPlan(BotUser user, String plan, int days, boolean extend) {
        user.setBillingPlan(plan);
        if ("pro".equals(plan)) {
            int actualDays = Math.max(1, days);
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime base = extend && user.getPlanExpiresAt() != null && user.getPlanExpiresAt().isAfter(now)
                    ? user.getPlanExpiresAt()
                    : now;
            user.setPlanExpiresAt(base.plusDays(actualDays));
            return;
        }
        user.setPlanExpiresAt(null);
    }

    private boolean isExpired(LocalDateTime expiresAt) {
        return expiresAt != null && !expiresAt.isAfter(LocalDateTime.now());
    }

    private int tokenLimit(String plan) {
        return switch (normalizePlan(plan)) {
            case "pro" -> proMonthlyTokens;
            case "owner" -> -1;
            default -> freeMonthlyTokens;
        };
    }

    private int messageLimit(String plan) {
        return switch (normalizePlan(plan)) {
            case "pro" -> proMonthlyMessages;
            case "owner" -> -1;
            default -> freeMonthlyMessages;
        };
    }

    private String currentPeriod() {
        return YearMonth.now().toString();
    }

    private String formatLimit(int limit) {
        return limit < 0 ? "unlimited" : String.valueOf(limit);
    }

    public record UsageStatus(
            String plan,
            String period,
            int totalTokensUsed,
            int totalMessages,
            int periodTokensUsed,
            int periodMessages,
            int tokenLimit,
            int messageLimit,
            LocalDateTime planExpiresAt
    ) {
        public boolean unlimited() {
            return tokenLimit < 0 && messageLimit < 0;
        }

        public int remainingTokens() {
            return tokenLimit < 0 ? -1 : Math.max(0, tokenLimit - periodTokensUsed);
        }

        public int remainingMessages() {
            return messageLimit < 0 ? -1 : Math.max(0, messageLimit - periodMessages);
        }
    }

    public record AdminUserSummary(
            Long telegramId,
            String username,
            String plan,
            LocalDateTime planExpiresAt,
            String period,
            int periodTokensUsed,
            int periodMessages,
            int totalTokensUsed,
            int totalMessages
    ) {
    }
}
