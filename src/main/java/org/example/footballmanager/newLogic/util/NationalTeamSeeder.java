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

    /**
     * Brings every existing senior side onto the current naming, and reports how many moved.
     *
     * <p>A standalone entry point because the rename was otherwise reachable only from
     * {@link #seedIfMissing}, and that made it depend on somebody pressing Re-seed. It is not: a world
     * built before the rename keeps the old names until this is called, and the owner found exactly
     * that - the code was right, compiled, and the database still said "Germany National Team" for all
     * forty-eight sides because nothing had run the path that fixes it.
     *
     * <p>Safe to call on a healthy world: it changes a name only where the name is exactly the country
     * name plus the old suffix, so a side renamed by hand is left alone and a second call does nothing.
     *
     * @return how many sides were renamed
     */
    @Transactional
    public int renameSeniorSides(List<Country> countries) {
        int renamed = 0;
        for (Country country : countries) {
            if (country != null && country.getSeniorNationalTeam() != null && renameSenior(country)) {
                renamed++;
            }
        }
        if (renamed > 0) {
            log.info("Renamed {} senior side(s) onto the country name.", renamed);
        }
        return renamed;
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
        List<Team> memoised = clubsByCountry.get(country.getId());
        if (memoised != null) {
            return memoised;
        }
        List<Team> found = teams.findClubTeamsForCountry(country.getId());
        if (found.isEmpty()) {
            // **An empty result is deliberately NOT memoised.** This seeder is a singleton, so a
            // cached empty list outlives the pyramid that was going to fill it: `seedWorldBeforePyramid`
            // seeds all forty-eight national sides before any club exists, so every country caches
            // "no clubs" and never looks again. Repair world would report success and replace nothing
            // - a green panel over an unchanged world, which is the one thing that must not happen.
            //
            // The cost is one indexed lookup on `team.country_id` per call, and only for the countries
            // that have no clubs. Every country that has them stays memoised, so the forty-eight pass
            // this memoisation was written for is unchanged.
            return found;
        }
        clubsByCountry.put(country.getId(), found);
        return found;
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
        List<Player> existing = players.findByTeamId(nationalTeam.getId());

        List<Player> eligible = new ArrayList<>();
        // **One club read per country, not one per squad and not one whole-table scan.** clubsIn reads
        // this country's clubs by query and memoises them: the senior side and the U21 side are drawn
        // from the same clubs, and forty-eight countries are drawn in one pass, so a per-country scan
        // of every club in the world is a scan of the world forty-eight times over.
        for (Team club : clubsIn(country)) {
            eligible.addAll(players.findByTeamId(club.getId()));
        }

        // **A generated squad is not a squad.** The owner's report: *"zasto su u u-21 i prvom timu
        // u 25 lazni igraci (verovatno nastali tokom init db) umesto stvarnih (koji se nalaze u
        // poolu ispod)? AKTIVNA liga MORA imati STVARNE igrace a ne simulirane!!!"*
        //
        // This is what produced it: `seedWorldBeforePyramid` seeds the national sides before the
        // pyramid exists, so `eligible` was empty and the fallback below filled the side with 25
        // generated players. The pyramid then created thousands of real players, and the old guard -
        // "a squad exists, so do not draw another" - made the simulated ones permanent. Serbia ended
        // up fielding `N. SRB-GK01` while Zoran Zivadinovic sat in the pool at 94.
        //
        // So the guard has to distinguish a squad that exists from a squad that is real. Anything
        // BotSquadGenerator made is dropped, and real players take its place. Deliberately NOT done
        // when `eligible` is empty: for a country with no clubs a generated side is the only thing
        // that can field an XI, and deleting it would leave nothing to play with.
        List<Player> generated = existing.stream().filter(botSquads::isGenerated).toList();

        // A squad of real players is already here, so leave it alone. The emptiness test has to be on
        // `existing` and not on `generated`: on a first draw both are empty, and testing `generated`
        // returns before drawing anything, so the side is never filled at all.
        if (!existing.isEmpty() && generated.isEmpty()) {
            log.debug("{} already has real players; not drawing a second squad.", nationalTeam.getName());
            return;
        }

        if (eligible.isEmpty()) {
            // A country with no clubs used to end up as a national side with a name and no players,
            // which cannot be drawn against - that is why the internationals drew nothing. A bot squad
            // makes every country in the map playable immediately, at no cost to a country that is
            // never activated.
            if (existing.isEmpty()) {
                botSquads.ensureSquad(nationalTeam, country, youth);
            } else {
                // Already generated and still nothing to replace them with: keep them. Deleting a
                // side's only XI to leave it empty is strictly worse than a squad that reads as
                // generated.
                log.debug("{} has no club players to draw from; keeping its {} generated players.",
                        nationalTeam.getName(), generated.size());
            }
            return;
        }

        if (!generated.isEmpty()) {
            log.info("{}: replacing {} generated players with real ones ({} eligible in the country).",
                    nationalTeam.getName(), generated.size(), eligible.size());
            players.deleteAll(generated);
        }

        // Anyone already called up survives - a selector may have picked a real player into a side
        // that was still generated - so the squad is topped up to size rather than rebuilt, and the
        // players behind those calls are not drawn a second time.
        java.util.Set<Long> calledUp = existing.stream()
                .filter(p -> !botSquads.isGenerated(p))
                .map(Player::getSourcePlayerId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        int room = SQUAD_SIZE - (existing.size() - generated.size());

        // Sorted on rating, the stored per-player attribute. `PlayerDTO.calculateOverall` is the
        // composite the UI shows, but it is private to the DTO and is a *display* number - it weighs
        // by position, so a keeper and a striker with the same rating are not equivalent. Selecting a
        // squad on the display number would quietly favour whichever position the formula favours.
        eligible.sort(Comparator.comparingInt((Player p) -> p.getRating()).reversed());
        List<Player> squad = eligible.stream()
                .filter(p -> !calledUp.contains(p.getId()))
                .limit(Math.max(room, 0))
                .toList();

        for (Player source : squad) {
            Player copy = new Player();
            copy.setName(source.getName());
            copy.setAge(source.getAge());
            copy.setPosition(source.getPosition());
            copy.setRating(source.getRating());
            copy.setForm(source.getForm());
            copy.setPlayerValue(0.0);
            // **The pool excludes called-up players by this id, and it was never set here.** Only
            // `NationalTeamService.addToSquad` set it, so a seeded squad member had a null and
            // appeared in the squad *and* in the pool it was drawn from - which is precisely what the
            // comment there says this column exists to prevent.
            copy.setSourcePlayerId(source.getId());
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
