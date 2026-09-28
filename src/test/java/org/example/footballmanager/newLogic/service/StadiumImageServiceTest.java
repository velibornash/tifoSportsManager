package org.example.footballmanager.newLogic.service;

import org.example.config.UploadResourceConfig;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stadium picture uploads (owner, 2026-09-28).
 *
 * <p>Everything here is about the two ways this feature can go quietly wrong. The first is
 * <b>where the file lands</b>: everything under {@code src/main/resources/static} is served from the
 * classpath, and the classpath copy is made at build time, so writing an upload into that source
 * directory stores the file successfully and then 404s it until the next rebuild. The second is
 * <b>what the manager's own filename can do</b> — it is a path-traversal vector, and it is reflected
 * back into markup.
 */
class StadiumImageServiceTest {

    @TempDir
    Path tempDir;

    private TeamRepository teams;
    private StadiumImageService service;

    private void setUp() {
        teams = mock(TeamRepository.class);
        when(teams.findById(any())).thenReturn(java.util.Optional.of(new Team()));
        when(teams.save(any())).thenAnswer(i -> i.getArgument(0));
        service = new StadiumImageService(teams, new UploadResourceConfig(tempDir.toString()));
    }

    @Test
    @DisplayName("a club with no artwork gets a real ground, not a grey box")
    void missingArtworkFallsBackToAPhotograph() {
        setUp();
        Stadium stadium = new Stadium();

        assertEquals(StadiumImageService.DEFAULT_STADIUM_IMAGE, service.imageFor(stadium));
        assertEquals("/images/dunjareal.png", StadiumImageService.DEFAULT_STADIUM_IMAGE,
                "the fallback is a real photograph of a real ground");
        assertEquals(StadiumImageService.DEFAULT_STADIUM_IMAGE, service.imageFor(null),
                "a club with no stadium row at all must not blow up the view");
    }

    @Test
    @DisplayName("a stadium's own picture wins over the fallback")
    void ownArtworkWins() {
        setUp();
        Stadium stadium = new Stadium();
        stadium.setImage("/images/livadice.png");

        assertEquals("/images/livadice.png", service.imageFor(stadium));
    }

    @Test
    @DisplayName("an upload lands outside the classpath, where it can actually be served")
    void uploadIsWrittenOutsideTheClasspath() {
        setUp();
        MockMultipartFile file = new MockMultipartFile(
                "file", "ground.png", "image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});

        String url = service.storeForTeam(1L, file);

        assertTrue(url.startsWith("/uploads/stadiums/"), "unexpected url: " + url);
        Path written = tempDir.resolve("stadiums").resolve(url.substring("/uploads/stadiums/".length()));
        assertTrue(Files.exists(written),
                "the file must exist on disk at " + written + " - an upload written into "
                        + "src/main/resources/static is stored and then never served until a rebuild");
    }

    @Test
    @DisplayName("the manager's filename is never used")
    void uploadedFilenameIsNotTrusted() throws Exception {
        setUp();
        MockMultipartFile file = new MockMultipartFile("file", "../../application.properties.png",
                "image/png", new byte[]{1, 2, 3});

        String url = service.storeForTeam(7L, file);

        assertTrue(url.startsWith("/uploads/stadiums/team-7-"), "unexpected url: " + url);
        assertTrue(url.endsWith(".png"), "the extension comes from the content type: " + url);
        // Nothing outside the upload directory, whatever the file was called.
        assertTrue(Files.list(tempDir.resolve("stadiums")).count() == 1,
                "exactly one file should have been written");
    }

    @Test
    @DisplayName("a non-image is refused with a message, not stored")
    void nonImageIsRefused() {
        setUp();
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.storeForTeam(1L, file));
        assertTrue(e.getMessage().toLowerCase().contains("image"), e.getMessage());
    }

    @Test
    @DisplayName("SVG is refused - it is a document that can carry script")
    void svgIsRefused() {
        setUp();
        MockMultipartFile file = new MockMultipartFile("file", "logo.svg", "image/svg+xml",
                "<svg onload='alert(1)'/>".getBytes());

        assertThrows(IllegalArgumentException.class, () -> service.storeForTeam(1L, file),
                "an allow-list must exclude svg, or a manager can store script served from this origin");
    }

    @Test
    @DisplayName("an empty upload is refused rather than storing a zero-byte file")
    void emptyUploadIsRefused() {
        setUp();
        MockMultipartFile file = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

        assertThrows(IllegalArgumentException.class, () -> service.storeForTeam(1L, file));
    }

    @Test
    @DisplayName("a club with no stadium row gets one, so the manager is not told to go create a stadium")
    void clubWithoutStadiumRowGetsOne() {
        setUp();
        MockMultipartFile file = new MockMultipartFile(
                "file", "g.png", "image/png", new byte[]{1});

        String url = service.storeForTeam(3L, file);

        assertTrue(url.contains("team-3-"), url);
        Team saved = teams.findById(3L).orElseThrow();
        assertNull(saved.getStadium().getName(), "the stadium row is created, not fully populated");
    }
}
