package org.tifo.footballmanager.newLogic.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tifo.footballmanager.ProductionSource;
import org.tifo.footballmanager.newLogic.controller.TeamController;
import org.tifo.footballmanager.newLogic.dto.PlayerDTO;
import org.tifo.footballmanager.newLogic.dto.PublicSquadMemberDTO;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The away squad is shown <b>without skills</b>, and that is a property of the data.
 *
 * <p>Owner, 2026-10-21: <i>"svaki user bi trebalo da moze da klikne na squad za drugi tim samo ga
 * vidi bez skilova, vidi formu, rejting, apps, goals, assists"</i>. A screen that merely declines to
 * render a skill is not what he asked for: the skill is still in the response, one devtools tab away.
 * So the read behind that screen answers a type that <b>has no skill field</b>, and these guards hold
 * that in place.
 *
 * <p><b>Why a guard and not just the type.</b> The type is the easy half. The half that rots is the
 * controller: the next person to add a field to the squad read reaches for {@code PlayerDTO}, because
 * it is the thing the rest of the app uses, and the away screen silently starts sending every skill
 * again. The second test reads the source for that exact mistake.
 *
 * <p>Both tests are written so they can fail: {@code PublicSquadMemberDTO} is a record, so a skill
 * added to it appears as a component here, and the controller check names the return type it must and
 * must not use.
 */
class AwaySquadHasNoSkillsTest {

    /**
     * The skills, by the names the owning manager's squad screen shows them under.
     *
     * <p>They are the DTO's own field names plus their user-facing labels, because a leak can be
     * spelled either way: {@code pace} in a payload, "Pace" in a column.
     */
    private static final Set<String> SKILL_NAMES = Set.of(
            "overall", "talent",
            "goalkeeper", "pace", "shooting", "passing", "technique", "defending", "stamina", "playmaker",
            "goalkeeperExact", "paceExact", "shootingExact", "passingExact", "techniqueExact",
            "defendingExact", "staminaExact", "playmakerExact",
            "fatigue", "value");

    @Test
    @DisplayName("the away-squad response carries no skill field at all")
    void theResponseTypeHasNoSkillOnIt() {
        List<String> components = Arrays.stream(PublicSquadMemberDTO.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertTrue(components.containsAll(
                        List.of("name", "position", "age", "form", "rating", "appearances", "goals", "assists")),
                "the fields the owner named are on it: " + components);

        List<String> leaked = components.stream()
                .filter(SKILL_NAMES::contains)
                .toList();

        assertEquals(List.of(), leaked,
                "the away squad shows the player without skills, so no skill field may be on the type "
                        + "that reaches a stranger. Found: " + leaked);
    }

    @Test
    @DisplayName("the skill-free type is a real narrowing of PlayerDTO, not a rename of it")
    void theTypeIsNarrowerThanPlayerDto() throws IOException {
        int dtoFields = PlayerDTO.class.getDeclaredFields().length;

        assertTrue(PublicSquadMemberDTO.class.getRecordComponents().length < dtoFields,
                "the away type exists to be smaller than PlayerDTO (" + dtoFields + " fields); a type of "
                        + "the same size is PlayerDTO under another name and hides nothing");
    }

    @Test
    @DisplayName("the /squad read answers the skill-free type, and never PlayerDTO")
    void theEndpointAnswersTheSkillFreeType() throws IOException {
        String source = Files.readString(ProductionSource.file(TeamController.class));

        String handler = methodBody(source, "getPublicSquad");
        assertFalse(handler.isEmpty(),
                "the away squad is read by TeamController.getPublicSquad; the guard cannot find it, "
                        + "which means it was renamed or removed");

        assertTrue(handler.contains("List<PublicSquadMemberDTO>"),
                "the read answers PublicSquadMemberDTO - the type with no skill on it");
        assertFalse(handler.contains("PlayerDTO"),
                "the read must not answer PlayerDTO: it carries the whole skill set, and the owner "
                        + "asked for a stranger to see the player without skills");

        String annotation = "@GetMapping(\"/{teamId}/squad\")";
        assertTrue(source.contains(annotation),
                "and it is routed at " + annotation + " - moving the route is fine, leaving the type "
                        + "behind is not");
    }

    /**
     * The source of one method, from its declaration to the closing brace at the same depth.
     *
     * <p>A plain {@code substring} between two markers would do for a one-line method and nothing for
     * this one, which holds a lambda and a nested call block. This walks the braces.
     */
    private static String methodBody(String source, String methodName) {
        int start = source.indexOf(methodName + "(");
        if (start < 0) {
            return "";
        }
        int open = source.indexOf('{', start);
        if (open < 0) {
            return "";
        }
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        return source.substring(start);
    }
}
