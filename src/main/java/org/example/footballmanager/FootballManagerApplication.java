package org.example.footballmanager;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

@EnableAsync
@SpringBootApplication
public class FootballManagerApplication implements CommandLineRunner {
    public static void main(String[] args) {
        SpringApplication.run(FootballManagerApplication.class, args);
    }

    @Override
    public void run(String... args) {
        try {
            String url = "http://localhost:8080/index.html";
            openBrowser(url);
        } catch (Exception e) {
            System.err.println("Manual open required: http://localhost:8080/index.html");
        }
    }


    private void openBrowser(String url) throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        Runtime rt = Runtime.getRuntime();

        if (os.contains("win")) {
            rt.exec("rundll32 url.dll,FileProtocolHandler " + url);
        } else if (os.contains("mac")) {
            rt.exec("open " + url);
        } else if (os.contains("nix") || os.contains("nux")) {
            String[] browsers = { "xdg-open", "google-chrome", "firefox" };
            String browser = Arrays.stream(browsers)
                    .filter(cmd -> new File("/usr/bin/" + cmd).exists())
                    .findFirst()
                    .orElseThrow(() -> new RuntimeException("No browser found"));
            rt.exec(new String[]{browser, url});
        } else {
            throw new UnsupportedOperationException("Unsupported OS: " + os);
        }
    }
}