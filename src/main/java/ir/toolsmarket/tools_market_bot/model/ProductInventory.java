package ir.toolsmarket.tools_market_bot.model;

import java.time.Instant;

public record ProductInventory(Integer productId, int totalQuantity, int reservedQuantity, Instant updatedAt) {
    public ProductInventory {
        if (totalQuantity < 0 || reservedQuantity < 0 || reservedQuantity > totalQuantity) {
            throw new IllegalArgumentException("موجودی نامعتبر است؛ موجودی کل نباید کمتر از رزرو باشد.");
        }
    }
    public int availableQuantity() { return totalQuantity - reservedQuantity; }
}
