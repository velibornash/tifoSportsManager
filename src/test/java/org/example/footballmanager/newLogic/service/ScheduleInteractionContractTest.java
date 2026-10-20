package org.example.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S8.3 item 4: {@code bindScheduleInteractions} passed {@code seasonYear} as a bare positional
 * argument, so the season a team link carried was thrown away.
 *
 * <p>The detail that makes this worth a test rather than a one-line fix: it was only ever invisible
 * because of luck. {@code loadLeagueTeam}'s real signature is
 * {@code (teamId, teamName, options = {})}, so handing it a Number means
 * {@code options.seasonYear} is {@code undefined} and the season silently defaults. Both live call
 * sites passed an adapter that converted the positional value back into an object, which is why
 * clicking a team name worked. The default fallback is {@code window.loadLeagueTeam} itself, and
 * that path was broken — it just happened that the one caller passing no handlers also renders no
 * {@code .js-load-CTeam} markup at all.
 *
 * <p>So the bug was latent, not absent, and a refactor that added a team link to a page using the
 * bare call would have shipped "open club, wrong season, no error". This pins the contract instead:
 * the caller speaks in options objects, and so must every adapter.
 */
class ScheduleInteractionContractTest {

    private static final Path RENDERERS =
            Path.of("src/main/resources/static/js/pages-renderers.js");

    @Test
    @DisplayName("loadLeagueTeam is called with an options object everywhere, never a bare value")
    void seasonIsNeverPassedPositional() throws IOException {
        String code = withoutComments(read(RENDERERS));

        List<String> positional = new ArrayList<>();
        Matcher calls = Pattern
                .compile("onLoadTeam\\s*\\(\\s*[^,]+,\\s*[^,]+,\\s*([^)]+?)\\s*\\)")
                .matcher(code);
        while (calls.find()) {
            String third = calls.group(1).trim();
            // A bare identifier, a number or null is positional. `{...}` is the object contract.
            boolean isObject = third.startsWith("{");
            if (!isObject) {
                positional.add(third);
            }
        }

        assertTrue(positional.isEmpty(),
                () -> "loadLeagueTeam's third parameter is an options object, but it was called with "
                        + "the bare value(s) " + positional + ". A Number or null there means "
                        + "options.seasonYear is undefined and the selected season is lost without "
                        + "any error being raised.");
    }

    @Test
    @DisplayName("every loadLeagueTeam adapter accepts the same options-object shape")
    void adaptersMatchTheCallerContract() throws IOException {
        String code = withoutComments(read(RENDERERS));

        List<String> bad = new ArrayList<>();
        Matcher adapters = Pattern
                .compile("loadLeagueTeam\\s*:\\s*\\(\\s*([^)]*?)\\)\\s*=>")
                .matcher(code);
        while (adapters.find()) {
            String params = adapters.group(1);
            // The third parameter must be an object, either destructured or defaulted.
            boolean takesOptions = params.contains("options")
                    || params.contains("{") && params.contains("}")
                    || params.contains("=");
            if (!takesOptions) {
                bad.add(params.trim());
            }
        }

        assertTrue(bad.isEmpty(),
                () -> "These loadLeagueTeam adapters do not accept an options object: " + bad
                        + ". The caller passes one, so these receive undefined for seasonYear.");
    }

    @Test
    @DisplayName("the season actually reaches the markup, so the contract has something to carry")
    void markupCarriesTheSeason() throws IOException {
        String code = read(RENDERERS);

        assertTrue(code.contains("data-season-year"),
                "the schedule markup must write data-season-year, or there is no season to preserve");
        assertTrue(code.contains("dataset.seasonYear"),
                "the binder must read it back off the node");
    }

    private String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** Comments stripped, so a mention of the old shape in prose does not fail the build. */
    private String withoutComments(String src) {
        return src.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }
}
