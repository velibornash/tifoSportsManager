package org.example.footballmanager.newLogic.util;

import org.example.footballmanager.newLogic.model.CompetitionTeamType;
import org.example.footballmanager.newLogic.model.Country;
import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * National teams, so the feature can be looked at (owner, 2026-09-28).
 *
 * <p>{@code Country.seniorNationalTeam} and {@code Country.u21NationalTeam} have existed as entity
 * references and as database columns since the country page was written, and they have been
 * <b>null for every country since</b> — which is why that panel rendered a hand-written name and a
 * button that went to a placeholder.
 *
 * <p>Creates the two teams per country and gives each a squad drawn from that country's clubs, so the
 * national-team screens have something real in them.
 *
 * <p><b>Provisional on the selector</b>, and stated plainly: the country's manager acts as selector
 * until the elections exist. Elections are a Sprint 5 item, and building a selector table now means
 * a second migration and a second concept to delete later. A manager running his own country's squad
 * is honest, is immediately testable, and is what elections will replace.
 */
@Component
public class NationalTeamSeeder {

    private static final Logger log = LoggerFactory.getLogger(NationalTeamSeeder.class);

    /** Squad size. The owner specified 25 in the squad, plus an observe list around it. */
    private static final int SQUAD_SIZE = 25;

    private final TeamRepository teams;
    private final PlayerRepository players;
    private final BotSquadGenerator botSquads;
    private final Random random;

    /**
     * Marked explicitly: this class has two constructors, and Spring refuses to guess between them.
     *
     * <p>The second, seedable {@link Random} constructor exists so the test can be deterministic —
     * and that is precisely why a unit test passing tells you nothing here. This is the same mistake
     * made with {@code TransferActivitySeeder} a few hours earlier and written down at the time. The
     * lesson did not transfer, so it is written down again next to the mistake rather than in a
     * progress file nobody reads before writing the next seeder.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public NationalTeamSeeder(TeamRepository teams, PlayerRepository players,
                             BotSquadGenerator botSquads) {
        this(teams, players, botSquads, new Random());
    }

    NationalTeamSeeder(TeamRepository teams, PlayerRepository players,
                      BotSquadGenerator botSquads, Random random) {
        this.teams = teams;
        this.players = players;
        this.botSquads = botSquads;
        this.random = random;
    }

    @Transactional
    /**
     * How many national sides exist, for reporting after a repair.
     *
     * <p>Deliberately counts rows rather than returning what the last seed created, because a repair
     * that finds nothing to do is the common case and reporting "0 sides created" reads like a
     * failure when the world is in fact complete.
     */
    public int totalSides() {
        return (int) teams.findByType(
                org.example.footballmanager.newLogic.model.CompetitionTeamType.NATIONAL_TEAM).size();
    }

    public void seedIfMissing(List<Country> countries) {
        int made = 0;
        for (Country country : countries) {
            if (country == null || country.getId() == null) {
                continue;
            }
            if (ensureSenior(country)) {
                made++;
            }
            if (ensureU21(country)) {
                made++;
            }
        }
        if (made > 0) {
            log.info("Created {} national teams across {} countries (Sprint 5 scaffolding).", made,
                    countries.size());
        }
    }

    private boolean ensureSenior(Country country) {
        if (country.getSeniorNationalTeam() != null) {
            // The team exists but may be EMPTY. Five countries - BIH, BRA, MKD, MNE, SVN - had their
            // sides created before there was a bot squad, by a seeder that only ever filled squads
            // from clubs, and they have no clubs. Returning here made those empty sides permanent:
            // the squad logic below was never reached for them, and the internationals still had
            // nothing to draw against. An existing side is topped up, not skipped.
            squadsFor(country.getSeniorNationalTeam(), country, false);
            renameSenior(country);
            return false;
        }
        Team nt = new Team();
        nt.setName(seniorName(country));
        nt.setType(CompetitionTeamType.NATIONAL_TEAM);
        nt.setCountry(country);
        // No club, no competition, no budget: a national team is not a club and must not be given a
        // wage bill or a transfer value. The nulls are the correct values here, not omissions.
        Team saved = teams.save(nt);
        country.setSeniorNationalTeam(saved);
        squadsFor(saved, country, false);
        return true;
    }

    /** The old suffix on a senior side, from before the owner asked for the plain country name. */
    private static final String SENIOR_SUFFIX = " National Team";

    /**
     * A senior side is called after its country and nothing else (owner, 2026-10-06).
     *
     * <p>"Germany National Team" said the same thing twice: the manager is on Germany's page, in the
     * national-team section, and the tab is already labelled. The U-21 side keeps its suffix, because
     * "Germany" alone would be ambiguous there - two German teams, one name.
     */
    private String seniorName(Country country) {
        return country.getName();
    }

    /**
     * Brings an existing senior side onto the current naming, and returns whether it changed.
     *
     * <p>Only strips the old suffix from a name built as the country name plus that suffix, so a side
     * somebody renamed by hand is left alone. Safe to repeat, which is what a re-seed needs to be.
     */
    private boolean renameSenior(Country country) {
        Team senior = country.getSeniorNationalTeam();
        String wanted = seniorName(country);
        if (wanted.equals(senior.getName())) {
            return false;
        }
        if (!(country.getName() + SENIOR_SUFFIX).equals(senior.getName())) {
            return false;
        }
        log.info("{}: senior side renamed '{}' -> '{}'.", country.getName(), senior.getName(), wanted);
        senior.setName(wanted);
        teams.save(senior);
        return true;
    }

