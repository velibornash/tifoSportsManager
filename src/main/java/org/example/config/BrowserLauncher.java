package org.example.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import lombok.extern.slf4j.Slf4j;
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
 * <p>It is a separate component with these rules:
 * <ul>
 *   <li><b>On by default</b>, because starting the game and having nothing happen is the surprising
 *       outcome. It was briefly opt-in, which cured the test popups and quietly broke normal use —
 *       the owner starting the app from the IDE got no browser and no explanation.</li>
 *   <li><b>Never in tests.</b> Three independent guards, because the failure they prevent (a dead
 *       tab on every test run) is noisy enough to be worth the belt and braces:
 *       <ol>
 *         <li>the {@code test} profile short-circuits it,</li>
 *         <li>{@code application-test.properties} sets {@code app.open-browser=false},</li>
 *         <li>and the property is only honoured when explicitly set to true, otherwise the default
 *             path applies — so a test that somehow ran without the profile still gets the
 *             property-based block.</li>
 *       </ol>
 *   <li><b>Off on a headless machine</b>, where there is no desktop to open a window on.</li>
 *   <li><b>It respects {@code server.port}</b> instead of hardcoding 8080.</li>
 *   <li><b>It opens the login page</b>, not the game-mode picker — an unauthenticated visitor has no
 *       token, so opening {@code /home.html} just bounced them to login anyway, one hop later.</li>
 * </ul>
 *
 * <p>To turn it off: {@code --app.open-browser=false}.
 *
 * <h2>It lives in {@code org.example.config} on purpose</h2>
 * {@code SportsManagerApplication} declares an explicit {@code scanBasePackages} list, and
 * {@code org.example} itself is <b>not</b> on it - a {@code @Component} in the root package is never
 * scanned, and no value of {@code app.open-browser} will make it appear. It sat there looking
 * correct, and starting the app from the IDE did nothing at all. {@code ComponentScanCoverageTest}
 * now fails the build if a bean lands outside the scanned packages again.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.open-browser", havingValue = "true", matchIfMissing = true)
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
        String url = baseUrl() + "/login.html";

        if (skipOnTestProfile) {
            log.debug("BrowserLauncher: not opening {} - this is the test profile", url);
            return;
        }
        if (isHeadless()) {
            // No desktop to open a window on. A CI agent or a remote shell would otherwise log a
            // failure it cannot act on.
            log.info("BrowserLauncher: not opening {} - no desktop available", url);
            return;
        }
        try {
            openBrowser(url);
            // Said out loud on success too. "It works but says nothing" is indistinguishable from
            // "it never ran", which is exactly the confusion this launcher caused: the owner was
            // told nothing happened and could not tell whether the bean was even present.
            log.info("Opened {} in your browser. Pass --app.open-browser=false to stop this.", url);
        } catch (Exception e) {
            // Never fail startup over this - it is a convenience, not a requirement.
            log.warn("Could not open a browser for {}. Open it manually, or pass "
                    + "--app.open-browser=false to stop asking. Cause: {}", url, e.toString());
        }
    }

    /**
     * Whether there is a desktop to open a browser on.
     *
     * <p><b>Deliberately does not consult {@code java.awt.headless}.</b> That property means "do not
     * initialise AWT", and Spring Boot sets it to true by default for server applications — so
     * reading it here meant the launcher always decided there was no desktop and never opened
     * anything, while the log cheerfully reported a headless machine. It is not a signal about a
     * display.
     *
     * <p>macOS and Windows are assumed to have one, because that is true for anyone running the game
     * locally. Only Linux is reliably detectable, and there the absence of both {@code DISPLAY} and
     * {@code WAYLAND_DISPLAY} is the honest test — which is also what a CI agent or a remote shell
     * looks like.
     */
    private boolean isHeadless() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac") || os.contains("win")) {
            return false;
        }
        return isBlank(System.getenv("DISPLAY")) && isBlank(System.getenv("WAYLAND_DISPLAY"));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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
            // The "open" binary is looked up on PATH. If it is missing, ProcessBuilder throws and
            // the reason is reported, instead of the browser silently never appearing.
            new ProcessBuilder("/usr/bin/open", url).start();
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
