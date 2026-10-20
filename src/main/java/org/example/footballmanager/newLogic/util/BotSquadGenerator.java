package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Random;

/**
 * Squads for countries that are not played (owner, 2026-09-29).
 *
 * <p>A SIMULATED country has no clubs, and a national side draws its players from its clubs, so it
 * used to end up as a row with a name and no players - which cannot be drawn against. This fills it,
 * so the national-team half of the game works across the whole map from day one rather than waiting
 * for 48 club pyramids to exist.
 *
 * <p><b>Skill 12 average</b>, the owner's number. A representative side should be respectable without
 * being anyone's equal, and a fixed number beats a random one because a simulated country would
 * otherwise produce a different standard of football on every fresh install.
 *
 * <p><b>Deterministic per country.</b> The random is seeded from the ISO code, so Croatia's squad is the
 * same squad on every machine and every reset. A country that mysteriously re-rolls its players between
 * installs is impossible to debug and impossible to reason about.
 */
@Component
public class BotSquadGenerator {

    private static final Logger log = LoggerFactory.getLogger(BotSquadGenerator.class);

    /** The owner's number. */
    private static final int BASE_SKILL = 12;

    /** 25, matching the squad size everywhere else. */
    private static final int SQUAD_SIZE = 25;

    /** A keeper and two of everything else, roughly. */
    private static final int KEEPERS = 3;

    /**
     * Squad shape: 3 GK, 8 DEF, 7 MID, 4 ATT, 3 WNG = 25.
     *
     * <p>Spelled out rather than left to a random draw, because a representative national side that
     * rolled 11 wingers would be unplayable and a manager would be told the world is broken.
     */
    private static final int DEFENDERS = 8;
    /** The initials {@link #name} draws from. Shared with {@link #isGenerated} so the two cannot drift. */
    private static final List<String> NAME_PREFIXES = List.of("A.", "D.", "I.", "L.", "M.", "N.", "S.", "V.");

    /**
     * The exact shape {@link #name} produces - {@code A. SRB-GK02} - built from the same prefix list
     * and the same {@link Position} values, so it tracks the generator instead of restating it.
     *
     * <p>This is how a national side that was filled before its clubs existed gets recognised later,
     * and replaced with real players once they do. See {@code NationalTeamSeeder.squadsFor}.
     */
    private static final Pattern GENERATED_NAME = Pattern.compile(
            "^(" + String.join("|", NAME_PREFIXES.stream().map(Pattern::quote).toList())
                    + ") [A-Z0-9]{3}-("   // the generator does not validate the country code, so neither does this
                    + Arrays.stream(Position.values()).map(p -> Pattern.quote(p.name()))
                            .collect(Collectors.joining("|"))
                    + ")\\d{2}$");

    /**
     * Whether this generator made this player.
     *
     * <p>There is no flag on the row, so the name is the record - which is the same reasoning
     * {@code squadsFor} already used for "a squad exists". A generated player is not a real squad
     * member and must not be mistaken for one: the owner asked for active sides to field real
     * players, and a guard that cannot tell the two apart is how 2,400 simulated players ended up
     * starting for sides that had 7,730 real ones on the books.
     */
    public boolean isGenerated(Player player) {
        return player != null
                && player.getName() != null
                && GENERATED_NAME.matcher(player.getName()).matches();
    }

    private static final int MIDFIELDERS = 7;
    private static final int ATTACKERS = 4;
    private static final int WINGERS = 3;

    private final PlayerRepository players;

    public BotSquadGenerator(PlayerRepository players) {
        this.players = players;
    }

    /**
     * Fills a national side, if it has no players.
     *
     * <p>Idempotent by squad size, not by a flag: the players are the record. Called on every boot, and
     * a second call must not add a second set of 25 to a side that already has them.
     */
    public void ensureSquad(Team nationalTeam, Country country, boolean youth) {
        if (nationalTeam == null || nationalTeam.getId() == null) {
            return;
        }
        if (!players.findByTeamId(nationalTeam.getId()).isEmpty()) {
            return;
        }
        int made = populate(nationalTeam, country, youth);
        log.info("Bot squad for {}: {} players at average skill {}.",
                nationalTeam.getName(), made, BASE_SKILL);
    }

