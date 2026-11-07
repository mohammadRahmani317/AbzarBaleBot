package ir.toolsmarket.tools_market_bot.customer.service;

import ir.toolsmarket.tools_market_bot.customer.menu.BotMenuOption;
import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.model.*;
import ir.toolsmarket.tools_market_bot.service.OrderSessionService;
import ir.toolsmarket.tools_market_bot.service.OrderStorageService;
import ir.toolsmarket.tools_market_bot.service.ProductCatalogService;
import ir.toolsmarket.tools_market_bot.customer.menu.BotMenuService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.List;

@Service
public class CustomerUpdateService {

    private static final Logger log =
            LoggerFactory.getLogger(CustomerUpdateService.class);

    private final BaleBotClient baleBotClient;
    private final ProductCatalogService productCatalogService;
    private final BotMenuService botMenuService;
    private final OrderSessionService orderSessionService;
    private final OrderStorageService orderStorageService;

    private long nextUpdateId = 0;

    public CustomerUpdateService(
            @Qualifier("customerBaleBotClient")
            BaleBotClient baleBotClient,
            ProductCatalogService productCatalogService,
            BotMenuService botMenuService,
            OrderSessionService orderSessionService,
            OrderStorageService orderStorageService
    ) {
        this.baleBotClient = baleBotClient;
        this.productCatalogService = productCatalogService;
        this.botMenuService = botMenuService;
        this.orderSessionService = orderSessionService;
        this.orderStorageService = orderStorageService;
    }

    @Scheduled(fixedDelay = 1000)
    public void receiveUpdates() {

        try {
            JsonNode response = baleBotClient.getUpdates(nextUpdateId);

            if (response == null || !response.path("ok").asBoolean()) {
                log.warn("دریافت آپدیت از بله ناموفق بود: {}", response);
                return;
            }

            for (JsonNode update : response.path("result")) {

                processUpdate(update);

                nextUpdateId =
                        update.path("update_id").asLong() + 1;
            }

        } catch (Exception exception) {
            log.error("خطا در دریافت پیام از بله", exception);
        }
    }

    private void processUpdate(JsonNode update) {

        JsonNode callbackQuery = update.path("callback_query");

        if (!callbackQuery.isMissingNode()) {
            processCallbackQuery(callbackQuery);
            return;
        }

        JsonNode message = update.path("message");

        if (message.isMissingNode()) {
            return;
        }

        String chatId = message.path("chat")
                .path("id")
                .asText();

        String text = message.path("text")
                .asText("")
                .trim();

        if (chatId.isBlank() || text.isBlank()) {
            return;
        }

        log.info("پیام دریافتی از مشتری {}: {}", chatId, text);

        if (orderSessionService.getOrder(chatId) != null) {
            processOrderMessage(chatId, text);
            return;
        }

        if ("/start".equals(text)) {
            botMenuService.showMainMenu(chatId);
            return;
        }

        BotMenuOption menuOption =
                BotMenuOption.fromTitle(text).orElse(null);

        if (menuOption != null) {
            handleMenuOption(chatId, menuOption);
            return;
        }

        ProductCategory category =
                ProductCategory.fromTitle(text).orElse(null);

        if (category != null) {
            showProducts(chatId, category);
            return;
        }

        baleBotClient.sendMessage(
                chatId,
                "لطفاً یکی از گزینه‌های منو را انتخاب کنید."
        );
    }

    private void handleMenuOption(String chatId, BotMenuOption option) {

        switch (option) {

            case VIEW_PRODUCTS, BACK_TO_CATEGORIES -> botMenuService.showCategoriesMenu(chatId);

            case CREATE_ORDER -> botMenuService.showOrderGuide(chatId);

            case CONTACT_SELLER -> botMenuService.showContactInfo(chatId);

            case BACK_TO_MAIN, BACK_TO_MAIN_FROM_CATEGORY -> botMenuService.showMainMenu(chatId);
        }
    }

    private void showProducts(
            String chatId,
            ProductCategory category
    ) {

        List<Product> products =
                productCatalogService.findByCategory(category);

        botMenuService.showProducts(
                chatId,
                category,
                products
        );
    }

