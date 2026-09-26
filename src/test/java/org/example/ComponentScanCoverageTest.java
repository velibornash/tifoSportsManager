package org.example;

import org.example.config.BrowserLauncher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A bean outside the scanned packages does not exist, however correct it looks.
 *
 * <p>{@code SportsManagerApplication} declares an explicit {@code scanBasePackages} list, and
 * {@code org.example} — the application's own package — is <b>not</b> on it. {@code BrowserLauncher}
 * was a {@code @Component} in that root package, so Spring never scanned it: the browser did not
 * open when the owner started the game from the IDE, and no value of {@code app.open-browser} could
 * change that, because the bean was not in the context at all. It looked entirely correct in the
 * source and was invisible at runtime.
 *
 * <p>This test turns that into a build failure instead of a mystery.
 */
class ComponentScanCoverageTest {

    /**
     * Beans that must be reachable. Each one is here because its absence is silent: no compile
     * error, no warning, just a feature that quietly does nothing.
     */
    private static final Class<?>[] MUST_BE_SCANNED = {
            BrowserLauncher.class
    };

    @Test
    @DisplayName("every infrastructure bean sits inside a scanned package")
    void infrastructureBeansAreScanned() {
        Set<String> scanned = scannedPackages();

        for (Class<?> type : MUST_BE_SCANNED) {
            String pkg = type.getPackageName();
            boolean covered = scanned.stream().anyMatch(pkg::equals);
            if (!covered) {
                fail(type.getSimpleName() + " is in '" + pkg + "', which is not in the application's "
                        + "scanBasePackages " + scanned + ". The bean will never be created, and nothing "
                        + "in the build or the log will say so. Move it into a scanned package.");
            }
        }
    }

    @Test
    @DisplayName("the application's own package is not scanned, so nothing may live there")
    void theRootPackageIsNotScanned() {
        Set<String> scanned = scannedPackages();
        assertTrue(!scanned.contains("org.example"),
                "If org.example ever IS scanned, the explicit list is doing less than it looks like "
                        + "and this test's premise needs revisiting");
    }

    @Test
    @DisplayName("BrowserLauncher is a Spring bean and not merely a class with a main method")
    void browserLauncherIsABean() {
        assertTrue(BrowserLauncher.class.isAnnotationPresent(org.springframework.stereotype.Component.class),
                "BrowserLauncher must be annotated so Spring can find it");
    }

    private Set<String> scannedPackages() {
        SpringBootApplication annotation =
                SportsManagerApplication.class.getAnnotation(SpringBootApplication.class);
        Set<String> packages = new LinkedHashSet<>(Arrays.asList(annotation.scanBasePackages()));
        // ComponentScan on the class would override the annotation's list, so it is checked too.
        ComponentScan scan = SportsManagerApplication.class.getAnnotation(ComponentScan.class);
        if (scan != null) {
            packages.addAll(Arrays.asList(scan.basePackages()));
            for (Class<?> c : scan.basePackageClasses()) {
                packages.add(c.getPackageName());
            }
        }
        return packages;
    }
}
