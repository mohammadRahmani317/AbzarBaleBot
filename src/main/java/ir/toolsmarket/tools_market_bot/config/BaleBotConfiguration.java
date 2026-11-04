package ir.toolsmarket.tools_market_bot.config;

import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class BaleBotConfiguration {
    @Primary
    @Bean("customerBaleBotClient")
    public BaleBotClient customerBaleBotClient(BaleBotProperties properties) {

        return new BaleBotClient(
                properties.baseUrl(),
                properties.token()
        );
    }

    @Bean("sellerBaleBotClient")
    public BaleBotClient sellerBaleBotClient(BaleBotProperties properties) {

        return new BaleBotClient(
                properties.baseUrl(),
                properties.sellerToken()
        );
    }
}