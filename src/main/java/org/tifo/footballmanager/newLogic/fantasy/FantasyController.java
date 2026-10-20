package org.tifo.footballmanager.newLogic.fantasy;

import lombok.RequiredArgsConstructor;
import org.tifo.commonmanager.model.User;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The fantasy league for one country (owner, 2026-10-17).
 *
 * <p>Three calls: read the pool, read my squad, save my squad. Everything else about fantasy — the
 * standings, the points, the transfers — is built on top of these and does not need its own shape here.
 *
 * <p><b>A refused squad is a 400 with reasons, not an error.</b> "Your captain is on the bench" is a
 * normal thing for a manager to be told. Each reason carries a stable code as well as English, so the
 * picker can highlight the right row without matching on a sentence.
 */
@RestController
@RequestMapping("/api/fantasy")
@RequiredArgsConstructor
public class FantasyController {

    private final FantasySquadService squads;
    private final FantasyScoringService scoring;
    private final org.tifo.footballmanager.newLogic.service.SeasonService seasons;
    private final org.tifo.footballmanager.newLogic.repository.CountryRepository countries;
    private final FantasyLeagueService league;

    /**
     * The country's pool, priced.
     *
     * <p>Also carries whether the country can host a fantasy league at all. The per-tier rule needs exactly
     * five tiers, so a country with four cannot be played and says so here rather than in the picker.
     */
    @GetMapping("/{isoCode}/pool")
    public ResponseEntity<PoolResponse> pool(@PathVariable String isoCode) {
        Long countryId = countryIdOf(isoCode);
        if (countryId == null) {
            return ResponseEntity.notFound().build();
        }
        FantasyPool pool = squads.pool(countryId);
        if (pool.tierCount() == 0) {
            return ResponseEntity.notFound().build();
        }
        boolean weekOpen = squads.weekOpen(countryId);
        return ResponseEntity.ok(new PoolResponse(pool.countryId(), pool.eligible(), pool.ineligibleReason(),
                pool.tierCount(), FantasyPricing.BUDGET, FantasyRules.SQUAD_SIZE, weekOpen,
                squads.lockReason(weekOpen), pool.players()));
    }

    /**
     * The manager's own squad for the active season.
     *
     * <p>**A manager who has not joined gets an empty squad, not a 404** (owner, 2026-10-20, from the
     * console: "GET /api/fantasy/SRB/squad 404 (Not Found)"). "Not joined" is a state of the page, not
     * a missing thing: the Fantasy page opens on the picker for a manager who has never picked a
     * squad, and the 404 was the browser's red line in the console for a state the page handles
     * perfectly well. The empty squad says the same thing without the alarm - and the page tells
     * "joined" by the picks it carries, because a squad that exists always has fifteen of them.
     */
    @GetMapping("/{isoCode}/squad")
    public ResponseEntity<SquadResponse> mySquad(@AuthenticationPrincipal User user,
                                                 @PathVariable String isoCode) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        Long countryId = countryIdOf(isoCode);
        if (countryId == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(squads.squad(user.getId(), countryId)
                .map(SquadResponse::of)
                .orElseGet(() -> SquadResponse.empty(countryId, activeSeasonYear())));
    }

    /** The season the country's fantasy league is played in. */
    private int activeSeasonYear() {
        return seasons.getActiveSeasonYear();
    }

    /**
     * Saves the manager's squad.
     *
     * <p>Validation happens here and not in the browser: the pool the submission is checked against is the
     * one the server just built, so a client cannot propose its own prices, tiers or squad size.
     */
    @PostMapping("/{isoCode}/squad")
    public ResponseEntity<?> saveSquad(@AuthenticationPrincipal User user,
                                       @PathVariable String isoCode,
                                       @RequestBody SaveRequest body) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        Long countryId = countryIdOf(isoCode);
        if (countryId == null) {
            return ResponseEntity.notFound().build();
        }
        List<FantasySquadValidator.Selection> selections = body == null || body.picks() == null
                ? List.of()
                : body.picks().stream()
                        .map(p -> new FantasySquadValidator.Selection(p.playerId(), p.starter(), p.captain()))
                        .toList();

