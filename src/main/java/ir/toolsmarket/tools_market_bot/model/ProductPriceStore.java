package ir.toolsmarket.tools_market_bot.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ProductPriceStore(
        Map<Integer, BigDecimal> prices,
        List<ProductPriceHistory> history
) {
    public ProductPriceStore {
        prices = prices == null ? Map.of() : Map.copyOf(prices);
        history = history == null ? List.of() : List.copyOf(history);
    }

    public static ProductPriceStore empty() {
        return new ProductPriceStore(Map.of(), List.of());
    }
}