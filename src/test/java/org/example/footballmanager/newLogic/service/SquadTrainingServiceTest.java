package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.Player;
import org.example.footballmanager.newLogic.model.PlayerRole;
import org.example.footballmanager.newLogic.model.Position;
import org.example.footballmanager.newLogic.model.SkillName;
import org.example.footballmanager.newLogic.model.Skills;
import org.example.footballmanager.newLogic.model.Stadium;
import org.example.footballmanager.newLogic.model.Team;
import org.example.footballmanager.newLogic.model.UserRole;
import org.example.footballmanager.newLogic.repository.GameClockRepository;
import org.example.footballmanager.newLogic.repository.PlayerRepository;
import org.example.footballmanager.newLogic.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every squad trains, and it is the same training (owner, 2026-09-27).
 *
 * <p>The owner's ruling was that AI clubs do nothing except that their players still improve. That
 * was written but never wired to the weekly tick, so three hundred clubs were not training at all —
 * and the version that was written had its own age curve and its own constants, meaning the same
 * player grew differently under a default week than under a manager's programme. Talent, coach and
 * minutes were ignored entirely.
 */
@SpringBootTest
@ActiveProfiles("test")
class SquadTrainingServiceTest {

    @Autowired SquadTrainingService squadTraining;
    @Autowired PlayerRepository players;
    @Autowired TeamRepository teams;
    @Autowired GameClockRepository clocks;

    @BeforeEach
    void setUp() {
        // **Create the clock rather than expect one.** Boot writes nothing (DatabaseInitializer's
        // ensureBaselineDataOnStartup has no caller), so the test database has no GameClock row and
        // `findFirst().orElseThrow()` failed in *every* method of this class before a single assertion ran.
        //
        // It also made the class look broken when it is only unseeded: the same code is **green in a full
        // run**, because some earlier class leaves a clock row behind. That is the clearest example on the
        // board of why "per-class green" and "green in a full run" are not the same measurement -- and why
        // neither is a substitute for reading the failure.
        var clock = clocks.findAll().stream()
                .findFirst()
                .orElseGet(org.example.footballmanager.newLogic.model.GameClock::new);
        clock.setCurrentSeason(1);
        clock.setCurrentWeek(1);
        clocks.save(clock);
    }

    private Team club(String name, double reputation) {
        Team t = new Team();
        t.setName(name + "-" + System.nanoTime());
        t.setBudget(5_000_000.0);
        t.setReputation(reputation);
        Stadium s = new Stadium();
        s.setName(name + " Ground");
        s.setCapacity(20_000);
        s.setTicketPrice(18.0);
        s.setPitchQuality(80.0);
        s.setPitchCondition(80);
        s.setMaintenanceRemaining(0);
        t.setStadium(s);
        return teams.save(t);
    }

    private Player aPlayer(Team at, Position position, int age, double talent) {
        Player p = new Player();
        p.setName("P" + System.nanoTime());
        p.setTeam(at);
        p.setPosition(position);
        p.setRole(TrainingPercent.primarySkillFor(position == null ? null
                : PlayerRole.defaultFor(position)) == SkillName.GOALKEEPER
                ? PlayerRole.GOALKEEPER : null);
        p.setAge(age);
        p.setTalent(talent);
        p.setPlayerValue(1_000_000);
        p.setEarnings(3_000);
        p.setForm(6.0);
        p.setMorale(60.0);
        p.setSkills(skills(10));
        return players.save(p);
    }

    private Skills skills(double value) {
        Skills s = new Skills();
        s.initializeExactFromVisibleIfNeeded();
        for (SkillName name : SkillName.values()) {
            if (name != SkillName.FATIGUE) s.setExact(name, value);
        }
        s.syncVisibleFromExact();
        return s;
    }

    @Test
    @DisplayName("an AI club nobody manages still improves its players")
    void anUnmanagedClubStillTrains() {
        Team ai = club("AI only club", 55);
        Player p = aPlayer(ai, Position.ATT, 22, 8.0);
        double before = p.getSkills().getExact(SkillName.STRIKER);

        int trained = squadTraining.trainSquad(ai.getId(), 1, 1);

        assertEquals(1, trained, "a club with no manager still trains its squad");
        Player reloaded = players.findById(p.getId()).orElseThrow();
        assertTrue(reloaded.getSkills().getExact(SkillName.STRIKER) > before,
                "a striker's main skill must grow: " + before + " -> "
                        + reloaded.getSkills().getExact(SkillName.STRIKER));
    }

