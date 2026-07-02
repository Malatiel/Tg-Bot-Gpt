package tgbotgpt.service.telegram;

import com.pengrad.telegrambot.model.Update;
import org.springframework.stereotype.Component;
import tgbotgpt.service.BotAdminService;
import tgbotgpt.service.openai.GptService;

@Component
class AdminCommandHandler {

    private static final String USAGE_COMMAND = "Usage: /admin usage <telegram_id>";
    private static final String PLAN_COMMAND = "Usage: /admin plan <telegram_id> <free|pro|owner> [days]";
    private static final String PLAN_PRO_COMMAND = "Usage: /admin plan <telegram_id> pro <days>";
    private static final String APPROVE_COMMAND = "Usage: /admin approve <telegram_id> [days]";
    private static final String EXTEND_COMMAND = "Usage: /admin extend <telegram_id> <days>";
    private static final String DOWNGRADE_COMMAND = "Usage: /admin downgrade <telegram_id>";

    private final GptService gptService;
    private final BotAdminService adminService;

    AdminCommandHandler(GptService gptService, BotAdminService adminService) {
        this.gptService = gptService;
        this.adminService = adminService;
    }

    String handle(Update update) {
        String text = update.message().text().trim();
        Long requesterId = update.message().from().id();
        String[] parts = text.split("\\s+");

        if (parts.length == 1) {
            return gptService.getAdminHelp(requesterId);
        }

        String action = parts[1].toLowerCase();
        return switch (action) {
            case "status" -> adminService.statusFor(requesterId);
            case "users" -> gptService.getAdminUsersSummary(requesterId);
            case "usage" -> handleUsageCommand(requesterId, parts);
            case "plan" -> handlePlanCommand(requesterId, parts);
            case "approve" -> handleApproveCommand(requesterId, parts);
            case "extend" -> handleExtendCommand(requesterId, parts);
            case "downgrade" -> handleDowngradeCommand(requesterId, parts);
            default -> gptService.getAdminHelp(requesterId);
        };
    }

    private String handleUsageCommand(Long requesterId, String[] parts) {
        if (parts.length != 3) {
            return USAGE_COMMAND;
        }
        try {
            Long targetUserId = Long.parseLong(parts[2]);
            return gptService.getAdminUserUsage(requesterId, targetUserId);
        } catch (NumberFormatException e) {
            return USAGE_COMMAND;
        }
    }

    private String handlePlanCommand(Long requesterId, String[] parts) {
        if (parts.length != 4 && parts.length != 5) {
            return PLAN_COMMAND;
        }
        try {
            Long targetUserId = Long.parseLong(parts[2]);
            if (parts.length == 5 && "pro".equalsIgnoreCase(parts[3])) {
                Integer days = parseDays(parts[4]);
                if (days == null) {
                    return PLAN_PRO_COMMAND;
                }
                return gptService.approveUserPro(requesterId, targetUserId, days);
            }
            return gptService.setUserBillingPlan(requesterId, targetUserId, parts[3]);
        } catch (NumberFormatException e) {
            return PLAN_COMMAND;
        }
    }

    private String handleApproveCommand(Long requesterId, String[] parts) {
        if (parts.length != 3 && parts.length != 4) {
            return APPROVE_COMMAND;
        }
        try {
            Long targetUserId = Long.parseLong(parts[2]);
            Integer days = parts.length == 4 ? parseDays(parts[3]) : 30;
            if (days == null) {
                return APPROVE_COMMAND;
            }
            return gptService.approveUserPro(requesterId, targetUserId, days);
        } catch (NumberFormatException e) {
            return APPROVE_COMMAND;
        }
    }

    private String handleExtendCommand(Long requesterId, String[] parts) {
        if (parts.length != 4) {
            return EXTEND_COMMAND;
        }
        try {
            Long targetUserId = Long.parseLong(parts[2]);
            Integer days = parseDays(parts[3]);
            if (days == null) {
                return EXTEND_COMMAND;
            }
            return gptService.extendUserPro(requesterId, targetUserId, days);
        } catch (NumberFormatException e) {
            return EXTEND_COMMAND;
        }
    }

    private String handleDowngradeCommand(Long requesterId, String[] parts) {
        if (parts.length != 3) {
            return DOWNGRADE_COMMAND;
        }
        try {
            Long targetUserId = Long.parseLong(parts[2]);
            return gptService.downgradeUser(requesterId, targetUserId);
        } catch (NumberFormatException e) {
            return DOWNGRADE_COMMAND;
        }
    }

    private Integer parseDays(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase();
        if (normalized.endsWith("d")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            int days = Integer.parseInt(normalized);
            return days > 0 ? days : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
