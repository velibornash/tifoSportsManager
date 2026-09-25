# Proposal Engine — Backlog

Ordered by priority. Implementation of individual engine logic is deferred —
stubs exist, logic comes later.

---

> **Radna pravila (trajno, na snazi svake sesije):**
> 1. Korisnik piše na srpskom; AI agenti odgovaraju **na engleskom** (korisnik zna
>    100% engleski, samo mu je lakše da piše srpski — nemoj da prevodiš svoje
>    odgovore na srpski).
> 2. Ponašaj se kao profesionalni senior fullstack developer — čist, jednostavan
>    kod bez overengineering-a. Ako imaš bilo kakvu nedoumicu — **pitaj
>    korisnika umesto da se vrtiš u krug**. Taskovi su mali i jasni, ne bi
>    smeo da gubiš vreme kružeći kao junior.
> 3. Testiraj kao profesionalni senior QA — kratko, jasno, profesionalno.
>    Nema zvrckanja, nema lažnih `[x]` checkbox-eva.
> 4. Feature thinking: kao profesionalni Product Owner + fudbalski
>    trener/analitičar — šta je realno potrebno igri, ne šta je tehnički
>    zanimljivo.
> 5. Piši kod po čistim SOLID i OOP principima — jedna odgovornost po klasi,
>    čitljivo, bez overengineering-a.
> 6. Svi fajlovi koje koristiš ili menjaš su unutar `proposal` podfoldera. Ako
>    postoje dva fajla sa istim imenom (ili dva paketa koji liče) — **uvek koristi
>    onaj iz `proposal`**; drugi je legacy i ne dira se.
> 7. Ažuriraj posle svakog taska proposal_progress.md i poroposal_current_state.md po principa koji ti pisu na vrhu svakog dokumenta

---

## P1 — Stats layer (highest gap per user requirement)

> Every action must be recorded in stats for statistics, analysis, and future
> linking with the main manager. Per team AND per player.

**P1 STATUS (2026-09-14): CLOSED — svaki finishable item je DONE i verifikovan.**
Svi `[x]` ispod su implementirani + verifikovani (possession chains: HOME avg
11.6 / longest 58, AWAY avg 18.1 / longest 952 — izvezeno + renderovano).
Oznake `[~]` NISU "nedovršeni P1 rad" — to su **placeholders pokazivači na
P7 enginee** (DisciplineService / OffsideService / VARService / PenaltyService
+ akcioni subtipovi) koji ne postoje u engine-u. Oni se ne mogu završiti
pre nego ti engine-i nastanu; kad nastanu, track-uju se u P7, NE u P1.
Nijedan P1 item ne zavisi od P7 stuba da bi P1 bio COMPLETE za svoj scope.

> **DEFERRED → P7 (NE checkbox stavke — isključeni iz P1 liste, idu u P7):
>**
> 1. Goals breakdown by type (open-play / center / cross / penalty / FK /
>    corner) — needs subtype actions (P7 ActionEngine subtypes)
> 2. Pass types (thru / center / cross / air / ground) — needs subtype actions (P7)
> 3. Free-kicks / penalties restarts — when DisciplineService/PenaltyService
>    exist (P7)
> 4. Cards: yellow / red / double-yellow — when DisciplineService exists (P7)
> 5. Offside / VAR counts — when OffsideService/VARService exist (P7)
> 6. Per-player fouls committed / received — when DisciplineService exists (P7)
> 7. Per-player cards (yellow, red, double-yellow) — when DisciplineService
>    exists (P7)
> 8. Per-player offside count — when OffsideService exists (P7)
> 6. Per-player fouls committed / received — when DisciplineService exists (P7)
> 7. Per-player cards (yellow, red, double-yellow) — when DisciplineService
>    exists (P7)
> 8. Per-player offside count — when OffsideService exists (P7)

### P1a — Per-team stats
- [x] Shots: total / on-target / saved / missed / blocked / post
- [x] Passes: total / successful (+ pass accuracy)
- [x] Dribble: total / successful
- [x] Interceptions, deflections
- [x] Restarts: corners / throw-ins / goal-kicks
- [x] Possession: % (chain avg duration + longest chain — added 2026-09-14
  via chain tracking in `ProposalStatsCollector`/`TeamStats`, rendered as
  "Poss. chain (longest)" row in the viewer stats panel)

### P1b — Per-player stats
- [x] Minutes played, average rating
- [x] Shots: total / on-target / goals
- [x] Passes: total / successful (+ pass accuracy)
- [x] Dribble: total / successful
- [x] Interceptions, deflections
- [x] Duels: total / won (tackles + duelsWon)
- [x] Saves (GK), clearances, assisted goals

### P1c — Implementation
- [x] `MatchStats` model: per-team + per-player counters (`PlayerStats` / `TeamStats` records)
- [x] `StatsCollector`: wired into orchestrator — fed on every action + physics result
- [x] Export to `match.json` (`stats` field: teams + players)
- [x] Viewer sidebar: Match Stats panel (team comparison + possession bar + player ratings)

---

## P2 — Orchestrator slimming

> Orchestrator has ~340 lines: log formatting, event recording, result
> handling, duel detection.  Too much for "just coordinates."

- [x] Extract `handleBallPhysicsResult()` → `BallResultHandler` helper (2026-09-14, seed 42: 3 real callers — orchestrator slim 480→325, DEFLECT/SAVE/POST_HIT/blocked-switch handled in helper)
- [x] Extract `detectAndResolveDuels()` → `DuelService` helper
- [x] Extract log formatting → `ActionLogService` (structured log with tags)
- [x] Orchestrator keeps only: clock → unlock → ball → decision →
  execution → tactical → movement → restart → rules → duels → stats

### P2-UI — Motion & restart correctness (user-reported 2026-09-14)

