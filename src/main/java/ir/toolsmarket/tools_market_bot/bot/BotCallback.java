package ir.toolsmarket.tools_market_bot.bot;

import ir.toolsmarket.tools_market_bot.model.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The wire format is intentionally compatible with buttons sent before this refactor. */
public record BotCallback(Action action, String value) {
    private enum Parameter { NONE, PRODUCT, TEXT, CATEGORY, GROUP }
    public enum Action {
        PRODUCT_DETAIL("product:detail", Parameter.PRODUCT), PRODUCT_ORDER("product:order", Parameter.PRODUCT),
        CATEGORY("category", Parameter.CATEGORY), NAV_CATEGORIES("nav:categories"), NAV_MAIN("nav:main"),
        CART_ADD("cart:add"), CART_VIEW("cart:view"), CART_REMOVE("cart:remove", Parameter.PRODUCT), CART_CHECKOUT("cart:checkout"),
        PROFILE_NAME("profile:name"), PROFILE_PHONE("profile:phone"),
        INVOICE_CONFIRM("invoice:confirm", Parameter.TEXT), INVOICE_CANCEL("invoice:cancel", Parameter.TEXT),
        LEGACY_CONFIRM("order:confirm"), LEGACY_CANCEL("order:cancel"),
        CUSTOMER_ORDER_VIEW("customer-orders:view", Parameter.TEXT),
        CATALOG_LIST("catalog:list"), CATALOG_ADD("catalog:add"), CATALOG_EDIT("catalog:edit", Parameter.PRODUCT),
        CATALOG_DELETE("catalog:delete", Parameter.PRODUCT), CATALOG_DELETE_CONFIRM("catalog:delete-confirm", Parameter.PRODUCT),
        PRICE_EDIT("price:edit", Parameter.PRODUCT), PRICE_HISTORY_LIST("price:history:list"),
        PRICE_HISTORY("price:history", Parameter.PRODUCT), PRICE_MENU("price:menu"), INVENTORY_EDIT("inventory:edit", Parameter.PRODUCT),
        ORDER_LIST("orders:list", Parameter.GROUP), ORDER_VIEW("orders:view", Parameter.TEXT),
        ORDER_PREPARE("orders:prepare", Parameter.TEXT), ORDER_DELIVER("orders:deliver", Parameter.TEXT), ORDER_HISTORY("orders:history", Parameter.TEXT),
        ACCESS_REVIEW("access:review", Parameter.TEXT), ACCESS_LIST("access:list"),
        ACCESS_REOPEN("access:reopen", Parameter.TEXT), ACCESS_PROMOTE("access:promote", Parameter.TEXT),
        ACCESS_DEMOTE("access:demote", Parameter.TEXT), ACCESS_DEACTIVATE("access:deactivate", Parameter.TEXT),
        ACCESS_DEACTIVATE_ADMIN("access:deactivate-admin", Parameter.TEXT), ACCESS_APPROVE("access:approve", Parameter.TEXT),
        ACCESS_REJECT("access:reject", Parameter.TEXT), ACCESS_PRICE_GRANT("access:price-grant", Parameter.TEXT),
        ACCESS_PRICE_REVOKE("access:price-revoke", Parameter.TEXT);

        private final String wire;
        private final Parameter parameter;
        Action(String wire) { this(wire, Parameter.NONE); }
        Action(String wire, Parameter parameter) { this.wire = wire; this.parameter = parameter; }
        public BotCallback callback() { return new BotCallback(this, null); }
        public BotCallback callback(String value) { return new BotCallback(this, value); }
        public BotCallback callback(int id) { return callback(Integer.toString(id)); }
        public BotCallback callback(ProductCategory category) { return callback(category.getCode()); }
        public BotCallback callback(OrderGroup group) { return callback(group.wire()); }
    }
    public enum OrderGroup {
        ACTIVE(Set.of(OrderStatus.CONFIRMED, OrderStatus.PREPARING)), DELIVERED(Set.of(OrderStatus.DELIVERED)),
        CANCELLED(Set.of(OrderStatus.CANCELLED)), EXPIRED(Set.of(OrderStatus.EXPIRED)), ALL(Set.of());
        private final Set<OrderStatus> statuses;
        OrderGroup(Set<OrderStatus> statuses) { this.statuses = statuses; }
        public Set<OrderStatus> statuses() { return statuses; }
        public String wire() { return name().toLowerCase(Locale.ROOT); }
        public static OrderGroup parse(String value) {
            return Arrays.stream(values()).filter(group -> group.wire().equals(value)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("فهرست نامعتبر است."));
        }
    }
    public BotCallback {
        if (action == null) throw invalid();
        if (action.parameter == Parameter.NONE) {
            if (value != null) throw invalid();
        } else {
            if (value == null || value.isBlank() || value.contains(":")) throw invalid();
            switch (action.parameter) {
                case PRODUCT -> {
                    try { if (!value.matches("[0-9]+") || Integer.parseInt(value) <= 0) throw invalid(); }
                    catch (NumberFormatException exception) { throw invalid(); }
                }
                case CATEGORY -> {
                    if (Arrays.stream(ProductCategory.values()).noneMatch(category -> category.getCode().equals(value))) throw invalid();
                }
                case GROUP -> OrderGroup.parse(value);
                default -> { }
            }
        }
        if ((action.wire + (value == null ? "" : ":" + value)).getBytes(StandardCharsets.UTF_8).length > 64) throw invalid();
    }
    public String encode() { return action.wire + (value == null ? "" : ":" + value); }
    public int productId() {
        if (action.parameter != Parameter.PRODUCT) throw invalid();
        return Integer.parseInt(value);
    }
    public ProductCategory category() {
        if (action.parameter != Parameter.CATEGORY) throw invalid();
        return ProductCategory.valueOf(value);
    }
    public OrderGroup group() {
        if (action.parameter != Parameter.GROUP) throw invalid();
        return OrderGroup.parse(value);
    }
    public static BotCallback parse(String data) {
        if (data == null) throw invalid();
        // Exact actions first: price:history:list must not be treated as a product ID.
        for (Action action : Action.values())
            if (action.parameter == Parameter.NONE && action.wire.equals(data)) return action.callback();
        for (Action action : Action.values())
            if (action.parameter != Parameter.NONE && data.startsWith(action.wire + ":"))
                return action.callback(data.substring(action.wire.length() + 1));
        throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("درخواست نامعتبر است."); }
}
