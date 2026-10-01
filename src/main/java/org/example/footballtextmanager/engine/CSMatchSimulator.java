package org.example.footballtextmanager.engine;

import org.example.footballtextmanager.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Cista Java simulacija meca — bez JPA, bez repozitorijuma.
 * Radi iskljucivo sa CS POJO modelima.
 */
public class CSMatchSimulator {

    /**
     * Generator po niti, a ne deljeno polje.
     *
     * <p>Bilo je {@code private final Random rnd = new Random()} — jedan generator za ceo
     * singleton, deljen između svih korisnika. Dva korisnika koja kliknu "Next Round" u istom
     * trenutku su ispremecali izvode iz iste sekvence, pa je ishod jednog meča zavisio od toga
     * da li je neko drugi u međuvremenu otvorio stranicu. Uz to, bez seeda nijedan rezultat
     * nije mogao da se ponovi — što za tabelu znači da se ne može ni verifikovati ni
     * kalibrisati.
     *
     * <p>Po niti zato što dva meča ne mogu da se preklapaju u istoj niti, a dva korisnika
     * dobijaju zasebne sekvence čak i kad se njihovi zahtevi izvršavaju paralelno.
     * {@link #simulate} postavlja seed na početku svakog meča, pa je svaki meč
     * reproducibilan nezavisno od onoga što se prethodno desilo.
     */
    private final ThreadLocal<Random> rng = ThreadLocal.withInitial(Random::new);

    private Random rnd() {
        return rng.get();
    }

    /**
     * Seed za meč: stabilna hash kombinacija identiteta utakmice.
     *
     * <p>Namerno <b>ne</b> uključuje vreme ni broj poziva — isti par klubova u istom kolu
     * daje isti meč, bez obzira na to šta se dešavalo između dva kola. To je isto pravilo koje
     * glavni engine primenjuje na fiksure, i jedini način da se tabela može proveriti.
     */
    private static long seedFor(CSTeam home, CSTeam away, int round) {
        long h = home.getId() == null ? 0L : home.getId();
        long a = away.getId() == null ? 0L : away.getId();
        long seed = h * 1_000_003L + a * 10_007L + round * 101L;
        // Smešanje (splitmix64 finalizer) — bez njega susedni id-jevi daju susedne seedeve,
        // pa bi mečevi 12 i 13 imala visoku korelaciju u šumu.
        seed ^= (seed >>> 33);
        seed *= 0xff51afd7ed558ccdL;
        seed ^= (seed >>> 33);
        seed *= 0xc4ceb9fe1a85ec53L;
        seed ^= (seed >>> 33);
        return seed;
    }

    public CSMatchResult simulate(CSTeam home, List<CSPlayer> homePlayers, List<CSPlayer> homeBench,
                                  CSTeam away, List<CSPlayer> awayPlayers, List<CSPlayer> awayBench,
                                  CSTactics homeTactics, CSTactics awayTactics,
                                  int round) {

        // Svaki meč dobija svoj seed iz identiteta utakmice — vidi seedFor().
        rng.get().setSeed(seedFor(home, away, round));

        // Create mutable copies to track on-field players
        List<CSPlayer> homeOnField = new ArrayList<>(homePlayers);
        List<CSPlayer> awayOnField = new ArrayList<>(awayPlayers);

        // Snaga se racuna na osnovu startnih 11 i taktike (formacija + stil)
        double homeStrength = calculateStrength(homePlayers, homeTactics, true);
        double awayStrength = calculateStrength(awayPlayers, awayTactics, false);
        double total = homeStrength + awayStrength;

        int homeGoals = generateGoals(homeStrength / total);
        int awayGoals = generateGoals(awayStrength / total);

        List<CSMatchEvent> events = new ArrayList<>();

        events.add(CSMatchEvent.builder()
                .minute(1)
                .eventType(CSEventType.MATCH_START)
                .description("Kick-off: " + home.getName() + " vs " + away.getName())
                .build());

        // Initialize minutes tracking for all potential players
        Map<Long, Integer> homeMinutes = new java.util.HashMap<>();
        Map<Long, Integer> awayMinutes = new java.util.HashMap<>();
        
        // Track who is currently on field (true = on field initially)
        Map<Long, Boolean> homeOnFieldStatus = new java.util.HashMap<>();
        Map<Long, Boolean> awayOnFieldStatus = new java.util.HashMap<>();
        
        // Entry minutes: when each player first set foot on the field (0 = started, 91 = never entered)
        Map<Long, Integer> homeEntryMinutes = new java.util.HashMap<>();
        Map<Long, Integer> awayEntryMinutes = new java.util.HashMap<>();

        homePlayers.forEach(p -> {
            homeMinutes.put(p.getId(), 90);
            homeOnFieldStatus.put(p.getId(), true);
            homeEntryMinutes.put(p.getId(), 0);
        });
        homeBench.forEach(p -> {
            homeMinutes.put(p.getId(), 0);
            homeOnFieldStatus.put(p.getId(), false);
            homeEntryMinutes.put(p.getId(), 91);
        });

        awayPlayers.forEach(p -> {
            awayMinutes.put(p.getId(), 90);
            awayOnFieldStatus.put(p.getId(), true);
            awayEntryMinutes.put(p.getId(), 0);
        });
        awayBench.forEach(p -> {
            awayMinutes.put(p.getId(), 0);
            awayOnFieldStatus.put(p.getId(), false);
            awayEntryMinutes.put(p.getId(), 91);
        });

        // Apply substitutions — updates entry minutes so goal scoring is correctly gated
        applySubstitutions(events, home, homeOnField, homeBench, homeMinutes, homeOnFieldStatus, homeEntryMinutes);
        applySubstitutions(events, away, awayOnField, awayBench, awayMinutes, awayOnFieldStatus, awayEntryMinutes);

        // Generate goals with entry-minute awareness (fixes sub scoring before entering)
        generateGoalEvents(events, home, homeOnField, away, awayOnField, homeGoals, awayGoals,
                homeEntryMinutes, awayEntryMinutes);

        // Penalties are goals. They used to be written into the timeline as pure decoration,
        // after the score had already been fixed, so a converted penalty did not make it onto
        // the scoreline and a missed one did nothing at all. Now each converted penalty adds a
        // goal to the score, a GOAL event to the timeline, and the taker's goal tally.
        PenaltyGoals penalties = generatePenalties(events, home, homeOnField, away, awayOnField);
        homeGoals += penalties.homeGoals();
        awayGoals += penalties.awayGoals();

        generateStats(events, home, homeOnField, away, awayOnField, homeGoals, awayGoals);

        events.add(CSMatchEvent.builder()
                .minute(90)
                .eventType(CSEventType.MATCH_END)
                .description("Full-time: " + home.getName() + " " + homeGoals + ":" + awayGoals + " " + away.getName())
                .build());

        events.sort((a, b) -> Integer.compare(a.getMinute(), b.getMinute()));

        // Rezultat se dodeljuje TEK OVDE, posle sortiranja po minuti — nikada u trenutku
        // upisa. To je jedina mesto koje zna pravi redosled kretanja po terenu.
        //
        // Pre ovoga se scoreAfterGoal racunao dok su se dogadjaji dodavali, sto je
        // pogresno u dva pravca. Kazneni udarci se biraju nezavisno po minuti i mogu
        // pasti PRE nekog gola iz otvorene igre, pa bi gol u 29' mogao da broji udarac
        // iz 80' i prikaze 2:0 posle 1:0. Ako se to radi u vremenu upisa, redosled
        // upisa i redosled na teletekstu su razliciti, pa rezultat nikad nije mogao
        // da bude tacan — pomeranje te izmene tamo-amo samo otkriva sledeci simptom.
        //
        // Cak i sa ispravnim redosledom upisa, prvi gol bi prikazao 0:0 jer sam
        // brojac vec video prethodne golove ali ne i ovaj. Zato ovde brojmo
        // UKUPNO kroz listu: posle sortiranja redosled je tacan, pa nema off-by-one.
        stampTimelineScores(events, home.getName(), away.getName());

        // Create full player lists for rating assignment (includes bench players who never played)
        List<CSPlayer> homeAll = new ArrayList<>(homePlayers);
        homeAll.addAll(homeBench);
        List<CSPlayer> awayAll = new ArrayList<>(awayPlayers);
        awayAll.addAll(awayBench);

        List<CSPlayerMatchStats> homeStats = assignRatings(homeAll, events, home.getName(), homeMinutes);
        List<CSPlayerMatchStats> awayStats = assignRatings(awayAll, events, away.getName(), awayMinutes);

        // Zamor se racuna za celu rosteru, ne samo za pocetnu jedanaesticu — igrac koji
        // nije igrao je odmaro, a zamena koja je ušla nosi samo svoje minute.
        updateFatigueAfterMatch(homeAll, homeMinutes);
        updateFatigueAfterMatch(awayAll, awayMinutes);

        return CSMatchResult.builder()
                .homeTeamName(home.getName())
                .awayTeamName(away.getName())
                .homeTeamId(home.getId())
                .awayTeamId(away.getId())
                .homeGoals(homeGoals)
                .awayGoals(awayGoals)
                .round(round)
                .events(events)
                .summary(home.getName() + " " + homeGoals + ":" + awayGoals + " " + away.getName())
                .homePlayerStats(homeStats)
                .awayPlayerStats(awayStats)
                .build();
    }

