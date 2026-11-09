package ir.toolsmarket.tools_market_bot.model;

import java.time.Instant;

public record Customer(String chatId, String name, String phoneNumber,
                       Instant registeredAt, Instant updatedAt) {
    public boolean complete() {
        return name != null && !name.isBlank() && phoneNumber != null && !phoneNumber.isBlank();
    }
}
