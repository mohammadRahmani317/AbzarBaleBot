package ir.toolsmarket.tools_market_bot.seller.menu;

import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.service.*;
import ir.toolsmarket.tools_market_bot.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.util.*;
import static ir.toolsmarket.tools_market_bot.util.OrderMessages.button;

@Service
public class InventoryMenuService {
    private final BaleBotClient client;
    private final InventoryService inventory;
    private final ProductCatalogService catalog;
    private final UserAccessService access;
    public InventoryMenuService(@Qualifier("sellerBaleBotClient") BaleBotClient client, InventoryService inventory,
                                ProductCatalogService catalog, UserAccessService access) {
        this.client = client; this.inventory = inventory; this.catalog = catalog; this.access = access;
    }
    public void showProducts(String actor) {
        access.requireOperator(actor);
        List<List<Map<String, Object>>> buttons = new ArrayList<>();
        for (var product : catalog.findAll()) buttons.add(List.of(button(product.name(), "inventory:edit:" + product.id())));
        OrderMessages.send(client, actor, "📦 مدیریت موجودی محصولات\nمحصول را انتخاب کنید:", buttons);
    }
    public void requestQuantity(String actor, Integer id) {
        access.requireOperator(actor);
        var product = catalog.findById(id).orElseThrow(() -> new IllegalArgumentException("محصول پیدا نشد."));
        var stock = inventory.find(id);
        client.sendMessage(actor, product.name() + "\nموجودی کل: " + BotFormatting.number(stock.totalQuantity())
                + "\nرزروشده: " + BotFormatting.number(stock.reservedQuantity())
                + "\nقابل فروش: " + BotFormatting.number(stock.availableQuantity())
                + "\nموجودی کل جدید را به صورت عدد صحیح نامنفی وارد کنید. برای انصراف «لغو» را ارسال کنید.");
    }
    public void save(String actor, Integer id, String text) {
        inventory.setQuantity(actor, id, BotFormatting.quantity(text));
        client.sendMessage(actor, "✅ موجودی ذخیره شد.");
        showProducts(actor);
    }
}