    private boolean ensureU21(Country country) {
        if (country.getU21NationalTeam() != null) {
            squadsFor(country.getU21NationalTeam(), country, true);
            return false;
        }
        Team nt = new Team();
        nt.setName(country.getName() + " U-21");
        nt.setType(CompetitionTeamType.NATIONAL_TEAM);
        nt.setCountry(country);
        Team saved = teams.save(nt);
        country.setU21NationalTeam(saved);
        squadsFor(saved, country, true);
        return true;
    }

    /**
     * Copies the country's best players into the national squad.
     *
     * <p><b>Copies, not references.</b> A national team does not own its players — they play for their
     * clubs, and moving a row between teams would take them out of the league. So this creates new
     * player rows carrying the same name, age and attributes. The consequence is honest and worth
     * knowing: a form or an injury on the club player does not automatically appear on the national
     * one, because they are separate rows. That is the T1 problem, not something to pretend away here.
     */
    /**
     * A country's clubs, read once and memoised.
     *
     * <p>The senior and the U21 side are drawn from the same clubs, so scanning the club table twice per
     * country bought nothing. Memoised per country id for the life of this seeder's run.
     *
     * <p><b>And it is a country's clubs, not every club in the world filtered afterwards.</b> The scan was
     * {@code findClubTeamsForOperations()} — the whole club table — narrowed in Java by country id, so
     * drawing the world's squads materialised 14,880 clubs to select the 310 of one country. The
     * repository now asks for the country's clubs directly, which is an indexed lookup on
     * {@code team.country_id} rather than a full-table scan per country.
     */
    private java.util.Map<Long, List<Team>> clubsByCountry = new java.util.HashMap<>();

    private List<Team> clubsIn(Country country) {
        if (country == null || country.getId() == null) {
            return List.of();
        }
        return clubsByCountry.computeIfAbsent(country.getId(), teams::findClubTeamsForCountry);
    }

    /**
     * Package-private so the idempotence test can drive it directly.
     *
     * <p>It is called from five call sites inside this class and is the thing the board's B5 finding is about,
     * so it is the unit under test rather than something reached only through a whole seeding pass.
     */
    void squadsFor(Team nationalTeam, Country country, boolean youth) {
        if (nationalTeam == null || nationalTeam.getId() == null) {
            return;
        }
        // **Idempotent by squad size, not by a flag: the players are the record.**
        //
        // This had no guard at all, and it runs on both seeding branches and from five call sites, so every
        // pass added another squad of up to 25 players to a side that already had one - up to 2,400 duplicate
        // player rows per pass. BotSquadGenerator.ensureSquad has had exactly this check the whole time, and
        // its comment says why: called on every boot, and a second call must not add a second set.
        if (!players.findByTeamId(nationalTeam.getId()).isEmpty()) {
            log.debug("{} already has players; not drawing a second squad.", nationalTeam.getName());
            return;
        }

        List<Player> eligible = new ArrayList<>();
        // **One club read per country, not one per squad and not one whole-table scan.** clubsIn reads
        // this country's clubs by query and memoises them: the senior side and the U21 side are drawn
        // from the same clubs, and forty-eight countries are drawn in one pass, so a per-country scan
        // of every club in the world is a scan of the world forty-eight times over.
        for (Team club : clubsIn(country)) {
            eligible.addAll(players.findByTeamId(club.getId()));
        }
        if (eligible.isEmpty()) {
            // A country with no clubs used to end up as a national side with a name and no players,
            // which cannot be drawn against - that is why the internationals drew nothing. A bot squad
            // makes every country in the map playable immediately, at no cost to a country that is
            // never activated.
            botSquads.ensureSquad(nationalTeam, country, youth);
            return;
        }

        // Sorted on rating, the stored per-player attribute. `PlayerDTO.calculateOverall` is the
        // composite the UI shows, but it is private to the DTO and is a *display* number - it weighs
        // by position, so a keeper and a striker with the same rating are not equivalent. Selecting a
        // squad on the display number would quietly favour whichever position the formula favours.
        eligible.sort(Comparator.comparingInt((Player p) -> p.getRating()).reversed());
        List<Player> squad = eligible.subList(0, Math.min(SQUAD_SIZE, eligible.size()));

        for (Player source : squad) {
            Player copy = new Player();
            copy.setName(source.getName());
            copy.setAge(source.getAge());
            copy.setPosition(source.getPosition());
            copy.setRating(source.getRating());
            copy.setForm(source.getForm());
            copy.setPlayerValue(0.0);
            copy.setNationality(country.getIsoCode());
            copy.setTeam(nationalTeam);
            if (copy.getSkills() == null) {
                copy.setSkills(source.getSkills());
            }
            players.save(copy);
        }
        log.debug("{} squad of {} drawn from {} eligible players.",
                nationalTeam.getName(), squad.size(), eligible.size());
    }
}