    /**
     * Simulacija za ostale meceve u kolu — sada takodje sa punom statistikom.
     */
    public CSMatchResult simulateQuick(CSTeam home, List<CSPlayer> homePlayers, List<CSPlayer> homeBench,
                                       CSTeam away, List<CSPlayer> awayPlayers, List<CSPlayer> awayBench,
                                       int round) {
        CSTactics defaultTactics = CSTactics.builder().build();
        return simulate(home, homePlayers, homeBench, away, awayPlayers, awayBench, defaultTactics, defaultTactics, round);
    }

    public List<CSPlayer> pickStartingEleven(List<CSPlayer> roster, List<Long> preferredStarterIds) {
        if (roster == null || roster.isEmpty()) return List.of();
        List<CSPlayer> picks = new ArrayList<>();
        if (preferredStarterIds != null) {
            for (Long id : preferredStarterIds) {
                if (id == null) continue;
                CSPlayer player = roster.stream().filter(p -> id.equals(p.getId())).findFirst().orElse(null);
                if (player != null && picks.stream().noneMatch(p -> p.getId().equals(player.getId()))) {
                    picks.add(player);
                    if (picks.size() >= 11) break;
                }
            }
        }
        if (picks.size() < 11) {
            List<CSPlayer> fallback = new ArrayList<>(roster);
            fallback.sort((a, b) -> Integer.compare(b.getRating(), a.getRating()));
            for (CSPlayer player : fallback) {
                if (picks.stream().noneMatch(p -> p.getId().equals(player.getId()))) {
                    picks.add(player);
                    if (picks.size() >= 11) break;
                }
            }
        }
        return picks;
    }

    public List<CSPlayer> pickBenchPlayers(List<CSPlayer> roster, List<CSPlayer> starters, List<Long> preferredBenchIds) {
        if (roster == null || roster.isEmpty()) return List.of();
        java.util.Set<Long> starterIds = starters.stream().map(CSPlayer::getId).collect(java.util.stream.Collectors.toSet());
        List<CSPlayer> bench = new ArrayList<>();
        if (preferredBenchIds != null) {
            for (Long id : preferredBenchIds) {
                if (id == null) continue;
                CSPlayer player = roster.stream().filter(p -> id.equals(p.getId()) && !starterIds.contains(p.getId())).findFirst().orElse(null);
                if (player != null && bench.stream().noneMatch(p -> p.getId().equals(player.getId()))) {
                    bench.add(player);
                    if (bench.size() >= 7) break;
                }
            }
        }
        if (bench.size() < 7) {
            List<CSPlayer> fallback = new ArrayList<>(roster);
            fallback.sort((a, b) -> Integer.compare(b.getRating(), a.getRating()));
            for (CSPlayer player : fallback) {
                if (starterIds.contains(player.getId())) continue;
                if (bench.stream().anyMatch(p -> p.getId().equals(player.getId()))) continue;
                bench.add(player);
                if (bench.size() >= 7) break;
            }
        }
        return bench;
    }

    /**
     * Racuna snagu tima na osnovu individualnih skillova po poziciji,
     * ratinga, forme, umora i taktike.
     */
    /**
     * Sredina i rezerva veštinske skale. Veštine su 1-20; 10 je sredina, a koristi se i kao
     * rezerva za igrača bez upisanih veština.
     */
    private static final double SKILL_MID = 10.0;
    private static final int NO_SKILL = 10;

    private double calculateStrength(List<CSPlayer> players, CSTactics tactics, boolean isHome) {
        if (players.isEmpty()) return 30.0;

        double avgRating = players.stream()
                .mapToInt(CSPlayer::getRating)
                .average()
                .orElse(6.0);

        double avgForm = players.stream()
                .mapToDouble(CSPlayer::getForm)
                .average()
                .orElse(6.0);

        double avgFatigue = players.stream()
                .mapToDouble(CSPlayer::getFatigue)
                .average()
                .orElse(0.0);

        // Skill-based component: each player contributes their positional skill
        double skillComponent = players.stream()
                .mapToDouble(this::getPositionalSkill)
                .average()
                .orElse(22.0);

        // Težine su prekalibrirane. Stale su bile
        //   rating*0.4 + skill*0.3 + form*3.5 - fatigue*2.0
        // na skalama rating 1-10, skill ~1-35, form 1-10, fatigue 0-10, pa je forma dala
        // ±12 poena, veština ±3, ocena ±1.6, a zamor -20..0 — odnosno dva nasumična
        // izvora (forma i zamor) su odlučivale ishod, dok su kvalitet ekipe i izbor taktike
        // zajedno davali manje od šuma od ±5. Sada kvalitet ekipe vodi, taktika ima vidljiv
        // uticaj, a forma i šum su umereni modifikatori.
        double base = avgRating * 1.6
                + skillComponent * 0.8
                + (avgForm - 6.0) * 0.9
                - avgFatigue * 0.7;

        if (isHome) base += 5.0;

        double formationFit = calculateFormationFit(players, tactics);
        double styleFit = calculateStyleFit(players, tactics);
        double styleBonus = switch (tactics.getStyle()) {
            case ATTACKING -> 2.4 + styleFit;
            case COUNTER -> 1.3 + styleFit;
            case BALANCED -> 0.6 + styleFit * 0.55;
            case DEFENSIVE -> 0.2 + styleFit;
        };
        base += formationFit + styleBonus;

        base += rnd().nextDouble() * 6.0 - 3.0;

        return Math.max(10.0, base);
    }