    @Test
    @DisplayName("the default week is gentle - a whole season is about a point and a half")
    void theDefaultWeekIsSmall() {
        Team ai = club("Gentle club", 55);
        Player p = aPlayer(ai, Position.ATT, 22, 8.0);
        double before = p.getSkills().getExact(SkillName.STRIKER);

        // Twelve weeks is a season.
        for (int week = 1; week <= 12; week++) {
            squadTraining.trainSquad(ai.getId(), 1, week);
        }

        double gained = players.findById(p.getId()).orElseThrow()
                .getSkills().getExact(SkillName.STRIKER) - before;
        assertTrue(gained > 0.3 && gained < 2.5,
                "a full season of nothing but default training should be worth about a point, was "
                        + gained);
    }

    @Test
    @DisplayName("age matters, and a veteran stops improving entirely")
    void ageStillGovernsTheFragment() {
        Team club = club("Ages", 55);
        Player young = aPlayer(club, Position.ATT, 20, 8.0);
        Player veteran = aPlayer(club, Position.ATT, 35, 8.0);
        double youngBefore = young.getSkills().getExact(SkillName.STRIKER);
        double veteranBefore = veteran.getSkills().getExact(SkillName.STRIKER);

        squadTraining.trainSquad(club.getId(), 1, 1);

        double youngGain = players.findById(young.getId()).orElseThrow()
                .getSkills().getExact(SkillName.STRIKER) - youngBefore;
        double veteranGain = players.findById(veteran.getId()).orElseThrow()
                .getSkills().getExact(SkillName.STRIKER) - veteranBefore;

        assertTrue(youngGain > veteranGain,
                "the percentage is the same for both, so the age factor must be what separates them: "
                        + youngGain + " vs " + veteranGain);
        assertEquals(0.0, veteranGain, 0.0001,
                "a player past 34 stops getting better on his own - that is what makes hoarding "
                        + "veterans a cost rather than a free strategy");
    }

    @Test
    @DisplayName("talent changes the default week too, not just a manager's programme")
    void talentFeedsTheDefaultWeekAsWell() {
        // The duplication this removed: the old default pass ignored talent entirely, so a
        // prospect and a journeyman improved at the same rate outside a manager's programme.
        Team club = club("Talents", 55);
        Player prodigy = aPlayer(club, Position.ATT, 20, 10.0);
        Player journeyman = aPlayer(club, Position.ATT, 20, 2.0);
        double a = prodigy.getSkills().getExact(SkillName.STRIKER);
        double b = journeyman.getSkills().getExact(SkillName.STRIKER);

        squadTraining.trainSquad(club.getId(), 1, 1);

        double prodigyGain = players.findById(prodigy.getId()).orElseThrow()
                .getSkills().getExact(SkillName.STRIKER) - a;
        double journeymanGain = players.findById(journeyman.getId()).orElseThrow()
                .getSkills().getExact(SkillName.STRIKER) - b;

        assertTrue(prodigyGain > journeymanGain,
                "talent must reach the default path as well, or the two paths disagree: "
                        + prodigyGain + " vs " + journeymanGain);
    }

    @Test
    @DisplayName("every club in the game is trained by the weekly run")
    void everyClubIsCovered() {
        Team one = club("Club one", 55);
        Team two = club("Club two", 55);
        aPlayer(one, Position.MID, 22, 7.0);
        aPlayer(two, Position.DEF, 22, 7.0);

        int trained = squadTraining.trainEveryClub(1, 1);

        // The world has hundreds of clubs; the two we made must be inside that number, and the call
        // must not blow up on the rest.
        assertTrue(trained >= 2, "both clubs should have been trained, got " + trained);
    }

    @Test
    @DisplayName("a club with no staff still trains, badly")
    void aClubWithNoCoachStillTrains() {
        // coachRating falls back rather than throwing, so an unstaffed club is a mediocre one rather
        // than a frozen one. That is a deliberate floor, not an oversight.
        Team bare = club("No staff", 55);
        Player p = aPlayer(bare, Position.MID, 22, 7.0);
        double before = p.getSkills().getExact(SkillName.PASSING);

        assertEquals(1, squadTraining.trainSquad(bare.getId(), 1, 1));
        assertTrue(players.findById(p.getId()).orElseThrow()
                        .getSkills().getExact(SkillName.PASSING) > before,
                "nobody employed means a poor week, not no week");
    }
}