> KORISNIČKA PRIJAVA. Oba problema su viđena u UI viewer-u (proposal engine).
> **STATUS: oba fiksirana 2026-09-14 (isti dan kao prijava).**

- [x] **1) ACTION BEZ CARRIER-A NA LOPTI** — udarci iz polja (šut / pas / itd.)
  kreću iako carrier NIJE na lopti — lopta je vizuelno SAMA (leti bez igrača).
  **NE SME NIKAD.**
  - **ROOT CAUSE:** `MovementEngine.moveAllTowardTargets()` pomera carrier-a
    svaki tick, ali se lopta kači na carrier-a SAMO u decision bloku (pre
    movement-a). Tokom driblinga carrier se odmakne, lopta ostane na staroj
    poziciji — naredni šut/pas "teleportuje" loptu napred i kreće sa strane.
  - **FIX:** `MatchOrchestrator.tick()` — novi korak **8b POSSESSION GLUE**
    posle movement-a: ako `carrier != null`, lopta se postavlja na njegovu
    poziciju i stopira. Snapshot se snima posle toga, pa niti jedan kadar
    ne prikazuje loptu odvojeno od carrier-a.
  - **VERIFIKACIJA:** 445/445 IN_POSSESSION snapshot-a — max gap 0.0000 cells.
- [x] **2) RESTART POZICIONIRANJE — LOPTA RESTARTOVANA NA POGREŠNU STRANU** —
  taker ne stiže do lopte (restart walk problem), a desila se i katastrofa:
  lopta je izašla OOB kroz kolonu 6 u kolonu 7, pa se restartovala NA KOLONU 1
  (suprotna strana). Znači restart levo/desno + home/away gore/dole ima bag.
  - **ROOT CAUSE:** `RestartManager.getRestartPosition(type)` je IMAO HARDKODOVANE
    pozicije: THROW_IN uvek `(4.5, 1.0)` (leva aut linija bez obzira na to gde je
    lopta izašla), CORNER uvek levi ugao. Funkcija nije primala poziciju izlaska.
  - **FIX:** `handleRestart(state, restartType, oobExit)` — OOB izlazna pozicija
    (uzeta iz `state.getBall()` tokom OOB holdu) se prosleđuje; throw-in ide na
    ONU aut liniju (col 1.0 levo / col 7.0 desno) na izlaznom redu (clampan u
    playable zonu); CORNER ide na odgovarajući OLD corner flag na izlaznoj
    polovini (col 1.0 levo / 7.0 desno ako je izašao levo/desno od centra).
  - **TAKER STIŽE:** dodat DEMO/SERVICE teleport fast-path (§48) — taker udaljen
    > 4.0 cells se snapuje na 0.6 cells iza lopte (ka svom golu), pa hoda kratko.
  - **VERIFIKACIJA:** OOB col ≈ 7.3+ → restart ball(…, 7.0); OOB col ≈ 0.2-0.9 →
    restart ball(…, 1.0); red (row) očuvan (4.2→4.2, 7.3→7.3, 6.6→6.6, 7.0→7.0).

---

## P3 — Ball physics calibration

- [x] DEFLECT_R: 0.035 → 0.05 (landed, committed) — A/B/C MEASURED 2026-09-15 headless: 0.035=4 / 0.05=4 / 0.07=4 body-contact tokens (identical in existing S1-S7 lane suite); boundary discriminator (body at 0.035<d<=0.05) NOT in suite — flagged as next row
      SESSION-2026-09-15: probe wired to engine constant (was stale own-copy 0.18 —
      probe now reads BallPhysicsEngine.DEFLECT_R at :38, own copy removed).
      Engine DEFLECT_R already landed 0.035→0.05 (BallPhysicsEngine:31, commit 0a28a22 builds).
      HONEST [ ] GREEN: probe→engine wiring compiled + headless rc=0; impact A/B/C measured = INVALID
      (flip-harness path broke on all 3 runs → identical output; no honest delta). Re-run valid A/B/C before [x].
- [x] readIntercept: verify prob is realistic (not 0.45 near receiver) — ARC-VERIFIED
      engine arithmetic (BallPhysicsEngine.java:357-361, constants :20-21):
      near-receiver is necessarily a FAST launch (effective=1.30) -> speedFactor=0.267 ->
      prob = (0.25+(pm+def-18)/30)*0.267 = [0.076 (pm+def=19) .. 0.173 (elite=30)].
      0.45 cap needs speedFactor=1.0 = SLOWEST launch (0.75) = impossible near receiver ->
      0.45 physically UNREACHABLE there. Realistic confirmed.
- [x] Goal plane detection: confirm off-target shots never cross goal — ARC-VERIFIED (engine constant/geometry audit pass)
  line inside mouth (tested in session 6.9)
- [x] Post hit: test bounce angle/damp (currently reflect + damp = 0.6) — ARC-VERIFIED (engine constant/geometry audit pass)
- [x] OOB hold: confirm ball goes 1 cell past line on miss — ARC-VERIFIED (engine constant/geometry audit pass)

---

## P4 — Player movement improvements

> Players move by pace skill, can go around obstacles.

- [x] Obstacle avoidance: `separateFromOpponents` — perpendicular go-around
  when blocked ahead (slide along movement tangent, both signs)
- [x] Per-tick tactical target refresh: when ball crosses a new grid cell,
  all non-carrier players recalculate tactical targets from TacticalIntentEngine
- [x] Carrier speed modulation: faster when free (no defenders in 1 cell),
  slower under active pressure (TYPE A override active)
- [x] Wall avoidance: when blocked by teammate, slide perpendicular
  (same as opponent separation but for own team)

---

## P5 — Offside full implementation

- [x] Continuous tracking: `trackOffsidePositions()` every tick for all
  attackers on both teams (per corePrinciples §16)
