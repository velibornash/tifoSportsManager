package org.example.footballmanager.newLogic.controller;

import lombok.RequiredArgsConstructor;
import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.service.MessageService;
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
import java.util.Map;

/**
 * Private messages (owner, 2026-10-05).
 *
 * <p>Separate from {@code CommunityController}, whose {@code /community/chat} route this replaces: that
 * one wrote a write on a GET, computed its unread state from a single timestamp, and had no thread at all.
 *
 * <p><b>Every route resolves the viewer from the token.</b> The one id in a path is a thread's, and
 * {@code MessageService} checks membership inside itself rather than trusting a route to have done it —
 * a thread id in a URL is a thing a caller can change.
 */
@RestController
@RequestMapping("/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messages;

    /** Every conversation the caller is in, newest activity first. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> inbox(
            @AuthenticationPrincipal User viewer,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok(messages.inbox(viewer, page, size));
    }

    /**
     * Who can be written to.
     *
     * <p>Every account that exists — <b>not</b> only the online ones. "Active" in the owner's sense is "has
     * a live login", and this application cannot answer "is he at the keyboard" correctly: a JWT stays
     * valid for 24 hours after a browser closes, and {@code PresenceRegistry}'s five-minute window is
     * stated on one page only. A picker of currently-online managers would make a message undeliverable
     * to somebody asleep, which is exactly who you want to write to.
     */
    @GetMapping("/recipients")
    public ResponseEntity<List<Map<String, Object>>> recipients(@AuthenticationPrincipal User viewer) {
        return ResponseEntity.ok(messages.activeRecipients(viewer));
    }

    /**
     * Sends a message, or replies in an existing thread.
     *
     * <p>One route for both. {@code threadId} in the body continues a conversation; {@code recipientId}
     * without one opens or continues the conversation with that manager. The alternative — two routes
     * with the client choosing — puts the decision on the client, where picking wrong either forks a
     * thread or writes a reply into nothing.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> send(
            @AuthenticationPrincipal User sender,
            @RequestBody(required = false) Map<String, Object> body) {
        Long threadId = longOrNull(body == null ? null : body.get("threadId"));
        Long recipientId = longOrNull(body == null ? null : body.get("recipientUserId"));
        String subject = body == null ? null : asString(body.get("subject"));
        String text = body == null ? null : asString(body.get("body"));

        var message = messages.send(sender, threadId, recipientId, subject, text);
        return ResponseEntity.ok(Map.of(
                "id", message.getId(),
                "threadId", message.getThread() == null ? null : message.getThread().getId()));
    }

    /** One conversation with its messages. Marks this side as having read it. */
    @GetMapping("/threads/{threadId}")
    public ResponseEntity<Map<String, Object>> thread(
            @AuthenticationPrincipal User viewer,
            @PathVariable Long threadId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok(messages.viewThread(viewer, threadId, page, size));
    }

    private static Long longOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("That is not an id.");
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}