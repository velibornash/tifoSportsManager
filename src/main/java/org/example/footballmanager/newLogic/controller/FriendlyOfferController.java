package org.example.footballmanager.newLogic.controller;

import org.example.footballmanager.newLogic.model.FriendlyOffer;
import org.example.footballmanager.newLogic.service.FriendlyOfferService;
import org.example.footballmanager.newLogic.service.SeasonService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The free-slot board, over HTTP (owner, 2026-10-06).
 *
 * <p>Thin over {@link FriendlyOfferService}: the rules live there, and every refusal is already a real
 * answer. A posting is refused for the reason the service gives, and that reason is worth reading - so
 * it is returned as the body rather than swallowed into a status code.
 *
 * <p>The board itself is not scoped to a club. It is the one place a manager goes to fill a slot, which
 * is the point of an advertisement: the day is published to teams that were not asked.
 */
@RestController
@RequestMapping("/api/season/friendly-offers")
public class FriendlyOfferController {

    private final FriendlyOfferService offers;
    private final SeasonService seasons;

    public FriendlyOfferController(FriendlyOfferService offers, SeasonService seasons) {
        this.offers = offers;
        this.seasons = seasons;
    }

    /** What the week offers, as a board. Open, not yet expired, and prints its day. */
    @GetMapping
    public Map<String, Object> board(@RequestParam(required = false) Integer season,
                                     @RequestParam(required = false) Integer week) {
        int seasonYear = season != null ? season : seasons.getActiveSeasonYear();
        int weekNumber = week != null ? week : seasons.getCurrentWeek();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FriendlyOffer offer : offers.board(seasonYear, weekNumber)) {
            rows.add(row(offer));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("season", seasonYear);
        body.put("week", weekNumber);
        body.put("offers", rows);
        return body;
    }

    /** This club's own postings in the week, whatever their state. */
    @GetMapping("/mine/{teamId}")
    public Map<String, Object> mine(@PathVariable Long teamId,
                                    @RequestParam(required = false) Integer season,
                                    @RequestParam(required = false) Integer week) {
        int seasonYear = season != null ? season : seasons.getActiveSeasonYear();
        int weekNumber = week != null ? week : seasons.getCurrentWeek();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FriendlyOffer offer : offers.mine(teamId, seasonYear, weekNumber)) {
            rows.add(row(offer));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("season", seasonYear);
        body.put("week", weekNumber);
        body.put("offers", rows);
        return body;
    }

    /**
     * Advertises a slot.
     *
     * <p>409 for every refusal rather than a stack trace: only a human club may post, a league slot may
     * not be advertised, a passed slot may not be advertised, and a club may not list the same slot
     * twice.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> post(@RequestParam Long teamId,
                                                   @RequestParam Integer week,
                                                   @RequestParam Integer slot) {
        int seasonYear = seasons.getActiveSeasonYear();
        return offers.post(teamId, seasonYear, week, slot)
                .map(offer -> ResponseEntity.ok(row(offer)))
                .orElseGet(() -> ResponseEntity.status(409).body(Map.of(
                        "error", "That slot cannot be advertised",
                        "detail", "Only human clubs post friendlies, only friendly-capable slots may be "
                                + "advertised, a passed slot is closed, and a club may not list the same "
                                + "slot twice.")));
    }

    /**
     * Takes an advertised slot, by asking the club that posted it.
     *
     * <p>409 rather than an error for the ordinary refusals: it has already been taken, claimed by its ad's
     * own club, made by or to a bot, or is past. Beneath the refusal it is a real request, and that is a
     * real answer.
     */
    @PostMapping("/{offerId}/claim")
    public ResponseEntity<Map<String, Object>> claim(@PathVariable Long offerId,
                                                    @RequestParam Long teamId) {
        return offers.claim(offerId, teamId)
                .map(offer -> ResponseEntity.ok(row(offer)))
                .orElseGet(() -> ResponseEntity.status(409).body(Map.of(
                        "error", "That slot cannot be taken",
                        "detail", "It may already be taken, expired, yours, or posted by a bot.")));
    }

    /** Withdraws a posting this club made. */
    @PostMapping("/{offerId}/withdraw")
    public ResponseEntity<Map<String, Object>> withdraw(@PathVariable Long offerId,
                                                       @RequestParam Long teamId) {
        return offers.withdraw(offerId, teamId)
                .map(offer -> ResponseEntity.ok(row(offer)))
                .orElseGet(() -> ResponseEntity.status(409).body(Map.of(
                        "error", "That posting cannot be withdrawn",
                        "detail", "It may no longer be open, or it was not yours.")));
    }

    /** One advertisement as the client reads it. The period is on the face of the row. */
    private Map<String, Object> row(FriendlyOffer offer) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", offer.getId());
        row.put("teamId", offer.getOfferingTeam() == null ? null : offer.getOfferingTeam().getId());
        row.put("teamName", offer.getOfferingTeam() == null ? null : offer.getOfferingTeam().getName());
        row.put("season", offer.getSeasonYear());
        row.put("week", offer.getWeekNumber());
        row.put("day", offer.getDayNumber());
        row.put("slot", offer.getSlot());
        row.put("status", offer.getStatus() == null ? null : offer.getStatus().name());
        row.put("createdAt", offer.getCreatedAt());
        return row;
    }
}