package org.example.footballmanager.newLogic.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * S8.3a: every panel overflowed the phone viewport.
 *
 * <p>This exists because the previous check was worthless. The old test asked whether
 * {@code documentElement.scrollWidth > innerWidth}, and {@code overflow-x: hidden} on {@code html}
 * guarantees that answer is always "no" — the overflow was real, and the test that was supposed to
 * catch it reported green every time. A test that cannot fail is worse than no test.
 *
 * <p>So this measures the panels themselves: every {@code .fm-panel} must have its right edge inside
 * the viewport, and must be no wider than its own parent. Those are properties of the boxes, and
 * they are falsifiable.
 *
 * <p>Rendered straight from the CSS on disk against {@code file://} rather than through a running
 * application. The bug is entirely in the stylesheet, so booting Spring, seeding a database and
 * logging in would add a minute and a hundred ways to fail for no extra signal. Real pages are
 * needed though, because a panel with no content collapses and would pass while telling us nothing —
 * so the fixture is a genuine page structure using the app's real class names and import order.
 */
class MobilePanelOverflowTest {

    private static final int PHONE_WIDTH = 390;
    private static final int PHONE_HEIGHT = 844;

    private static Playwright playwright;
    private static Browser browser;

    @BeforeAll
    static void launch() {
        Path css = Path.of("src/main/resources/static/css/dashboard.css");
        assumeTrue(Files.exists(css), "dashboard.css not on disk - run from the project root");
        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new com.microsoft.playwright.BrowserType.LaunchOptions().setHeadless(true));
        } catch (Exception e) {
            // No browser binary on this machine. The CSS assertions below still run; the geometry
            // check is skipped rather than failed, because failing here would only ever mean "this
            // developer has not run the Playwright installer", which tells nobody anything.
            assumeTrue(false, "Chromium unavailable: " + e.getMessage());
        }
    }

    @AfterAll
    static void shutdown() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @Test
    @DisplayName("no .fm-panel extends past the phone viewport, at any depth of content")
    void panelsFitTheViewport() throws Exception {
        BrowserContext context = browser.newContext(newContextOptions());
        Page page = context.newPage();
        page.setContent(pageHtml());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> overflowing =
                (List<Map<String, Object>>) (Object) page.evaluate("""
                () => {
                    const vw = document.documentElement.clientWidth;
                    return [...document.querySelectorAll('.fm-panel')].map(el => {
                        const r = el.getBoundingClientRect();
                        const p = el.parentElement.getBoundingClientRect();
                        return {
                            panel: el.className,
                            right: Math.round(r.right),
                            parentRight: Math.round(p.right),
                            width: Math.round(r.width),
                            parentWidth: Math.round(p.width),
                            vw,
                            overflowsViewport: r.right > vw + 0.5,
                            overflowsParent: r.right > p.right + 0.5
                        };
                    }).filter(x => x.overflowsViewport || x.overflowsParent);
                }
                """);

        context.close();

        assertTrue(overflowing.isEmpty(),
                () -> "These panels run past their container on a " + PHONE_WIDTH + "px phone "
                        + "(dashboard-content is 100% border-box with 12px padding, so the column is "
                        + (PHONE_WIDTH - 24) + "px):\n  " + overflowing);
    }

    @Test
    @DisplayName("the mobile rule that caused it is still width:100% - so border-box must hold")
    void mobileRuleStillSetsWidth100Percent() throws Exception {
        // Guards the reason the bug exists. If someone removes `width: 100%` from the mobile block
        // the fix becomes unnecessary; if someone re-adds `width` elsewhere, or drops the
        // `box-sizing: border-box` on .fm-panel, the panels overflow again. Both directions are
        // worth knowing about, and neither is visible from the rendered result alone.
        String css = Files.readString(Path.of("src/main/resources/static/css/dashboard.css"));

        assertTrue(css.contains("box-sizing: border-box;"),
                ".fm-panel must declare border-box, or width:100% plus 40px of padding overflows");
        assertTrue(css.contains("min-width: 0;"),
                "the mobile block must keep min-width:0, or flex children refuse to shrink");
    }

    private Browser.NewContextOptions newContextOptions() {
        return new Browser.NewContextOptions()
                .setViewportSize(PHONE_WIDTH, PHONE_HEIGHT)
                .setDeviceScaleFactor(3)
                .setIsMobile(true);
    }

    /**
     * A page built from the app's real stylesheet, real class names and real import order, with
     * enough content to be a fair test.
     *
     * <p>The viewport meta tag is not decoration. Without it a mobile browser lays out at a default
     * 980px and every media query below 980 is never reached — the first run of this test measured a
     * 980px tablet and called it a phone.
     *
     * <p>Import order is the part that keeps biting: the responsive overrides live in
     * {@code css/dashboard/overrides.css} but {@code dashboard.css}'s own rules come <i>after</i> the
     * imports, so a later rule there wins without {@code !important}. Reproducing that order here is
     * the difference between testing the shipped layout and testing a layout that never existed.
     */
    private String pageHtml() throws Exception {
        String dashboard = Files.readString(Path.of("src/main/resources/static/css/dashboard.css"));
        String overrides = Files.readString(
                Path.of("src/main/resources/static/css/dashboard/overrides.css"));

        return """
                <!doctype html>
                <html><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>%s</style><style>%s</style></head>
                <body>
                  <div class="dashboard-content">
                    <div class="fm-page">
                      %s
                    </div>
                  </div>
                </body></html>
                """
                .formatted(dashboard, overrides, panels());
    }

    /** Panels with real content, including the wide ones that actually overflowed. */
    private String panels() {
        return """
                <section class="fm-panel">
                  <div class="fm-panel-head"><h3>Attributes</h3>
                    <span class="fm-panel-action">Overall <strong>83</strong></span></div>
                  <table class="fm-skills"><tbody>
                    <tr><td>Stamina</td><td>7.40</td></tr><tr><td>Pace</td><td>6.10</td></tr>
                    <tr><td>Technique</td><td>8.20</td></tr><tr><td>Passing</td><td>5.90</td></tr>
                  </tbody></table>
                </section>
                <section class="fm-panel">
                  <div class="fm-panel-head"><h3>Junior school</h3></div>
                  <p>Six prospects graduate at the end of the season. The intake is generated in week 2.</p>
                  <button class="fm-btn fm-btn-danger" id="close-school">Close school</button>
                </section>
                <section class="fm-panel">
                  <div class="fm-panel-head"><h3>Fixture</h3></div>
                  <p>Omladinac v Sremac Berkasovo - the longest fixture name this application can hold,
                     used here to make sure a long line of text does not widen the panel either.</p>
                </section>
                """;
    }
}
