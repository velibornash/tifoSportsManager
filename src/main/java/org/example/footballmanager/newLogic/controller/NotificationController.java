package org.example.footballmanager.newLogic.controller;

import lombok.RequiredArgsConstructor;
import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.service.NotificationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The notification store (owner, 2026-10-05).
 *
 * <p><b>Every route resolves the recipient from the token.</b> There is no recipient id in any path or
 * query string, so there is nothing for a caller to change. That is the same design as
 * {@code CommunityController} and it is why the route needs no ownership test beyond "it needs a token":
 * the question "whose notifications are these" has no answer a caller can supply.
 *
 * <p>{@code markRead} is the one route where an id <i>is</i> in the path — the notification's own — and it
 * checks ownership inside the query rather than beside it, so somebody else's notification is a 404 and
 * not a silent no-op. A silent no-op there produces a badge that never clears and a support ticket that
 * says "the read button does nothing".
 */
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notifications;

    /**
     * One page of notifications, plus the unread count.
     *
     * <p>Both in one response because the frontend polls this every 30 seconds and the badge and the list
     * must not be able to disagree — two requests are two chances for one to be stale.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @AuthenticationPrincipal User caller,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok(notifications.list(caller == null ? null : caller.getId(), page, size));
    }

    /**
     * The badge number on its own.
     *
     * <p>A separate route so the 30-second poll can be a cheap count, and so a frontend that only wants a
     * badge does not download thirty summaries to get it.
     */
    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Object>> unreadCount(@AuthenticationPrincipal User caller) {
        long count = notifications.unreadCount(caller == null ? null : caller.getId());
        return ResponseEntity.ok(Map.of("unreadCount", count));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Map<String, Object>> markRead(
            @AuthenticationPrincipal User caller,
            @PathVariable Long id) {
        if (caller == null) {
            return ResponseEntity.status(401).build();
        }
        notifications.markRead(caller.getId(), id);
        return ResponseEntity.ok(Map.of(
                "id", id,
                "unreadCount", notifications.unreadCount(caller.getId())));
    }

    /** Clears the badge. Returns how many were cleared, so the UI can say "3 read" rather than nothing. */
    @PostMapping("/read-all")
    public ResponseEntity<Map<String, Object>> markAllRead(@AuthenticationPrincipal User caller) {
        if (caller == null) {
            return ResponseEntity.status(401).build();
        }
        long cleared = notifications.markAllRead(caller.getId());
        return ResponseEntity.ok(Map.of("cleared", cleared, "unreadCount", 0L));
    }
}