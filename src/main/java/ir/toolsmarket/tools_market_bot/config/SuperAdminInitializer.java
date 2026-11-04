package ir.toolsmarket.tools_market_bot.config;

import ir.toolsmarket.tools_market_bot.service.UserAccessService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class SuperAdminInitializer implements ApplicationRunner {

    private final UserAccessService userAccessService;
    private final BaleBotProperties properties;

    public SuperAdminInitializer(
            UserAccessService userAccessService,
            BaleBotProperties properties
    ) {
        this.userAccessService = userAccessService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {

        userAccessService.ensureSuperAdmin(
                properties.superAdminChatId()
        );
    }
}