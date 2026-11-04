package ir.toolsmarket.tools_market_bot.model;

public record Product(
        Integer id,
        String categoryCode,
        String name,
        String description,
        String priceLabel,
        String imagePath
) {
}