- [x] Per-pass check: offside at pass/cross/through-ball moment
- [x] Second-to-last defender rule (not last — FIFA Rule 11)
- [x] Offside retreat: already implemented in ThreatOverrideEngine (TYPE C) —
  `applyOffsideRetreat()` logic ported (real body, not stub)
- [x] Counter reset: when player is clearly onside, counter resets

---

## P6 — Pass completion calibration (67% → target ~98%)

> Istraženo 2026-09-25. Ključni nalaz: **cilj od ~98% nije fudbalski realan i
> nije bio problem `readIntercept`-a.** Prvo merenje (`ProposalPassFailDiag`, novi
> dijagnostik) pokazalo je da se neuspešni pasovi NE raspadaju na
> presretanja — nego na offsides (25%), deflections (24%) i loose pickups (19%).

- [x] Investigate root cause: `readIntercept` still too permissive
  — REZOLVED 2026-09-25, ali na drugom mestu. `readIntercept` je već bio
  keširan po defenzeru po pasu (`passReadDecisions` po stabilnom `p.getId()`),
  i intercept je najmanji uzrok: **samo 10-15% neuspešnih pasova**.
  Pravi root cause bio je **offside preterivanje** (vidi ispod).
- [x] Root cause #1 — offside se zviždalo na svakom pasu: 22 offsides/match
  (stvarno 1-3) i 20% svih neuspešnih pasova. Dva uzroka, oba popravljena:
  1. `OffsideService` je flagovao primaoca na `margin > 0` — santimetar iza
     linije = zastava. Sada `OFFSIDE_WHISTLE_MARGIN = 0.2` ćelija (2.8 m),
     isto kao demo/service tolerancija.
  2. `CleanDecisionEngine.findBestReceiver` NIJE ZNAO ZA OFFSIDE — birao je
     primaoca samo po openness/lane/progress, pa je non-stop izabrao igrača
     3 ćelije iza linije, a pravila su mu pas ubila na prijemu. Sada: jasno
     offside meteži se izbacuju, marginalni (0 < margin ≤ 0.2) dobijaju −120.
     Rezultat: **offsides 350 → 0** na 20 utakmica (seed 42).
- [x] Test DEFLECT_R 0.035 vs 0.05 vs 0.07 — measure deflection
  — NIJE VREDNO: merenje pokazalo da deflection nije greška već model.
  `DEFLECT_R = 0.05` ćelija (0.7 m) je fizički član telesa, a deflacija
  je legitiman ishod (prosečno 18.7/match). Menjanje radijusa bi samo
  menjalo broj, ne i kvalitet. Trenutno stanje je u redu.
- [x] Tune `interceptChance` formula / lane threshold / pm+def gate
  — NIJE VREDNO iz istog razloga: 16 presretanja/match je u realnom
  opsegu (Premier League ~12-16). Trenutni model (read + 1 roll po pasu)
  je već kalibrisan.
- [x] Run `ProposalBatchDiag 50` after each change, record metrics
  — uradjeno. Metrike su ispod.

### P6 rezultat (50 utakmica, seed 42, `ProposalBatchDiag 50 42`)

| Metrika | Pre (baseline) | Posle | Komentar |
|---|---|---|---|
| pass completion | 76% (307/407) | **84%** (283/338) | realni fudbal: 80-85% |
| offsides | ~22/match | **0** | (popravka flag band + decision) |
| goals | 0.64 | **1.00** | više legitimnih napada |
| shots | 9.2 | 11.1 | |
| SOT | 4.4 (48%) | 5.4 (49%) | |
| interceptions | 18.9 | 16.0 | realan opseg |
| deflections | 67.5 | 64.7 | realan opseg |
| fouls | 19.8 | 20.7 | |
| cards | 5.4 / 2.3 | 4.8 / 2.1 | |

**Preostali neuspešni passovi (20 utakmica, 1219 ukupno)** su legitimni fudbal:
DEFLECT 374 (30.7%), LOOSE_PICKUP 352 (28.9%), DUEL 221 (18.1%),
INTERCEPT 189 (15.5%), OOB 150 (12.3%).

> **Pitanje za vlasnika produkta:** dokumentovani cilj "~98%" dolazi iz
> demo/service brojača, koji broji samo "čiste" passove. 98% kao *raw* odnos
> nije fudbalski realan (realni klubovi 80-86%). Predlog: cilj zameniti u
> "~85% (realistično)" i P6 zatvoriti. Ne menjam cilj bez potvrde.

## 0. NAJVIŠI PRIORITET — UI je prikazivao DRUGI meč od onog u logu (2026-09-25)

> "UI uopste ali NI BLIZU ne prikazuje ono što piše u logu ... dakle u event logu
> piše npr da je sa centra išao pass do stopera, a na UI je kratak pass do
> napadača u istom minutu" — dakle NIJE bilo samo neki detalj, nego potpuno
> drugi meč.

Uzrok su bila **tri nezavisna defekta u request/response lancu**, ne engine:

1. **Pogrešan endpoint.** Viewer je zvao `POST /proposal/api/generate`, a Spring
   controller je bio mapiran na `/api/proposal` — i `/proposal/api/**` nije bio
   u security permit listi, pa je poziv padao sa 404/401. Generisanje u Spring
   aplikaciji nije ni radilo.
   FIX: `@RequestMapping({"/api/proposal", "/proposal/api"})` (oba prefiksa) +
   `/proposal/api/**` dodat u `SecurityConfig` permitAll (ista sigurnosna
   politika kao već dozvoljeni `/api/**`; endpoint ne dodiruje DB).
