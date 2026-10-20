package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;


@EnableAsync
@SpringBootApplication(scanBasePackages = {
    "org.example.config",
    "org.example.footballmanager.newLogic",
    "org.example.footballtextmanager",
    "org.example.basketballmanager",
    "org.example.americanfootballmanager",
    "org.example.commonmanager"
})
@EnableJpaRepositories(basePackages = {
    "org.example.footballmanager.newLogic.repository",
    "org.example.footballtextmanager.repository",
    "org.example.basketballmanager.repository",
    "org.example.americanfootballmanager.repository",
    "org.example.commonmanager.repository"
})
@EntityScan(basePackages = {
    "org.example.footballmanager.newLogic.model",
    "org.example.footballmanager.newLogic.model.event",
    "org.example.footballtextmanager.model",
    "org.example.basketballmanager.model",
    "org.example.americanfootballmanager.model",
    "org.example.commonmanager.model"
})

public class SportsManagerApplication {
    public static void main(String[] args) {
        SpringApplication.run(SportsManagerApplication.class, args);
    }
}
