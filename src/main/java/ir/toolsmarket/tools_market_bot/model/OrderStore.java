package ir.toolsmarket.tools_market_bot.model;

import java.util.*;

/** One snapshot is the commit boundary for orders, stock, history and notification outbox. */
public record OrderStore(Map<String, Order> orders, Map<Integer, ProductInventory> inventory,
                         Map<String, Customer> customers, List<OrderStatusHistory> history,
                         List<InventoryChange> inventoryHistory, List<OrderNotification> notifications,
                         Map<Integer, Product> products) {
    public OrderStore {
        products = new LinkedHashMap<>(products == null ? Map.of() : products);
        orders = new LinkedHashMap<>(orders == null ? Map.of() : orders);
        inventory = new LinkedHashMap<>(inventory == null ? Map.of() : inventory);
        customers = new LinkedHashMap<>(customers == null ? Map.of() : customers);
        history = new ArrayList<>(history == null ? List.of() : history);
        inventoryHistory = new ArrayList<>(inventoryHistory == null ? List.of() : inventoryHistory);
        notifications = new ArrayList<>(notifications == null ? List.of() : notifications);
    }
    public OrderStore(Map<String, Order> orders, Map<Integer, ProductInventory> inventory,
                      Map<String, Customer> customers, List<OrderStatusHistory> history,
                      List<InventoryChange> inventoryHistory, List<OrderNotification> notifications) {
        this(orders, inventory, customers, history, inventoryHistory, notifications, null);
    }
    public static OrderStore empty() { return new OrderStore(null, null, null, null, null, null, null); }
}
