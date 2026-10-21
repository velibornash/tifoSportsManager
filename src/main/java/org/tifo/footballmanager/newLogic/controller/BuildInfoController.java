package org.tifo.footballmanager.newLogic.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Which build this is.
 *
 * <p>The owner asked for it after looking at the same page on two machines and not being able to say
 * whether they were the same code: *"trebamo uvesti verziju app da pise negde na settings ili gde
 * god i da dizemo svaki put kad izmenimo da mozemo da pratimo"* - the app must carry a version, it
 * must be written somewhere a manager can see it, and it must rise every time the app changes.
 *
 * <p><b>The version is the commit.</b> A hand-written counter has to be remembered, and a forgotten
 * counter is worse than none: it says "3.14" and means nothing. The commit says exactly which code
 * this is, it rises on every change by itself, and two machines can be compared by reading it.
 *
 * <p>The values come from {@code git.properties}, which the build writes into the jar
 * ({@code git-commit-id-maven-plugin}). **The file is read, not required**: a build without the
 * plugin's output - a source tarball, a jar someone edited - answers "unknown" instead of refusing to
 * start, because a build that cannot say its version is still a build that works.
 */
@RestController
@RequestMapping("/api")
public class BuildInfoController {

    private static final String PROPERTIES_FILE = "git.properties";

    /**
     * The build's own words, read once.
     *
     * <p>Read lazily and kept: the file never changes while the app runs, and the class is asked for
     * it on every Settings page.
     */
    private static final Map<String, String> BUILD = readBuildProperties();

    @GetMapping("/build-info")
    public ResponseEntity<Map<String, String>> buildInfo() {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("commit", BUILD.getOrDefault("commit", "unknown"));
        payload.put("branch", BUILD.getOrDefault("branch", "unknown"));
        payload.put("builtAt", BUILD.getOrDefault("builtAt", "unknown"));
        payload.put("app", "Tifo Sports Manager");
        return ResponseEntity.ok(payload);
    }

    private static Map<String, String> readBuildProperties() {
        Map<String, String> values = new LinkedHashMap<>();
        try (InputStream stream = new ClassPathResource(PROPERTIES_FILE).getInputStream()) {
            Properties properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            values.put("commit", trimmed(properties.getProperty("git.commit.id.abbrev")));
            values.put("branch", trimmed(properties.getProperty("git.branch")));
            values.put("builtAt", trimmed(properties.getProperty("git.build.time")));
        } catch (IOException | RuntimeException ignored) {
            // No git.properties, or one that cannot be read: the build is still a build.
            values.put("commit", "unknown");
            values.put("branch", "unknown");
            values.put("builtAt", "unknown");
        }
        return values;
    }

    private static String trimmed(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim();
    }
}