    // پردازش کلیک روی دکمه‌های Inline
    private void processCallbackQuery(JsonNode callbackQuery) {

        String callbackQueryId =
                callbackQuery.path("id").asText();

        String chatId = callbackQuery.path("message")
                .path("chat")
                .path("id").asText();

        String callbackData =
                callbackQuery.path("data").asText("");

        if (callbackQueryId.isBlank() || chatId.isBlank()) {
            return;
        }

        // متوقف کردن حالت انتظار دکمه در بله

        try {
            baleBotClient.answerCallbackQuery(callbackQueryId);
        } catch (Exception exception) {
            log.warn(
                    "پاسخ به Callback Query ناموفق بود. callbackQueryId: {}، علت: {}",
                    callbackQueryId,
                    exception.getMessage(),
                    exception
            );
        }


        log.info("Callback دریافتی از کاربر {}: {}", chatId, callbackData);

        String[] parts = callbackData.split(":");

        // تأیید یا لغو سفارش
        if ("order:confirm".equals(callbackData)) {

            OrderState orderState = orderSessionService.getOrder(chatId);

            if (orderState == null ||
                    orderSessionService.getStep(chatId) != OrderStep.WAITING_FOR_CONFIRMATION) {

                baleBotClient.sendMessage(
                        chatId,
                        "سفارش فعالی برای تأیید وجود ندارد."
                );
                return;
            }

            Product product = productCatalogService
                    .findById(orderState.getProductId())
                    .orElse(null);

            if (product == null) {
                baleBotClient.sendMessage(chatId, "محصول سفارش پیدا نشد.");
                orderSessionService.removeOrder(chatId);
                return;
            }

            Order order = new Order(
                    java.util.UUID.randomUUID().toString(),
                    chatId,
                    product.id(),
                    product.name(),
                    orderState.getQuantity(),
                    orderState.getCustomerName(),
                    orderState.getPhoneNumber(),
                    java.time.Instant.now()
            );

            try {
                // ذخیره سفارش در فایل JSON
                orderStorageService.save(order);

                orderSessionService.removeOrder(chatId);

                baleBotClient.sendMessage(
                        chatId,
                        """
                                ✅ سفارش شما با موفقیت ثبت شد.
                                
                                شماره سفارش:
                                %s
                                
                                فروشنده برای هماهنگی با شما تماس خواهد گرفت.
                                از اعتماد شما به ابزارمارکت سپاسگزاریم.
                                """.formatted(order.orderId())
                );

            } catch (Exception exception) {

                log.error("خطا در ذخیره سفارش", exception);

                baleBotClient.sendMessage(
                        chatId,
                        "متأسفانه ذخیره سفارش با خطا مواجه شد. لطفاً دوباره تلاش کنید."
                );
            }

            return;
        }

        if ("order:cancel".equals(callbackData)) {

            OrderState orderState = orderSessionService.getOrder(chatId);

            if (orderState == null) {
                baleBotClient.sendMessage(
                        chatId,
                        "سفارش فعالی برای لغو وجود ندارد."
                );
                return;
            }

            orderSessionService.removeOrder(chatId);

            baleBotClient.sendMessage(
                    chatId,
                    """
                            ❌ سفارش شما لغو شد.
                            
                            در صورت تمایل می‌توانید مجدداً محصول موردنظر خود را انتخاب کنید.
                            """
            );

            return;
        }

        if (parts.length == 3 && "product".equals(parts[0])) {

            Integer productId;

            try {
                productId = Integer.parseInt(parts[2]);
            } catch (NumberFormatException exception) {
                return;
            }

            Product product = productCatalogService.findById(productId)
                    .orElse(null);

            if (product == null) {
                baleBotClient.sendMessage(
                        chatId,
                        "محصول موردنظر پیدا نشد."
                );
                return;
            }

            switch (parts[1]) {
                case "detail" -> botMenuService.showProductDetail(chatId, product);

                case "order" -> {
                    orderSessionService.startOrder(chatId, product.id());

                    baleBotClient.sendMessage(
                            chatId,
                            """
                                    📦 ثبت سفارش %s
                                    
                                    لطفاً تعداد موردنیاز را به عدد وارد کنید.
                                    """.formatted(product.name())
                    );
                }

                default -> baleBotClient.sendMessage(
                        chatId,
                        "درخواست نامعتبر است."
                );
            }

            return;
        }

        if ("nav:categories".equals(callbackData)) {
            botMenuService.showCategoriesMenu(chatId);
            return;
        }

        if ("nav:main".equals(callbackData)) {
            botMenuService.showMainMenu(chatId);
            return;
        }

        if (callbackData.startsWith("category:")) {

            String categoryCode = parts[1];

            ProductCategory category =
                    java.util.Arrays.stream(ProductCategory.values())
                            .filter(item -> item.getCode().equals(categoryCode))
                            .findFirst()
                            .orElse(null);

            if (category != null) {
                showProducts(chatId, category);
            }
        }
    }

