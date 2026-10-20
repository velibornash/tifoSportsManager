package org.tifo.footballmanager.newLogic.config;

import org.tifo.footballmanager.newLogic.store.MatchStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NewLogicInfrastructureConfig {

    @Bean
    public MatchStore matchStore() {
        return new MatchStore();
    }
}
