package ir.toolsmarket.tools_market_bot.customer.menu;

import ir.toolsmarket.tools_market_bot.model.Product;
import ir.toolsmarket.tools_market_bot.model.ProductCategory;
import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.service.ProductPriceService;
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

    public BotMenuService(BaleBotClient baleBotClient, ProductPriceService productPriceService) {
        this.baleBotClient = baleBotClient;
        this.productPriceService = productPriceService;
    }

    // نمایش منوی اصلی
    public void showMainMenu(String chatId) {

        List<List<String>> keyboard = List.of(
                List.of(BotMenuOption.VIEW_PRODUCTS.getTitle()),
                List.of(BotMenuOption.CREATE_ORDER.getTitle()),
                List.of("🛒 سبد خرید", "👤 اطلاعات من"),
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
                            "callback_data", "product:detail:" + product.id()
                    )
            ));
        }

        inlineKeyboard.add(List.of(
                Map.of(
                        "text", "🔙 بازگشت به دسته‌بندی‌ها",
                        "callback_data", "nav:categories"
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
                                "text", "📦 ثبت سفارش این محصول",
                                "callback_data", "product:order:" + product.id()
                        )
                ),
                List.of(
                        Map.of(
                                "text", "🔙 بازگشت به محصولات",
                                "callback_data", "category:" + product.categoryCode()
                        )
                )
        );

        Map<String, Object> replyMarkup = Map.of(
                "inline_keyboard", inlineKeyboard
        );

        String caption = """
                🔧 %s
                
                %s
                
                💰 قیمت: %s
                """.formatted(
                product.name(),
                product.description(),
                productPriceService.findCurrentPrice(product.id())
                        .map(BotMenuService::formatPrice)
                        .orElse("استعلام قیمت")
        );

        var image = new org.springframework.core.io.ClassPathResource(
                product.imagePath()
        );

        try {
            if (image.exists()) {

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
                                "text", "✅ تأیید سفارش",
                                "callback_data", "order:confirm"
                        ),
                        Map.of(
                                "text", "❌ لغو سفارش",
                                "callback_data", "order:cancel"
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
