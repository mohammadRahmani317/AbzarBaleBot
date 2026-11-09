package ir.toolsmarket.tools_market_bot;

import ir.toolsmarket.tools_market_bot.config.BaleBotProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(BaleBotProperties.class)
public class ToolsMarketBotApplication {

	public static void main(String[] args) {
		SpringApplication.run(ToolsMarketBotApplication.class, args);
	}

}
