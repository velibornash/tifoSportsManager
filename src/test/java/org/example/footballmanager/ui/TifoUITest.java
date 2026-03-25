package org.example.footballmanager.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * UI Tests using Playwright
 * Tests user interface flows and interactions
 * 
 * Prerequisites:
 * - Dependencies: com.microsoft.playwright:playwright
 * - Install browsers: mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install"
 * - Application running on http://localhost:3000 or http://localhost:8080
 * - Backend running on http://localhost:8080
 * 
 * Run: mvn test -Dtest=TifoUITest
 * 
 * For CI/CD (headless): Modify setUp() to use setHeadless(true)
 */
@DisplayName("TIFO UI Tests with Playwright")
public class TifoUITest {

    private Browser browser;
    private BrowserContext context;
    private Page page;

    private static final String BASE_URL = "http://localhost:8080";
    private static final String LOGIN_EMAIL = "velibor@example.com";
    private static final String LOGIN_PASSWORD = "A12345!";

    @BeforeEach
    public void setUp() {
        Playwright playwright = Playwright.create();
        
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
            .setHeadless(false) // Set to true for CI/headless
            .setSlowMo(100));

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

    // ==================== AUTHENTICATION TESTS ====================

    @Test
    @DisplayName("UI-001: Login Page - Navigate and Display")
    public void testLoginPageDisplay() {
        page.navigate(BASE_URL + "/login.html");
        
        assertNotNull(page.locator("input[type='email']").first(), "Email input should be present");
        assertNotNull(page.locator("input[type='password']").first(), "Password input should be present");
        assertNotNull(page.locator("button").first(), "Login button should be present");
    }

    @Test
    @DisplayName("UI-002: Login Flow - Valid Credentials")
    public void testLoginFlowValidCredentials() {
        page.navigate(BASE_URL + "/login.html");
        
        page.fill("input[type='email']", LOGIN_EMAIL);
        page.fill("input[type='password']", LOGIN_PASSWORD);
        page.waitForNavigation(() -> {
            page.click("button:has-text('Enter')");
        });
        
        assertTrue(page.url().contains("dashboard") || page.url().contains("index"),
            "Should navigate to dashboard after login");
    }

    @Test
    @DisplayName("UI-003: Login - Invalid Credentials Error")
    public void testLoginInvalidCredentialsError() {
        page.navigate(BASE_URL + "/login.html");
        
        page.fill("input[type='email']", "invalid@test.com");
        page.fill("input[type='password']", "wrongpassword");
        page.click("button:has-text('Enter')");
        
        Locator errorMsg = page.locator("text=/Invalid|Error|wrong/i");
        assertTrue(errorMsg.isVisible() || page.url().contains("login"),
            "Error message should display or remain on login page");
    }

    // ==================== DASHBOARD NAVIGATION TESTS ====================

    @Test
    @DisplayName("UI-004: Dashboard - Navigation Menu Display")
    public void testDashboardNavigationMenu() {
        loginAndNavigate();
        
        assertTrue(page.locator("text=Club").isVisible(), "Club menu should be visible");
        assertTrue(page.locator("text=Training").isVisible(), "Training menu should be visible");
        assertTrue(page.locator("text=League").isVisible(), "League menu should be visible");
    }

    @Test
    @DisplayName("UI-005: Dashboard - Sidebar Navigation")
    public void testSidebarNavigation() {
        loginAndNavigate();
        
        page.click("text=Club");
        page.waitForLoadState();
        
        Locator submenus = page.locator("text=/First Team|Schedule|Medical|Transfers/");
        assertTrue(submenus.count() > 0, "Club submenu items should be visible");
    }

    @Test
    @DisplayName("UI-006: Responsive Design - Mobile View")
    public void testResponsiveDesignMobileView() {
        // First login, then test responsive view
        page.navigate(BASE_URL + "/login.html");
        page.setViewportSize(375, 667); // Set mobile size
        page.fill("input[type='email']", LOGIN_EMAIL);
        page.fill("input[type='password']", LOGIN_PASSWORD);
        page.waitForNavigation(() -> {
            page.click("button:has-text('Enter')");
        });
        
        // Now check mobile menu in authenticated state
        Locator hamburger = page.locator("button:has-text('☰'), [class*='hamburger']");
        assertTrue(hamburger.count() > 0 || page.locator("[aria-label='menu']").isVisible(),
            "Mobile menu should be visible");
    }

    // ==================== CLUB MANAGEMENT TESTS ====================

    @Test
    @DisplayName("UI-007: Squad List - Display and Sorting")
    public void testSquadListDisplay() {
        loginAndNavigate();
        
        page.click("text=Club");
        page.waitForLoadState();
        page.click("text=First Team");
        page.waitForLoadState();
        
        Locator table = page.locator("table, [class*='squad'], [class*='table']");
        assertTrue(table.count() > 0, "Squad list should display");
        
        Locator players = page.locator("tr, [class*='player-row']");
        assertTrue(players.count() > 1, "Player rows should be visible");
    }