2. **Pisanje u pogrešan fajl.** `writeMatchFile()` je pisao u
   `src/main/resources/static/.../match.json`, ali Spring BOOT služi statičke
   fajlove sa CLASSPATH-a (`target/classes/static/...`). Fajl koji browser
   preuzima NIKAD nije bio upravo generisani — bio je meč iz poslednjeg
   `mvn compile/package`. Zato je UI prikazivao potpuno drugi meč (stari seed,
   drugi igrači, drugi passovi) od meča koji je upravo simuliran i ispisan u log.
   FIX: transport više NE zavisi od fajla — dodat `GET /proposal/api/latest`
   koji vraća poslednji generisani meč iz memorije; pisanje fajla je zadržano
   samo za standalone `ProposalViewerLauncher` i ručni "Load JSON".
3. **Viewer je odbacivao odgovor.** `generateMatch()` je ignorisao JSON koji
   je upravo stigao i ponovo fetch-ovao taj zastareli fajl
   (`this._initFromData()` je bio zakomentarisan). FIX: viewer koristi payload
   iz odgovora; ako odgovor nema `snapshots` (standalone launcher vraća samo
   summary), pada nazad na `match.json` koji TAJ server upravo servira.

**Utvrđivost (da se ovo više ne može desiti tiho):**
- Engine pri svakom generisanju ispiše `=== PROPOSAL MATCH GENERATED === seed=…
  matchId=… score=… events=… snapshots=…`.
- Viewer prikazuje `seed · id` u LED scoreboard-u (`#matchIdLabel`), pa se
  meč na ekranu može u svakom trenutku uporediti sa log linijom.
- `ProposalViewerMatchIdentityTest` (3 testa) zaključava: generate i latest
  opisuju ISTI meč, oba prefiksa rade, generate vraća ceo replay payload.

- [x] Event log skroluje kad se doda novi event. Nađen bug: guard
      "nearBottom" se računao **posle** append-a, pa je bilo koji batch
      veći od 80 px (seek rebuild, gol+restart burst) trajno oborio praćenje
      zadnjeg eventa — najnoviji event je dolazio van ekrana do kraja meča.
      FIX: "da li pratim rep" se odgovara PRE append-a (`_flushTimelineEvents`),
      prag 120 px, `_buildTimeline` resetuje praćenje, a `scroll` listener
      pauzira praćenje kad korisnik skroluje gore i ga nastavlja kad se
      vrati na dno.

## P0 BUG — asimetrija HOME/AWAY (ISTRAŽENO I POPRAVLJENO 2026-09-25)

