package org.example.footballmanager.newLogic.service;

import org.example.config.UploadResourceConfig;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Manager-uploaded stadium pictures (owner, 2026-09-28).
 *
 * <p>Replaces two things that were both wrong in the same way. The stadium page read
 * {@code s.image} from a payload that never had an {@code image} key, so it had never once shown a
 * real ground. The fixture view guessed a picture from the stadium's <i>name</i> by substring, in a
 * function nothing called, so three real ground images were unreachable. Both inferred a fact from a
 * name when a column could simply hold it.
 */
@Service
public class StadiumImageService {

    /**
     * A real photograph of a real ground, used when a club has not uploaded one.
     *
     * <p>Deliberately a photograph rather than the generated placeholder used for a missing club
     * crest. A stadium with no artwork should still look like a football ground; a grey gradient reads
     * as a broken image and, like the broken-image glyph it replaced, looks like a bug rather than an
     * absence.
     */
    public static final String DEFAULT_STADIUM_IMAGE = "/images/dunjareal.png";

    private static final long MAX_BYTES = 4L * 1024 * 1024;

    /**
     * Formats actually allowed.
     *
     * <p>An allow-list, never a deny-list. The file is served back to every browser that views the
     * stadium, so a permissive content type here would let a manager store something that executes in
     * the context of this origin. {@code image/svg+xml} is excluded for the same reason: SVG is a
     * document that can carry script.
     */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif");

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "gif");

    private final TeamRepository teams;
    private final Path uploadRoot;

    public StadiumImageService(TeamRepository teams, UploadResourceConfig config) {
        this.teams = teams;
        this.uploadRoot = config.getUploadRoot().resolve("stadiums");
    }

    /** The picture to show for a stadium: its own if it has one, otherwise the fallback. */
    public String imageFor(Stadium stadium) {
        if (stadium == null) {
            return DEFAULT_STADIUM_IMAGE;
        }
        String own = stadium.getImage();
        if (own == null || own.isBlank()) {
            return DEFAULT_STADIUM_IMAGE;
        }
        return own;
    }

    /**
     * Stores {@code file} as the picture for {@code team}'s stadium and returns the URL to show.
     *
     * <p>The stored name is generated, never the uploaded one. A manager-supplied filename is a
     * path-traversal vector ({@code ../../application.properties}) and a second injection point if it
     * is ever reflected back into markup, so the extension is taken from the file's own content type
     * and the name is a UUID.
     *
     * @throws IllegalArgumentException when the upload is empty, too large, or not an image
     */
    public String storeForTeam(Long teamId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Choose an image to upload.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("That image is larger than 4 MB.");
        }
        String contentType = file.getContentType() == null
                ? ""
                : file.getContentType().toLowerCase(Locale.ROOT).trim();
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Stadium pictures must be a PNG, JPEG, WEBP or GIF image.");
        }

        Team team = teams.findById(teamId).orElseThrow(() -> new IllegalArgumentException("Club not found."));
        Stadium stadium = team.getStadium();
        if (stadium == null) {
            // A club with no ground row yet still deserves one picture rather than an error: the
            // manager pressed "change picture", not "create a stadium".
            stadium = new Stadium();
            stadium.setTeam(team);
            team.setStadium(stadium);
        }

        String extension = extensionFor(contentType);
        String stored = "team-" + teamId + "-" + UUID.randomUUID() + "." + extension;
        try {
            Files.createDirectories(uploadRoot);
            Path target = uploadRoot.resolve(stored).normalize();
            // Belt and braces: the name is generated, but never write outside the upload root.
            if (!target.startsWith(uploadRoot)) {
                throw new IllegalArgumentException("That file name cannot be stored.");
            }
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("The image could not be saved: " + e.getMessage(), e);
        }

        String url = "/uploads/stadiums/" + stored;
        stadium.setImage(url);
        teams.save(team);
        return url;
    }

    private String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/jpeg", "image/jpg" -> "jpg";
            default -> throw new IllegalArgumentException("Unsupported image type.");
        };
    }
}
