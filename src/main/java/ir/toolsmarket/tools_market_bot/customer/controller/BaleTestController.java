package ir.toolsmarket.tools_market_bot.customer.controller;

import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BaleTestController {

    private final BaleBotClient baleBotClient;

    public BaleTestController(@Qualifier("customerBaleBotClient") BaleBotClient baleBotClient) {
        this.baleBotClient = baleBotClient;
    }

    @PostMapping("/webhook/disable")
    public String disableWebhook() {
        return baleBotClient.deleteWebhook().toString();
    }
}
