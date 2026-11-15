package ir.toolsmarket.tools_market_bot.customer.menu;

import ir.toolsmarket.tools_market_bot.bot.BotCallback.Action;
import ir.toolsmarket.tools_market_bot.bot.BotButton;
import ir.toolsmarket.tools_market_bot.model.Product;
import ir.toolsmarket.tools_market_bot.model.ProductCategory;
import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.service.ProductPriceService;
import ir.toolsmarket.tools_market_bot.service.InventoryService;
import ir.toolsmarket.tools_market_bot.util.BotFormatting;
import java.math.BigDecimal;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class BotMenuService {

    private static final Logger log = LoggerFactory.getLogger(BotMenuService.class);

    private final BaleBotClient baleBotClient;
    private final ProductPriceService productPriceService;

    private final InventoryService inventory;

    public BotMenuService(BaleBotClient baleBotClient, ProductPriceService productPriceService, InventoryService inventory) {
        this.baleBotClient = baleBotClient;
        this.productPriceService = productPriceService;
        this.inventory = inventory;
    }

    // نمایش منوی اصلی
    public void showMainMenu(String chatId) {

        List<List<String>> keyboard = List.of(
                List.of(BotMenuOption.VIEW_PRODUCTS.getTitle()),
                List.of(BotMenuOption.CREATE_ORDER.getTitle()),
                List.of(BotButton.CART.text(), BotButton.PROFILE.text()),
                List.of(BotMenuOption.MY_ORDERS.getTitle()),
                List.of(BotMenuOption.CONTACT_SELLER.getTitle())
        );

        sendMessage(
                chatId,
                """
                        به ابزارمارکت خوش آمدید 🔧
                        
                        لطفاً یکی از گزینه‌های زیر را انتخاب کنید:
                        """,
                keyboard
        );
    }

    // نمایش دسته‌بندی محصولات
    public void showCategoriesMenu(String chatId) {

        List<List<String>> keyboard = java.util.Arrays.stream(
                        ProductCategory.values()
                )
                .map(category -> List.of(category.getTitle()))
                .collect(java.util.stream.Collectors.toList());

        keyboard.add(
                List.of(BotMenuOption.BACK_TO_MAIN_FROM_CATEGORY.getTitle())
        );

        sendMessage(
                chatId,
                """
                        🛒 دسته‌بندی محصولات
                        
                        لطفاً دسته‌بندی موردنظر خود را انتخاب کنید:
                        """,
                keyboard
        );
    }

    // نمایش محصولات یک دسته‌بندی
    // نمایش محصولات یک دسته‌بندی با دکمه‌های Inline
    public void showProducts(String chatId, ProductCategory category, List<Product> products) {
        if (products.isEmpty()) {
            sendMessage(
                    chatId,
                    "در حال حاضر محصولی در این دسته‌بندی ثبت نشده است.",
                    List.of(
                            List.of(BotMenuOption.BACK_TO_CATEGORIES.getTitle()),
                            List.of(BotMenuOption.BACK_TO_MAIN.getTitle())
                    )
            );
            return;
        }

        List<List<Map<String, Object>>> inlineKeyboard =
                new java.util.ArrayList<>();

        for (Product product : products) {
            inlineKeyboard.add(List.of(
                    Map.of(
                            "text", product.name(),
                            "callback_data", Action.PRODUCT_DETAIL.callback(product.id()).encode()
                    )
            ));
        }

        inlineKeyboard.add(List.of(
                Map.of(
                        "text", BotButton.BACK_TO_CATEGORIES.text(),
                        "callback_data", Action.NAV_CATEGORIES.callback().encode()
                )
        ));

        Map<String, Object> replyMarkup = Map.of(
                "inline_keyboard", inlineKeyboard
        );

        baleBotClient.sendMessage(
                chatId,
                category.getTitle() + "\n\nبرای مشاهده جزئیات، محصول موردنظر را انتخاب کنید:",
                replyMarkup
        );
    }

    // نمایش پیام ثبت سفارش
    public void showOrderGuide(String chatId) {
        showCategoriesMenu(chatId);
    }

    // نمایش راه ارتباط با فروشنده
    public void showContactInfo(String chatId) {

        baleBotClient.sendMessage(
                chatId,
                """
                        📞 ارتباط با فروشنده
                        
                        برای استعلام قیمت و دریافت راهنمایی،
                        درخواست خود را ارسال کنید.
                        """
        );
    }

    // متد مشترک ارسال پیام همراه کیبورد
    private void sendMessage(
            String chatId,
            String text,
            List<List<String>> keyboard
    ) {

        Map<String, Object> replyMarkup = Map.of(
                "keyboard", keyboard,
                "resize_keyboard", true
        );

        baleBotClient.sendMessage(chatId, text, replyMarkup);
    }

    // نمایش جزئیات یک محصول


    public void showProductDetail(String chatId, Product product) {

        long totalStart = System.currentTimeMillis();

        List<List<Map<String, Object>>> inlineKeyboard = List.of(
                List.of(
                        Map.of(
                                "text", BotButton.ORDER_PRODUCT.text(),
                                "callback_data", Action.PRODUCT_ORDER.callback(product.id()).encode()
                        )
                ),
                List.of(
                        Map.of(
                                "text", BotButton.BACK_TO_PRODUCTS.text(),
                                "callback_data", Action.CATEGORY.callback(product.categoryCode()).encode()
                        )
                )
        );

        Map<String, Object> replyMarkup = Map.of(
                "inline_keyboard", inlineKeyboard
        );

        String caption = """
                🔧 %s
                
                %s
                
                🏷 برند: %s
                💰 قیمت: %s
                📦 موجودی قابل فروش: %s
                """.formatted(
                product.name(),
                product.description(),
                product.brand().isBlank() ? "ثبت نشده" : product.brand(),
                productPriceService.findCurrentPrice(product.id())
                        .map(BotMenuService::formatPrice)
                        .orElse("استعلام قیمت"),
                BotFormatting.number(inventory.find(product.id()).availableQuantity())
        );

        var image = new org.springframework.core.io.ClassPathResource(
                product.imagePath()
        );

        try {
            if (!product.imagePath().isBlank() && image.exists()) {

                // اندازه‌گیری زمان خواندن عکس
                long readStart = System.currentTimeMillis();

                byte[] photoBytes;
                try (var stream = image.getInputStream()) { photoBytes = stream.readAllBytes(); }

                long readTime = System.currentTimeMillis() - readStart;

                log.info(
                        "خواندن عکس محصول {} در {} میلی‌ثانیه انجام شد. حجم: {} بایت",
                        product.name(),
                        readTime,
                        photoBytes.length
                );

                // اندازه‌گیری زمان ارسال عکس به بله
                long sendStart = System.currentTimeMillis();

                baleBotClient.sendPhoto(
                        chatId,
                        photoBytes,
                        image.getFilename(),
                        caption,
                        replyMarkup
                );

                long sendTime = System.currentTimeMillis() - sendStart;

                log.info(
                        "ارسال عکس محصول {} به بله {} میلی‌ثانیه طول کشید",
                        product.name(),
                        sendTime
                );


            } else {
                log.warn(
                        "عکس محصول {} پیدا نشد: {}",
                        product.name(),
                        product.imagePath()
                );

                baleBotClient.sendMessage(chatId, caption, replyMarkup);
            }

        } catch (Exception exception) {

            log.error(
                    "خطا در نمایش جزئیات محصول {} ({})",
                    product.name(),
                    exception.getClass().getSimpleName()
            );

            baleBotClient.sendMessage(chatId, caption, replyMarkup);
        }

        log.info(
                "مجموع زمان نمایش جزئیات محصول {} برابر {} میلی‌ثانیه",
                product.name(),
                System.currentTimeMillis() - totalStart
        );
    }


    // نمایش راهنمای سفارش برای یک محصول مشخص
    public void showOrderGuide(String chatId, Product product) {
        showProductDetail(chatId, product);
    }

    public void showOrderConfirmation(
            String chatId,
            String message
    ) {

        List<List<Map<String, Object>>> inlineKeyboard = List.of(
                List.of(
                        Map.of(
                                "text", BotButton.CONFIRM_ORDER.text(),
                                "callback_data", Action.LEGACY_CONFIRM.callback().encode()
                        ),
                        Map.of(
                                "text", BotButton.CANCEL_ORDER.text(),
                                "callback_data", Action.LEGACY_CANCEL.callback().encode()
                        )
                )
        );

        Map<String, Object> replyMarkup = Map.of(
                "inline_keyboard", inlineKeyboard
        );

        baleBotClient.sendMessage(
                chatId,
                message,
                replyMarkup
        );
    }
    private static String formatPrice(BigDecimal price) {
        return ir.toolsmarket.tools_market_bot.util.BotFormatting.price(price);
    }
}