    private double calculateFormationFit(List<CSPlayer> players, CSTactics tactics) {
        int[] desired = parseFormation(String.valueOf(tactics.getFormation()));
        int defenders = (int) players.stream().filter(p -> "DEF".equals(p.getPosition())).count();
        int mids = (int) players.stream().filter(p -> "MID".equals(p.getPosition()) || "WNG".equals(p.getPosition())).count();
        int attackers = (int) players.stream().filter(p -> "ATT".equals(p.getPosition()) || "WNG".equals(p.getPosition())).count();
        int goalkeepers = (int) players.stream().filter(p -> "GK".equals(p.getPosition())).count();

        double mismatch = Math.abs(goalkeepers - 1) * 4.0
                + Math.abs(defenders - desired[0]) * 2.3
                + Math.abs(mids - desired[1]) * 1.8
                + Math.abs(attackers - desired[2]) * 2.0;

        return Math.max(-10.0, 5.5 - mismatch);
    }

    private double calculateStyleFit(List<CSPlayer> players, CSTactics tactics) {
        // Veštine žive na skali 1-20 (seeder upisuje 4-18), a ovo je ranije računalo
        // `(avg - 52) / 6` — skala 0-100. Rezultat: prosečan igrač je imao (10 - 52) / 6 = -7.0,
        // dakle svaka ekipa je dobijala oko -7 odmah, bez obzira na stil, a razlika između
        // stilova bila je nekoliko desetinki. Sada je sredina skale 10, a raspon ±3
        // standardne veštine, pa izbor stila zapravo nešto znači.
        double pace = players.stream().mapToInt(CSPlayer::getPace).average().orElse(NO_SKILL);
        double defending = players.stream().mapToInt(CSPlayer::getDefending).average().orElse(NO_SKILL);
        double passing = players.stream().mapToInt(CSPlayer::getPassing).average().orElse(NO_SKILL);
        double shooting = players.stream().mapToInt(CSPlayer::getShooting).average().orElse(NO_SKILL);
        double stamina = players.stream().mapToInt(CSPlayer::getStamina).average().orElse(NO_SKILL);

        return switch (tactics.getStyle()) {
            case ATTACKING -> ((shooting + passing + pace) / 3.0 - SKILL_MID) / 3.0;
            case COUNTER -> ((pace + shooting + stamina) / 3.0 - SKILL_MID) / 3.0;
            case BALANCED -> ((passing + stamina + defending) / 3.0 - SKILL_MID) / 3.0;
            case DEFENSIVE -> ((defending + stamina + passing) / 3.0 - SKILL_MID) / 3.0;
        };
    }

    private int[] parseFormation(String formation) {
        String[] parts = (formation == null ? "4-4-2" : formation).split("-");
        int def = parts.length > 0 ? parsePart(parts[0], 4) : 4;
        int mid = parts.length > 1 ? parsePart(parts[1], 4) : 4;
        int att = parts.length > 2 ? parsePart(parts[2], 2) : 2;
        return new int[]{def, mid, att};
    }

