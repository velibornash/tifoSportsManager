package org.tifo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;


@EnableAsync
@SpringBootApplication(scanBasePackages = {
    "org.tifo.config",
    "org.tifo.footballmanager.newLogic",
    "org.tifo.footballtextmanager",
    "org.tifo.basketballmanager",
    "org.tifo.americanfootballmanager",
    "org.tifo.commonmanager"
})
@EnableJpaRepositories(basePackages = {
    "org.tifo.footballmanager.newLogic.repository",
    "org.tifo.footballtextmanager.repository",
    "org.tifo.basketballmanager.repository",
    "org.tifo.americanfootballmanager.repository",
    "org.tifo.commonmanager.repository"
})
@EntityScan(basePackages = {
    "org.tifo.footballmanager.newLogic.model",
    "org.tifo.footballmanager.newLogic.model.event",
    "org.tifo.footballtextmanager.model",
    "org.tifo.basketballmanager.model",
    "org.tifo.americanfootballmanager.model",
    "org.tifo.commonmanager.model"
})

public class SportsManagerApplication {
    public static void main(String[] args) {
        SpringApplication.run(SportsManagerApplication.class, args);
    }
}
