package org.example.footballtextmanager;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.footballtextmanager.model.*;
import org.example.footballtextmanager.repository.*;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class CSDataInitializer {

    private final CSCountryRepository csCountryRepository;
    private final CSCompetitionRepository csCompetitionRepository;
    private final CSSeasonCompetitionRepository csSeasonCompetitionRepository;
    private final CSCompetitionEntryRepository csCompetitionEntryRepository;
    private final CSTeamRepository csTeamRepository;
    private final CSPlayerRepository csPlayerRepository;

    /** Squad size per club. Named because "15" appears in seeding, repair and the size check. */
    /**
     * Sixteen: two goalkeepers and fourteen outfielders.
     *
     * <p>Fifteen with a single keeper was the original figure. One keeper is not a squad, it is a
     * starting lineup with no cover — the first injury or suspension to the number one and the club
     * cannot field a legal eleven. The owner asked for two, and the right number for a text manager
     * that also carries a seven-man bench.
     */
    private static final int SQUAD_SIZE = 16;

    private static final String[] SERBIAN_FIRST_NAMES = {
        "Marko", "Nikola", "Stefan", "Milan", "Dragan", "Zoran", "Dejan", "Slobodan",
        "Vladimir", "Ivan", "Aleksandar", "Petar", "Miloš", "Dušan", "Filip", "Lazar",
        "Andrija", "Matija", "Strahinja", "Vuk", "Bogdan", "Nemanja", "Jovan", "Stevan",
        "Branislav", "Goran", "Miloš", "Radovan", "Tihomir", "Željko"
    };

    private static final String[] SERBIAN_LAST_NAMES = {
        "Jovanović", "Petrović", "Nikolić", "Marković", "Đorđević", "Stojanović", "Ilić",
        "Stanković", "Pavlović", "Milošević", "Todorović", "Stevanović", "Kovačević",
        "Popović", "Savić", "Vasić", "Obradović", "Mitrović", "Radović", "Filipović",
        "Tadić", "Ivanović", "Sekulić", "Vuković", "Gajić", "Miljković", "Ristić"
    };

    /**
     * Popravlja šta fali u tekst-svetu, pri svakom bootu.
     *
     * <p>Ovdje je bila provera {@code isSeeded()} sa {@code return}-om, i to je bila druga
     * iteracija iste greške: prvi guard je gledao zemlju, ovaj gleda ligu, ali oba su
     * <b>short-circuit</b> i zato se nikad ne dolazi do popravka. Svaki od njih je mogao da
     * preskoči ceo seeding na delimičnom svetu — liga postoji, a igrača ili tabela nemа, i
     * tada je {@code return} bez ikakve poruke o tome šta nedostaje.
     *
     * <p>Zato je provera uklonjena, a ne popravljena. {@link #seed()} je vec idempotentan
     * korak po korak — svaka zemlja, liga, sezona, stadion, unos i igrač se traži pre
     * kreiranja ({@code findByName(...).orElseGet(...)}, {@code if (getCsStadium() == null)},
     * {@code if (existingEntry.isEmpty())}, {@code if (countByCTeam >= 15) continue}). Zato
     * poziv na zdravom svetu ne menja nista, a na delimičnom dopunjuje tačno ono što
     * nedostaje. To je jedini iskren oblik "seed": pokreni, i neka svaki korak sam odluči.
     *
     * <p>Log prijavljuje <b>delte</b>, ne apsolutne brojeve. Raniji log je uvek ispisivao
     * "1 country, N competitions, M teams" i izgledao je kao dokaz da je posao uradjen, bez
     * obaveštenja da je za 0.5 sekunde ništa napravljeno. Broj koji raste za vreme poziva
     * je jedini koji govori da li se nešto desilo.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void ensureCSDataOnStartup() {
        long competitionsBefore = csCompetitionRepository.count();
        long teamsBefore = csTeamRepository.count();
        long playersBefore = csPlayerRepository.count();

        seed();

        long newCompetitions = csCompetitionRepository.count() - competitionsBefore;
        long newTeams = csTeamRepository.count() - teamsBefore;
        long newPlayers = csPlayerRepository.count() - playersBefore;

        if (newCompetitions == 0 && newTeams == 0 && newPlayers == 0) {
            log.info("CS world already complete: {} competitions, {} teams, {} players — nothing created",
                    csCompetitionRepository.count(), csTeamRepository.count(), csPlayerRepository.count());
        } else {
            log.info("CS world repaired: +{} competitions, +{} teams, +{} players "
                            + "(now {} competitions, {} teams, {} players)",
                    newCompetitions, newTeams, newPlayers,
                    csCompetitionRepository.count(), csTeamRepository.count(), csPlayerRepository.count());
        }
    }

    private void seed() {
        // Serbia
        CSCountry serbia = csCountryRepository.findByIsoCodeIgnoreCase("SRB")
                .orElseGet(() -> {
                    CSCountry c = new CSCountry();
                    c.setName("Serbia");
                    c.setIsoCode("SRB");
                    // /images/flags/ does not exist, so this was a 404 for the one country that has a flag.
// Serbia is the only country with artwork and the football game already points at the file that is
                    // actually there; the eight countries without artwork are not given a broken path, they fall back
                    // to a real flag emoji from the ISO code in the country view.
                    c.setFlagImagePath("/images/serbiaflag.png");
                    c.setCurrencyCode("RSD");
                    c.setReputation(55);
                    c.setYouthRating(65);
                    return csCountryRepository.save(c);
                });
        Random rng = new Random(42);

        // Link any existing CTeams that lack csCountry to Serbia
        List<CTeam> existingTeams = csTeamRepository.findAll();
        for (CTeam t : existingTeams) {
            if (t.getCsCountry() == null) {
                t.setCsCountry(serbia);
                csTeamRepository.save(t);
            }
        }

        // Serbia First League
        CSCompetition league = csCompetitionRepository.findAll().stream()
                .filter(c -> c.getName().equals("Serbian First League"))
                .findFirst()
                .orElseGet(() -> {
                    CSCompetition l = new CSCompetition();
                    l.setName("Serbian First League");
                    l.setType(CSCompetitionType.LEAGUE);
                    l.setScope(CSCompetitionScope.NATIONAL);
                    l.setTeamType(CSCompetitionTeamType.CLUB);
                    l.setCsCountry(serbia);
                    l.setTier(1);
                    l.setDivisionLevel(1);
                    l.setTeamsPerCompetition(16);
                    l.setHasPlayoff(false);
                    l.setHasPlayout(false);
                    l.setPromotionSpots(1);
                    l.setRelegationSpots(3);
                    l.setReputationWeight(10);
                    l.setHasSeeding(false);
                    return csCompetitionRepository.save(l);
                });

        // Current season
        int currentYear = Year.now().getValue();
        CSSeasonCompetition season = csSeasonCompetitionRepository
                .findByCsCompetitionAndSeasonYear(league, currentYear)
                .orElseGet(() -> {
                    CSSeasonCompetition s = new CSSeasonCompetition();
                    s.setSeasonYear(currentYear);
                    s.setCsCompetition(league);
                    s.setFinished(false);
                    return csSeasonCompetitionRepository.save(s);
                });

        // Link existing CTeams that lack CSCompetition to the league
        for (CTeam t : existingTeams) {
            if (t.getCSCompetition() == null) {
                t.setCSCompetition(league);
                csTeamRepository.save(t);
                CSCompetitionEntry entry = new CSCompetitionEntry();
                entry.setCsSeasonCompetition(season);
                entry.setCTeam(t);
                entry.setPoints(rng.nextInt(50));
                entry.setGoalsScored(10 + rng.nextInt(30));
                entry.setGoalsConceded(10 + rng.nextInt(30));
                entry.setPosition(1);
                entry.setWins(rng.nextInt(10));
                entry.setDraws(rng.nextInt(8));
                entry.setLosses(rng.nextInt(8));
                csCompetitionEntryRepository.save(entry);
            }
        }

        // Previous season (for historical data)
        if (csSeasonCompetitionRepository.findByCsCompetitionAndSeasonYear(league, currentYear - 1).isEmpty()) {
            CSSeasonCompetition prevSeason = new CSSeasonCompetition();
            prevSeason.setSeasonYear(currentYear - 1);
            prevSeason.setCsCompetition(league);
            prevSeason.setFinished(true);
            csSeasonCompetitionRepository.save(prevSeason);
        }

        // Create 16 teams
        String[] teamNames = {
            "OFK Omladinac", "GFK Pobeda Ćuprija", "FK Radnički Svilajnac",
            "FK Timočanin", "FK Moravac", "FK Đerdap", "FK Župa",
            "FK Remont", "FK Budućnost", "FK Sloga", "FK Mladost",
            "FK Napredak", "FK Proleter", "FK Metalac", "FK Rudar",
            "OFK Kruševac"
        };

        for (int i = 0; i < teamNames.length; i++) {
            String teamName = teamNames[i];
            CTeam team = csTeamRepository.findByName(teamName).orElseGet(() -> {
                CTeam newTeam = new CTeam();
                newTeam.setName(teamName);
                return newTeam;
            });
            team.setType(CSCompetitionTeamType.CLUB);
            team.setCsCountry(serbia);
            team.setCSCompetition(league);
            team.setBudget(500_000.0 + rng.nextInt(500_000));
            team.setReputation(40.0 + rng.nextDouble() * 30.0);
            team.setJuniorCoachSkill(30 + rng.nextInt(50));
            if (!team.isHumanControlled() && i == 0) team.setHumanControlled(true);
            team = csTeamRepository.save(team);

            // Create stadium only if team doesn't have one
            if (team.getCsStadium() == null) {
                CSStadium stadium = new CSStadium();
                stadium.setName("Stadion " + teamName.replaceAll("^(OFK|GFK|FK) ", ""));
                stadium.setCapacity(3000 + rng.nextInt(12000));
                stadium.setTicketPrice(300.0 + rng.nextDouble() * 500.0);
                stadium.setPitchQuality(50.0 + rng.nextDouble() * 40.0);
                stadium.setCondition(60 + rng.nextInt(35));
                stadium.setTrainingQuality(40 + rng.nextInt(40));
                team.setCsStadium(stadium);
                team = csTeamRepository.save(team);
            }

            // Create competition entry (skip if exists)
            Optional<CSCompetitionEntry> existingEntry = csCompetitionEntryRepository.findByCsSeasonCompetitionAndCTeam(season, team);
            if (existingEntry.isEmpty()) {
                CSCompetitionEntry entry = new CSCompetitionEntry();
                entry.setCsSeasonCompetition(season);
                entry.setCTeam(team);
                entry.setPoints(rng.nextInt(50));
                entry.setGoalsScored(10 + rng.nextInt(30));
                entry.setGoalsConceded(10 + rng.nextInt(30));
                entry.setPosition(i + 1);
                entry.setWins(entry.getGoalsScored() / 3);
                entry.setDraws(rng.nextInt(8));
                entry.setLosses(10 - entry.getWins() - entry.getDraws());
                if (entry.getLosses() < 0) entry.setLosses(0);
                csCompetitionEntryRepository.save(entry);
            }

            // Create 15 players per team (skip if exists)
            ensureSquad(team, rng);
        }

        // WHY THIS REPAIR PASS EXISTS — the actual defect behind "match lost 0-2, header said 3-2"
        // and the "?" player names.
        //
        // Squad creation used to live INSIDE the loop over the hardcoded `teamNames` array, so only
        // those 16 clubs could ever receive players. `Sremac Berkasovo` exists in `cteam` (added by
        // another code path) and was linked to the league by the `existingTeams` pass above — which
        // created a competition entry for it — but no loop ever reached it, so it kept ZERO players,
        // on every boot, permanently.
        //
        // A team with no roster is not a cosmetic problem. `pickStartingEleven` returns an empty
        // list, the simulator still generates a scoreline for it, and no GOAL event can be
        // attributed to anyone: the header carried a score the timeline never contained, and every
        // yellow card printed "?" because there was no player to name. The season table then showed
        // a club that could never score a goal.
        //
        // The repair is idempotent and runs over EVERY team that belongs to the league, not over a
        // name list, so a club added by any path is filled on the next boot.
        //
        // The count-based top-up is necessary but not sufficient: a club that is already the right
        // SIZE can still be the wrong shape. Measured on the live database, all seventeen clubs
        // held one goalkeeper and the identical outfield arrangement, so the per-club squad
        // templates had never reached a single one of them — they were already "full" and
        // `ensureSquad` returned at its first line. That is why nine clubs fielded no goalkeeper.
        //
        // `repairSquadComposition` fixes composition rather than size. It keeps every player's
        // identity, appearances and record, and is idempotent.
        int repaired = 0;
        for (CTeam leagueTeam : csTeamRepository.findAll()) {
            CSCompetition clubCompetition = leagueTeam.getCSCompetition();
            if (clubCompetition == null) continue;
            if (!"Serbian First League".equals(clubCompetition.getName())) continue;
            ensureSquad(leagueTeam, rng);
            if (repairSquadComposition(leagueTeam, rng)) repaired++;
        }
        if (repaired > 0) {
            log.info("CS squad composition repaired for {} club(s): keeper cover and per-club shape",
                    repaired);
        }

        // Stvarni brojevi iz baze, ne pretpostavljeni. Runi se printao "1, 1, 1" — sto je
        // tvrdnja da je seeder napravio tacno jednu zemlju, jedno takmicanje i jedan unos
        // bez ikakvog dokaza. Zelen status nije dokaz da je posao uradjen.
        log.info("CS world ready: {} countries, {} competitions, {} season competitions, "
                        + "{} teams in league, {} competition entries this season, {} players",
                csCountryRepository.count(),
                csCompetitionRepository.count(),
                csSeasonCompetitionRepository.count(),
                csTeamRepository.countByCSCompetition(league),
                csCompetitionEntryRepository.countByCsSeasonCompetition(season),
                csPlayerRepository.count());
    }

    /**
     * Tops a club up to a full squad, and does nothing when it already has one.
     *
     * <p>Idempotent by count, so calling it for every league team on every boot changes nothing on
     * a healthy world and repairs a partial one. Squad numbers are assigned from the existing
     * count rather than from the loop index, so topping up an eight-player club does not restart
     * numbering at 1 and collide with the shirts already issued.
     */
    /** Builds and saves one player at a position. Shared by the seeder and the repair pass. */
    private CPlayer createPlayer(CTeam team, CSPosition pos, int squadNumber, Random rng,
                                 Set<String> usedNames) {
        CPlayer player = new CPlayer();
        player.setCTeam(team);
        player.setCSPosition(pos);
        player.setSquadNumber(squadNumber);
        player.setAge(18 + rng.nextInt(15));
        player.setForm(4.0 + rng.nextDouble() * 6.0);
        player.setHeight(1.70 + rng.nextDouble() * 0.20);
        player.setWeight(65.0 + rng.nextDouble() * 20.0);
        player.setCSSkills(skillsForPosition(pos, 0, rng));
        player.setRating(computeRating(player.getCSSkills(), pos));
        player.setPlayerValue(10000.0 + rng.nextDouble() * 90000.0);
        player.setEarnings(500.0 + rng.nextDouble() * 4500.0);
        player.setTotalGoals(0);
        player.setTotalAssists(0);
        player.setName(uniqueName(rng, usedNames));
        return csPlayerRepository.save(player);
    }

    /**
     * A skill profile suited to the position, scaled so the player comes out at the target rating.
     *
     * <p>A target of 0 means "whatever this player naturally is", which is how a newly seeded club is
     * filled. A positive target preserves a player's existing overall quality while changing what
     * he is good at, so a striker turned into a midfielder is still as good a player as he was — he
     * is just good at something else now.
     */
    private CSSkills skillsForPosition(CSPosition pos, int targetRating, Random rng) {
        // The shape of the profile: what matters for this role, and what is deliberately mediocre.
        int primaryLo;
        int primaryHi;
        int secondLo;
        int secondHi;
        switch (pos) {
            case GK -> {
                primaryLo = 8; primaryHi = 17; secondLo = 4; secondHi = 12;
            }
            case DEF -> {
                primaryLo = 7; primaryHi = 16; secondLo = 4; secondHi = 12;
            }
            case MID -> {
                primaryLo = 8; primaryHi = 16; secondLo = 4; secondHi = 13;
            }
            case WNG -> {
                primaryLo = 8; primaryHi = 16; secondLo = 5; secondHi = 14;
            }
            case ATT -> {
                primaryLo = 8; primaryHi = 17; secondLo = 4; secondHi = 13;
            }
            default -> {
                primaryLo = 5; primaryHi = 12; secondLo = 5; secondHi = 12;
            }
        }

        // Draw a profile once, then scale the whole thing to land on the requested rating.
        double[] weights = new double[7];
        CSSkills probe = new CSSkills();
        setProfile(probe, pos, primaryLo, primaryHi, secondLo, secondHi, rng);

        double naturalScore = probe.getRatingScore(pos);
        double wantedScore = targetRating > 0 ? targetRating * 10.0 : naturalScore;
        double scale = naturalScore <= 0 ? 1.0 : wantedScore / naturalScore;

        CSSkills skills = new CSSkills();
        // Two passes: scale, then correct for the clamping at the skill ceiling.
        for (int pass = 0; pass < 3; pass++) {
            double current = skills.getRatingScore(pos);
            double correction = current <= 0 ? 1.0 : wantedScore / current;
            double factor = pass == 0 ? scale * correction : correction;
            if (Math.abs(factor - 1.0) < 0.01) break;
            scaleProfile(probe, skills, factor, pos, primaryLo, primaryHi, secondLo, secondHi, rng, pass == 2);
        }
        skills.setFatigue(0);
        skills.initializeExactFromVisibleIfNeeded();
        return skills;
    }

    private void setProfile(CSSkills s, CSPosition pos, int primaryLo, int primaryHi,
                            int secondLo, int secondHi, Random rng) {
        s.setStamina(5 + rng.nextInt(10));
        s.setPassing(4 + rng.nextInt(12));
        s.setPace(4 + rng.nextInt(12));
        s.setTechnique(4 + rng.nextInt(12));

        int primary = primaryLo + rng.nextInt(Math.max(1, primaryHi - primaryLo + 1));
        int second = secondLo + rng.nextInt(Math.max(1, secondHi - secondLo + 1));
        int off = 2 + rng.nextInt(6);

        s.setGoalkeeper(pos == CSPosition.GK ? primary : off);
        s.setDefender(pos == CSPosition.GK || pos == CSPosition.DEF ? primary : off);
        s.setPlaymaker(pos == CSPosition.MID ? primary : off);
        s.setStriker(pos == CSPosition.ATT ? primary : off);
        if (pos == CSPosition.WNG) {
            s.setPace(second + 2);
            s.setPassing(primary);
            s.setTechnique(second);
        }
        s.setPassing(Math.max(s.getPassing(), pos == CSPosition.MID ? second : s.getPassing()));
    }

    private void scaleProfile(CSSkills from, CSSkills to, double factor, CSPosition pos,
                              int primaryLo, int primaryHi, int secondLo, int secondHi,
                              Random rng, boolean finalPass) {
        to.setStamina(clampSkill((int) Math.round(from.getStamina() * factor)));
        to.setGoalkeeper(clampSkill((int) Math.round(from.getGoalkeeper() * factor)));
        to.setDefender(clampSkill((int) Math.round(from.getDefender() * factor)));
        to.setPace(clampSkill((int) Math.round(from.getPace() * factor)));
        to.setTechnique(clampSkill((int) Math.round(from.getTechnique() * factor)));
        to.setPlaymaker(clampSkill((int) Math.round(from.getPlaymaker() * factor)));
        to.setPassing(clampSkill((int) Math.round(from.getPassing() * factor)));
        to.setStriker(clampSkill((int) Math.round(from.getStriker() * factor)));

        // If clamping at the ceiling left the rating short, put the shortfall into this role's
        // primary skill, which is the one that actually moves getRatingScore for this position.
        double shortfall = 0;
        for (int pass = 0; pass < 6 && Math.abs(shortfall) > 0.5; pass++) {
            double score = to.getRatingScore(pos);
            double target = score + (shortfall == 0 ? 0 : shortfall);
            if (Math.abs(score - target) < 0.5) break;
            int perPoint = switch (pos) {
                case GK -> 2;
                case MID -> 2;
                case WNG -> 2;
                case ATT -> 2;
                case DEF -> 15;   // defender is weighted 1.5 in a 15-point scale
            };
            int bump = (int) Math.round((target - score) / perPoint);
            if (bump == 0) break;
            applyPrimaryBump(to, pos, bump);
            shortfall = target - to.getRatingScore(pos);
        }
    }

    private void applyPrimaryBump(CSSkills s, CSPosition pos, int bump) {
        switch (pos) {
            case GK -> s.setGoalkeeper(clampSkill(s.getGoalkeeper() + bump));
            case DEF -> s.setDefender(clampSkill(s.getDefender() + bump));
            case MID -> s.setPlaymaker(clampSkill(s.getPlaymaker() + bump));
            case WNG -> s.setPassing(clampSkill(s.getPassing() + bump));
            case ATT -> s.setStriker(clampSkill(s.getStriker() + bump));
            default -> s.setPace(clampSkill(s.getPace() + bump));
        }
    }

    /** The seeder already produced 17 for a first-choice keeper, so the ceiling is not 15. */
    private static int clampSkill(int value) {
        return Math.max(1, Math.min(17, value));
    }

    /**
     * Repairs an existing squad's <b>composition</b> without deleting anything.
     *
     * <p>WHY THIS EXISTS, and why it is not {@code Reset DB}.
     *
     * <p>Two defects survived every boot of {@link #ensureSquad}, which tops a club up <em>by
     * count</em> and so does nothing to a club that is already full:
     *
     * <ol>
     *   <li><b>Every club had one goalkeeper.</b> {@code positionsFor} added a single {@code GK}.
     *       With one keeper, the first injury to the number one leaves no legal eleven.</li>
     *   <li><b>Every club had the identical shape</b> — measured against the live database, all
     *       seventeen were {@code GK,DEF,DEF,DEF,DEF,MID,MID,MID,WNG,WNG,ATT,ATT,ATT,ATT,ATT}.
     *       The per-club templates added for the scouting work had therefore never reached a single
     *       club, because those clubs were already "full". This is the second source of truth
     *       problem again, one level down: a correct template behind a count-based guard that can
     *       never fire.</li>
     * </ol>
     *
     * <p>Combined with {@code pickStartingEleven} taking the eleven highest-rated players by
     * rating alone, the keeper ranked outside the top eleven for <b>nine of the seventeen clubs</b>,
     * so those nine played every match with no goalkeeper at all. Nothing reported it.
     *
     * <p><b>What this deliberately does not touch:</b> player identity, names, ages, appearances,
     * goals, assists, contract values, the fixture list, the table, the inbox and the transfer
     * market. Only positions and the skills that make a player good at their position change.
     *
     * <p><b>Why skills are regenerated.</b> Repositioning a striker into central midfield and
     * leaving his shooting at 14 and his defending at 3 produces a midfielder who cannot defend,
     * which is the same incoherence as the original bug in a new place. Each moved player keeps
     * their <b>overall rating</b> and gets a skill profile appropriate to the new role, so the
     * table does not shift underneath the manager while their football stops being nonsense.
     *
     * <p>Idempotent: a second boot finds the composition already correct and returns.
     */
    private boolean repairSquadComposition(CTeam team, Random rng) {
        List<CPlayer> squad = csPlayerRepository.findByCTeam(team);
        if (squad.isEmpty()) {
            ensureSquad(team, rng);
            return true;
        }

        List<CSPosition> target = positionsFor(team.getId());
        List<CPlayer> keepers = squad.stream().filter(p -> p.getCSPosition() == CSPosition.GK).toList();
        List<CPlayer> outfield = squad.stream().filter(p -> p.getCSPosition() != CSPosition.GK).toList();

        boolean changed = false;

        // ---- 1. Two keepers.
        if (keepers.size() < 2) {
            changed = true;
            // A new player, rather than converting an existing one: promotion from outfield would
            // silently delete a player the manager has already watched play, and the owner's stated
            // priority is that squad data is not destroyed.
            int needed = 2 - keepers.size();
            Set<String> usedNames = new HashSet<>(squad.stream().map(CPlayer::getName)
                    .filter(Objects::nonNull).toList());
            for (int i = 0; i < needed; i++) {
                squad.add(createPlayer(team, CSPosition.GK, squad.size() + 1, rng, usedNames));
            }
            outfield = outfield.stream()
                    .filter(p -> !squad.stream().map(CPlayer::getId).collect(java.util.stream.Collectors.toSet()).contains(p.getId()))
                    .toList();
        }

        // ---- 2. The outfield shape matches this club's template.
        List<CSPosition> targetOutfield = target.stream().filter(p -> p != CSPosition.GK).toList();
        boolean shapeCorrect = multisetOf(outfield.stream().map(CPlayer::getCSPosition).toList())
                .equals(multisetOf(targetOutfield));

        if (shapeCorrect) return changed;

        // Outfielders ordered strongest first, so the best players keep the most important shirts.
        List<CPlayer> ranked = outfield.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(CPlayer::getRating).reversed()
                        .thenComparing(CPlayer::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        int limit = Math.min(ranked.size(), targetOutfield.size());
        for (int i = 0; i < limit; i++) {
            CPlayer player = ranked.get(i);
            CSPosition wanted = targetOutfield.get(i);
            if (player.getCSPosition() == wanted) continue;

            int ratingBefore = player.getRating();
            changed = true;
            player.setCSPosition(wanted);
            player.setCSSkills(skillsForPosition(wanted, ratingBefore, rng));
            player.setRating(ratingBefore);
        }

        return changed;
    }

    /** Position counts as a comparable multiset, order irrelevant. */
    private static List<CSPosition> multisetOf(List<CSPosition> positions) {
        Map<CSPosition, Integer> counts = new EnumMap<>(CSPosition.class);
        for (CSPosition p : positions) counts.merge(p, 1, Integer::sum);
        List<CSPosition> flat = new ArrayList<>();
        counts.forEach((position, n) -> {
            for (int i = 0; i < n; i++) flat.add(position);
        });
        return flat;
    }

    private void ensureSquad(CTeam team, Random rng) {
        int existing = csPlayerRepository.countByCTeam(team);
        if (existing >= SQUAD_SIZE) return;

        // Slot for every position in this club's template, in squad order. `existing` indexes into
        // it so a top-up continues the shape rather than restarting it.
        List<CSPosition> squadPositions = positionsFor(team.getId());

        Set<String> usedNames = new HashSet<>();
        for (int j = existing; j < SQUAD_SIZE; j++) {
            createPlayer(team, squadPositions.get(j), j + 1, rng, usedNames);
        }
    }

/**
 * Squad templates: (defenders, midfielders, wingers, strikers), always summing to 14 outfielders.
 *
 * <p>WHY THIS EXISTS. {@code pickPosition(int index)} used to depend on nothing but the index, so
 * every club in the league was seeded with the identical shape — 1 GK, 4 DEF, 3 MID, 2 WNG, 5 ATT.
 * Seventeen clubs holding seventeen photocopies of the same squad. It also made the derived
 * formation worthless: with every roster identical, {@code CSOpponentProfile} snapped all of them
 * to the same formation, and no amount of cleverness in the derivation could make clubs differ.
 *
 * <p>Assigning by club id keeps it deterministic — same club, same squad shape, on every boot and
 * every machine — while letting the league actually contain different kinds of side.
 */
private static final int[][] SQUAD_TEMPLATES = {
            {4, 6, 0, 4},   // midfield engine
            {4, 3, 2, 5},   // front-foot, lots of width
            {3, 8, 0, 3},   // back three, huge midfield
            {5, 4, 0, 5},   // defensive, two banks
            {3, 5, 4, 2},   // wing-heavy
            {4, 5, 2, 3}    // balanced
    };

    /** Outfield slots in squad order for one club: GK first, then the template's blocks. */
    private static List<CSPosition> positionsFor(Long teamId) {
        int templateIndex = teamId == null ? 0 : (int) Math.floorMod(teamId, (long) SQUAD_TEMPLATES.length);
        int[] template = SQUAD_TEMPLATES[templateIndex];

        List<CSPosition> positions = new ArrayList<>();
        // Two keepers, first-choice and deputy. A squad of one keeper is a lineup, not a squad.
        positions.add(CSPosition.GK);
        positions.add(CSPosition.GK);
        for (int i = 0; i < template[0]; i++) positions.add(CSPosition.DEF);
        for (int i = 0; i < template[1]; i++) positions.add(CSPosition.MID);
        for (int i = 0; i < template[2]; i++) positions.add(CSPosition.WNG);
        for (int i = 0; i < template[3]; i++) positions.add(CSPosition.ATT);
        return positions;
    }

    private int computeRating(CSSkills skills, CSPosition pos) {
        return (int) Math.round(skills.getRatingScore(pos) / 10.0);
    }

    private String uniqueName(Random rng, Set<String> used) {
        for (int i = 0; i < 100; i++) {
            String first = SERBIAN_FIRST_NAMES[rng.nextInt(SERBIAN_FIRST_NAMES.length)];
            String last = SERBIAN_LAST_NAMES[rng.nextInt(SERBIAN_LAST_NAMES.length)];
            String name = first + " " + last;
            if (used.add(name)) return name;
        }
        String fallback = "Igrač " + (used.size() + 1);
        used.add(fallback);
        return fallback;
    }
}
