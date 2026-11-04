package ir.toolsmarket.tools_market_bot.model;

import java.time.Instant;

public record Order(
        String orderId,
        String chatId,
        Integer productId,
        String productName,
        Integer quantity,
        String customerName,
        String phoneNumber,
        Instant createdAt
) {
}