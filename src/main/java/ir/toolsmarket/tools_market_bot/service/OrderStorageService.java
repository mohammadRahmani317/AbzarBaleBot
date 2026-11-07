
package ir.toolsmarket.tools_market_bot.service;

import ir.toolsmarket.tools_market_bot.model.Order;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class OrderStorageService {

    private final ObjectMapper objectMapper;

    // فایل ذخیره سفارش‌ها
    private final Path filePath = Path.of("data", "orders.json");

    public OrderStorageService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ذخیره سفارش جدید
    public synchronized void save(Order order) throws IOException {

        Files.createDirectories(filePath.getParent());

        List<Order> orders = readOrders();
        orders.add(order);

        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(filePath.toFile(), orders);
    }

    // دریافت تمام سفارش‌ها
    public synchronized List<Order> findAll() throws IOException {
        return readOrders();
    }

    // خواندن سفارش‌های ذخیره‌شده
    private List<Order> readOrders() throws IOException {

        if (Files.notExists(filePath)) {
            return new ArrayList<>();
        }

        return objectMapper.readValue(
                filePath.toFile(),
                new TypeReference<List<Order>>() {}
        );
    }
}
