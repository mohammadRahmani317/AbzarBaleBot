package ir.toolsmarket.tools_market_bot.bot;

import ir.toolsmarket.tools_market_bot.bot.BotCallback.Action;
import ir.toolsmarket.tools_market_bot.model.ProductCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BotCallbackTest {
    @Test void existingCallbackWiresKeepTheirMeaningAndRoundTrip() {
        Map<String, Action> existing = Map.ofEntries(
                Map.entry("product:detail:1", Action.PRODUCT_DETAIL), Map.entry("product:order:2", Action.PRODUCT_ORDER),
                Map.entry("category:HAND_TOOLS", Action.CATEGORY), Map.entry("nav:categories", Action.NAV_CATEGORIES),
                Map.entry("cart:remove:3", Action.CART_REMOVE), Map.entry("cart:checkout", Action.CART_CHECKOUT),
                Map.entry("profile:name", Action.PROFILE_NAME), Map.entry("profile:phone", Action.PROFILE_PHONE),
                Map.entry("invoice:confirm:ORD-123", Action.INVOICE_CONFIRM), Map.entry("invoice:cancel:ORD-123", Action.INVOICE_CANCEL),
                Map.entry("order:confirm", Action.LEGACY_CONFIRM), Map.entry("order:cancel", Action.LEGACY_CANCEL),
                Map.entry("price:history:list", Action.PRICE_HISTORY_LIST), Map.entry("price:history:1", Action.PRICE_HISTORY),
                Map.entry("price:edit:1", Action.PRICE_EDIT), Map.entry("inventory:edit:1", Action.INVENTORY_EDIT),
                Map.entry("orders:list:active", Action.ORDER_LIST), Map.entry("orders:view:ORD-123", Action.ORDER_VIEW),
                Map.entry("orders:prepare:ORD-123", Action.ORDER_PREPARE), Map.entry("orders:deliver:ORD-123", Action.ORDER_DELIVER),
                Map.entry("access:approve:1972662390", Action.ACCESS_APPROVE), Map.entry("access:reject:123", Action.ACCESS_REJECT),
                Map.entry("access:deactivate-admin:123", Action.ACCESS_DEACTIVATE_ADMIN), Map.entry("access:price-grant:123", Action.ACCESS_PRICE_GRANT));
        existing.forEach((wire, action) -> {
            BotCallback parsed = BotCallback.parse(wire);
            assertEquals(action, parsed.action()); assertEquals(wire, parsed.encode());
        });
        assertEquals(1, BotCallback.parse("product:detail:1").productId());
        assertEquals(ProductCategory.HAND_TOOLS, BotCallback.parse("category:HAND_TOOLS").category());
        assertEquals(BotCallback.OrderGroup.ACTIVE, BotCallback.parse("orders:list:active").group());
    }
    @ParameterizedTest
    @ValueSource(strings = {"", "unknown:confirm:1", "product:detail", "product:detail:0", "product:detail:-1",
            "product:detail:2147483648", "product:detail:abc", "product:detail:1:2", "cart:checkout:1", "profile:name:extra",
            "invoice:confirm:", "invoice:confirm:ORD:extra", "orders:list:unknown", "category:UNKNOWN", "price:history:list:1"})
    void malformedCallbacksAreRejectedBeforeAnyHandlerRuns(String wire) {
        assertThrows(IllegalArgumentException.class, () -> BotCallback.parse(wire));
    }
    @Test void buildersEnforceArgumentKindsAndBaleByteLimit() {
        assertEquals("category:HAND_TOOLS", Action.CATEGORY.callback(ProductCategory.HAND_TOOLS).encode());
        assertEquals("orders:list:all", Action.ORDER_LIST.callback(BotCallback.OrderGroup.ALL).encode());
        assertThrows(IllegalArgumentException.class, () -> Action.PRODUCT_DETAIL.callback());
        assertThrows(IllegalArgumentException.class, () -> Action.CART_CHECKOUT.callback(1));
        assertThrows(IllegalArgumentException.class, () -> Action.PRODUCT_DETAIL.callback("wrong"));
        assertThrows(IllegalArgumentException.class, () -> Action.ORDER_VIEW.callback("الف".repeat(20)));
        assertThrows(IllegalArgumentException.class, () -> Action.PROFILE_NAME.callback().productId());
    }
}
