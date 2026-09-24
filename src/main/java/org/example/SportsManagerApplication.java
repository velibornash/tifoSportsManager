package org.example;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

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

public class SportsManagerApplication implements CommandLineRunner {
    public static void main(String[] args) {
        SpringApplication.run(SportsManagerApplication.class, args);
    }

    @Override
    public void run(String... args) {
        try {
            String url = "http://localhost:8080/home.html";
            openBrowser(url);
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
    }

    private void openBrowser(String url) throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
        } else if (os.contains("mac")) {
            new ProcessBuilder("open", url).start();
        } else if (os.contains("nix") || os.contains("nux")) {
            String[] browsers = { "xdg-open", "google-chrome", "firefox" };
            String browser = Arrays.stream(browsers)
                    .filter(cmd -> new File("/usr/bin/" + cmd).exists())
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No browser found"));
            new ProcessBuilder(browser, url).start();
        } else {
            throw new UnsupportedOperationException("Unsupported OS: " + os);
        }
    }
}
