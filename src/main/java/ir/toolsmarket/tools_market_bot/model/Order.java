package ir.toolsmarket.tools_market_bot.model;

import java.math.BigDecimal;
import java.time.Instant;

public record Order(
        String orderId,
        String chatId,
        Integer productId,
        String productName,
        Integer quantity,
        String customerName,
        String phoneNumber,
        Instant createdAt,
        BigDecimal unitPriceSnapshot
) {

    // سازگاری با سفارش‌های قدیمی و سازنده قبلی
    public Order(
            String orderId, String chatId, Integer productId, String productName,
            Integer quantity, String customerName, String phoneNumber, Instant createdAt
    ) {
        this(orderId, chatId, productId, productName, quantity, customerName,
                phoneNumber, createdAt, null);
    }
}