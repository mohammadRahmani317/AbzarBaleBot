package ir.toolsmarket.tools_market_bot.service;

import ir.toolsmarket.tools_market_bot.model.OrderItem;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

/** Carts are temporary; only checkout reserves inventory. */
@Service
public class ShoppingCartService {
    private final Map<String, Map<Integer, Integer>> carts = new HashMap<>();
    private final ProductCatalogService catalog;
    private final ProductPriceService prices;
    private final InventoryService inventory;
    public ShoppingCartService(ProductCatalogService catalog, ProductPriceService prices, InventoryService inventory) {
        this.catalog = catalog; this.prices = prices; this.inventory = inventory;
    }
    public synchronized void setQuantity(String chatId, Integer id, int quantity) {
        if (catalog.findById(id).isEmpty()) throw new IllegalArgumentException("محصول پیدا نشد.");
        if (quantity <= 0) throw new IllegalArgumentException("تعداد باید بزرگ‌تر از صفر باشد.");
        if (inventory.find(id).availableQuantity() < quantity) throw new IllegalArgumentException("موجودی کافی نیست.");
        carts.computeIfAbsent(chatId, key -> new LinkedHashMap<>()).put(id, quantity);
    }
    public synchronized void remove(String chatId, Integer id) {
        if (carts.containsKey(chatId)) carts.get(chatId).remove(id);
    }
    public synchronized Map<Integer, Integer> quantities(String chatId) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(carts.getOrDefault(chatId, Map.of())));
    }
    public synchronized List<OrderItem> items(String chatId) {
        return quantities(chatId).entrySet().stream().map(entry -> {
            var product = catalog.findById(entry.getKey()).orElseThrow();
            return new OrderItem(product.id(), product.name(), entry.getValue(),
                    prices.findCurrentPrice(product.id()).orElse(null));
        }).toList();
    }
    public synchronized BigDecimal total(String chatId) {
        return items(chatId).stream().map(OrderItem::total).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    public synchronized void clear(String chatId) { carts.remove(chatId); }
}