    private int populate(Team nationalTeam, Country country, boolean youth) {
        // Seeded from the country and the level, so senior and U-21 are different squads and both are
        // stable across installs.
        long seed = country.getId() * 31 + (youth ? 7 : 1);
        Random random = new Random(seed);

        int made = 0;
        for (int index = 0; index < SQUAD_SIZE; index++) {
            Position position = positionFor(index);
            Player player = new Player();
            player.setName(name(country, position, index, random));
            player.setAge(ageFor(position, random, youth));
            player.setPosition(position);
            player.setForm(6.0);
            player.setPlayerValue(0.0);
            player.setNationality(country.getIsoCode());
            player.setTeam(nationalTeam);
            player.setSkills(skillsFor(position, random));
            // After the skills, not before: the rating is derived from them. This was
            // BASE_SKILL * 8, which made every bot in the world read exactly 96 and handed each of
            // them a free +6 of OVR over a human of identical ability.
            player.setRating(player.careerRating());
            players.save(player);
            made++;
        }
        return made;
    }

    /** The shape above, walked in order, so every bot side is the same shape. */
    private Position positionFor(int index) {
        if (index < KEEPERS) {
            return Position.GK;
        }
        if (index < KEEPERS + DEFENDERS) {
            return Position.DEF;
        }
        if (index < KEEPERS + DEFENDERS + MIDFIELDERS) {
            return Position.MID;
        }
        if (index < KEEPERS + DEFENDERS + MIDFIELDERS + ATTACKERS) {
            return Position.ATT;
        }
        return Position.WNG;
    }

    /**
     * Every skill at 12, with a little spread so players are not identical.
     *
     * <p>Spread is small on purpose: a bot national side should be a solid standard, not a collection
     * of one world-class player. A 1-3 band around 12 keeps the side honest while still making one
     * player slightly better than another, which is what a real squad looks like.
     */
    private Skills skillsFor(Position position, Random random) {
        Skills skills = new Skills();
        for (SkillName skill : SkillName.values()) {
            skills.setExact(skill, BASE_SKILL + random.nextInt(3) - 1);
        }
        // A keeper is a keeper: the one attribute that should not be average.
        if (position == Position.GK) {
            skills.setExact(SkillName.GOALKEEPER, BASE_SKILL + 2 + random.nextInt(3));
        }
        return skills;
    }

    /**
     * No squad role is set, deliberately.
     *
     * <p>{@code SquadRole} lives on {@code PlayerContract} because it is a contract concept - it
     * decides wage expectation and reluctance to sell. A national-team player has no contract, so
     * there is nothing to attach it to, and inventing one would put club-contract fields on a player
     * who is not at a club. The playing shape above is what the squad actually needs.
     */

    private int ageFor(Position position, Random random, boolean youth) {
        if (youth) {
            return 17 + random.nextInt(4);
        }
        return switch (position) {
            case GK -> 26 + random.nextInt(9);
            case DEF -> 24 + random.nextInt(10);
            case MID -> 22 + random.nextInt(11);
            case ATT -> 21 + random.nextInt(11);
            // Wingers peak a little earlier than the rest of the attack.
            case WNG -> 20 + random.nextInt(10);
        };
    }

    /**
     * A name, built from the country's own code so it reads as belonging to that country.
     *
     * <p>Deliberately not a real name pool. Real club and player names carry licensing exposure, and a
     * per-country pool is needed anyway - one shared Faker pool gives 48 countries that all sound
     * identical. A code-based surname is honest about being generated, and the football still works.
     */
    private String name(Country country, Position position, int index, Random random) {
        String prefix = NAME_PREFIXES.get(random.nextInt(NAME_PREFIXES.size()));
        String code = country.getIsoCode() == null ? "XXX" : country.getIsoCode();
        return prefix + " " + code + "-" + position.name() + String.format("%02d", index + 1);
    }
}
