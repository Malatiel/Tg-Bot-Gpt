package tgbotgpt.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserSettingsService {

    @Value("${openai.model}")
    private String defaultModel;

    @Value("${openai.allowed.models:gpt-4o-mini,gpt-4o,gpt-4-turbo,gpt-3.5-turbo}")
    private String allowedModelsString;

    private Set<String> allowedModels;
    private final ConcurrentHashMap<Long, String> userModels = new ConcurrentHashMap<>();

    @PostConstruct
    private void init() {
        allowedModels = Set.of(allowedModelsString.split(","));
    }

    public String getModel(Long userId) {
        return userModels.getOrDefault(userId, defaultModel);
    }

    public boolean setModel(Long userId, String model) {
        if (!allowedModels.contains(model)) {
            return false;
        }
        userModels.put(userId, model);
        return true;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public Set<String> getAllowedModels() {
        return allowedModels;
    }
}
