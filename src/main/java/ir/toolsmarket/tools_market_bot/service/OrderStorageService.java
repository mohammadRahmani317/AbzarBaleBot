package ir.toolsmarket.tools_market_bot.service;

import ir.toolsmarket.tools_market_bot.model.*;
import ir.toolsmarket.tools_market_bot.util.AtomicJsonFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

@Service
public class OrderStorageService {
    private final ObjectMapper mapper;
    private final Path filePath;
    private final Path legacyPath;

    @org.springframework.beans.factory.annotation.Autowired
    public OrderStorageService(ObjectMapper mapper,
            @Value("${tools-market.storage.order-state-file:data/order-state.json}") String file,
            @Value("${tools-market.storage.orders-file:data/orders.json}") String legacyFile) {
        this.mapper = mapper;
        this.filePath = Path.of(file);
        this.legacyPath = Path.of(legacyFile);
    }

    public OrderStorageService(ObjectMapper mapper) {
        this(mapper, "data/order-state.json", "data/orders.json");
    }

    /** All stock/order mutations use this lock and one atomic file replacement. */
    synchronized <T> T transact(Function<OrderStore, T> operation) {
        OrderStore state = read();
        T result = operation.apply(state);
        // Keep the public product stock field consistent with authoritative inventory on every commit.
        state.products().replaceAll((id, product) -> product.withStock(
                state.inventory().getOrDefault(id, new ProductInventory(id, 0, 0, null)).totalQuantity()));
        AtomicJsonFile.write(mapper, filePath, state);
        return result;
    }

    synchronized OrderStore snapshot() { return read(); }

    public synchronized List<Order> findAll() throws IOException {
        return List.copyOf(read().orders().values());
    }

    /** Compatibility entry point for legacy imports only; new invoices must go through OrderService. */
    public synchronized void save(Order order) throws IOException {
        if (!order.legacy()) throw new IllegalArgumentException("از سرویس سفارش استفاده کنید.");
        transact(state -> {
            if (state.orders().putIfAbsent(order.orderId(), order) != null)
                throw new IllegalArgumentException("شناسه سفارش تکراری است.");
            return null;
        });
    }

    private OrderStore read() {
        if (Files.exists(filePath)) return mapper.readValue(filePath.toFile(), OrderStore.class);
        OrderStore state = OrderStore.empty();
        if (Files.exists(legacyPath)) {
            try {
                Path backup = legacyPath.resolveSibling(legacyPath.getFileName() + ".pre-cart.bak");
                if (Files.notExists(backup)) Files.copy(legacyPath, backup);
                for (var node : mapper.readTree(legacyPath.toFile())) {
                    ObjectNode value = (ObjectNode) node.deepCopy();
                    String status = value.path("status").asText("");
                    // Unknown historical states are evidence, never a reason to deduct stock.
                    value.put("legacyStatus", status);
                    value.putNull("status");
                    value.put("legacy", true);
                    Order order = mapper.treeToValue(value, Order.class);
                    if (state.orders().putIfAbsent(order.orderId(), order) != null)
                        throw new IllegalStateException("شناسه تکراری در سفارش‌های قدیمی");
                }
            } catch (IOException exception) {
                throw new IllegalStateException("خطا در پشتیبان‌گیری سفارش‌های قدیمی", exception);
            }
        }
        AtomicJsonFile.write(mapper, filePath, state);
        return state;
    }
}
