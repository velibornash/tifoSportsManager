package org.example.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The national warm-up must be reachable, and it must read as optional (owner, 2026-10-06 / 2026-10-08).
 *
 * <p><b>What was there:</b> three finished endpoints — {@code /slot}, {@code /opponents},
 * {@code POST /} — written on 2026-10-06, and <b>no frontend caller anywhere</b>. The whole feature was
 * unreachable: no manager could see the slot, name an opponent, or ask for a match, and the country page
 * looked complete without it. That is the same shape as four ranking services with no caller: finished,
 * tested, invisible.
 *
 * <p><b>Why a text guard.</b> The failure this guards is invisible from Java — the endpoints answer, and
 * the page simply has no panel. Same reason {@code SidebarBindingTest} and
 * {@code ClubMilestonesRenderHonoursTest} read the script.
 *
 * <p><b>And the optional half.</b> The owner's point is that a warm-up is not compulsory, so the panel has
 * to say so in words. A panel that merely offers a button reads as an obligation nobody explained.
 */
class NationalWarmUpPanelRenderTest {

    private static final Path COUNTRY_VIEW =
            Path.of("src/main/resources/static/js/pages/views/country-view.js");

    private String countryView() throws IOException {
        return Files.readString(COUNTRY_VIEW, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the country page calls the three warm-up endpoints")
    void theEndpointsAreCalled() throws IOException {
        String js = countryView();

        assertTrue(js.contains("/api/national/friendly-requests/"),
                "the warm-up view is never read, so a country cannot see who it has asked or been asked");
        assertTrue(js.contains("/api/national/friendly-requests/opponents"),
                "the opponent list is never read, so no national side can be named");
        assertTrue(js.contains("/api/national/friendly-requests?"),
                "nothing ever asks for one — the feature was readable and not writable");
    }

    @Test
    @DisplayName("the slot comes from the server rather than being written into the page")
    void theSlotIsNotHardCoded() throws IOException {
        assertTrue(countryView().contains("/api/national/friendly-requests/slot"),
                "the week and day are restated in the page instead of read from /slot, so the two can "
                        + "disagree and the page will be the one that is wrong");
    }

    @Test
    @DisplayName("the panel says a warm-up is optional, not compulsory")
    void thePanelSaysItIsOptional() throws IOException {
        String js = countryView();

        assertTrue(js.contains("Optional."),
                "the panel does not say the warm-up is optional");
        assertTrue(js.contains("costs nothing"),
                "and does not say what not playing costs — which is nothing, and is the owner's point");
    }

    @Test
    @DisplayName("the read only happens on the two national-team tabs")
    void itIsNotReadOnEveryTab() throws IOException {
        assertTrue(countryView().contains("const wantsWarmUp = tab === 'senior' || tab === 'u21'"),
                "the warm-up reads are unconditional, so every tab switch pays three requests for a panel "
                        + "only two tabs can show");
    }
}