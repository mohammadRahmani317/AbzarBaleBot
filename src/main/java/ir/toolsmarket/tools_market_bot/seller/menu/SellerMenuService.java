package ir.toolsmarket.tools_market_bot.seller.menu;

import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class SellerMenuService {

    private final BaleBotClient baleBotClient;

    public SellerMenuService(
            @Qualifier("sellerBaleBotClient") BaleBotClient baleBotClient
    ) {
        this.baleBotClient = baleBotClient;
    }

    public void showMainMenu(String chatId) {

        String message = """
                سلام، به پنل مدیریت ابزارمارکت خوش آمدید.

                لطفاً یکی از گزینه‌های زیر را انتخاب کنید:
                """;

        Map<String, Object> replyMarkup = Map.of(
                "keyboard", List.of(
                        List.of(
                                Map.of("text", "📦 مشاهده سفارش‌ها")
                        )
                ),
                "resize_keyboard", true
        );

        baleBotClient.sendMessage(chatId, message, replyMarkup);
    }

    public void showHelp(String chatId) {
        baleBotClient.sendMessage(
                chatId,
                "برای مدیریت سفارش‌های مشتریان از گزینه «📦 مشاهده سفارش‌ها» استفاده کنید."
        );
    }
}