package ir.toolsmarket.tools_market_bot.model;

import java.util.Arrays;
import java.util.Optional;

public enum ProductCategory {

    POWER_TOOLS("POWER_TOOLS", "🔌 ابزار برقی"),
    HAND_TOOLS("HAND_TOOLS", "🛠 ابزار دستی"),
    DRILL_BITS("DRILL_BITS", "🔩 مته و سری"),
    SAFETY_EQUIPMENT("SAFETY_EQUIPMENT", "🧤 تجهیزات ایمنی");

    private final String code;
    private final String title;

    ProductCategory(String code, String title) {
        this.code = code;
        this.title = title;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public static Optional<ProductCategory> fromTitle(String title) {
        return Arrays.stream(values())
                .filter(category -> category.title.equals(title))
                .findFirst();
    }
}