package ir.toolsmarket.tools_market_bot.customer.menu;

import ir.toolsmarket.tools_market_bot.model.Product;
import ir.toolsmarket.tools_market_bot.model.ProductCategory;
import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class BotMenuService {

    private final BaleBotClient baleBotClient;

    public BotMenuService(BaleBotClient baleBotClient) {
        this.baleBotClient = baleBotClient;
    }

    // نمایش منوی اصلی
    public void showMainMenu(String chatId) {

        List<List<String>> keyboard = List.of(
                List.of(BotMenuOption.VIEW_PRODUCTS.getTitle()),
                List.of(BotMenuOption.CREATE_ORDER.getTitle()),
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

        baleBotClient.sendMessage(
                chatId,
                """
                📦 ثبت سفارش

                لطفاً اطلاعات زیر را ارسال کنید:

                ۱. نام و نام خانوادگی
                ۲. شماره تماس
                ۳. نام محصول
                ۴. تعداد موردنیاز

                فروشنده برای هماهنگی با شما تماس خواهد گرفت.
                """
        );
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
                product.priceLabel()
        );

        // مسیر عکس از resources خوانده می‌شود
        org.springframework.core.io.ClassPathResource image =
                new org.springframework.core.io.ClassPathResource(product.imagePath());

        try {
            if (image.exists()) {
                // عکس همراه توضیحات و دکمه‌ها ارسال می‌شود
                baleBotClient.sendPhoto(
                        chatId,
                        image.getInputStream().readAllBytes(),
                        image.getFilename(),
                        caption,
                        replyMarkup
                );
            } else {
                // اگر عکس موجود نبود، جزئیات متنی ارسال می‌شود
                baleBotClient.sendMessage(
                        chatId,
                        caption,
                        replyMarkup
                );
            }
        } catch (java.io.IOException exception) {
            baleBotClient.sendMessage(
                    chatId,
                    caption,
                    replyMarkup
            );
        }
    }

    // نمایش راهنمای سفارش برای یک محصول مشخص
    public void showOrderGuide(String chatId, Product product) {

        baleBotClient.sendMessage(
                chatId,
                """
                📦 ثبت سفارش
    
                محصول انتخاب‌شده: %s
    
                لطفاً اطلاعات زیر را ارسال کنید:
    
                ۱. نام و نام خانوادگی
                ۲. شماره تماس
                ۳. تعداد موردنیاز
    
                فروشنده برای هماهنگی با شما تماس خواهد گرفت.
                """.formatted(product.name())
        );
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
}