package ir.toolsmarket.tools_market_bot.model;

import java.math.BigDecimal;

public record OrderItem(Integer productId, String productName, Integer quantity,
                        BigDecimal unitPriceSnapshot) {
    public BigDecimal total() {
        return unitPriceSnapshot == null || quantity == null ? BigDecimal.ZERO
                : unitPriceSnapshot.multiply(BigDecimal.valueOf(quantity));
    }
}