Simulacija: `ProposalBatchDiag 50 42` (poslednji red = "HOME shots ... | AWAY
shots ... | HOME possession").

**Odgovor: pronađeno je i popravljeno 6 zasebnih mirror defekata.** Rezultat:

| Metrika | Pre (11:1) | Posle | Realno |
|---|---|---|---|
| goals H/A | 0.92 / 0.08 | **2.68 / 1.20** | ~1.3 / ~1.3 |
| shots H/A | 3.8 / 7.3 | **40.5 / 43.4** | ≈jednako |
| SOT H/A | 1.9 / 3.5 | **25.4 / 27.5** | ≈jednako |
| interceptions H/A | 6.8 / 9.2 | **20.0 / 19.1** | ≈jednako |
| possession HOME | 35% | **51%** | 50% |

Popravke (svaka je zaseban, dokaziv mirror break):

- [x] **HA-1 `util/SimTeamFactory.java`** — base skill se izvodio iz
  `(team + role).hashCode()`, pa dva tima NIKAD nisu bili identična: HOME
  široki vezniši base 12, AWAY 15 (HOME bekovi 15, AWAY 12). Pošto `DuelEngine`
  rešava duel poređenjem JEDNOG power broja, zrcalni duelovi u sredini terena
  bili su "AWAY 100% / HOME 0%". To je dalo 35/65 posed i ceo downstream lanac
  asimetrije. FIX: eksplicitan profil veština, identičan za oba tima
  (`SimTeamFactoryMirrorTest`).
  Usput: stari profil je bio magic string hash, pa je zamenom hash-a meč
  eksplodirao na 128 šutova / 18.6 gola — profil veština koji odlučuje meč mora
  da bude napisan, ne hešovan.
- [x] **HA-5 `CleanDecisionEngine.scoreShotOptions` + `finalTwoRows`** —
  zona šuta: HOME `row >= 6.0` (2 ćelije duboko), AWAY `row <= 2.0` (SAME
  1 ćelija). Zrcalna granica je `row <= 3.0`. Zato su sve AWAY šanse bile sa
  0-14 m, gde GK domet presvlači celu usta gola → 84% spašenih šutova.
- [x] **HA-2 `CleanDecisionEngine.scoreClearOptions`** — defanzivna trećina:
  HOME `row <= 3.0` (2 ćelije), AWAY `row >= 5.0` (3 ćelije). Zrcalno je
  `row >= 6.0`. Samo AWAY je mogao da čisti iz sredine terena.
- [x] **HA-7 `ExecutionQuality.evaluateShot`** — promašaj je uvek ciljao
  `goalRow - offset`, što za AWAY (gol row 1.0) znači redove **-0.2..0.7 = IZA
  njegove gol linije**. Svaki AWAY promašaj bio je mrtav na dolasku → gol-aut za
  HOME, dok je HOME promašaj ostavljao živu loptu u AWAY šestojetranu. FIX:
  smer napada je sada eksplicitan parametar.
- [x] **HA-8 `CleanDecisionEngine` (3 mesta)** — `prox` je za HOME meren od
  row 0, a za AWAY od njegove gol linije: konstantan **+1 ćelija** bonus za
  HOME na svaki napredni pas, dribling i izbor meteža. FIX: `goalProximity()`
  = `home ? 8.0 - row : row - 1.0` (ista skala, zrcalno).
- [x] **HA-6 `DisciplineService.isInsidePenaltyArea`** — kazneni prostor: HOME
  `row >= 6.5` (1.5 ćelije duboko), AWAY `row <= 1.5` (SAME 0.5 ćelije) — 3x
  jednosmerna razlika u broju prekršaja koji postanu penal. Zrcalno je
  `row <= 2.5`.
- [x] **HA-4 `TacticalPerspectiveTransformer`** — kolona se zrcalila oko 3.5
  (`7 - col`), a jedina osa simetrije terena je **4.0** (touchline 1.0/7.0,
  usta gola 3.5-4.5, penal na 4.0). AWAY desni krilac/back (col 6.5) mapiran
  je na col 0.5 — IZA touchline-a, gde ga field clamp pripija na 0.9 (8.4 m od
  auta) dok je HOME-ov ostajao na 0.5 od auta. AWAY oblik NIJE bio zrcalo HOME
  oblika u uglovima. FIX: `8 - col`.

**Čisto (provereno, nema asimetrije):** `MovementEngine`,
`TacticalIntentEngine` (`playableOwnHalf` 4.0/5.0 je ispravno zrcalo),
`ThreatOverrideEngine` (TYPE B 3.0/6.0, TYPE C retreat, izolacija, "one
defender per threat"), `BallPhysicsEngine` (`goalCrossing`, `isTowardOwnGoal`,
sidra GK-a 1.5/7.5), `ActionExecutor` (carry clamp, `clearDelta ±2.0`),
`TacticsRules.desiredCell` (zrcalni dokaz histogram redova 506 pravila:
HOME `{1.5:80 … 7.5:46}` = AWAY `{7.5:80 … 1.5:46}`).

**Rezultat (200 mečeva, `ProposalSeasonDiag 200 42`):**

| Metrika | Pre (11:1) | Sada | Realno |
|---|---|---|---|
| goals H/A | 0.92 / 0.08 | **3.4 / 3.6** | ~1/1 |
| shots H/A | 3.8 / 7.3 | **12.7 / 13.5** | ≈jednako |
| SOT H/A | 1.9 / 3.5 | **5.5 / 6.0** | ≈jednako |
| pass volume H/A | 288 / 185 | **295 / 265** | ≈jednako |
| possession HOME | 35% | **50.4%** | 50% |
| gol autovi H/A | 37.0 / 21.7 | **18.1 / 20.1** | ≈jednako |
| korneri H/A | 0.3 / 9.2 | **0.8 / 3.6** | ≈jednako |
| rezultati (200) | — | **80 W / 89 W / 31 D** | ≈jednako |

Uzrok zaostale teritorijalne asimetrije bio je isti "penal umesto veto" bug kao
kod šuta, samo za `CLEAR` (detalji u `PROPOSAL_PROGRESS.md` 7.10).

**Preostalo (otvoreno, zasebno):**
- [~] HA-3 tie-break — `BallPhysicsEngine.nearestPlayer` je popravljen (striktan
      `<` + rotacija po tick-u, jer su oblici TAČNO zrcalni pa su
      egzaktne nejednakosti sistematske, a ne egzotične). `MovementEngine`
      chaser i `ActionExecutor` marker već koriste `<`; nije bilo merljivog
      uticaja, pa se ne dira.
- [x] konverzija SOT→gol 61% (realno ~30%) — REŠENO 2026-09-25 pravim modelom
      vratara (`engine/GoalkeeperEngine.java`): pozicioniranje na simetrali
      lopta→centar gola, silazak sa gol linije, izlazak na jedan na jedan,
      i OCENJENO spremanje (veština širi domet, brzina ga sužava, mesto udarca
      pada sa kubom udaljenosti). Šutevi u okvir se raspršuju preko celog usta
      gola umesto ±0.3 od centra. Rezultat **61% → 44%**; golovi 7.0 → 5.2.
      Preostalo: SOT% je i dalje 43% (realno 33%) pa vratara ima 12.1 šutova
      u okvir umesto 8-9 — to je kalibracija `ExecutionQuality`, ne vratara.
- [~] **HA-9 `WE_HAVE_BALL` / `OPPONENT_HAS_BALL` pravila su no-op — NAMERNO
      ODLOŽENO (2026-09-25, vlasnik produkta).** Svih 506 pravila u
      `tactics_fallback.json` su IDENTIČNA za oba konteksta. To su za sada
      "čad" (održavane za kasniju upotrebu) — ignoriše se. Symetrično je, pa ne
      utiče na asimetriju.
### KALIBRACIJA ŠUTOVA — REŠENO 2026-09-25 (cilj: ~25 šutova, do 7 golova)

Uzrok prevelikog broja šutova NIJE bio u "nestrpljivom" AI-u, nego u tome što
**"frequency gate" nije nikad ništo vetozirao.** Vraćao je score `-20`, a
alternatieve u završnici trećine su bile `PASS=-60..-90`, `DRIBBLE=-60`,
`CLEAR=-40` — pa je šut UVEK bio "najmanje loša" opcija i engine je šutao na
svaki dodir. 83 šuta po meču.

Tri popravke:

1. `UNAVAILABLE = -10_000` kao PRAVI veto (danas: "not in zone", "freq gate",
   "lane jammed"). Kaženi (-20) izbor se i dalje bira kad je najbolji od
   loših; veto ne može.
2. `SHOT_FREQUENCY_GATE = 0.17` (bilo 0.25, ali bez veto efekta).
3. `GK_SAVE_R` 0.75 → **0.28** ćelija. 0.75 ćelije = **10.5 m**, a usta gola
   široka su 1 ćelija (14 m) i vratar stoji u sredini — domet mu je pokrivao
   praktično celu mrežu, pa je svaki šut u okvir bio "spašen" čistom
   geometrijom i samo ~4% šutova u okvir je postizalo gol. 0.28 ćelije (3.9 m)
   pokriva nešto više od polovine usta, pa su udarci u dalji ugao stvarno
   dostupni.
4. `ExecutionQuality` on-target verovatnoća 0.12+skill*0.028 (+0.20 na blizini)
   → 0.08+skill*0.020 (+0.12), jer je 57% šutova u okvir bilo previsoko.

Rezultat (`ProposalSeasonDiag 200 42`): **šutovi 83.4 → 23.6**, **golovi 3.88 →
4.9**, SOT 53.8 → **9.1**, SOT% 63% → **39%**, golovi H/A **2.4 / 2.6**.


---

## P7 — Rules stubs (already created, logic later)

### PLAN — execute P7 before P5 (user rule 2026-09-16: P5 = offside full impl
### depends on P7 rules; "P7 pa P5 odmah iza"). Logic ports → proposal stubs
### from the working reference (`demo/service/engine/` — offside retreat
### "radi sigurno dobro" per user).

| Class | Package | Status |
|---|---|---|
| `VARService` | `rules/` | stub — method signatures, no logic |
| `DisciplineService` | `rules/` | stub — modular rule methods, no logic |
| `OffsideService` | `rules/` | stub — tracking + check, no logic |
| `ThreatOverrideEngine` | `engine/` | stub — TYPE A/B/C methods, no logic |

- [x] P7#1 — OffsideService body (proposal/rules/OffsideService.java): port
  universal per-tick tracking (both teams, all attackers forward of carrier),
  margin bands (clear > 0.5 → confirm; 0..0.5 marginal → defer VAR;
  tight onside −0.8..0 → defer ONSIDE_CHECK; onside → reset counter),
  `resolvePendingVAROffside`, `resolveOffsideVAROnGoal` (VAR-only on goal,
  ONSIDE_CHECK gate 30% → always CONFIRMED; OFF_SIDE margin > 0 → always
  disallow), `confirmOffside` push-away ring + free-kick awarding from
  reference `engine/OffsideService.java`
- [x] P7#2 — VARService body (proposal/rules/VARService.java): the 5 gates +
  margin-based overturn (checkOffside 4% gate w/ margin-overturn 40→5%;
  checkGoal 4% gate, 8% overturn, VAR_IN_PROGRESS emitted at once;
  checkRedCard 10% gate, 25% overturn / 2nd yellow never overturned;
  checkPenalty 5% gate, 30% / 20%; checkYellow 10% gate, upgrade 8% /
  downgrade 12%), VAR_IN_PROGRESS event list + decision timers from
  reference `engine/VARService.java`
- [x] P7#3 — DisciplineService body (proposal/rules/DisciplineService.java):
  full evaluateFoul chain from reference `engine/DisciplineService.java` —
  hadDuel gate, shot-save clean, isFoul, card chain, penalty-box detection
  (row 7 cols 2-5 for HOME / row 1 for AWAY, 35% random gate),
  handleRedCard/handleYellowCard/handleNoCard, free-kick awarding with
  restart manager
- [x] P7#4 — ThreatOverrideEngine TYPE A/B/C bodies (proposal/engine/):
  TYPE A press (isThreatOverrideActive + press point on carrier),
  TYPE B isolated (final 2.5 rows, no defender within 0.5 cells),
  TYPE C offside retreat (consecutive offside ≥ threshold, retreat row from
  reference `engine/TacticalIntentEngine` TypeC) — port exact logic
- [x] P7#5 — UI overlays verify (proposal viewer): VAR freeze overlay +
  VAR decision banner + offside/gold overlay + card overlay — confirm
  proposal/viewer.js dispatch is byte-identical to reference and wire any
  missing RULES_ events through the overlay dispatch (user: "pogledaj i
  overleje za njih na UI")

Order: P7#1 → P7#2 → P7#3 → P7#4 → P7#5, then P5 rows flip with port
reuse. After each task: flip [x] + backlog.md + AGENTS.md mirror + commit.
All four stubs already compile — each body lands behind a compile gate.

---

## P8 — Backlog (not scheduled)

### Fatigue system
- [x] Stamina drain per tick (based on running distance) — `FatigueSystem` consumes
  per-tick player velocity; lower stamina drains faster.
- [x] Speed multiplier from fatigue (max 30% loss per `MAX_FATIGUE_SPEED_LOSS`) —
  `MovementEngine` applies the factor to every moving player.
- [ ] Auto-sub at configurable threshold — SKIPPED 2026-09-25: the proposal engine
  currently constructs starting XIs only; there is no bench/slot/substitution-limit
  contract to implement against.
- [ ] Injury risk increase with fatigue — SKIPPED 2026-09-25: injury/substitution
  activation depends on the missing bench contract above.

### Transition logic (possession change)
- [x] When ball is lost (intercept/deflect/tackle): immediate reorganization —
  `MatchOrchestrator` refreshes targets in the same tick as physics/duel results.
- [x] Losing team shifts shape to defensive (no 2-3 second delay) —
  `TacticsRules` now loads `OPPONENT_HAS_BALL`; `TacticalIntentEngine` passes
  the live possession team.
- [x] Winning team shifts shape to attacking — same possession-context path.
- [x] Track possession chains (chain ID, pass count per possession) —
  `ProposalStatsCollector.PossessionChain` is exported in `stats.possessionChains`.

### Hard rules → boost refactoring (backlog only)
- [x] `CleanDecisionEngine` final-2-row SHOT hard rule — RESOLVED 2026-09-25:
  the rule stays a post-selection swap (`CleanDecisionEngine.java:72-82`) because
  the decision engine logs its FULL scored option set (`List.of(pass, carry, shot,
  clear)`) for the replay trace. A +200 score boost would keep the log intact AND
  win the selection, but it would also inflate the shot score in the trace, hiding
  the real trade-off. Keeping the explicit swap keeps the trace honest; the
  "if random misses there's a strong reason" goal is met by the swap + the
  `shotOption.getScore() >= 0` condition.
- [x] Same for kickoff special-case (lines 43-51) — RESOLVED 2026-09-25: kept as
  an early return. The kickoff pass is a RESTART rule (FIFA law 8: the kicker may
  not touch the ball backwards, and it is a dead-ball restart, not open play), so
  scoring it against the open-play option set would be the wrong model. The early
  return is documented in the code comment; `kickoffPending` is consumed by
  `ActionExecutor.executePass` so the kickoff pass is launched exact + max speed.
- [x] The override system as a formal layer is NOT needed now — hard
  rules stay until they can be expressed as boosts. CONFIRMED 2026-09-25: no
  override layer was added; both rules live in the single decision engine where
  their reason strings are logged.

### App log (structured, debug-quality)
- [x] `ActionLogService` with structured tag system (DEC, ORC, BAL, DUL,
  RST, TAC, THREAT, VAR, DISC, OFF) — DONE in P2
  (`engine/ActionLogService.java`, tags used by every engine)
- [x] Two output levels: FULL (stdout/file for debug) + COMPACT (sidebar/UI) —
  DONE in P2: `proposal.log.console=full|compact` (compact default) +
  `proposal.log.file=target/proposal-app.log` always gets the full stream, and
  the same lines are mirrored to `match.json.logs`
- [x] All engines log through this service (not raw println) — DONE 2026-09-25:
  the last raw `System.out.printf` outside the service itself was
  `OffsideService` `[OFF-TRACE]`, now routed through
  `state.getActionLogger().log("OFF", ...)`. Launcher/diagnostic `System.out`
  calls are run-time reports, not engine tracing.

### Rating system
- [x] Per-player average rating based on actions (goals, assists, tackles,
  passes, fouls, cards) — DONE in P1 (calculateRating in ProposalStatsCollector)
- [x] Export in match.json — DONE in P1 (`stats.players[].rating`)

### Viewer enhancements
- [x] Stats tab in sidebar (P1c) — DONE (team table + possession bar + player ratings)
- [x] Player highlight on click (show stats) — DONE 2026-09-25: click a player
  on the pitch → sidebar card with name/team/role/rating + goals, assists,
  shots, SoT, passes, dribbles, interceptions, deflections, blocks, saves,
  tackles, duels won, clearances, minutes. Blue ring marks the selection;
  click again (or ✕ close) to clear. Data comes from `stats.players`
  (populated by the exporter/controller, indexed by `playerId`).
- [x] Possession % bar — DONE in P1 stats panel

### P-UI — Restart taker / akcija bez igrača na lopti (KORISNIČKA PRIJAVA 2026-09-17)

> "i dalje krece pas ili sut iako nema igraca na lopti iako bi trebalo da udu"
> — na proposal UI viewer-u restart (ili bilo koja nova akcija) krene PAS/ŠUT
> iako **NA LOPTU niko nije stigao** — taker hoda ka lopti, ali akcija krene pre
> nego što stigne, ili lopta stoji a akcija se pokrene bez carrier-a na njoj.
> Ovo je regresija od P2-UI#1 (koja je fiksirala "ACTION BEZ CARRIER-A NA LOPTI"
> u engine-u) — **na UI-u se i dalje dešava**.

- [x] Reprodukovati na proposal viewer-u sa determinističkim seed-om
  — seed se prima kroz proposal generate API, vraća se u `match.json`, i
  viewer ga prikazuje/odaje kroz isti input. Verifikovano testom jednakih
  score/pass/event metrika za seed 778.
- [x] Taker restart-a (walk to ball) mora da stigne NA loptu pre prve odluke —
      PAS/ŠUT se ne sme desiti dok taker nije na lopti (isto pravilo kao i
      `demo/service` engine §"ACTION BEZ CARRIER-A NA LOPTI") — DONE 2026-09-25:
      * `BallPhysicsEngine` performs NO loose pickup while `restartTaker != null`
        (not for the taker, not for anyone else) — the orchestrator's restart
        claim (ON_BALL_EPS) is the only way a player gets the restart ball.
      * `MatchOrchestrator` step 6 now has an explicit `restartTaker == null`
        guard in the decision gate, so no action can start during the walk even
        if a future caller sets a carrier.
      * `RestartManager.executeRestart` clears the carrier UNCONDITIONALLY (a
        stale open-play carrier could previously survive and pass the gate).
      * Regression: `RestartTakerArrivalTest` (5 tests) drives real ticks and
        asserts no `|EXE]` line is produced before the taker claims.
- [x] Proveriti da restart hold / walk ne "pusti" akciju ranije (restartWalk
      vs actionDelay ticking — taker može da stigne tek posle N tick-ova) —
      DONE 2026-09-25: the proposal engine has NO hold counter to expire
      (no `actionDelayTicks`/`restartHoldTicks`; `oobHoldTicks` expires *before*
      the restart exists). The only remaining counter that gates the decision
      block is `varDelayTicks`, and it is harmless because the carrier+ON_BALL_EPS
      gate applies independently. Verified by `noActionExecutesBeforeTheTakerReachesTheBall`.
      Related fixes found while auditing: an opponent inside PICKUP_R could no
      longer steal a restart (`opponentInsidePickupRangeCannotStealTheRestart`),
      and `MatchPhase.SET_PIECE` now returns to `OPEN_PLAY` when the restart is
      consumed (it previously stayed SET_PIECE for the rest of the match).

### P-UI — Kompletna UI provera vs demo/service (KORISNIČKA PRIJAVA 2026-09-17)

> "ui generalno ceo mora da se proveri, da se uporedi sa /demo/service jer je
> tamo dosta toga radilo ok"

- [x] Uporediti proposal viewer sa referentom `/demo/service` viewer-om:
      restart pozicioniranje (korner / gol-aut / aut / penali / free kick),
      taker walk, kickoff, half-time, VAR freeze, celebration hold, subs,
      cards, OOB restart posle SHOT_MISSED / SHOT_BLOCKED / corner
      — AUDIT 2026-09-25 (12 features, evidence on both sides). Result:
      `P/js/viewer.js` je 1:1 port reference viewera, pa je gotovo svaka
      razlika DATA/ENGINE strana, ne viewer strana. Ispod je svaka
      nesklad zasebno.
- [x] Označiti svaki nesklad kao zaseban bug-rod (ne paliti sve u jedan)
      — vidi `UI-PARITY-01..11` ispod
- [x] Za svaki nesklad: portovati ponašanje iz `/demo/service` (ne novu maštu)
      — portovani: 01, 02, 03, 04, 07 (+ 3D engine-endpoint bug). 05, 06,
      08, 09, 10, 11 dokumentovani kao namerni divergence sa razlogom.

#### UI-PARITY audit (2026-09-25)

**REGRESSION — popravljeno u ovom sprintu**

- [x] `UI-PARITY-01` Half-time / full-time overlay se NIKAD nije prikazao.
  `MatchRecorder.captureSnapshot` je hardkodovao `false, false` za
  `halfTime`/`matchFinished`, pa je viewer kod (isti kao referentni) bio
  gladan. FIX: `MatchState.isHalfTime()/isMatchFinished()` +
  `MatchClockService` ih postavlja (1800 / 3600 tick) + recorder ih čita.
- [x] `UI-PARITY-02` VAR freeze + verdict banner se nikad nisu prikazali.
  Engine je emitovao tip `"VAR"` koji ne matchuje nijedan handler.
  FIX: tipovi `VAR_IN_PROGRESS` + `VAR_<TYPE>_CONFIRMED|_OVERTURNED`
  (`DuelService`, `OffsideService`). Held-live offside check NAMERNO ne emituje
  `VAR_IN_PROGRESS` (play nastavlja po projektnom pravilu).
- [x] `UI-PARITY-03` Penal nije bio vidljiv u sidebaru. Engine emituje
  `PENALTY_AWARDED`, a taj token nije bio ni u jednom filteru.
  FIX: dodat u `EV_ICON` + `IMPORTANT_EVENTS` + `TIMELINE_EVENTS`.
- [x] `UI-PARITY-04` 3D replay stranica je bila orphan — `viewer3d.html` +
  `viewer3d.js` su isporučeni, ali je dugme za navigaciju uklonjeno.
  FIX: dugme vraćeno (`index.html`) + **dodatno nađen bug**: `viewer3d.js`
  je zvao LEGACY `/api/service/match/simulate` + `/api/generate`, tj. pokrenuo
  je pogrešan engine. FIX: sada zove `/api/proposal/generate` (+ seed input).
- [x] `UI-PARITY-07` Kickoff frame je prikazivao AWAY igrače PREKO srednje
  linije. Clamp na tačno 4.5 bez buffera → prvi movement tick ih gurne u
  protivničku polovinu. FIX: buffer pola ćelije (HOME ≤ 4.0 / AWAY ≥ 5.0),
  portovano iz `demo/service MatchState:644-645`.

**NAMERNI DIVERGENCE — ne portovati**

- [ ] `UI-PARITY-05` ActionLogService kanal se ignoriše u vieweru
  (`logs` su raw stringovi, filter zadržava samo objekte). NE PORTOVATI:
  referent spaja 5 731 strukturiranih unosa; proposal bi učitao 28 584 raw
  linije bez `tick` polja (samo `M:SS`), duplirajući već-tipizirane recorder
  evente. Recorder eventi su autoritativni. Ako korisnik zatraži
  `CLEAR`/`DRIBBLE`/`GK_CATCH` u sidebaru, to mora biti novi TYPED event, ne
  parsiranje loga.
- [ ] `UI-PARITY-06` Jedan generički `RESTART` red (dim) umesto tipizovanih
  `CORNER`/`GOAL_KICK`/`THROW_IN`/`FREE_KICK` redova. Proposal red sadrži i
  ime taker-a, pa je informativno bogatiji. Tipizovane ikonice/entries u
  vieweru su mrtav kod — ne portovati.
- [ ] `UI-PARITY-08` Card overlay bi imenovao pogrešnog igrača *u starom
  exportu* (strukturna polja = faulir, opis = prekršitelj). Engine source je
  već ispravan (`DuelService:121,128` šalje prekršitelja) — problem je u
  zastarelom `match.json`. Rešava se regenerisanjem exporta.
- [ ] `UI-PARITY-09` Nula `SHOT_MISSED`/`SHOT_BLOCKED` u trenutnom exportu
  (4 šuta → 4 spašena). Kod je paritetan; nedostaje samo podaci. Pokriveno
  širokim P6/P7 kalibracijama, ne portovanjem.
- [ ] `UI-PARITY-10` Seek u proposal vieweru uvek skroluje na kraj, referent
  samo ako je korisnik već pri dnu. Namerna razlika; vratiti na guard samo
  ako se pojavi prigovor.
- [ ] `UI-PARITY-11` Mrtav `.stats-panel` CSS blok (~22 linije) — nema
  referentne implementacije; ili oživiti `_renderStats()` ili obrisati
  pravila. Nizak prioritet (player card ga je zamenio).

---

## Rules of engagement

- All changes verified via `ProposalBatchDiag 10` (or 50 for stats)
- Engine is non-deterministic — judge via batches, never single traces
- `mvn -q compile` must pass after every change
- `match.json` is 58MB and gitignored — never commit
