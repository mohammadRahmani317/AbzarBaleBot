package ir.toolsmarket.tools_market_bot;

import ir.toolsmarket.tools_market_bot.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.nio.file.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"tools-market.scheduling.enabled=false",
        "tools-market.bale.token=", "tools-market.bale.seller-token=", "tools-market.bale.super-admin-chat-id=test-super"})
class ToolsMarketBotApplicationTests {
    static final Path DIRECTORY = createDirectory();
    @Autowired ProductCatalogService catalog;
    @Autowired UserAccessService access;
    @Autowired OrderService orders;

    private static Path createDirectory() {
        try { return Files.createTempDirectory("tools-market-context-"); }
        catch (IOException exception) { throw new IllegalStateException(exception); }
    }
    @DynamicPropertySource
    static void storagePaths(DynamicPropertyRegistry properties) {
        properties.add("tools-market.storage.users-file", () -> DIRECTORY.resolve("users.json").toString());
        properties.add("tools-market.storage.order-state-file", () -> DIRECTORY.resolve("state.json").toString());
        properties.add("tools-market.storage.orders-file", () -> DIRECTORY.resolve("orders.json").toString());
        properties.add("tools-market.storage.product-prices-file", () -> DIRECTORY.resolve("prices.json").toString());
    }
    @Test void contextLoadsWithRealCatalogAndIsolatedStorage() {
        assertFalse(catalog.findAll().isEmpty());
        assertTrue(catalog.findById(1).isPresent());
        assertEquals("test-super", access.requireOperator("test-super").chatId());
        assertTrue(orders.list("test-super", java.util.Set.of()).isEmpty());
    }
}
