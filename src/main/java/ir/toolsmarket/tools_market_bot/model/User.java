package ir.toolsmarket.tools_market_bot.model;

public record User(
        String chatId,
        String name,
        UserRole role,
        UserStatus status
) {
}