        FantasySquadService.SaveResult result = squads.save(user.getId(), countryId, selections);
        if (!result.ok()) {
            return ResponseEntity.badRequest().body(new RefusedResponse(result.violations()));
        }
        return ResponseEntity.ok(SquadResponse.of(result.squad()));
    }

    /** The standings: every entry in this country's league, best first. */
    @GetMapping("/{isoCode}/league")
    public ResponseEntity<FantasyLeagueService.Standings> league(@PathVariable String isoCode) {
        Long countryId = countryIdOf(isoCode);
        if (countryId == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(league.standings(countryId, seasons.getActiveSeasonYear()));
    }

    /**
     * The season's best players in this country, for the player-points table.
     *
     * <p>Twenty by default, because the question a manager is asking is "who is actually scoring", and the
     * answer to that is a short list. Capped at 200 in the service so a hand-typed limit cannot ask the
     * database for ninety thousand rows.
     */
    @GetMapping("/{isoCode}/player-points")
    public ResponseEntity<List<FantasyPlayerTotal>> playerPoints(@PathVariable String isoCode,
                                                                 @RequestParam(defaultValue = "20") int limit) {
        Long countryId = countryIdOf(isoCode);
        if (countryId == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(league.topPlayers(countryId, seasons.getActiveSeasonYear(), limit));
    }

    /**
     * Scores every finished gameweek that has not been scored yet, for one country (admin only).
     *
     * <p>The job does this on day 7 at 17:00 on its own. This exists for the two cases the job cannot
     * cover: an administrator who has advanced the clock by hand, and a week that was skipped because the
     * app was down when the hour passed. It is idempotent — a week already scored is left alone — so
     * pressing it twice cannot double anybody's points.
     */
    @PostMapping("/{isoCode}/score")
    public ResponseEntity<?> scoreNow(@AuthenticationPrincipal User user, @PathVariable String isoCode) {
        if (user == null || !isAdmin(user)) {
            return ResponseEntity.status(403).build();
        }
        Long countryId = countryIdOf(isoCode);
        if (countryId == null) {
            return ResponseEntity.notFound().build();
        }
        int seasonYear = seasons.getActiveSeasonYear();
        List<FantasyScoringService.ScoreResult> scored =
                scoring.scoreFinishedWeeks(countryId, seasonYear, seasons.getCurrentWeek());
        return ResponseEntity.ok(new ScoredResponse(seasonYear, seasons.getCurrentWeek(), scored));
    }

    /**
     * Resolves the ISO code the whole country API is addressed by into the id the fantasy tables store.
     *
     * <p>ISO codes and not ids, because every other country route in the game is addressed this way
     * ({@code /countries/{isoCode}/leagues}) and the SPA already holds the code. A second addressing scheme
     * for one feature is a thing to get wrong later.
     *
     * <p>Uppercased before the lookup: the code arrives from a URL and from a session, and neither is a
     * promise about case.
     */
    private Long countryIdOf(String isoCode) {
        if (isoCode == null || isoCode.isBlank()) {
            return null;
        }
        return countries.findByIsoCode(isoCode.trim().toUpperCase())
                .map(org.tifo.footballmanager.newLogic.model.Country::getId)
                .orElse(null);
    }

    private static boolean isAdmin(User user) {
        String role = user.getRole() == null ? null : user.getRole().name();
        return "ADMIN".equals(role) || "OWNER".equals(role) || "DEV".equals(role);
    }

    public record ScoredResponse(int seasonYear, int upToWeek, List<FantasyScoringService.ScoreResult> scored) {
    }

    // ------------------------------------------------------------------ wire shapes

    public record PoolResponse(Long countryId,
                               boolean eligible,
                               String reason,
                               int tierCount,
                               double budget,
                               int squadSize,
                               boolean weekOpen,
                               String lockReason,
                               List<FantasyPoolPlayer> players) {
    }

    public record SquadResponse(Long teamId,
                               Long countryId,
                               int seasonYear,
                               double budget,
                               double currentPrice,
                               double purchasePrice,
                               double remaining,
                               int changes,
                               int transferCost,
                               int freeChangesPerWeek,
                               int costPerExtraChange,
                               List<FantasySquad.FantasySquadPick> picks) {

        static SquadResponse of(FantasySquad squad) {
            return new SquadResponse(squad.teamId(), squad.countryId(), squad.seasonYear(),
                    FantasyPricing.BUDGET, squad.currentPrice(), squad.purchasePrice(), squad.remaining(),
                    squad.changes(), squad.transferCost(),
                    FantasyTransferRules.FREE_PER_WEEK, FantasyTransferRules.COST_PER_EXTRA,
                    squad.picks());
        }

        /**
         * The squad of a manager who has not joined yet: the whole budget, no picks, no changes.
         *
         * <p>It is the same answer as an empty one, and the page tells the two apart by the picks.
         */
        static SquadResponse empty(Long countryId, int seasonYear) {
            return new SquadResponse(null, countryId, seasonYear,
                    FantasyPricing.BUDGET, 0, 0, FantasyPricing.BUDGET, 0, 0,
                    FantasyTransferRules.FREE_PER_WEEK, FantasyTransferRules.COST_PER_EXTRA,
                    List.of());
        }
    }

    /** One player as submitted. Only the id and the two flags: price, tier and club are the server's. */
    public record PickRequest(long playerId, boolean starter, boolean captain) {
    }

    public record SaveRequest(List<PickRequest> picks) {
    }

    public record RefusedResponse(List<FantasySquadValidator.Violation> violations) {
    }
}
