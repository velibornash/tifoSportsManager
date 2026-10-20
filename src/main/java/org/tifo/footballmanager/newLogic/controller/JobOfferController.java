package org.tifo.footballmanager.newLogic.controller;

import lombok.RequiredArgsConstructor;
import org.tifo.commonmanager.model.User;
import org.tifo.footballmanager.newLogic.service.JobOfferService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Job offers, from the manager's side (owner, 2026-10-18).
 *
 * <p>Read and answer. Making offers is the weekly job's business and the admin button's, not a route a
 * manager can call: a manager who could ask for an offer would be offered a job by himself.
 */
@RestController
@RequestMapping("/api/job-offers")
@RequiredArgsConstructor
public class JobOfferController {

    private final JobOfferService jobOffers;

    /** What is on the table, with everything the Office page shows about it. */
    @GetMapping
    public ResponseEntity<List<JobOfferService.OfferView>> myOffers(@AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(jobOffers.openOffers(user.getId()));
    }

    /** The clubs this manager has run, newest first. */
    @GetMapping("/history")
    public ResponseEntity<List<JobOfferService.TenureView>> myHistory(@AuthenticationPrincipal User user) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(jobOffers.history(user.getId()));
    }

    /**
     * Takes the job.
     *
     * <p>A stale offer — expired while the page sat open, or already answered — is a **409 with a reason**,
     * not a 500 and not a silent no. Pressing a day-old button is a normal thing to do and the answer is
     * "that is no longer available", which is a fact about the offer rather than an error in the request.
     */
    @PostMapping("/{offerId}/accept")
    public ResponseEntity<Map<String, Object>> accept(@AuthenticationPrincipal User user,
                                                      @PathVariable Long offerId) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        if (!jobOffers.accept(offerId, user.getId())) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "offer_unavailable",
                    "message", "That offer is no longer available. It has either been answered or it has expired."));
        }
        return ResponseEntity.ok(Map.of("status", "accepted"));
    }

    /** Turns it down. Nothing else happens. */
    @PostMapping("/{offerId}/decline")
    public ResponseEntity<Map<String, Object>> decline(@AuthenticationPrincipal User user,
                                                       @PathVariable Long offerId) {
        if (user == null) {
            return ResponseEntity.status(401).build();
        }
        if (!jobOffers.decline(offerId, user.getId())) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "offer_unavailable",
                    "message", "That offer is no longer available. It has either been answered or it has expired."));
        }
        return ResponseEntity.ok(Map.of("status", "declined"));
    }
}
