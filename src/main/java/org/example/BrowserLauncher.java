package org.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/**
 * Opens the game in a browser once the app is up.
 *
 * <p>This used to be a {@code CommandLineRunner} on the application class itself, which meant it ran
 * on <em>every</em> context start - including the ones inside {@code @SpringBootTest}. The two
 * {@code BaseTest} integration classes each launched a browser pointed at
 * {@code http://localhost:8080/home.html} while the test server was on a different (or mock) port,
 * so a dead tab popped up on every test run and looked like a stray UI test that could never pass.
 *
 * <p>It is now a separate, opt-in component:
 * <ul>
 *   <li><b>Off by default.</b> Nothing opens unless you ask for it.</li>
 *   <li><b>Never in tests.</b> The {@code test} profile disables it explicitly, so a context start
 *       in a test can never spawn a browser regardless of how the property is set.</li>
 *   <li><b>It respects {@code server.port}</b> instead of hardcoding 8080.</li>
 *   <li><b>It opens the login page</b>, not the game-mode picker — an unauthenticated visitor has no
 *       token, so opening {@code /home.html} just bounced them to login anyway, one hop later.</li>
 * </ul>
 *
 * <p>To enable: {@code mvn spring-boot:run -Dspring-boot.run.arguments=--app.open-browser=true}
 * or set {@code app.open-browser=true} in the active profile.
 */
@Component
@ConditionalOnProperty(name = "app.open-browser", havingValue = "true")
public class BrowserLauncher implements ApplicationRunner {

    private final Environment env;
    private final boolean skipOnTestProfile;

    public BrowserLauncher(Environment env,
                           @Value("${spring.profiles.active:}") String activeProfile) {
        this.env = env;
        // Belt and braces alongside the property: even with app.open-browser=true, a test profile
        // must never spawn a browser.
        this.skipOnTestProfile = "test".equalsIgnoreCase(activeProfile);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (skipOnTestProfile) {
            return;
        }
        try {
            openBrowser(baseUrl() + "/login.html");
        } catch (Exception e) {
            // Never fail startup over this - it is a convenience, not a requirement.
            System.err.println("[BrowserLauncher] could not open a browser: " + e.getMessage());
        }
    }

    /** Honours server.port / server.servlet.port so a non-default port is not ignored. */
    private String baseUrl() {
        String port = env.getProperty("server.port", env.getProperty("server.servlet.port", "8080"));
        String host = env.getProperty("server.address", "localhost");
        return "http://" + host + ":" + port;
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
