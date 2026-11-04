package ir.toolsmarket.tools_market_bot.service;

import ir.toolsmarket.tools_market_bot.model.OrderState;
import ir.toolsmarket.tools_market_bot.model.OrderStep;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrderSessionService {

    private final Map<String, OrderState> sessions =
            new ConcurrentHashMap<>();

    private final Map<String, OrderStep> steps =
            new ConcurrentHashMap<>();

    public void startOrder(String chatId, Integer productId) {
        sessions.put(chatId, new OrderState(productId));
        steps.put(chatId, OrderStep.WAITING_FOR_QUANTITY);
    }

    public OrderState getOrder(String chatId) {
        return sessions.get(chatId);
    }

    public OrderStep getStep(String chatId) {
        return steps.get(chatId);
    }

    public void setStep(String chatId, OrderStep step) {
        steps.put(chatId, step);
    }

    public void removeOrder(String chatId) {
        sessions.remove(chatId);
        steps.remove(chatId);
    }
}