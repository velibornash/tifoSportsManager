package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.CountryRepository;
import org.example.footballmanager.newLogic.repository.MatchRepository;
import org.example.footballmanager.newLogic.repository.ScoredMatch;
import org.example.footballmanager.newLogic.model.CompetitionScope;
import org.example.footballmanager.newLogic.model.CompetitionType;
import org.example.footballmanager.newLogic.model.MatchFixture;
import org.example.footballmanager.newLogic.model.NationalStage;
import org.example.footballmanager.newLogic.repository.CompetitionRepository;
import org.example.footballmanager.newLogic.repository.MatchFixtureRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The international Elo replay must read the world once, not three times per match.
 *
 * <p><b>Why a query count and not a clock.</b> The bug was {@code findOwningCountry} walking
 * {@code countries.findAll()}, called three times per match — twice from {@code isNationalSide} and once
 * from {@code isYouth}. On a 48-row table each of those reads costs less than the loop that asks for it,
 * so <b>every clock in this repository would have called the broken code fast</b>. The number that
 * actually distinguishes them is how many times the database is asked.
 *
 * <p>Pure mocked unit test: milliseconds, exact counts, no Spring context and no H2. An earlier attempt
 * at a guard in this repository used {@code @SpyBean}, skipped on the empty test database and reported
 * green — see {@code kanbanProgress.md}.
 */
class NationalRatingServiceQueryCountTest {

    private final MatchRepository matches = mock(MatchRepository.class);
    private final CountryRepository countries = mock(CountryRepository.class);
    private final NationalRatingService ratings = new NationalRatingService(
            matches, mock(MatchFixtureRepository.class), countries, mock(CompetitionRepository.class),
            mock(PlatformTransactionManager.class));

    @Test
    @DisplayName("the world is read once for a replay, however many matches it replays")
    void theWorldIsReadOnce() {
        when(countries.findAll()).thenReturn(aWorldOf(24));
        when(matches.findPlayedNationalScoredInOrder())
                .thenReturn(internationalsBetween(1, 24, 10));

        ratings.recompute();

        verify(countries, times(1)).findAll();
    }

    @Test
    @DisplayName("tripling the replayed matches does not add a single query")
    void theCountDoesNotGrowWithTheHistory() {
        when(countries.findAll()).thenReturn(aWorldOf(24));

        when(matches.findPlayedNationalScoredInOrder()).thenReturn(internationalsBetween(1, 24, 1));
        ratings.recompute();

        when(matches.findPlayedNationalScoredInOrder()).thenReturn(internationalsBetween(1, 24, 10));
        ratings.recompute();

        // Two replays, one match each and then ten. Before the fix this was 1 + (3 x 1) and then
        // 1 + (3 x 10): the count grew with the history instead of with the work.
        verify(countries, times(2)).findAll();
    }

    @Test
    @DisplayName("a replay over no matches still reads the world exactly once")
    void anEmptyReplayStillReadsTheWorldOnce() {
        when(countries.findAll()).thenReturn(aWorldOf(24));
        when(matches.findPlayedNationalScoredInOrder()).thenReturn(List.of());

        NationalRatingService.Result result = ratings.recompute();

        assertEquals(0, result.matchesReplayed());
        verify(countries, times(1)).findAll();
    }

    /** 24 countries, each with a senior side at {@code id} and a U-21 side at {@code id + 1000}. */
    private List<Country> aWorldOf(int countryCount) {
        List<Country> world = new ArrayList<>();
        for (int i = 1; i <= countryCount; i++) {
            Country country = new Country();
            country.setIsoCode(String.format("Q%02d", i));
            country.setName("Nation " + i);
            country.setSeniorNationalTeam(nationalSide(i));
            country.setU21NationalTeam(nationalSide(i + 1000));
            world.add(country);
        }
        return world;
    }

    private Team nationalSide(long id) {
        Team side = new Team();
        side.setId(id);
        side.setName("Nation side " + id);
        return side;
    }

    /**
     * Consecutive internationals between the same national sides, so every one of them is rated.
     *
     * <p>Between nations 1 and 2 and back, which also means the replay moves ratings and the assertion
     * is not passing on an empty loop.
     */
    private List<ScoredMatch> internationalsBetween(int firstNation, int lastNation, int count) {
        List<ScoredMatch> played = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            long home = firstNation + (long) (i % Math.max(1, lastNation - firstNation + 1));
            long away = home == firstNation ? lastNation : firstNation;
            played.add(new ScoredMatch((long) i + 1, home, "Nation " + home, away, "Nation " + away,
                    i % 3, (i + 1) % 2, CompetitionScope.INTERNATIONAL, CompetitionType.INTERNATIONAL,
                    NationalStage.OTHER));
        }
        return played;
    }
}
