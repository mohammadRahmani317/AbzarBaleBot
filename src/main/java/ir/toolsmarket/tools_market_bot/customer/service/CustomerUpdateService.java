package ir.toolsmarket.tools_market_bot.customer.service;

import ir.toolsmarket.tools_market_bot.customer.menu.*;
import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.model.*;
import ir.toolsmarket.tools_market_bot.service.*;
import ir.toolsmarket.tools_market_bot.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static ir.toolsmarket.tools_market_bot.util.OrderMessages.button;

@Service
public class CustomerUpdateService {
    private static final Logger log = LoggerFactory.getLogger(CustomerUpdateService.class);
    private final BaleBotClient client;
    private final ProductCatalogService catalog;
    private final BotMenuService menu;
    private final OrderSessionService sessions;
    private final ShoppingCartService carts;
    private final CustomerService customers;
    private final OrderService orders;
    private final Map<String, String> profileInput = new ConcurrentHashMap<>();
    private long nextUpdateId;

    public CustomerUpdateService(@Qualifier("customerBaleBotClient") BaleBotClient client,
            ProductCatalogService catalog, BotMenuService menu, OrderSessionService sessions,
            ShoppingCartService carts, CustomerService customers, OrderService orders) {
        this.client = client; this.catalog = catalog; this.menu = menu; this.sessions = sessions;
        this.carts = carts; this.customers = customers; this.orders = orders;
    }

    @Scheduled(fixedDelay = 1000)
    public void receiveUpdates() {
        if (!client.configured()) return;
        try {
            JsonNode response = client.getUpdates(nextUpdateId);
            if (response == null || !response.path("ok").asBoolean()) return;
            for (JsonNode update : response.path("result")) {
                try { processUpdate(update); }
                catch (Exception exception) { log.error("Customer update failed ({})", exception.getClass().getSimpleName()); }
                finally { nextUpdateId = update.path("update_id").asLong() + 1; }
            }
        } catch (Exception exception) { log.warn("Customer polling failed ({})", exception.getClass().getSimpleName()); }
    }

    // Both bots use private conversations: never use a group ID as the callback actor.
    synchronized void processUpdate(JsonNode update) {
        JsonNode callback = update.path("callback_query");
        JsonNode message = callback.isMissingNode() ? update.path("message") : callback.path("message");
        String chatId = message.path("chat").path("id").asText();
        String actor = (callback.isMissingNode() ? message : callback).path("from").path("id").asText();
        if (chatId.isBlank() || !chatId.equals(actor)) return;
        try {
            if (!callback.isMissingNode()) {
                try { client.answerCallbackQuery(callback.path("id").asText()); }
                catch (Exception exception) { log.debug("Callback acknowledgement failed"); }
                handleCallback(chatId, callback.path("data").asText());
            } else handleMessage(chatId, message.path("text").asText("").trim());
        } catch (IllegalArgumentException exception) { client.sendMessage(chatId, exception.getMessage()); }
    }

    private void handleMessage(String chatId, String text) {
        if (text.isBlank()) return;
        if ("/start".equals(text) || "لغو".equals(text)) {
            sessions.removeOrder(chatId); profileInput.remove(chatId); menu.showMainMenu(chatId); return;
        }
        if ("🛒 سبد خرید".equals(text)) { profileInput.remove(chatId); sessions.removeOrder(chatId); showCart(chatId); return; }
        if ("👤 اطلاعات من".equals(text)) { showProfile(chatId); return; }
        if (profileInput.containsKey(chatId)) { handleProfileInput(chatId, text); return; }
        OrderState selection = sessions.getOrder(chatId);
        if (selection != null) {
            carts.setQuantity(chatId, selection.getProductId(), BotFormatting.quantity(text));
            sessions.removeOrder(chatId); showCart(chatId); return;
        }
        var option = BotMenuOption.fromTitle(text).orElse(null);
        if (option != null) {
            switch (option) {
                case VIEW_PRODUCTS, CREATE_ORDER, BACK_TO_CATEGORIES -> menu.showCategoriesMenu(chatId);
                case CONTACT_SELLER -> menu.showContactInfo(chatId);
                case BACK_TO_MAIN, BACK_TO_MAIN_FROM_CATEGORY -> menu.showMainMenu(chatId);
            }
            return;
        }
        ProductCategory.fromTitle(text).ifPresentOrElse(category -> showProducts(chatId, category),
                () -> client.sendMessage(chatId, "لطفاً از منو استفاده کنید. برای انصراف «لغو» را ارسال کنید."));
    }

