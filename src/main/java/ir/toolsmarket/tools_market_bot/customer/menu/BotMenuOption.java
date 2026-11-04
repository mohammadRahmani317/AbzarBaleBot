package ir.toolsmarket.tools_market_bot.customer.menu;

import java.util.Arrays;
import java.util.Optional;

public enum BotMenuOption {

    VIEW_PRODUCTS("🛒 مشاهده محصولات"),
    CREATE_ORDER("📦 ثبت سفارش"),
    CONTACT_SELLER("📞 ارتباط با فروشنده"),
    BACK_TO_CATEGORIES("🔙 بازگشت به دسته‌بندی‌ها"),
    BACK_TO_MAIN("🏠 منوی اصلی"),
    BACK_TO_MAIN_FROM_CATEGORY("🔙 بازگشت به منوی اصلی");

    private final String title;

    BotMenuOption(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }

    public static Optional<BotMenuOption> fromTitle(String title) {
        return Arrays.stream(values())
                .filter(option -> option.title.equals(title))
                .findFirst();
    }
}