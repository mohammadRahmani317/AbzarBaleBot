package ir.toolsmarket.tools_market_bot.customer.menu;

import java.util.Arrays;
import java.util.Optional;

public enum BotMenuOption {

    VIEW_PRODUCTS(ir.toolsmarket.tools_market_bot.bot.BotButton.VIEW_PRODUCTS),
    CREATE_ORDER(ir.toolsmarket.tools_market_bot.bot.BotButton.CREATE_ORDER),
    MY_ORDERS(ir.toolsmarket.tools_market_bot.bot.BotButton.MY_ORDERS),
    CONTACT_SELLER(ir.toolsmarket.tools_market_bot.bot.BotButton.CONTACT_SELLER),
    BACK_TO_CATEGORIES(ir.toolsmarket.tools_market_bot.bot.BotButton.BACK_TO_CATEGORIES),
    BACK_TO_MAIN(ir.toolsmarket.tools_market_bot.bot.BotButton.BACK_TO_MAIN),
    BACK_TO_MAIN_FROM_CATEGORY(ir.toolsmarket.tools_market_bot.bot.BotButton.BACK_TO_MAIN_FROM_CATEGORY);

    private final String title;

    BotMenuOption(ir.toolsmarket.tools_market_bot.bot.BotButton button) {
        this.title = button.text();
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