    private void handleCallback(String chatId, String data) {
        String[] parts = data.split(":");
        if (parts.length == 3 && "product".equals(parts[0])) {
            int id = BotFormatting.quantity(parts[2]);
            Product product = catalog.findById(id).orElseThrow(() -> new IllegalArgumentException("محصول پیدا نشد."));
            if ("detail".equals(parts[1])) menu.showProductDetail(chatId, product);
            else if ("order".equals(parts[1])) {
                sessions.startOrder(chatId, id);
                profileInput.remove(chatId);
                client.sendMessage(chatId, "تعداد «" + product.name() + "» را وارد کنید. تعداد جدید جایگزین تعداد قبلی سبد می‌شود. برای انصراف «لغو» را بفرستید.");
            }
            return;
        }
        if (parts.length == 3 && "invoice".equals(parts[0])) {
            Order result = switch (parts[1]) {
                case "confirm" -> orders.confirm(parts[2], chatId);
                case "cancel" -> orders.cancel(parts[2], chatId);
                default -> throw new IllegalArgumentException("درخواست نامعتبر است.");
            };
            client.sendMessage(chatId, "سفارش " + result.orderId() + "\nوضعیت: " + result.status().title());
            return;
        }
        // Old callbacks never act on a different or newly created invoice.
        if ("order:confirm".equals(data) || "order:cancel".equals(data)) {
            client.sendMessage(chatId, "این دکمه قدیمی است. از دکمه پیش‌فاکتور جدید استفاده کنید."); return;
        }
        if (data.startsWith("category:")) {
            Arrays.stream(ProductCategory.values()).filter(c -> c.getCode().equals(data.substring(9)))
                    .findFirst().ifPresent(c -> showProducts(chatId, c)); return;
        }
        if ("nav:categories".equals(data) || "cart:add".equals(data)) { menu.showCategoriesMenu(chatId); return; }
        if ("nav:main".equals(data)) { menu.showMainMenu(chatId); return; }
        if ("cart:view".equals(data)) { showCart(chatId); return; }
        if (parts.length == 3 && "cart".equals(parts[0]) && "remove".equals(parts[1])) {
            carts.remove(chatId, BotFormatting.quantity(parts[2])); showCart(chatId); return;
        }
        if ("cart:checkout".equals(data)) { sessions.removeOrder(chatId); checkout(chatId); return; }
        if ("profile:name".equals(data) || "profile:phone".equals(data)) {
            profileInput.put(chatId, "profile:name".equals(data) ? "EDIT_NAME" : "EDIT_PHONE");
            client.sendMessage(chatId, "profile:name".equals(data) ? "نام و نام خانوادگی را وارد کنید:" : "شماره موبایل را وارد کنید:");
        }
    }

    private void showProducts(String chatId, ProductCategory category) {
        menu.showProducts(chatId, category, catalog.findByCategory(category));
    }
    private void showCart(String chatId) {
        var items = carts.items(chatId);
        List<List<Map<String, Object>>> buttons = new ArrayList<>();
        for (OrderItem item : items) buttons.add(List.of(
                button("✏️ تعداد " + item.productName(), "product:order:" + item.productId()),
                button("❌ حذف", "cart:remove:" + item.productId())));
        buttons.add(List.of(button("➕ افزودن محصول", "cart:add"), button("🧾 دریافت پیش‌فاکتور", "cart:checkout")));
        OrderMessages.send(client, chatId, "🛒 سبد خرید\nقیمت‌های فعلی؛ قیمت نهایی هنگام صدور فاکتور ثبت می‌شود.\n\n"
                + (items.isEmpty() ? "سبد خرید خالی است." : OrderMessages.items(items)
                + "جمع اقلام دارای قیمت: " + BotFormatting.price(carts.total(chatId))), buttons);
    }
    private void showProfile(String chatId) {
        sessions.removeOrder(chatId);
        profileInput.remove(chatId);
        var profile = customers.find(chatId).orElse(null);
        OrderMessages.send(client, chatId, "👤 اطلاعات من\nنام: " + (profile == null ? "—" : profile.name())
                + "\nتلفن: " + (profile == null ? "—" : profile.phoneNumber()),
                List.of(List.of(button("✏️ نام", "profile:name"), button("✏️ تلفن", "profile:phone"))));
    }
    private void handleProfileInput(String chatId, String text) {
        String mode = profileInput.get(chatId);
        boolean name = mode.endsWith("NAME");
        customers.update(chatId, name ? text : null, name ? null : text);
        profileInput.remove(chatId);
        if (mode.startsWith("CHECKOUT")) checkout(chatId); else showProfile(chatId);
    }
    private void checkout(String chatId) {
        if (carts.quantities(chatId).isEmpty()) throw new IllegalArgumentException("سبد خرید خالی است.");
        Customer profile = customers.find(chatId).orElse(null);
        if (profile == null || profile.name() == null || profile.name().isBlank()) {
            profileInput.put(chatId, "CHECKOUT_NAME"); client.sendMessage(chatId, "نام و نام خانوادگی را وارد کنید:"); return;
        }
        if (profile.phoneNumber() == null || profile.phoneNumber().isBlank()) {
            profileInput.put(chatId, "CHECKOUT_PHONE"); client.sendMessage(chatId, "شماره موبایل را وارد کنید:"); return;
        }
        Order invoice = orders.createInvoice(chatId, carts.quantities(chatId));
        if (invoice.invoiceSentAt() != null) {
            client.sendMessage(chatId, "یک پیش‌فاکتور فعال دارید. از دکمه‌های همان پیش‌فاکتور استفاده کنید.\nشماره: " + invoice.orderId());
            return;
        }
        try {
            OrderMessages.send(client, chatId, "🧾 پیش‌فاکتور ابزارمارکت\nشماره: " + invoice.orderId()
                    + "\nمشتری: " + invoice.customerName() + "\n\n" + OrderMessages.items(invoice.items())
                    + "\nمبلغ نهایی: " + BotFormatting.price(invoice.totalAmount())
                    + "\nاعتبار: ۲ ساعت پس از ارسال موفق این پیام",
                    List.of(List.of(button("✅ تأیید سفارش", "invoice:confirm:" + invoice.orderId()),
                            button("❌ لغو سفارش", "invoice:cancel:" + invoice.orderId()))));
            orders.markInvoiceSent(invoice.orderId(), chatId);
            carts.clear(chatId);
        } catch (Exception exception) {
            orders.invoiceDeliveryFailed(invoice.orderId(), chatId);
            log.warn("Invoice delivery did not complete: {}", invoice.orderId());
            client.sendMessage(chatId, "ارسال پیش‌فاکتور تکمیل نشد. دوباره از سبد خرید اقدام کنید.");
        }
    }
}
