package org.tifo.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A test run must never open a browser on the owner's machine.
 *
 * <p><b>What happened.</b> {@code BrowserLauncher} is an {@code ApplicationRunner}, and
 * {@code @SpringBootTest} runs the application runners. The launcher has three guards against
 * opening a browser in a test — the {@code test} profile short-circuits it,
 * {@code application-test.properties} sets {@code app.open-browser=false}, and the property is only
 * honoured when explicitly set — and **all three were defeated by the same thing**: the
 * {@code test} profile is never activated. {@code application.properties} sets
 * {@code spring.profiles.active=dev}, the dev profile points at the owner's PostgreSQL, and there was
 * no surefire configuration to say otherwise. So the profile was {@code dev},
 * {@code application-test.properties} was never read, and the launcher ran with
 * {@code matchIfMissing = true}.
 *
 * <p>On macOS that is {@code /usr/bin/open http://localhost:8080/login.html} — **the owner's own
 * browser**, once for every test run that booted a context. The launcher has three comments about
 * never doing that in a test, and the property in {@code pom.xml} is what makes the three true.
 *
 * <p><b>Why this guard reads a system property and does not boot a context.</b> A guard whose failure
 * mode is "opens a browser on the owner's machine" is a bad guard. This one reads what the test JVM
 * was told, and it never starts a Spring context — so it cannot open anything, whatever it finds.
 */
class TestRunsDoNotOpenABrowserTest {

    @Test
    @DisplayName("the test JVM is told not to open a browser")
    void theTestJvmIsToldNotToOpenABrowser() {
        assertEquals("false", System.getProperty("app.open-browser"),
                "every test run must be told not to open a browser. The launcher is an "
                        + "ApplicationRunner and @SpringBootTest runs the runners, so without this "
                        + "every test that boots a context opens http://localhost:8080/login.html in "
                        + "the owner's own browser — a tab per run, in the browser the owner is "
                        + "using.");
    }
}