    private int parsePart(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /**
     * Vraca najbitniji skill igraca u zavisnosti od pozicije.
     */
    private double getPositionalSkill(CSPlayer p) {
        return switch (p.getPosition()) {
            case "GK" -> p.getGoalkeeper() * 1.5 + p.getPace() * 0.3;
            case "DEF" -> p.getDefending() * 1.2 + p.getPace() * 0.5 + p.getPassing() * 0.3;
            case "MID" -> p.getPlaymaker() * 1.0 + p.getPassing() * 0.8 + p.getTechnique() * 0.5;
            case "WNG" -> p.getPace() * 1.0 + p.getTechnique() * 0.7 + p.getPassing() * 0.5;
            case "ATT" -> p.getShooting() * 1.2 + p.getTechnique() * 0.5 + p.getPace() * 0.5;
            default -> (p.getTechnique() + p.getPassing()) * 0.5;
        };
    }

    /**
     * Dodeljuje ocene igracima na osnovu dogadjaja u mecu.
     * Bazna ocena 6.0-7.0 + bonus za gol/asist, mali random.
     * VAZNO: Samo igraci koji su stvarno igrali (minutesPlayed > 0) dobijaju rating.
     * Igraci sa klupe koji nisu usli u igru imaju minutesPlayed = 0 i NE dobijaju rating.
     * Igraci koji su izasli (substituted out) i igraci koji su usli (substituted in) 
     * DOBIJAJU rating i upisuju im se golovi/asistencije.
     */
    private List<CSPlayerMatchStats> assignRatings(List<CSPlayer> players,
                                                   List<CSMatchEvent> events,
                                                   String teamName,
                                                   java.util.Map<Long, Integer> minutesByPlayer) {
        List<CSPlayerMatchStats> stats = new ArrayList<>();
        long teamGoals = events.stream()
                .filter(e -> e.getEventType() == CSEventType.GOAL && teamName.equals(e.getTeamName()))
                .count();
        long concededGoals = events.stream()
                .filter(e -> e.getEventType() == CSEventType.GOAL && !teamName.equals(e.getTeamName()))
                .count();
        boolean cleanSheet = concededGoals == 0;

        for (CSPlayer p : players) {
            int goalsInMatch = 0;
            int assistsInMatch = 0;
            if (events != null) {
                // Pripisuje se po id-ju, ne po imenu. Imena se ponavljaju između klubova
                // (30 x 27 kombinacija za ~240 igraca), a stari kod nije ni proveravao koji je
                // tim u dogadjaju — pa je igrac sa istim imenom u protivnickom klubu brojao
                // tudje golove, i to na obe strane meca.
                for (CSMatchEvent e : events) {
                    if (e.getEventType() == CSEventType.GOAL) {
                        if (p.getId().equals(e.getPlayerId())) {
                            goalsInMatch++;
                        }
                        if (e.getAssistPlayerId() != null && p.getId().equals(e.getAssistPlayerId())) {
                            assistsInMatch++;
                        }
                    }
                }
            }
            int minutesPlayed = minutesByPlayer.getOrDefault(p.getId(), 0);

            double base;
            if (goalsInMatch >= 2 || assistsInMatch >= 2) {
                base = 7.0 + rnd().nextDouble() * 1.5;
            } else if (goalsInMatch >= 1 || assistsInMatch >= 1) {
                base = 6.5 + rnd().nextDouble() * 1.2;
            } else {
                base = 5.5 + rnd().nextDouble() * 1.0;
            }

            if ("GK".equals(p.getPosition())) {
                base += cleanSheet ? 1.0 : -0.5;
            } else if ("DEF".equals(p.getPosition())) {
                base += cleanSheet ? 0.5 : 0.0;
            }

            if (minutesPlayed >= 60) base += 0.2;
            else if (minutesPlayed <= 30) base -= 0.3;

            if (goalsInMatch >= 3) base += 0.4;
            else if (goalsInMatch == 2) base += 0.2;

            if (minutesPlayed >= 45) {
                if (cleanSheet) {
                    if ("GK".equals(p.getPosition())) base += 0.8;
                    else if ("DEF".equals(p.getPosition())) base += 0.5;
                }
                if (concededGoals >= 3) {
                    if ("GK".equals(p.getPosition())) base -= 0.6;
                    else if ("DEF".equals(p.getPosition())) base -= 0.4;
                }
            }

            if (teamGoals > concededGoals) base += 0.1;
            else if (teamGoals < concededGoals) base -= 0.1;

            // Extended stats
            int passesAttempted = 0, passesCompleted = 0, tackles = 0, interceptions = 0;
            int duelsWon = 0, duelsLost = 0, aerialDuelsWon = 0, keyPasses = 0;
            int dribblesCompleted = 0, dribblesLost = 0, saves = 0;
            double distanceCovered = 0.0;

            if (minutesPlayed > 0) {
                passesAttempted = (int) (minutesPlayed * (0.4 + rnd().nextDouble() * 0.4));
                passesCompleted = (int) (passesAttempted * (0.65 + rnd().nextDouble() * 0.25));
                distanceCovered = Math.round(minutesPlayed * (0.08 + rnd().nextDouble() * 0.04) * 10.0) / 10.0;

                switch (p.getPosition()) {
                    case "GK" -> {
                        saves = (int) (concededGoals == 0 ? rnd().nextInt(3) : rnd().nextInt(5) + 2);
                        duelsWon = (int) (rnd().nextDouble() * 2);
                        aerialDuelsWon = (int) (rnd().nextDouble() * 2);
                    }
                    case "DEF" -> {
                        tackles = (int) (minutesPlayed / 15.0 + rnd().nextInt(3));
                        interceptions = (int) (minutesPlayed / 20.0 + rnd().nextInt(2));
                        duelsWon = (int) (minutesPlayed / 10.0 + rnd().nextInt(4));
                        duelsLost = (int) (minutesPlayed / 20.0 + rnd().nextInt(3));
                        aerialDuelsWon = (int) (minutesPlayed / 12.0 + rnd().nextInt(3));
                    }
                    case "MID" -> {
                        tackles = (int) (minutesPlayed / 20.0 + rnd().nextInt(3));
                        interceptions = (int) (minutesPlayed / 18.0 + rnd().nextInt(3));
                        duelsWon = (int) (minutesPlayed / 12.0 + rnd().nextInt(4));
                        duelsLost = (int) (minutesPlayed / 15.0 + rnd().nextInt(4));
                        keyPasses = (int) (minutesPlayed / 25.0 + rnd().nextInt(3));
                        dribblesCompleted = (int) (minutesPlayed / 30.0 + rnd().nextInt(4));
                        dribblesLost = (int) (minutesPlayed / 40.0 + rnd().nextInt(3));
                    }
                    case "WNG" -> {
                        tackles = (int) (minutesPlayed / 25.0 + rnd().nextInt(2));
                        duelsWon = (int) (minutesPlayed / 10.0 + rnd().nextInt(5));
                        duelsLost = (int) (minutesPlayed / 12.0 + rnd().nextInt(4));
                        keyPasses = (int) (minutesPlayed / 20.0 + rnd().nextInt(4));
                        dribblesCompleted = (int) (minutesPlayed / 15.0 + rnd().nextInt(5));
                        dribblesLost = (int) (minutesPlayed / 20.0 + rnd().nextInt(4));
                    }
                    case "ATT" -> {
                        tackles = (int) (rnd().nextDouble() * 1);
                        duelsWon = (int) (minutesPlayed / 12.0 + rnd().nextInt(4));
                        duelsLost = (int) (minutesPlayed / 15.0 + rnd().nextInt(3));
                        keyPasses = (int) (minutesPlayed / 30.0 + rnd().nextInt(2));
                        dribblesCompleted = (int) (minutesPlayed / 18.0 + rnd().nextInt(4));
                        dribblesLost = (int) (minutesPlayed / 25.0 + rnd().nextInt(3));
                    }
                }
            }

            double rating = Math.min(10.0, Math.max(1.0, Math.round(base * 10.0) / 10.0));

            stats.add(CSPlayerMatchStats.builder()
                    .playerId(p.getId())
                    .playerName(p.getName())
                    .position(p.getPosition())
                    .rating(rating)
                    .goals((int) goalsInMatch)
                    .assists((int) assistsInMatch)
                    .minutesPlayed(minutesPlayed)
                    .passesAttempted(passesAttempted)
                    .passesCompleted(passesCompleted)
                    .tackles(tackles)
                    .interceptions(interceptions)
                    .duelsWon(duelsWon)
                    .duelsLost(duelsLost)
                    .aerialDuelsWon(aerialDuelsWon)
                    .keyPasses(keyPasses)
                    .dribblesCompleted(dribblesCompleted)
                    .dribblesLost(dribblesLost)
                    .distanceCovered(distanceCovered)
                    .saves(saves)
                    .cleanSheet(cleanSheet)
                    .goalsConceded((int) concededGoals)
                    .build());
        }
        return stats;
    }

    private void applySubstitutions(List<CSMatchEvent> events,
                                    CSTeam team,
                                    List<CSPlayer> onField,
                                    List<CSPlayer> bench,
                                    java.util.Map<Long, Integer> minutesByPlayer,
                                    java.util.Map<Long, Boolean> onFieldStatus,
                                    java.util.Map<Long, Integer> entryMinutes) {
        if (onField.isEmpty() || bench.isEmpty()) return;
        int maxSubs = Math.min(3, bench.size());
        int subs = rnd().nextDouble() < 0.55 ? rnd().nextInt(maxSubs + 1) : 0;
        for (int i = 0; i < subs; i++) {
            int minute = 55 + rnd().nextInt(31);
            CSPlayer out = pickMostTired(onField);
            if (out == null) break;
            CSPlayer in = pickLikeForLike(bench, out.getPosition());
            if (in == null) break;
            onField.removeIf(p -> p.getId().equals(out.getId()));
            onField.add(in);
            bench.removeIf(p -> p.getId().equals(in.getId()));
            
            // Update minutes for substituted players
            minutesByPlayer.put(out.getId(), Math.max(1, minute));
            minutesByPlayer.put(in.getId(), Math.max(0, 91 - minute));

            // Record when the substitute entered the field (fixes scoring-before-entering bug)
            if (entryMinutes != null) {
                entryMinutes.put(in.getId(), minute);
            }

            // Update on-field status
            if (onFieldStatus != null) {
                onFieldStatus.put(out.getId(), false);
                onFieldStatus.put(in.getId(), true);
            }
            
            events.add(CSMatchEvent.builder()
                    .minute(minute)
                    .eventType(CSEventType.SUBSTITUTION)
                    .teamName(team.getName())
                    .playerOutId(out.getId())
                    .playerInId(in.getId())
                    .playerOutName(out.getName())
                    .playerInName(in.getName())
                    .description(describeSubstitution(team.getName(), out.getName(), in.getName()))
                    .build());
        }
    }

    private CSPlayer pickMostTired(List<CSPlayer> starters) {
        return starters.stream().max(java.util.Comparator.comparingDouble(CSPlayer::getFatigue)).orElse(null);
    }

    private CSPlayer pickLikeForLike(List<CSPlayer> bench, String position) {
        return bench.stream().filter(p -> position.equals(p.getPosition())).findFirst().orElse(bench.isEmpty() ? null : bench.getFirst());
    }

    private int generateGoals(double strengthRatio) {
        // Poisson-like distribucija: ocekivani golovi na osnovu snage
        double lambda = strengthRatio * 3.0; // prosecno ~1.5 gola po timu
        int goals = 0;
        double p = Math.exp(-lambda);
        double cumulative = p;
        double uniform = rnd().nextDouble();
        while (uniform > cumulative && goals < 8) {
            goals++;
            p *= lambda / goals;
            cumulative += p;
        }
        return goals;
    }

    private void generateGoalEvents(List<CSMatchEvent> events,
                                    CSTeam home, List<CSPlayer> homePlayers,
                                    CSTeam away, List<CSPlayer> awayPlayers,
                                    int homeGoals, int awayGoals,
                                    Map<Long, Integer> homeEntryMinutes,
                                    Map<Long, Integer> awayEntryMinutes) {
        int remainingHome = homeGoals;
        int remainingAway = awayGoals;
        int lastMinute = 0;

        while (remainingHome > 0 || remainingAway > 0) {
            boolean isHome;
            if (remainingHome == 0) isHome = false;
            else if (remainingAway == 0) isHome = true;
            else isHome = rnd().nextDouble() < ((double) remainingHome / (remainingHome + remainingAway) + 0.1);

            // Compute minute FIRST so we can filter eligible scorers correctly
            int remaining = remainingHome + remainingAway - 1;
            int minMinute = lastMinute + 1;
            int maxMinute = 90 - remaining * 3;
            if (maxMinute < minMinute) maxMinute = minMinute;
            if (maxMinute > 90) maxMinute = 90;
            int minute = minMinute + rnd().nextInt(Math.max(1, maxMinute - minMinute + 1));

            CSTeam scoringTeam = isHome ? home : away;
            List<CSPlayer> allScoringPlayers = isHome ? homePlayers : awayPlayers;
            Map<Long, Integer> entryMin = isHome ? homeEntryMinutes : awayEntryMinutes;

            // Only players who were already on the field at this minute can score
            final int goalMinute = minute;
            List<CSPlayer> eligibleScorers = allScoringPlayers.stream()
                    .filter(p -> entryMin.getOrDefault(p.getId(), 0) <= goalMinute)
                    .toList();
            List<CSPlayer> scoringPlayers = eligibleScorers.isEmpty() ? allScoringPlayers : eligibleScorers;

            CSPlayer scorer = pickScorer(scoringPlayers);
            CSPlayer assist = pickAssist(scoringPlayers, scorer);
            if (scorer != null && assist != null && scorer.getId().equals(assist.getId())) {
                assist = null;
            }

            if (isHome) remainingHome--;
            else remainingAway--;

            // scoreAfterGoal se NE racuna ovde. Vreme upisa nije vreme na koje se
            // teletext gleda: kazneni udarci se biraju nezavisno po minuti, pa se
            // rezultat mora racunati tek kada je lista sortirana po minuti. Vidi
            // stampTimelineScores.
            scorer.setGoals(scorer.getGoals() + 1);
            if (assist != null) assist.setAssists(assist.getAssists() + 1);

            org.example.footballtextmanager.model.CSGoalType goalType = assignGoalType(scorer);
            events.add(CSMatchEvent.builder()
                    .minute(minute)
                    .eventType(CSEventType.GOAL)
                    .goalType(goalType)
                    .playerId(scorer.getId())
                    .assistPlayerId(assist != null ? assist.getId() : null)
                    .playerName(scorer.getName())
                    .assistName(assist != null ? assist.getName() : null)
                    .teamName(scoringTeam.getName())
                    .description(describeGoal(scoringTeam.getName(), scorer.getName(),
                            assist != null ? assist.getName() : null, null, goalType))
                    .build());

            lastMinute = minute;
        }
    }

    /**
     * Assigns a goal type based on the scorer's position and randomness.
     * Mirrors real-world tendencies: ATT scores tap-ins/one-on-ones, DEF headers, MID long-range/FK.
     */
    private org.example.footballtextmanager.model.CSGoalType assignGoalType(CSPlayer scorer) {
        if (scorer == null) return org.example.footballtextmanager.model.CSGoalType.TAP_IN;
        double roll = rnd().nextDouble();
        return switch (scorer.getPosition()) {
            case "ATT" -> {
                if (roll < 0.28) yield org.example.footballtextmanager.model.CSGoalType.TAP_IN;
                if (roll < 0.50) yield org.example.footballtextmanager.model.CSGoalType.ONE_ON_ONE;
                if (roll < 0.65) yield org.example.footballtextmanager.model.CSGoalType.POACHERS;
                if (roll < 0.78) yield org.example.footballtextmanager.model.CSGoalType.HEADER;
                if (roll < 0.90) yield org.example.footballtextmanager.model.CSGoalType.VOLLEY;
                yield org.example.footballtextmanager.model.CSGoalType.COUNTER;
            }
            case "WNG" -> {
                if (roll < 0.28) yield org.example.footballtextmanager.model.CSGoalType.LONG_RANGE;
                if (roll < 0.48) yield org.example.footballtextmanager.model.CSGoalType.ONE_ON_ONE;
                if (roll < 0.62) yield org.example.footballtextmanager.model.CSGoalType.COUNTER;
                if (roll < 0.76) yield org.example.footballtextmanager.model.CSGoalType.SCREAMER;
                if (roll < 0.88) yield org.example.footballtextmanager.model.CSGoalType.TAP_IN;
                yield org.example.footballtextmanager.model.CSGoalType.VOLLEY;
            }
            case "MID" -> {
                if (roll < 0.32) yield org.example.footballtextmanager.model.CSGoalType.LONG_RANGE;
                if (roll < 0.52) yield org.example.footballtextmanager.model.CSGoalType.FREE_KICK;
                if (roll < 0.66) yield org.example.footballtextmanager.model.CSGoalType.SCREAMER;
                if (roll < 0.80) yield org.example.footballtextmanager.model.CSGoalType.TAP_IN;
                if (roll < 0.90) yield org.example.footballtextmanager.model.CSGoalType.HEADER;
                yield org.example.footballtextmanager.model.CSGoalType.COUNTER;
            }
            case "DEF" -> {
                if (roll < 0.52) yield org.example.footballtextmanager.model.CSGoalType.HEADER;
                if (roll < 0.70) yield org.example.footballtextmanager.model.CSGoalType.TAP_IN;
                if (roll < 0.84) yield org.example.footballtextmanager.model.CSGoalType.LONG_RANGE;
                yield org.example.footballtextmanager.model.CSGoalType.FREE_KICK;
            }
            default -> org.example.footballtextmanager.model.CSGoalType.TAP_IN;
        };
    }

    private CSPlayer pickScorer(List<CSPlayer> players) {
        if (players.isEmpty()) return null;

        // Tezinski izbor — napadaci i krilni imaju vecu sansu
        List<CSPlayer> weighted = new ArrayList<>();
        for (CSPlayer p : players) {
            int weight = switch (p.getPosition()) {
                case "ATT" -> 5;
                case "WNG" -> 3;
                case "MID" -> 2;
                case "DEF" -> 1;
                default -> 0; // GK
            };
            for (int i = 0; i < weight; i++) weighted.add(p);
        }
        if (weighted.isEmpty()) return players.get(rnd().nextInt(players.size()));
        return weighted.get(rnd().nextInt(weighted.size()));
    }

    private CSPlayer pickAssist(List<CSPlayer> players, CSPlayer scorer) {
        if (players.size() < 2) return null;
        if (rnd().nextDouble() < 0.3) return null; // 30% sansa nema asista

        List<CSPlayer> candidates = players.stream()
                .filter(p -> !p.getId().equals(scorer.getId()))
                .toList();
        if (candidates.isEmpty()) return null;

        // Bias ka playmakerima i krilnim
        List<CSPlayer> weighted = new ArrayList<>();
        for (CSPlayer p : candidates) {
            int weight = switch (p.getPosition()) {
                case "MID" -> 4;
                case "WNG" -> 3;
                case "ATT" -> 2;
                case "DEF" -> 1;
                default -> 1;
            };
            for (int i = 0; i < weight; i++) weighted.add(p);
        }
        return weighted.get(rnd().nextInt(weighted.size()));
    }

    /**
     * Broj golova dobijenih iz kaznenih udaraca, po domaćoj i gostujućoj strani.
     */
    private record PenaltyGoals(int homeGoals, int awayGoals) { }

    /**
     * Generiše kaznene udarce. Svaki realizovani dodaje GOL na rezultat, GOL događaj u
     * timeline-u i jedan gol izvođaocu; svaki promašen samo ostaje kao PENALTY događaj bez
     * posledica, što je jedina ispravna razlika između njih.
     */
    private PenaltyGoals generatePenalties(List<CSMatchEvent> events,
                                           CSTeam home, List<CSPlayer> homePlayers,
                                           CSTeam away, List<CSPlayer> awayPlayers) {
        int homePenaltyGoals = addPenalty(events, home, homePlayers, true);
        int awayPenaltyGoals = addPenalty(events, away, awayPlayers, false);
        return new PenaltyGoals(homePenaltyGoals, awayPenaltyGoals);
    }

    /**
     * @param isHome        koja je strana u pitanju — nužno prosleđeno jer se kazneni udarci
     *                      dodaju <i>posle</i> golova iz otvorene igre, pa se iz broja golova
     *                      ne može zaključiti koja je strana domaća
     * @param homeGoals     rezultat pre ovog udarca, da bi stanje na prikazu bilo ispravno
     * @return 1 ako je kazneni udarac realizovan, inače 0
     */
    private int addPenalty(List<CSMatchEvent> events, CSTeam team, List<CSPlayer> players,
                           boolean isHome) {
        if (rnd().nextDouble() >= 0.12) return 0;

        CSPlayer taker = pickScorer(players);
        boolean scored = rnd().nextDouble() < 0.75;
        int minute = rnd().nextInt(90) + 1;
        String takerName = taker != null ? taker.getName() : "?";

        events.add(CSMatchEvent.builder()
                .minute(minute)
                .eventType(CSEventType.PENALTY)
                .playerId(taker != null ? taker.getId() : null)
                .playerName(takerName)
                .teamName(team.getName())
                .penaltyScored(scored)
                .description(describePenalty(takerName, team.getName(), scored))
                .build());

        if (!scored) return 0;

        // Pored PENALTY događaja ide i GOL događaj, jer se golovi svuda drugim računaju iz
        // GOAL događaja (assignRatings, Golden Boot, izveštaj meča). Sam PENALTY bi ostao
        // nevidljiv u svemu osim u tekstu.
        if (taker != null) taker.setGoals(taker.getGoals() + 1);

        events.add(CSMatchEvent.builder()
                .minute(minute)
                .eventType(CSEventType.GOAL)
                .goalType(org.example.footballtextmanager.model.CSGoalType.PENALTY)
                .playerId(taker != null ? taker.getId() : null)
                .playerName(takerName)
                .teamName(team.getName())
                .description("Penalty: " + takerName + " converts from the spot for " + team.getName() + ".")
                .build());

        return 1;
    }

    private void generateStats(List<CSMatchEvent> events,
                               CSTeam home, List<CSPlayer> homePlayers,
                               CSTeam away, List<CSPlayer> awayPlayers,
                               int homeGoals, int awayGoals) {

        // Sutevi u okvir
        int homeShotsOn = homeGoals + rnd().nextInt(5) + 1;
        int awayShotsOn = awayGoals + rnd().nextInt(5) + 1;
        for (int i = 0; i < homeShotsOn; i++) {
            events.add(buildStatEvent(CSEventType.SHOT_ON_TARGET, home, pickScorer(homePlayers)));
        }
        for (int i = 0; i < awayShotsOn; i++) {
            events.add(buildStatEvent(CSEventType.SHOT_ON_TARGET, away, pickScorer(awayPlayers)));
        }

        // Sutevi van okvira
        int homeShotsOff = rnd().nextInt(6) + 2;
        int awayShotsOff = rnd().nextInt(6) + 2;
        for (int i = 0; i < homeShotsOff; i++) {
            events.add(buildStatEvent(CSEventType.SHOT_OFF_TARGET, home, pickScorer(homePlayers)));
        }
        for (int i = 0; i < awayShotsOff; i++) {
            events.add(buildStatEvent(CSEventType.SHOT_OFF_TARGET, away, pickScorer(awayPlayers)));
        }

        // Korneri
        int homeCorners = rnd().nextInt(10) + 2;
        int awayCorners = rnd().nextInt(10) + 2;
        for (int i = 0; i < homeCorners; i++) {
            events.add(buildStatEvent(CSEventType.CORNER, home, randomPlayer(homePlayers)));
        }
        for (int i = 0; i < awayCorners; i++) {
            events.add(buildStatEvent(CSEventType.CORNER, away, randomPlayer(awayPlayers)));
        }

        int homeFouls = rnd().nextInt(4) + 2;
        int awayFouls = rnd().nextInt(4) + 2;
        for (int i = 0; i < homeFouls; i++) {
            events.add(buildStatEvent(CSEventType.FOUL, home, randomPlayer(homePlayers)));
        }
        for (int i = 0; i < awayFouls; i++) {
            events.add(buildStatEvent(CSEventType.FOUL, away, randomPlayer(awayPlayers)));
        }

        int homeOffsides = rnd().nextInt(3);
        int awayOffsides = rnd().nextInt(3);
        for (int i = 0; i < homeOffsides; i++) {
            events.add(buildStatEvent(CSEventType.OFFSIDE, home, pickScorer(homePlayers)));
        }
        for (int i = 0; i < awayOffsides; i++) {
            events.add(buildStatEvent(CSEventType.OFFSIDE, away, pickScorer(awayPlayers)));
        }

        int homeFreeKicks = rnd().nextInt(3) + 1;
        int awayFreeKicks = rnd().nextInt(3) + 1;
        for (int i = 0; i < homeFreeKicks; i++) {
            events.add(buildStatEvent(CSEventType.FREE_KICK, home, randomPlayer(homePlayers)));
        }
        for (int i = 0; i < awayFreeKicks; i++) {
            events.add(buildStatEvent(CSEventType.FREE_KICK, away, randomPlayer(awayPlayers)));
        }

        // Zuti kartoni
        int homeYellows = rnd().nextInt(4);
        int awayYellows = rnd().nextInt(4);
        for (int i = 0; i < homeYellows; i++) {
            events.add(buildStatEvent(CSEventType.YELLOW_CARD, home, randomPlayer(homePlayers)));
        }
        for (int i = 0; i < awayYellows; i++) {
            events.add(buildStatEvent(CSEventType.YELLOW_CARD, away, randomPlayer(awayPlayers)));
        }

        // Crveni kartoni (retki)
        if (rnd().nextDouble() < 0.08) {
            events.add(buildStatEvent(CSEventType.RED_CARD, home, randomPlayer(homePlayers)));
        }
        if (rnd().nextDouble() < 0.08) {
            events.add(buildStatEvent(CSEventType.RED_CARD, away, randomPlayer(awayPlayers)));
        }

        if (rnd().nextDouble() < 0.12) {
            boolean homeIncident = rnd().nextBoolean();
            CSTeam incidentTeam = homeIncident ? home : away;
            List<CSPlayer> incidentPlayers = homeIncident ? homePlayers : awayPlayers;
            events.add(buildStatEvent(CSEventType.INJURY, incidentTeam, randomPlayer(incidentPlayers)));
        }
        if (rnd().nextDouble() < 0.16) {
            boolean homeIncident = rnd().nextBoolean();
            CSTeam incidentTeam = homeIncident ? home : away;
            List<CSPlayer> incidentPlayers = homeIncident ? homePlayers : awayPlayers;
            events.add(buildStatEvent(CSEventType.VAR_REVIEW, incidentTeam, randomPlayer(incidentPlayers)));
        }
    }

    private CSMatchEvent buildStatEvent(CSEventType type, CSTeam team, CSPlayer player) {
        return CSMatchEvent.builder()
                .minute(rnd().nextInt(90) + 1)
                .eventType(type)
                .playerId(player != null ? player.getId() : null)
                .playerName(player != null ? player.getName() : "?")
                .teamName(team.getName())
                .description(describeStatEvent(type, team.getName(), player != null ? player.getName() : "?"))
                .build();
    }

    private String describeGoal(String teamName, String scorerName, String assistName, String scoreAfter,
                               org.example.footballtextmanager.model.CSGoalType goalType) {
        String assistText = assistName == null || assistName.isBlank() ? "" : " Assist: " + assistName + ".";
        String scoreText = scoreAfter == null || scoreAfter.isBlank() ? "" : " [" + scoreAfter + "]";
        if (goalType == null) {
            return pick(
                    scorerName + " applies the finish for " + teamName + "." + assistText + scoreText,
                    "Goal for " + teamName + ": " + scorerName + " converts the move." + assistText + scoreText,
                    scorerName + " finds the net for " + teamName + "." + assistText + scoreText
            );
        }
        String base = switch (goalType) {
            case HEADER -> pick(
                    scorerName + " wins the aerial battle and heads home for " + teamName,
                    "Powerful header from " + scorerName + " beats the keeper",
                    scorerName + " gets above his marker and guides the header in");
            case VOLLEY -> pick(
                    "Stunning volley from " + scorerName + " — the keeper had no chance",
                    scorerName + " catches the ball on the half-volley and rifles it in",
                    "Sweet volley from " + scorerName + " puts " + teamName + " ahead");
            case TAP_IN -> pick(
                    scorerName + " taps home from close range for " + teamName,
                    "The keeper can only parry and " + scorerName + " is first to react",
                    "Simple finish from " + scorerName + " — all the hard work done by those around him");
            case LONG_RANGE -> pick(
                    scorerName + " lets fly from distance and it flies into the corner",
                    "Audacious effort from " + scorerName + " from outside the area — and it's in",
                    scorerName + " unleashes from 25 yards — keeper rooted to the spot");
            case ONE_ON_ONE -> pick(
                    scorerName + " rounds the keeper and rolls it into an empty net",
                    "Composed finish from " + scorerName + " one-on-one with the goalkeeper",
                    scorerName + " stays cool and slots home after breaking clear");
            case SCREAMER -> pick(
                    "Thunderous strike from " + scorerName + " — absolutely unstoppable",
                    scorerName + " unleashes a screamer from the edge of the box — pure class",
                    "Goal of the season contender from " + scorerName + " for " + teamName);
            case FREE_KICK -> pick(
                    scorerName + " curls a brilliant free kick over the wall and into the corner",
                    "Direct free kick from " + scorerName + " beats the wall and the keeper",
                    "Set-piece quality from " + scorerName + " — the keeper had no answer");
            case POACHERS -> pick(
                    scorerName + " ghosts in at the back post and converts the cross",
                    "Poacher's finish from " + scorerName + " — two yards out, he doesn't miss",
                    "Classic striker's goal from " + scorerName + " — right place, right time");
            case PENALTY -> pick(
                    scorerName + " sends the keeper the wrong way from the spot for " + teamName,
                    "No mistake from " + scorerName + " — the penalty is buried",
                    scorerName + " keeps his nerve and converts the penalty");
            case COUNTER -> pick(
                    scorerName + " finishes off a clinical counter-attack for " + teamName,
                    "Three passes and it's in the net — " + scorerName + " completes the counter",
                    "Devastating on the break — " + scorerName + " slots home after a quick transition");
        };
        return base + "." + assistText + scoreText;
    }

    /**
     * Dodeljuje {@code scoreAfterGoal} svakom GOAL dogadjaju, hodajuci kroz listu u redosledu
     * u kom je teletext prikazan.
     *
     * <p>Uslov je da je lista vec sortirana po minuti. Poziva se tacno jednom, posle
     * {@code events.sort(...)} — vidi napomenu na mestu poziva.
     *
     * <p>Opis se prepisuje sa istim rezultatom. {@code describeGoal} se zove sa {@code null}
     * score-om i zato u opis uopste ne upisuje rezultat; ovde ga se dodaje na kraj, posle
     * eventualnog asista, da bi format ostao isti kao ranije ({@code "..." [2:1]}) i da opis
     * nikad ne kaze jedno a prikazuje drugo.
     */
    private void stampTimelineScores(List<CSMatchEvent> events, String homeName, String awayName) {
        int home = 0;
        int away = 0;

        for (CSMatchEvent e : events) {
            if (e.getEventType() != CSEventType.GOAL) continue;

            if (homeName.equals(e.getTeamName())) home++;
            else if (awayName.equals(e.getTeamName())) away++;
            else continue; // nepoznati tim — ne diramo, da ne upisemo pogresan rezultat

            String score = home + ":" + away;
            e.setScoreAfterGoal(score);

            String description = e.getDescription();
            if (description == null || description.isBlank()) continue;

            // Vec stampiran ovaj gol? Ne moze se desiti sa ovim pozivom, ali opis se
            // prepisuje bezbedno: stari "[h:a]" se uklanja pre dodavanja novog.
            String cleaned = description.replaceAll("\\s*\\[\\d+:\\d+\\]$", "");
            e.setDescription(cleaned + " [" + score + "]");
        }
    }

    private String describeSubstitution(String teamName, String playerOut, String playerIn) {
        return pick(
                teamName + " make a change: " + playerOut + " off, " + playerIn + " on.",
                "Tactical switch for " + teamName + " as " + playerIn + " replaces " + playerOut + ".",
                teamName + " send on " + playerIn + " for " + playerOut + "."
        );
    }

    private String describePenalty(String takerName, String teamName, boolean scored) {
        return scored
                ? pick(
                        takerName + " converts the penalty for " + teamName + ".",
                        "Penalty scored by " + takerName + " for " + teamName + ".",
                        takerName + " keeps his nerve from the spot for " + teamName + "."
                )
                : pick(
                        takerName + " misses the penalty for " + teamName + ".",
                        "Penalty wasted by " + takerName + " for " + teamName + ".",
                        takerName + " fails from the spot for " + teamName + "."
                );
    }

    private String describeStatEvent(CSEventType type, String teamName, String playerName) {
        return switch (type) {
            case SHOT_ON_TARGET -> pick(
                    playerName + " forces a save for " + teamName + ".",
                    teamName + " work a shot on target through " + playerName + ".",
                    playerName + " tests the goalkeeper for " + teamName + "."
            );
            case SHOT_OFF_TARGET -> pick(
                    playerName + " fires wide for " + teamName + ".",
                    teamName + " see " + playerName + " miss the target.",
                    playerName + " cannot keep the effort down for " + teamName + "."
            );
            case CORNER -> pick(
                    "Corner kick to " + teamName + ".",
                    teamName + " win a corner.",
                    "Set-piece chance for " + teamName + "."
            );
            case YELLOW_CARD -> pick(
                    playerName + " goes into the book.",
                    "Yellow card shown to " + playerName + ".",
                    playerName + " is cautioned for " + teamName + "."
            );
            case RED_CARD -> pick(
                    playerName + " is sent off for " + teamName + ".",
                    "Red card for " + playerName + ".",
                    teamName + " are reduced to ten men after " + playerName + " sees red."
            );
            case FOUL -> pick(
                    playerName + " concedes a foul for " + teamName + ".",
                    "Free kick given against " + playerName + ".",
                    playerName + " arrives late and the whistle goes."
            );
            case OFFSIDE -> pick(
                    playerName + " is caught offside.",
                    "The flag goes up against " + playerName + ".",
                    playerName + " strays beyond the last line for " + teamName + "."
            );
            case FREE_KICK -> pick(
                    "Free kick to " + teamName + ".",
                    teamName + " earn a set-piece chance.",
                    "Dead-ball opportunity for " + teamName + "."
            );
            case INJURY -> pick(
                    playerName + " needs treatment.",
                    "Medical staff are called for " + playerName + ".",
                    "There is an injury concern involving " + playerName + "."
            );
            case VAR_REVIEW -> pick(
                    "VAR is checking an incident for " + teamName + ".",
                    "The referee pauses for a VAR review.",
                    "A short VAR delay interrupts play."
            );
            default -> type.name() + " - " + playerName;
        };
    }

    private String pick(String... variants) {
        if (variants == null || variants.length == 0) {
            return "";
        }
        return variants[rnd().nextInt(variants.length)];
    }

    private CSPlayer randomPlayer(List<CSPlayer> players) {
        if (players.isEmpty()) return null;
        return players.get(rnd().nextInt(players.size()));
    }

    /**
     * Zamor nakon meča, proporcionalno odigranim minutima.
     *
     * <p>Prethodno je ovo primenjivalo fiksni plus od 1.5–3.0 <b>samo na početnu jedanaesticu</b>.
     * Dve posledice su bile ozbiljne:
     * <ul>
     *   <li>igrač koji uđe kao zamena nikad nije dobijao zamor, pa je zamena bila besplatno
     *       bolja od starta — igrač sa 0 zamora koji uđe u 70. minutu bio je jači od
     *       fiktivnog 11. igrača;</li>
     *   <li>početna jedanaestica je dobijala neto +0.5 po kolu (teret 2.25, oporavak 1.75),
     *       pa je svaka ekipa do polusezone svih igrača gurla ka plafonu od 10 — zamor je tada
     *       bio konstantan za sve i nije više ništa razlikovao, a oporavak se nije mogao
     *       nadoknaditi izborom postave.</li>
     * </ul>
     *
     * <p>Sada opterećenje zavisi od minuta, a oporavak se <b>zaradjuje odmorom</b>: igrač koji nije
     * igrao se oporavi u potpunosti. To je razlog zbog kog zamena uopšte ima smisla — klupa
     * ostaje sveža, a(startna jedanaestica vremenom umire, i to je cena kontinuiteta.
     *
     * @param minutesByPlayer odigrani minuti po igraču; igrač sa 0 minuta je odmaro
     */
    private void updateFatigueAfterMatch(List<CSPlayer> players, Map<Long, Integer> minutesByPlayer) {
        for (CSPlayer p : players) {
            int minutes = minutesByPlayer.getOrDefault(p.getId(), 0);
            if (minutes > 0) {
                double load = (0.9 + rnd().nextDouble() * 0.9) * (minutes / 90.0);
                p.setFatigue(Math.min(10.0, p.getFatigue() + load));

                // Forma se blago menja
                double formChange = (rnd().nextDouble() - 0.5) * 1.0;
                p.setForm(Math.max(1.0, Math.min(10.0, p.getForm() + formChange)));
            } else {
                // Nije igrao — pun odmor, dovoljan da se svaki nagomiljani zamor obriše.
                p.setFatigue(Math.max(0.0, p.getFatigue() - (2.5 + rnd().nextDouble() * 1.5)));
            }
        }
    }
}
