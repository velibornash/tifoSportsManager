package org.example.footballmanager.newLogic.controller;

import lombok.RequiredArgsConstructor;
import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.model.ForumSection;
import org.example.footballmanager.newLogic.service.ForumService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The forum (owner, 2026-10-05).
 *
 * <p><b>This controller holds no authorization logic on purpose.</b> Reads are open to any manager — a
 * community forum you cannot read without a role is a staff board — and every write decision, including
 * the ban, is in {@link ForumService}. A ban check here would hold for exactly these five routes and stop
 * holding the moment a sixth writer appeared.
 *
 * <p>The ids in the paths are forum objects, not people, and the actor always comes from the token. So
 * "may this actor touch this post" is answered by the service from the post's own author, and a caller
 * cannot supply an answer to it.
 */
@RestController
@RequestMapping("/forum")
@RequiredArgsConstructor
public class ForumController {

    private final ForumService forum;

    /** The two sections with their topic counts, for the forum index. */
    @GetMapping("/sections")
    public ResponseEntity<Map<String, Object>> sections() {
        return ResponseEntity.ok(forum.listTopics(null, 0, 0));
    }

    /** One section's topics, most recently active first. */
    @GetMapping("/topics")
    public ResponseEntity<Map<String, Object>> topics(
            @RequestParam(required = false) String section,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok(forum.listTopics(parseSection(section), page, size));
    }

    /** One topic with a page of its posts, oldest first. */
    @GetMapping("/topics/{topicId}")
    public ResponseEntity<Map<String, Object>> topic(
            @AuthenticationPrincipal User viewer,
            @PathVariable Long topicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return ResponseEntity.ok(forum.viewTopic(viewer, topicId, page, size));
    }

    /**
     * Opens a topic with its first post.
     *
     * <p>One route, not two: a topic created without a post is a title in a list that goes nowhere, and
     * splitting it would need a transaction that spans two HTTP calls.
     */
    @PostMapping("/topics")
    public ResponseEntity<Map<String, Object>> createTopic(
            @AuthenticationPrincipal User author,
            @RequestBody(required = false) Map<String, String> body) {
        String section = body == null ? null : body.get("section");
        String title = body == null ? null : body.get("title");
        String text = body == null ? null : body.get("body");
        var topic = forum.createTopic(author, parseSection(section), title, text);
        return ResponseEntity.ok(Map.of("id", topic.getId(), "title", topic.getTitle()));
    }

    /** Adds a reply. Refused with the reason when the author is forum-banned. */
    @PostMapping("/topics/{topicId}/posts")
    public ResponseEntity<Map<String, Object>> reply(
            @AuthenticationPrincipal User author,
            @PathVariable Long topicId,
            @RequestBody(required = false) Map<String, String> body) {
        var post = forum.createPost(author, topicId, body == null ? null : body.get("body"));
        return ResponseEntity.ok(Map.of("id", post.getId()));
    }

    /**
     * Edits a post.
     *
     * <p>PUT would be the tidy verb for a full replacement, and PATCH for a partial one. This replaces the
     * body wholesale — there is no partial edit of a forum post — so PUT is the honest choice. It is also
     * new on this application, which has three PUTs and no PATCH outside tactic templates, so it is worth
     * the sentence.
     */
    @PutMapping("/posts/{postId}")
    public ResponseEntity<Map<String, Object>> edit(
            @AuthenticationPrincipal User actor,
            @PathVariable Long postId,
            @RequestBody(required = false) Map<String, String> body) {
        forum.editPost(actor, postId, body == null ? null : body.get("body"));
        return ResponseEntity.ok(Map.of("id", postId, "edited", true));
    }

    @DeleteMapping("/posts/{postId}")
    public ResponseEntity<Map<String, Object>> delete(
            @AuthenticationPrincipal User actor,
            @PathVariable Long postId) {
        forum.deletePost(actor, postId);
        return ResponseEntity.ok(Map.of("id", postId, "deleted", true));
    }

    /**
     * A section name from the query string, or null for "all".
     *
     * <p>An unrecognised name falls back to {@code null} rather than throwing: the parameter is a UI
     * convenience and a mistyped filter should show every topic, not a 400.
     */
    private static ForumSection parseSection(String raw) {
        if (raw == null || raw.isBlank() || "all".equalsIgnoreCase(raw.trim())) {
            return null;
        }
        try {
            return ForumSection.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}