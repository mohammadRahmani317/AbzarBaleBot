package ir.toolsmarket.tools_market_bot.service;

import ir.toolsmarket.tools_market_bot.model.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Map;

@Service
public class InventoryService {
    private final OrderStorageService storage;
    private final UserAccessService access;
    private final ProductCatalogService catalog;
    public InventoryService(OrderStorageService storage, UserAccessService access, ProductCatalogService catalog) {
        this.storage = storage; this.access = access; this.catalog = catalog;
    }
    public ProductInventory find(Integer id) {
        return storage.snapshot().inventory().getOrDefault(id, new ProductInventory(id, 0, 0, null));
    }
    public Map<Integer, ProductInventory> findAll(String actor) {
        access.requireOperator(actor);
        return Map.copyOf(storage.snapshot().inventory());
    }
    public ProductInventory adjustQuantity(String actor, Integer id, int delta) {
        User user = access.requirePriceManager(actor);
        return storage.transact(state -> {
            ProductCatalogService.requireActive(state, id);
            ProductInventory old = state.inventory().getOrDefault(id, new ProductInventory(id, 0, 0, null));
            int quantity;
            try { quantity = Math.addExact(old.totalQuantity(), delta); }
            catch (ArithmeticException exception) { throw new IllegalArgumentException("موجودی بیش از حد بزرگ است."); }
            Instant now = Instant.now();
            ProductInventory updated = new ProductInventory(id, quantity, old.reservedQuantity(), now);
            state.inventory().put(id, updated);
            state.inventoryHistory().add(new InventoryChange(id, old.totalQuantity(), quantity, actor, user.name(), now));
            return updated;
        });
    }
    public ProductInventory setQuantity(String actor, Integer id, int quantity) {
        User user = access.requirePriceManager(actor);
        if (catalog.findById(id).isEmpty()) throw new IllegalArgumentException("محصول پیدا نشد.");
        if (quantity < 0) throw new IllegalArgumentException("موجودی نباید منفی باشد.");
        return storage.transact(state -> {
            ProductCatalogService.requireActive(state, id);
            ProductInventory old = state.inventory().getOrDefault(id, new ProductInventory(id, 0, 0, null));
            Instant now = Instant.now();
            ProductInventory updated = new ProductInventory(id, quantity, old.reservedQuantity(), now);
            state.inventory().put(id, updated);
            state.inventoryHistory().add(new InventoryChange(id, old.totalQuantity(), quantity, actor, user.name(), now));
            return updated;
        });
    }
}
