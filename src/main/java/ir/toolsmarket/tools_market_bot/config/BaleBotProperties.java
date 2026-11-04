package ir.toolsmarket.tools_market_bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tools-market.bale")
public record BaleBotProperties(
        String baseUrl,
        String token,
        String sellerToken,
        String superAdminChatId
) {
}