    private void processOrderMessage(String chatId, String text) {

        OrderState orderState = orderSessionService.getOrder(chatId);
        OrderStep step = orderSessionService.getStep(chatId);

        if (orderState == null || step == null) {
            return;
        }

        switch (step) {

            case WAITING_FOR_QUANTITY -> {
                try {
                    int quantity = Integer.parseInt(text);

                    if (quantity <= 0) {
                        baleBotClient.sendMessage(
                                chatId,
                                "تعداد باید عددی بزرگ‌تر از صفر باشد."
                        );
                        return;
                    }

                    orderState.setQuantity(quantity);

                    orderSessionService.setStep(
                            chatId,
                            OrderStep.WAITING_FOR_NAME
                    );

                    baleBotClient.sendMessage(
                            chatId,
                            "لطفاً نام و نام خانوادگی خود را وارد کنید."
                    );

                } catch (NumberFormatException exception) {
                    baleBotClient.sendMessage(
                            chatId,
                            "لطفاً تعداد را به‌صورت عدد وارد کنید."
                    );
                }
            }

            case WAITING_FOR_NAME -> {
                if (text.length() < 3) {
                    baleBotClient.sendMessage(
                            chatId,
                            "لطفاً نام و نام خانوادگی معتبر وارد کنید."
                    );
                    return;
                }

                orderState.setCustomerName(text);

                orderSessionService.setStep(
                        chatId,
                        OrderStep.WAITING_FOR_PHONE
                );

                baleBotClient.sendMessage(
                        chatId,
                        "لطفاً شماره تماس خود را وارد کنید."
                );
            }

            case WAITING_FOR_PHONE -> {
                String phone = text.replaceAll("[\\s-]", "");

                if (!phone.matches("^(09\\d{9}|\\+989\\d{9})$")) {
                    baleBotClient.sendMessage(
                            chatId,
                            "شماره تماس معتبر نیست. لطفاً شماره موبایل خود را وارد کنید."
                    );
                    return;
                }

                orderState.setPhoneNumber(phone);

                orderSessionService.setStep(
                        chatId,
                        OrderStep.WAITING_FOR_CONFIRMATION
                );

                showOrderConfirmation(chatId, orderState);
            }

            case WAITING_FOR_CONFIRMATION -> {
                baleBotClient.sendMessage(
                        chatId,
                        "لطفاً از دکمه‌های تأیید یا لغو سفارش استفاده کنید."
                );
            }
        }
    }

    private void showOrderConfirmation(
            String chatId,
            OrderState orderState
    ) {

        Product product = productCatalogService
                .findById(orderState.getProductId())
                .orElse(null);

        if (product == null) {
            baleBotClient.sendMessage(
                    chatId,
                    "محصول سفارش پیدا نشد."
            );

            orderSessionService.removeOrder(chatId);
            return;
        }

        String message = """
                🧾 خلاصه سفارش
                
                محصول: %s
                تعداد: %d
                نام مشتری: %s
                شماره تماس: %s
                
                قیمت: استعلام از فروشنده
                
                آیا سفارش را تأیید می‌کنید؟
                """.formatted(
                product.name(),
                orderState.getQuantity(),
                orderState.getCustomerName(),
                orderState.getPhoneNumber()
        );

        botMenuService.showOrderConfirmation(
                chatId,
                message
        );
    }
}