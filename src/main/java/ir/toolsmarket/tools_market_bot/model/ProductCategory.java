package ir.toolsmarket.tools_market_bot.model;

import java.util.Arrays;
import java.util.Optional;

public enum ProductCategory {

    HAND_TOOLS("HAND_TOOLS", "🛠 ابزار دستی"),

    HOME_SUPPLIES("HOME_SUPPLIES", "🏠 لوازم کاربردی منزل"),

    CONSUMABLES("CONSUMABLES", "🧴 چسب و لوازم مصرفی"),

    TOOL_ACCESSORIES("TOOL_ACCESSORIES", "🧰 کیف و لوازم ابزار"),

    SAFETY_EQUIPMENT("SAFETY_EQUIPMENT", "🦺 تجهیزات ایمنی"),

    MEASURING_TOOLS("MEASURING_TOOLS", "📏 ابزار اندازه‌گیری");

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