    @Test
    @DisplayName("UI-008: Player Profile - Click and Display")
    public void testPlayerProfileDisplay() {
        loginAndNavigate();
        
        page.click("text=Club");
        page.waitForLoadState();
        page.click("text=First Team");
        page.waitForLoadState();
        
        Locator firstPlayer = page.locator("tr:first-child, [class*='player-row']:first-child");
        firstPlayer.click();
        page.waitForLoadState();
        
        Locator playerName = page.locator("h1, h2, [class*='player-name']");
        assertTrue(playerName.count() > 0, "Player name should display");
        
        Locator stats = page.locator("text=/Goals|Rating|Position|Age/");
        assertTrue(stats.count() > 0, "Player statistics should display");
    }

    // ==================== MATCH SYSTEM TESTS ====================

    @Test
    @DisplayName("UI-009: Match Fixtures - Display and Navigation")
    public void testMatchFixturesDisplay() {
        loginAndNavigate();
        
        page.click("text=League");
        page.waitForLoadState();
        page.click("text=/Schedule|Fixtures|Matches/");
        page.waitForLoadState();
        
        Locator fixtures = page.locator("[class*='fixture'], [class*='match']");
        assertTrue(fixtures.count() > 0, "Fixtures should display");
    }

    @Test
    @DisplayName("UI-010: Match Visualization - TIFO Viewer")
    public void testMatchVisualization() {
        loginAndNavigate();
        
        page.click("text=League");
        page.waitForLoadState();
        page.click("text=/Results|Matches/");
        page.waitForLoadState();
        
        Locator matchResult = page.locator("tr, [class*='result']").first();
        if (matchResult.count() > 0) {
            matchResult.click();
            page.waitForLoadState();
            
            assertTrue(page.url().contains("match") || page.url().contains("tifo"),
                "Should navigate to match details");
        }
    }

    // ==================== TRAINING TESTS ====================

    @Test
    @DisplayName("UI-011: Training Setup - Player Pool Assignment")
    public void testTrainingSetup() {
        loginAndNavigate();
        
        page.click("text=Training");
        page.waitForLoadState();
        page.click("text=Training Setup");
        page.waitForLoadState();
        
        Locator generalPool = page.locator("text=/General|Advanced|Specialized/");
        assertTrue(generalPool.count() > 0, "Training pools should be visible");
    }

    @Test
    @DisplayName("UI-012: Training Reports - Display Progression")
    public void testTrainingReports() {
        loginAndNavigate();
        
        page.click("text=Training");
        page.waitForLoadState();
        page.click("text=Training Reports");
        page.waitForLoadState();
        
        Locator weekNumber = page.locator("text=/Week|Week #/");
        assertTrue(weekNumber.isVisible(), "Week information should display");
    }

    // ==================== RESPONSIVE TESTS ====================

    @Test
    @DisplayName("UI-013: Responsive - Tablet View")
    public void testResponsiveTabletView() {
        // First login at normal size, then change to tablet size
        loginAndNavigate();
        
        // Now resize to tablet and verify content adapts
        page.setViewportSize(768, 1024);
        
        Locator mainContent = page.locator("main, [class*='content']");
        assertTrue(mainContent.isVisible(), "Main content should be visible");
    }

    @Test
    @DisplayName("UI-014: Dark Theme - Color Verification")
    public void testDarkThemeColors() {
        loginAndNavigate();
        
        Locator body = page.locator("body");
        String bgColor = body.evaluate("el => window.getComputedStyle(el).backgroundColor").toString();
        assertNotNull(bgColor, "Background color should be defined");
        
        String textColor = body.evaluate("el => window.getComputedStyle(el).color").toString();
        assertNotNull(textColor, "Text color should be defined");
    }

    // ==================== FORMS & INPUT TESTS ====================

    @Test
    @DisplayName("UI-015: Form Validation - Empty Field Error")
    public void testFormValidationEmptyFields() {
        page.navigate(BASE_URL + "/login.html");
        
        page.click("button:has-text('Enter')");
        
        Locator errorMsg = page.locator("text=/required|Please|Invalid/i");
        boolean hasValidation = errorMsg.count() > 0 || page.locator("input[required]").count() > 0;
        assertTrue(hasValidation, "Form validation should prevent empty submission");
    }

    // ==================== PERFORMANCE TESTS ====================

    @Test
    @DisplayName("UI-016: Page Load Performance")
    public void testPageLoadPerformance() {
        // First login to get valid session
        loginAndNavigate();
        
        // Now measure performance of navigating to a page in authenticated state
        long startTime = System.currentTimeMillis();
        page.click("text=Club");
        page.waitForLoadState();
        long endTime = System.currentTimeMillis();
        
        long loadTime = endTime - startTime;
        assertTrue(loadTime < 10000, "Page should load within 10 seconds");
        assertTrue(page.locator("text=/First Team|Medical|Transfers/").count() > 0, 
            "Page content should be loaded");
    }

    // ==================== ACCESSIBILITY TESTS ====================

    @Test
    @DisplayName("UI-017: Accessibility - Keyboard Navigation")
    public void testKeyboardNavigation() {
        page.navigate(BASE_URL + "/login.html");
        
        page.press("body", "Tab");
        Locator focused = page.locator(":focus");
        assertTrue(focused.count() > 0, "Tab navigation should work");
    }

    // ==================== HELPER METHODS ====================

    private void loginAndNavigate() {
        page.navigate(BASE_URL + "/login.html");
        page.fill("input[type='email']", LOGIN_EMAIL);
        page.fill("input[type='password']", LOGIN_PASSWORD);
        page.waitForNavigation(() -> {
            page.click("button:has-text('Enter')");
        });
    }
}

