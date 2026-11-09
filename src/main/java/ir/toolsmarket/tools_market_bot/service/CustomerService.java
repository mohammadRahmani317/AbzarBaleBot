package ir.toolsmarket.tools_market_bot.service;

import ir.toolsmarket.tools_market_bot.model.Customer;
import ir.toolsmarket.tools_market_bot.util.BotFormatting;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Optional;

@Service
public class CustomerService {
    private final OrderStorageService storage;
    public CustomerService(OrderStorageService storage) { this.storage = storage; }

    public Optional<Customer> find(String chatId) {
        return Optional.ofNullable(storage.snapshot().customers().get(chatId));
    }

    public Customer update(String chatId, String name, String phone) {
        if (chatId == null || chatId.isBlank()) throw new IllegalArgumentException("شناسه مشتری نامعتبر است.");
        if (name != null && (name.trim().length() < 3 || name.length() > 100))
            throw new IllegalArgumentException("نام باید بین ۳ تا ۱۰۰ حرف باشد.");
        String normalizedPhone = phone == null ? null : BotFormatting.digits(phone).replaceAll("[\\s-]", "");
        if (normalizedPhone != null && !normalizedPhone.matches("^(09[0-9]{9}|\\+989[0-9]{9})$"))
            throw new IllegalArgumentException("شماره موبایل معتبر وارد کنید.");
        return storage.transact(state -> {
            Customer old = state.customers().get(chatId);
            Instant now = Instant.now();
            Customer customer = new Customer(chatId,
                    name == null ? (old == null ? null : old.name()) : name.trim(),
                    normalizedPhone == null ? (old == null ? null : old.phoneNumber()) : normalizedPhone,
                    old == null ? now : old.registeredAt(), now);
            state.customers().put(chatId, customer);
            return customer;
        });
    }
}
