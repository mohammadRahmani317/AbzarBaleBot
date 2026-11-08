package ir.toolsmarket.tools_market_bot.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProductPriceHistory(
        String id,
        Integer productId,
        BigDecimal previousPrice,
        BigDecimal newPrice,
        String changedByChatId,
        String changedByName,
        LocalDateTime changedAt
) {

    /**
     * سازگاری با کدهای قدیمی که هنوز نام ثبت‌کننده را ارسال نمی‌کنند.
     */
    public ProductPriceHistory(
            String id,
            Integer productId,
            BigDecimal previousPrice,
            BigDecimal newPrice,
            String changedByChatId,
            LocalDateTime changedAt
    ) {
        this(
                id,
                productId,
                previousPrice,
                newPrice,
                changedByChatId,
                null,
                changedAt
        );
    }
}