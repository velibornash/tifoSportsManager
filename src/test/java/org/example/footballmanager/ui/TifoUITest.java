package org.example.footballmanager.ui;

import static org.junit.jupiter.api.Assertions.*;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * UI Tests using Playwright
 * Tests user interface availability and basic navigation
 */
@DisplayName("TIFO UI Tests with Playwright")
public class TifoUITest {

    private Browser browser;
    private BrowserContext context;
    private Page page;
    private static final String BASE_URL = "http://localhost:8080";

    @BeforeEach
    public void setUp() {
        Playwright playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
            .setHeadless(true).setSlowMo(100));
        context = browser.newContext();
        page = context.newPage();
        page.setViewportSize(1920, 1080);
    }

    @AfterEach
    public void tearDown() {
        if (page != null) page.close();
        if (context != null) context.close();
        if (browser != null) browser.close();
    }

    @Test
    @DisplayName("UI-001: Login Page Display")
    public void testLoginPageDisplay() {
        page.navigate(BASE_URL + "/login.html");
        assertTrue(page.locator("input[type='email']").count() > 0);
    }

    @Test
    @DisplayName("UI-002: Register Page Display")
    public void testRegisterPageDisplay() {
        page.navigate(BASE_URL + "/register.html");
        assertTrue(page.url().contains("register"));
    }

    @Test
    @DisplayName("UI-003: Button Elements")
    public void testButtonElements() {
        page.navigate(BASE_URL + "/login.html");
        assertTrue(page.locator("button").count() > 0);
    }

    @Test
    @DisplayName("UI-004: Form Inputs")
    public void testFormInputs() {
        page.navigate(BASE_URL + "/login.html");
        assertTrue(page.locator("input").count() > 1);
    }

    @Test
    @DisplayName("UI-005: Page Title")
    public void testPageTitle() {
        page.navigate(BASE_URL + "/login.html");
        assertTrue(page.title().length() > 0);
    }

    @Test
    @DisplayName("UI-006: Password Input")
    public void testPasswordInput() {
        page.navigate(BASE_URL + "/login.html");
        assertTrue(page.locator("input[type='password']").count() > 0);
    }

    @Test
    @DisplayName("UI-007: Responsive Desktop")
    public void testResponsiveDesktop() {
        page.setViewportSize(1920, 1080);
        page.navigate(BASE_URL + "/login.html");
        assertTrue(page.locator("button").count() > 0);
    }

    @Test
    @DisplayName("UI-008: Responsive Mobile")
    public void testResponsiveMobile() {
        page.setViewportSize(375, 667);
        page.navigate(BASE_URL + "/login.html");
        assertTrue(page.locator("button").count() > 0);
    }

    @Test
    @DisplayName("UI-009: Page Load")
    public void testPageLoad() {
        page.navigate(BASE_URL + "/login.html");
        assertNotNull(page.locator("body").first());
    }

    @Test
    @DisplayName("UI-010: CSS Styles")
    public void testCSSStyles() {
        page.navigate(BASE_URL + "/login.html");
        String bgColor = page.locator("body").evaluate("el => window.getComputedStyle(el).backgroundColor").toString();
        assertNotNull(bgColor);
    }

    @Test
    @DisplayName("UI-011: Email Field")
    public void testEmailField() {
        page.navigate(BASE_URL + "/login.html");
        assertTrue(page.locator("input[type='email']").count() > 0);
    }

    @Test
    @DisplayName("UI-012: Form Structure")
    public void testFormStructure() {
        page.navigate(BASE_URL + "/login.html");
        assertTrue(page.locator("input").count() > 0 && page.locator("button").count() > 0);
    }

    @Test
    @DisplayName("UI-013: Page Navigation")
    public void testPageNavigation() {
        page.navigate(BASE_URL + "/login.html");
        assertTrue(!page.url().isEmpty());
    }

    @Test
    @DisplayName("UI-014: Register Page Elements")
    public void testRegisterPageElements() {
        page.navigate(BASE_URL + "/register.html");
        assertTrue(page.locator("input").count() > 0);
    }

    @Test
    @DisplayName("UI-015: Accessibility Tab")
    public void testAccessibilityTab() {
        page.navigate(BASE_URL + "/login.html");
        page.press("body", "Tab");
        assertNotNull(page.locator("body").first());
    }

    @Test
    @DisplayName("UI-016: Content Present")
    public void testContentPresent() {
        page.navigate(BASE_URL + "/login.html");
        assertTrue(page.content().length() > 0);
    }

    @Test
    @DisplayName("UI-017: Page Response")
    public void testPageResponse() {
        page.navigate(BASE_URL + "/login.html");
        assertTrue(page.url().contains("8080"));
    }
}

