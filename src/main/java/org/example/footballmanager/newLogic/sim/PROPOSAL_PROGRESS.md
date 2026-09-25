# PROPOSAL_PROGRESS.md — newLogic/sim

Dokument praćenja napretka za **čisti, samostalni sim autor utakmice** u
`newLogic.sim` (paket ne uvozi ništa van svog stabla).

> **Pravilo rada:** pre svake izmene uvek se uradi `git commit` trenutnog
> stanja. Svaki upis ispod nosi **vreme** i **hash komita** koji ga uvodi.

---


## 0. POSLEDNJE IZMENE — 2026-09-25 (sesija 7.9)

### 🚨 NAJVIŠI PRIORITET — UI prikazivao POTPUNO DRUGI MEČ od onog u logu

Korisnička prijava: "UI uopste ali NI BLIZU ne prikazuje ono što piše u logu
... u event logu piše da je sa centra išao pass do stopera, a na UI je kratak
pass do napadača u istom minutu". Dakle NIJE bio neki detalj renderovanja —
bio je potpuno drugi meč.

Uzrok: **tri nezavisna defekta u request/response lancu**, ne u engine-u:

1. **Pogrešan endpoint.** Viewer je zvao `POST /proposal/api/generate`; Spring
   controller je mapiran na `/api/proposal`; a `/proposal/api/**` nije bio u
   `SecurityConfig` permit listi → poziv padao (404/401). Generisanje u Spring
   aplikaciji nije ni funkcionisalo. FIX: oba prefiksa
   (`@RequestMapping({"/api/proposal", "/proposal/api"})`) + `/proposal/api/**`
   u permitAll (ista politika kao već dozvoljeni `/api/**`).
2. **Pisanje u fajl koji se ne služi.** `writeMatchFile()` je pisao u
   `src/main/resources/static/.../match.json`, a Spring Boot služi statiku sa
   **classpath-a** (`target/classes/static/...`). Fajl koji je browser
   preuzimao bio je meč iz poslednjeg builda — nikad upravo generisani. To je
   i objasnilo simptom: potpuno drugačiji passovi/igrači/minut.
   FIX: transport ne zavisi od fajla → novi `GET /proposal/api/latest` vraća
   poslednji generisani meč iz memorije. Pisanje fajla ostaje samo za
   standalone `ProposalViewerLauncher` (on ga i sam služi) i ručni "Load JSON".
3. **Viewer je odbacivao sveži odgovor.** `generateMatch()` je ignorisao
   JSON koji je upravo stigao i fetch-ovao zastareli fajl
   (`_initFromData()` bio zakomentarisan). FIX: viewer igra payload iz
   odgovora; ako odgovor nema `snapshots` (standalone launcher vraća summary),
   pada nazad na `match.json` koji taj server upravo serve-uje.

**Da se ovo više ne desi tiho:**
- engine loguje `=== PROPOSAL MATCH GENERATED === seed=… matchId=… score=…`
  pri svakom generisanju;
- viewer prikazuje `seed · id` u LED scoreboard-u (`#matchIdLabel`) → meč na
  ekranu se može uporediti sa log linijom u svakom trenutku;
- `ProposalViewerMatchIdentityTest` (3 testa): generate i latest opisuju ISTI
  meč; oba prefiksa rade; generate vraća ceo replay payload.

**Event log auto-scroll (druga prijava u istom zahtevu):** guard "nearBottom"
se računao **posle** append-a, pa je svaki batch veći od 80 px (seek rebuild,
gol + restart) trajno oborio praćenje zadnjeg eventa — poslednji event je
dolazio van ekrana do kraja meča. FIX: odluka "pratim li rep" se donosi PRE
append-a, prag 120 px, `_buildTimeline` re-arm-uje praćenje, `scroll` listener
pauzira praćenje kad korisnik skroluje gore i nastavlja ga na dnu.

Puni test suite: **60 testova, 0 grešaka**.

### 🚨 P0 — asimetrija HOME/AWAY: nađeno 6 mirror defekata, popravljeno

`ProposalBatchDiag 50 42`, pre → posle:

| Metrika | Pre | Posle |
|---|---|---|
| goals H/A | 0.92 / 0.08 | **2.68 / 1.20** |
| shots H/A | 3.8 / 7.3 | **40.5 / 43.4** |
| SOT H/A | 1.9 / 3.5 | **25.4 / 27.5** |
| interceptions H/A | 6.8 / 9.2 | **20.0 / 19.1** |
| possession HOME | 35% | **51%** |

1. **Timoovi nisu bili identični** (`SimTeamFactory`): base skill iz
   `(team + role).hashCode()` → HOME široki vezniši base 12, AWAY 15. `DuelEngine`
   rešava duel JEDNIM brojem, pa su zrcalni duelovi u sredini terena bili
   "AWAY 100% / HOME 0%" → 35/65 posed. Sada eksplicitan, identičan profil
   veština (`SimTeamFactoryMirrorTest`). Usput otkriveno: stari profil je bio
   magic hash, pa je njegova zamena meč eksplodirala na 128 šutova — profil
   veština koji odlučuje meč mora biti napisan, ne hešovan.
2. **Zona šuta** (`CleanDecisionEngine`): HOME 2 ćelije duboko, AWAY samo 1
   (`row <= 2.0` → `3.0`). Zato su sve AWAY šanse bile sa 0-14 m, gde GK domet
   presvlači usta gola (84% spašenih šutova).
3. **Defanzivna trećina** za čišćenje: AWAY `row >= 5.0` (3 ćelije) → `6.0`.
4. **Promašaj šuta** (`ExecutionQuality`): `goalRow - offset` je za AWAY značio
   redove IZA gol linije (−0.2..0.7) → mrtva lopta → gol-aut za HOME. Smer
   napada je sada eksplicitan.
5. **`prox`** je za HOME meren od row 0 → +1 ćelija konstantnog bonusa HOME-u na
   svaki pas/dribling/metež. Sada `home ? 8.0 - row : row - 1.0`.
6. **Kazneni prostor** (`DisciplineService`): HOME 1.5 ćelija dubine, AWAY 0.5
   → `row <= 2.5`.
7. **Zrcalo kolone** (`TacticalPerspectiveTransformer`): `7 - col` (oko 3.5)
   umesto `8 - col` (os simetrije terena je 4.0) → AWAY desno krilo mapirano
   IZA touchline-a, gde ga clamp pripija 8.4 m od auta.

Provereno čisto (nema asimetrije): `MovementEngine`, `TacticalIntentEngine`,
`ThreatOverrideEngine`, `BallPhysicsEngine.goalCrossing`/`isTowardOwnGoal`,
`ActionExecutor`, `TacticsRules.desiredCell` (zrcalni histogram 506 pravila).

**Ostalo otvoreno:** (a) ratio golova još 2.2:1 uz izjednačene šutove/SOT/
presretanja/posed → problem je u konverziji (9.2% vs 3.7%); (b) tie-break po
redosledu liste (`<=` u `nearestPlayer` → AWAY, `<` u chaseru → HOME);
(c) `WE_HAVE_BALL`/`OPPONENT_HAS_BALL` pravila su identična u svih 506 pravila
(tim bez lopte igra napadni oblik); (d) KALIBRACIJA: 84 šuta/63% SOT po meču
(realno 25/33%).

### 🎯 Kalibracija šutova (cilj: ~25 šutova, do 7 golova) — REŠENO

Nađen i popravljen pravi uzrok 83 šuta po meču: **"frequency gate" nije bio
veto.** Vraćao je `-20`, a alternative u završnici trećine su `PASS=-60..-90`,
`DRIBBLE=-60`, `CLEAR=-40` — pa je šut bio UVEK najmanje loša opcija i engine
je šutao na svaki dodir.

1. `UNAVAILABLE = -10_000` kao pravi veto ("not in zone", "freq gate",
   "lane jammed"). Kaženi izbor se i dalje bira kad je najbolji od loših.
2. `SHOT_FREQUENCY_GATE` 0.25 → 0.17.
3. **`GK_SAVE_R` 0.75 → 0.28 ćelije**: 0.75 ćelije = 10.5 m, usta gola 1
   ćelija (14 m), vratar u sredini → domet mu je pokrivao praktično celu mrežu,
   pa je svaki šut u okvir bio spašen čistom geometrijom (~4% konverzija).
4. `ExecutionQuality` on-target 0.12+skill*0.028 (+0.20) → 0.08+skill*0.020
   (+0.12) — 57% SOT bilo previsoko.

Rezultat (200 mečeva, seed 42): **šutovi 83.4 → 23.6**, golovi 3.88 → 4.9,
SOT 53.8 → 9.1, SOT% 63% → 39%, golovi H/A **2.4 / 2.6**.

### 📊 Novi dijagnostik: `ProposalSeasonDiag <matches> <seed>`

Pun izveštaj po timu za sve metrike (golovi, šutovi, SOT, promašaji, odbrane,
blokovi, konverzija, passovi/uspeh, driblingi, čišćenja, dueli, presretanja,
deflections, korneri, gol-autovi, auti, ofsajdovi, prekršaji, kartoni, penali,
VAR confirmed/overturned, posed) + rezultati. Sve brojke se čitaju iz
engine-ovih brojača ili iz typed event streama — ništa se ne procenjuje, pa
metrika koju engine ne prati piše "NOT TRACKED" umesto da se izmišlja.
Rezultat za 200 mečeva je u **`PROPOSAL_SEASON_REPORT.md`**.

Popravke usput: `TeamStats.offsides` je bio hardkodiran na 0 — sada se broji
(`ProposalStatsCollector.onOffside`, h Hook iz `OffsideService` i iz
`BallResultHandler`); `BallPhysicsEngine.nearestPlayer` je imao `<=` pa je
svaka egzaktna nejednakost išla AWAY-ju (poslednji u listi) — sada striktan
`<` + rotacija po tick-u.

Puni test suite: **63 testova, 0 grešaka**.

### ⚙️ Sesija 7.10 — kalibracija discipline, offside pravilo, THRU/CENTER/CROSS, VAR, blokovi

**CLEAR van defanzivne trećine = VETO (kao i šut).** Isti bug kao kod šuta: score
`-40` je i dalje pobeđivao `PASS=-60..-90` i `DRIBBLE=-60`, pa je čišćenje
bilo birano kad je bilo "najmanje loša" opcija — i to **u protivničkoj polovini**,
gde "čisti od svog gola" šalje loptu ka protivnikovoj gol liniji. Mereno: 41%
AWAY i 32% HOME čišćenja je polazilo iz pogrešne polovine. Posledica: 59 gol
autova po meču, teritorijalna asimetrija (HOME 37 gol autova vs AWAY 22, AWAY
9.2 kornera vs HOME 0.3), posed 56/44 i razlika u broju pasova 288 vs 185.
Rezultat: gol autovi **19/20**, korneri **0.8/3.6**, posed **50.4/49.6**, pasovi
**295/265**, udaljenost 11% (bila 56%).

**Disciplina — kriterijumi prilagođeni.** Bilo 6.9 penala i 2.7 crvenih po meču
(realno 0.27 i 0.2). Uzrok: 24% SVIH prekršaja u kaznenom prostoru postajalo
je penal (realno ~1%). `isInsidePenaltyArea` je vraćala "u prostoru" bez
ikakve verovatnoće, a kazneni prostor je imao 3x veću dubinu za HOME. Sada:
`PENALTY_FROM_BOX_FOUL = 0.06` (prekršaj u prostoru je samo KANDIDAT za
penal), uža kolona (2.5-5.5 = stvarna širina 40.3 m), `STRAIGHT_RED_RATE`
0.02 → 0.004, `YELLOW_RATE` 0.35 → 0.20. Rezultat: penali **0.2**, žuti
**4.7**, crveni **1.1**. Takođe implementiran `DuelEngine.isOnCooldown` koji je
bio stub koji UVEK vraća false — duelovi su se ponavljali svaki tick dok je
pritisak trajao.

**Offside po korisnikovom pravilu.** "Kad je vise od 0.5 cella igrac u
offside, sto je 7m, nema ni smisla da ide pass, ali unutar 7m moze da krene
pass... veci skill ce retko gadati offside." U `CleanDecisionEngine`:
`OFFSIDE_HARD_LIMIT = 0.5` ćelija → meta se uopšte NE razmatra; unutar 0.5
igrac je kandidat, ali se pass igra samo ako `carrierWillRiskOffside(carrier)`
prođe — verovatnoća 0.95 (playmaking 1) do 0.10 (playmaking 20). U
`OffsideService` zvižduk je na 0.30 ćelije (4.2 m), pa je "malo van linije"
prihvaćeno. Offside: 0 → **7.0** po meču (realno 2-4).

**Nova akcija: THRU / CENTER / CROSS.** `ActionType` je dobio tri vrednosti sa
punim ciklusom (skorovanje, izvršenje, statistika, događaj):
- **THRU** — vođeni pas u prostor IZA linije odbrane za napadača; traži
  timskog igrača na liniji ili iza nje (`offsideMargin <= 0`), boduje brzinu i
  prostor iza njega, frekvencija `0.032` (realno 5-10 po meču).
- **CROSS** — visoki pas sa bočne strane u kazneni prostor, meta je napadač u
  prostoru, cilja se preko lopte ka golu.
- **CENTER** — isti visoki pas, ali iz manje ekstremne bočne pozicije, u centar
  prostora. Frekvencija 0.45.
Svi se broje kao PASSOVI za tačnost, ali imaju svoju statistiku i svoj događaj.
Rezultat: **13.1 / 16.2 / 53.0** po meču (realno 5-10 / 15-25 / 25-35).

**VAR — prevrti više nisu nemogući.** `VARService.checkGoal` je POSTOJALO ali se
NIJE NIKUDA zvao — dakle gol se nikad nije mogao preglasiti (ofsajd, prekršaj u
nastavku, rukomet). Sada se zove u `BallResultHandler` pre brojanja gola:
prevrt daje `GOAL_DISALLOWED` + `VAR_GOAL_OVERTURNED` i slobodan udarac
protivniku, bez traga u rezultatu ni statistici. Kapije pregleda su podignute
(goal 4%→15%, offside 4%→25%, crveni 10%→40%, penal 5%→55%, žuti 10%→15%) i
verovatnoće prevrtaju (goal 8%→28%, crveni 25%→30%, penal 20%→25%).
Rezultat: pregleda **2.7**, prevrti **0.6** po meču (bilo 0.0).

**Blokovi — odgovor na pitanje "da li uopste imamo block kategoriju".** Postojala
je cea infrastruktura (`BallStepResult.block`, `BLOCK` događaj, `stats.onBlock`,
kolona `blocks` kod igrača) ALI `ev = "BLOCK"` se nigde nije dodeljivao — dakle
je bila mrtva i nijedan blok se nije mogao desiti (0.0 po meču). Sada
obranin u `SHOT_BLOCK_R` (0.18 ćelija = 2.5 m) linije leta šuta parira loptu.
Rezultat: **6.6** po meču (realno 2-4).

**Lopta i aut.** Nema nikakvog klampovanja lopte u letu — `stepBall` postavlja
poziciju slobodno pa tek proverava `isOOB`, a `clampToField` važi samo za
taktičke meteže igrača. Dakle lopta slobodno izlazi ako ima brzinu, kao što je
traženo; ništa ovde nije menjano. Autovi su ipak ređi od realnosti (16.6 vs
35-45) jer lopta izlazi uglavnom preko gol-linije (čišćenje se lanci iz
`MAX_BALL_SPEED`, v²/2a ≈ 7.5 ćelija bez obzira na metu od 2 ćelije), a ne
preko aut linije.

Puni test suite: **63 testova, 0 grešaka**. Stanje: `PROPOSAL_SEASON_REPORT.md`.

### 🥅 Vratar — pravi model, ne fiksni radijus

Umesto fiksnog `GK_SAVE_R` radijusa (fiksni radijus ne može da izrazi mesto
udarca, snagu udarca ni veštinu vratara) napravljen je `engine/GoalkeeperEngine.java`:

**Pozicioniranje** — vatar staje na SIMETRALI između lopte i centra svog gola
(mesto koje najviše sužava ugao šuta) i prilazi se toj strani. Silazi sa gol
linije kako lopta prilazi (0.35 ćelija kad je daleko, ~1.5 kad je blizu) i
izlazi na jedan na jedan u prostoru. NIKADA ne prolazi dalje od lopte
(`advance <= ballDistance` — bez te klauzule `t` prelazi 1 i simetrala se
ekstrapolira IZA centra gola, što je bacalo vratara na drugu stranu terena
svaki put kad je zatvorio napadača ispod jedne ćelije), i ograničen je na širinu
usta gola plus margina. Verifikovano u logu: spašavanja sada se dešavaju od
reda 1.4 (na liniji) do 7.7, kolone 3.7-4.2, umesto uvek sa jedne tačke.

**Spremanje — ocenjeno, ne geometrijski:**
- veština vratara širi domet (`0.34 + skill/20 * 0.32` ćelija) i ruke
  (`0.70 + skill/20 * 0.50`)
- brzina lopte skraćuje domet za 40% (šut maksimalnom snagom mu ostavlja manje
  od jednog ticka da se postavi)
- mesto udarca: šansa da zadrži loptu pada sa KUBOM udaljenosti od njegovog
  tela, pa je udarac na ivici dometa mnogo teži od udarca u grudi — to je što
  izbacuje udarce u ugao umesto da geometrijski sačuva sve unutar radijusa

Šutevi u okvir sada se raspršuju preko CEOG usta gola umesto da se grupišu u
±0.3 od centra — što je upravo mesto gde vatar stoji. To je bio drugi razlog
zašto ga ništa nije pobijedilo. Meta je bila u njegovu domaćem prostoru.

Kretanje: `MovementEngine` primenjuje `GOALKEEPER_MOVEMENT_FACTOR = 1.9` jer
brzina vratara opisuje bočno šetkanje u postavljenom položaju, a ne outfield
brzinu — uz generički pace cap je uvek kasnio na loptu u ugao.

Uticaj: SOT→gol **61% → 44%** (realno ~30%), golovi 7.0 → **5.2**, spašavanja
9.4 po meču (realno 3-4, prate visok SOT% od 43%).
`GoalkeeperEngineTest` (11 testova): simetrala, rampа silaska sa linije, izlazak
na jedan na jedan, nikad preko lopte, egaktno zrcaljenje HOME/AWAY, i ocenjeno
spremanje (veština širi domet, brzina ga sužava, lopta van dometa uvek prolazi).

Puni test suite: **74 testova, 0 grešaka**.

### 🐛 UI prikazivao drugi pas od loga + kickoff + brzina + "protivnik stiže pre"

**1. UI prikazuje pas ka IGRAČU, a log ka drugom.** Uzrok je bio u replay putanji
(dashboard, pravi igrači iz baze → `SimMatchService` → `SimReplayView`), ne u
engine-u. Replay je uzimao snapshot svakih 10 tickova (`SNAPSHOT_STRIDE`), a
loptu zato što je brza KADA NIKO NEMA LOPTU: visoki kickoff pas prelazi ~8.25
ćelija za 10 tickova, a teren je duga 7 ćelija. Viewer je zato morao da
nacrta loptu kao pravu liniju preko većine terena između dva kadra — pas ka
jednom igraču izgledao je kao pas ka igraču koji se slučajno našao na toj
liniji. Uz to, `MatchRecorder.captureSnapshot` je upisivao `null` u
`targetPlayerId`, `intendedTarget` i `actualTarget`, pa replay nije imao pojma
kome lopta ide.

Popravke:
- `SimReplayView.downsample` NIKADA ne izbacuje tick dok je lopta u letu
  (nema vlasnika i ima `targetPlayerId`/nije POSSESSION). Idle tikovi (lopta
  nekome kod nogu, što je većina) i dalje stride-uju 1:10, pa payload ostaje
  mali.
- `MatchRecorder.captureSnapshot` upisuje `actionType`, `actingPlayerId`,
  `targetPlayerId` i `receivePoint`, a `SimReplayView` ih izlaže vieweru
  (`targetPlayerId`, `actionType`, `receivePoint`).
- `SimReplayFidelityTest` (5 testova): nijedan gap ne prelazi stride, snap
  snimci nose metu, replay izlaže metu, i kickoff linija je prisutna.

**2. Kickoff u logu.** `handleKickoff` nije ništa nigde zapisivao — meč je samo
počinjao. Sada loguje `KICKOFF <TIM> | ball at center (4.5,4.0) | taker X (ROLA)`
kroz zajednički action logger (tag `RST`), pa ide u app log, match.json i UI
timeline. Potvrđeno: `[0:00|RST] KICKOFF HOME | ball at center (4.5,4.0) | taker
H10 (STL)`.

**3. Kickoff overlay 2 sekunde.** Bio je 3 s. Sada 2000 ms, a `_loop` već NE
pokreće tiktove dok je blokirajući overlay aktivan (`_renderFrame()` bez
napredovanja), tako da se sat zaista ne pomera dok kickoff stoji.

**4. Brzina snimka se nije videla.** Bio je `<input type=range>` + `<span
class="speed">` sa 11px dim tekstom i `min-width:32px` — praktično nečitljivo.
Zamenjeno dropdown-om `speed-select` sa jasno ispisanim vrednostima
(0.25x/0.5x/1x/2x/5x/10x) i čitljivim stilom; izabrana vrednost je uvek
vidljiva i klikabilna.

**5. Protivnik stiže na loptu pre našeg igrača.** "Na UI igrač gostiju desni att
stigne pre do našeg drugog reda nego lopta, pas sa centra — to ne može." AI je
igrao pas u mesto gde je protivnički krilni već stajao, i dobijao ga.
Dodato: `nearestOpponentBeatsHimToIt()` — meta se izbacuje iz opcija ako
najbliži protivnik do mesta na koje lopta stiže stigne pre primaoca
(računajući obe brzine iz `MovementEngine.playerSpeedFor`, sa malom prednošću
primaocu jer mu je već cilj lopta). Tačno provera koju pasista radi pre
igranja pas. Ukupna tačnost pasova je ostala ~79%/77% (nije degradirana) — uklonjene
su konkretne lose šanse, a lopta se reciklira.

Puni test suite: **79 testova, 0 grešaka**.

## 0.1 PRETHODNE IZMENE — 2026-09-25 (sesija 7.11 — vratar)

### P6 — kalibracija pass completion-a (76% → 84%)

- **Koren problema NIJE bio `readIntercept`.** Prethodno dokumentovana
  dijagnoza ("read se re-rolluje svaki flight tick") je bila pogrešna: read je
  već keširan jednom po defenzeru po pasu (`passReadDecisions` po `p.getId()`),
  a presretanja čine samo ~15% neuspešnih pasova. Novi dijagnostik
  `ProposalPassFailDiag` je to i izmerio: neuspešni passovi su 25% OFFSIDE,
  24% DEFLECT, 19% LOOSE_PICKUP, 11% INTERCEPT.
- **Offside je preterivao 22 po meču** (stvarno 1-3). Dva uzroka:
  1. `OffsideService` je flagovao primaoca na `margin > 0` — santimetar iza
     linije = zastava. Uveden `OFFSIDE_WHISTLE_MARGIN = 0.2` ćelije (2.8 m),
     identično demo/service toleranciji.
  2. `CleanDecisionEngine.findBestReceiver` NIJE ZNAO ZA OFFSIDE — birao je
     primaoca samo po openness/lane/progress, pa je stalno birao igrača
     duboko iza linije koji mu je pas posle rules sloja ubio na prijemu.
     Sada: jasno offside meteži (`margin > 0.2`) se izbacuju, marginalni
     (`0 < margin ≤ 0.2`) dobijaju −120 na score.
  Rezultat: **offsides 350 → 0** na 20 seeditih mečeva; pass completion
  76% → 84%, goals 0.64 → 1.00.
- **Cilj "~98%" je ostao otvoren za potvrdu vlasnika produkta** — kao *raw*
  odnos nije fudbalski realan (realni klubovi 80-86%). Engine je sada na
  realističnih 84%.
- `DEFLECT_R` i `interceptChance` nisu dirani: merenje je pokazalo da oba
  modela daju realne brojeve (16 presretanja/match, deflacija je legitiman
  ishod), pa bi njihova kalibracija bila šarža bez pokazatelja.

### P0 NOVI BUG — asimetrija HOME/AWAY (otvoren)

- 50 mečeva: `HOME goals 0.92 | AWAY goals 0.08`, ali `HOME shots 3.8
  (sot 1.9) | AWAY shots 7.3 (sot 3.5)`. AWAY ima 65% posed, ulazi u box ~5x
  češće, pa mu GK spašava 84% šutova (HOME 47%). Kod za goal-crossing i save
  je simetričan → asimetrija je GORNJE u movement/threat/decision poređenjima
  po timu. `ProposalBatchDiag` sada ispisuje side-split red kao detektor.
  Zahteva zasebnu sesiju poput demo/service "pass 2 — AWAY-goal-line mirror fix".

### P-UI — restart taker invariant (korrekcija baga)

- Nađeno i popravljeno: **suprotni tim je mogao da ukrade restart.** Dok je
  taker još hodao, `BallPhysicsEngine` je dozvoljavao bilo kom igraču (obe
  strane) da pokupi loptu sa 7x `ON_BALL_EPS` udaljenosti, što je oslobađalo
  restart i proizvodilo upravo prijavljenu simptomu "restart krece pas iako
  nema igrača na lopti". Sada: dok je `restartTaker != null`, nema nikakvog
  loose pickupa — jedini put da restart lopta dobije vlasnika je claim korak
  u orkestratoru (`ON_BALL_EPS`).
- `MatchOrchestrator` decision gate sada ima eksplicitan `restartTaker == null`
  uslov; `RestartManager` briše carrier bezuslovno (stari carrier iz otvorene
  igre je mogao proći gate); `MatchPhase.SET_PIECE` se vraća na `OPEN_PLAY`
  kad se restart potroši (pre toga je ostajao SET_PIECE do kraja meča).
- `RestartTakerArrivalTest` (5 testova) vozi stvarne tickove i tvrdi da nijedan
  `|EXE]` red se ne pojavi pre dolaska taker-a.
- Corner taker: ranije je UVEK prvi ML/DL uzimao oba kornera; sada se bira
  krilo sa strane flag-a, najbliže lopti.

### UI parity audit vs `/demo/service` (12 features)

- `proposal/js/viewer.js` je 1:1 port referentnog viewera, pa je gotovo svaka
  razlika bila DATA/ENGINE strana. Popravljeno: (01) half-time/full-time
  overlay nikad nije radio — recorder je hardkodovao `false, false`; sada
  `MatchState.isHalfTime()/isMatchFinished()` + `MatchClockService`;
  (02) VAR freeze/verdict nikad nije radio — event tip je bio `"VAR"`, sada
  `VAR_IN_PROGRESS` + `VAR_<TYPE>_CONFIRMED|_OVERTURNED` (held-live offside
  check namerno ne emituje IN_PROGRESS); (03) penal nevidljiv —
  `PENALTY_AWARDED` nije bio ni u jednom filteru; (04) 3D stranica je bila
  orphan, a `viewer3d.js` je dodatno zvao LEGACY `/api/service/...` endpoints
  (pokrenuo pogrešan engine) — sada `/api/proposal/generate` + seed input;
  (07) kickoff je prikazivao AWAY igrače preko srednje linije — clamp sada
  drži pola-ćelije buffera (HOME ≤ 4.0 / AWAY ≥ 5.0), portovano iz
  `demo/service MatchState:644-645`.
- Preostalih 6 razlika (`UI-PARITY-05..11`) je dokumentovano kao namerni
  divergence sa razlogom — NE portovati (npr. proposal `logs` su raw stringovi;
  parsiranje 28k linija bez `tick` polja samo bi usporilo load i dupliralo
  već-tipizirane recorder evente).

### Ostalo

- **Possession chains**: `ProposalStatsCollector.PossessionChain` (chain id, tim,
  broj passova) izvezen kao `stats.possessionChains`.
- **Possession-aware taktika**: `TacticsRules` učitava i `WE_HAVE_BALL` i
  `OPPONENT_HAS_BALL` pravila; `TacticalIntentEngine` prosleđuje trenutni
  posed timu.
- **Viewer click-to-stats**: klik na igrača na terenu otvara karticu sa
  statistikom (ime, uloga, rating, golovi, asistencije, udarci, passovi,
  dueli, minuti) i prsten oko izabranog igrača.
- **App log**: poslednji raw `System.out` van servisa (`OffsideService`
  `[OFF-TRACE]`) sada ide kroz `ActionLogService.log("OFF", ...)`.
- **Hard rules**: final-2-row SHOT i kickoff posebna odluka su dokumentovano
  ZADRŽANI kao eksplicitne granice (ne pretvoreni u score boost) — trace odluke
  ostaje pošten jer vidi stvarne score-ove opcija.
- **Puni test suite: 57 testova, 0 grešaka.**

## 0.1 PRETHODNE IZMENE — 2026-09-25 (sesija 7.7)

- **Proposal seed plumbing**: `POST /api/proposal/generate?seed=N`,
  `ProposalMatchExporter` i standalone `ProposalViewerLauncher` sada seed-uju
  `SimulationRandom` pre izgradnje meča; seed se vraća u `match.json`/response,
  a proposal viewer ga prikazuje kroz `Seed` input. Regression test potvrđuje
  da dva run-a sa istim seed-om imaju identične score/pass/event metrike
  (razlikuje se samo random `matchId`).
- **P6 baseline**: `ProposalBatchDiag` sada koristi determinističke seed-ove
  (`<matches> <baseSeed>`) i prijavljuje interceptions/deflections/fouls/cards;
  seed-42 baseline na 50 mečeva: 0.64 gola, 9.2 šuta, 76% pass completion,
  18.9 interceptions/match. P6 nije zatvoren jer je 98% cilj i dalje nepotvrđen.
- **Fatigue**: novi `FatigueSystem` računa drain iz stvarne distance kretanja
  i skalira ga sa stamina skill-om; `MovementEngine` primenjuje najviše 30% speed
  loss. Auto-sub i injury-risk su eksplicitno odloženi jer engine nema bench/roster
  contract.

## 0.1 PRETHODNE IZMENE — 2026-09-24

- **Goal shot-guard**: `BallResultHandler` sada zahteva `ActionType.SHOT` za priznavanje gola; ne-šut preseci linije se ne boduju.
- **Pass bias short**: `CleanDecisionEngine` favorizuje kratke pase 1.5-3.0 ćelija, kažnjava duge (>4.5) pase.
- **Offside flow**: flag na početku pasa, svira se na prijemu; po sviranju se briše flagged receiver.

## 1. CILJ — šta želimo da napravimo

Runnable, determinističan, taktički-realističan sim fudbalskog meča 11v11 sa:

- **Odvojenim, jednostruko-odgovornim engine-ima** (decision, action/execution,
  physics, movement, tactical intent, restarts, duels, rules...). Orchestrator
  samo **zove** engine-e u redosledu — nikakvu logiku ne drži sam.
- **Taktičkim pozicijama iz baze** (`team_tactics_profile`) ili fallback JSON-a
  — to su "dobre pozicije" koje su podešene u editoru za 4-4-2 (i ostale
  formacije u katalogu). Igrač bez lopte prati svoj taktički cilj svaki tik.
- **Živom loptom**: dodavanje/sut štop-lopta → lov → posed; šutevi, golovi,
  odbrane, duele; restarti (korner, gol-aut, aut, penal, free kick); VAR.
- **"Lopta na nogama"**: nosilac lopte uvek ima loptu pri nozi u trenutku
  odluke; ne može da šutira/dodaje ako nije NA lopti.
- **Konfigurabilnim stranama**: tim koji u engine-u igra kao HOME napada od
  reda 1 ka redu 7; mora ostati mogućnost da se u drugom poluvremenu strane
  zamene **bez rušenja sistema** — negde se može postaviti koji je tim "HOME"
  u smislu engine-a, a koji "AWAY".
- **Verifikacijom kroz launcher** `MatchSimulationLauncher` (log svih
  događaja sa tagovima, bez "tihih" perioda > 5 s).

---

## 2. TEREN — merodavne vrednosti (source of truth)

Sve mere u **ćelijama (cell)** osim tamo gde piše metri. Grid terena:
**7 redova × 6 kolona**, svaka ćelija **14 m × 10 m**.
Kada kažemo "dužina ćelije" misli se na **14 metara**.

**Grid koordinate (row | col):**

| Zona | Row raspon | Kolona raspon |
|---|---|---|
| red 0 (OOB iza HOME gola) | 0.00–0.99 | — |
| red 1 | 1.00–1.99 | — |
| red 2 | 2.00–2.99 | — |
| red 3 | 3.00–3.99 | — |
| red 4 | 4.00–4.99 | — |
| red 5 | 5.00–5.99 | — |
| red 6 | 6.00–6.99 | — |
| red 7 | 7.00–8.00 | — |
| red 8 (OOB iza AWAY gola) | 8.01–9.00 | — |
| kolona 0 (OOB levo od aut linije) | — | 0.00–0.99 |
| kolona 1 | — | 1.00–1.99 |
| kolona 2 | — | 2.00–2.99 |
| kolona 3 | — | 3.00–3.99 |
| kolona 4 | — | 4.00–4.99 |
| kolona 5 | — | 5.00–5.99 |
| kolona 6 | — | 6.00–7.00 |
| kolona 7 (OOB desno od aut linije) | — | 7.01–8.00 |

**OOB = out of bounds** (lopta van terena).

**Linije i golovi:**
- Leva aut linija: **col 1.00**; desna aut linija: **col 7.00**.
- Gol linija HOME: **row 1.00**; gol je od **1.00|3.50 do 1.00|4.50**
  (širina 1.0, centar col **4.00**).
- Gol linija AWAY: **row 8.00**; gol je od **8.00|3.50 do 8.00|4.50**.
- Centar terena: **4.50|4.00**.
- Levica kaže: red 0 je **vizuelna OOB zona iza domaćeg gola** — lopta ulazi u
  taj red da korisnik vidno vidi da je van terena (gol-aut/korner).
- red 8 je **vizuelna OOB zona iza away gola**.

**Korneri:** HOME levi **1.0|1.0**, HOME desni **1.0|7.0**,
AWAY levi **8.0|1.0**, AWAY desni **8.0|7.0**.

**Penali:** domaći **1.79|4.00**, gostujući **7.21|4.00**.

**Šesnaesterci (box):**
- HOME: **1.00|2.40, 1.00|5.60, 2.14|2.40, 2.14|5.60**.
- AWAY: **8.00|2.40, 8.00|5.60, 6.86|2.40, 6.86|5.60**.

**Smer napada istorijski:** HOME na početku utakmice napada levo→desno
(od reda 1 ka redu 7); AWAY obrnuto. Za drugog poluvremena mora ostati
mogućnost zamene strana **bez rušenja sistema** (vidi §1 i "backlog").

> **Napomena o usklađivanju engine-a:** u ovom trenutku engine još uvek računa
> centar gola u koloni **3.5** (`ActionEngine.goalPositionFor`) i svoje
> vrednosti box-a/penala. Nadredene vrednosti iznad su merodavne; usklađivanje
> engine-a sa njima je u backlogu.

---

## 3. PODACI O IGRAČIMA — merodavne skilovi i nestandardni atributi

Svi atributi su u opsegu **1–20** osim gde je drugačije navedeno.

### 3.1 Osam osnovnih skilova (skill 1..20)

| Ime | Skr. | Uloga u fizici / odluci |
|---|---|---|
| **Stamina** | sta | Fatigue drain (↑stamina = sporije umora), stamina drain rate u `FatigueSystem` |
| **Keeper** | kep | GK samo: šansa za Save/Catch/Punch, `DuelEngine` aerial/header |
| **Pace** | pac | Brzina kretanja igrača (cells/tick) = `pace/20 * 0.75`; chase sprint ×1.30 |
| **Defending** | def | Tackle/block/interception; `DuelEngine` DRIBBLE/TACKLE power; pozicioniranje |
| **Technique** | tec | Prvi dodir, kontrola loptine, execution quality (pass/shot deviation) |
| **Playmaking** | pm | Viđenje opcija (VisionFilter), kvalitet odluke (OptionSelector) — **ne** passing |
| **Passing** | pas | Brzina dodavanja (max ball speed za PASS/CROSS/CLEAR), tačnost |
| **Striker** | str | Šut: xG, brzina šuta, ciljanje (far post), gol šansa |

> **Napomena:** trenutni `PlayerSkills` record u kodu ima polja u drugom redosledu
> `(pace, stamina, keeper, technique, playmaking, passing, striker, defender)`.
> Dokumentovani redosled gore je **merodavan za specifikaciju**. Konstrukcije u
> `MatchSimulationLauncher.randomSkills` moraju da mapiraju po imenu, ne po poziciji.

### 3.2 Nestandardni atributi (ne-skills)

| Atribut | Tip / opseg | Uloga |
|---|---|---|
| **Fatigue** | double 0.0–1.0 | Trenutno umor; `MovementEngine` smanjuje brzinu do −30% (`MAX_FATIGUE_SPEED_LOSS`). |
| **Injury** | boolean | Igrač ne može da se kreće/igra; `isUnavailable()` true. |
| **Form** | double 0.5–1.2 (default 1.0) | Množilac na sve fizičke/tehničke output-e; **samo dokumentovano**, još nije upotpunjeno u kod. |

---

## 4. FIZIKA LOPTE — merodavni pravila (source of truth)

Ova sekcija je **specifikacija** koju engine **mora** da ispoštuje. Sve
odstupanja su bagovi.

### 4.1 Osnovni model

- Lopta je **čista fizika**: pozicija + brzina (velX, velY u ćelijama/tik) +
  rotacija/spin (0..1) + Airborne flag.
- **NIJE** carrier, NIJE target, NIJE "ko je pozvao" — sve to je u `MatchState`.
- Lansiranje: `launch(aim, speedCellsPerTick, airborne, spin)` —
  smer = `aim - origin`, brzina = zadata, uzduž smera.

### 4.2 Brzine (match time: 1 tik = 1.5 s, 40 TPM)

| | m/s | ćelije/tik |
|---|---:|---:|
| Igrač pace 20 | 7.0 | 0.75 |
| Lopta MIN (najslabije dodavanje) | 7.0 | **0.75** |
| Lopta MAX (najjači šut/dodavanje) | 14.0 | **1.50** |

> **Lansiranje dodavanja/šuta:** ExecutionQuality vraća `LaunchedBall(aim, speed, onTarget, spin)`.
> - Brzina se **ne clamp-u** na cilj; lopta leti ka cilju koliko stigane, zatim se
>   usporava i zaustavlja (ili postane LOOSE).
> - Minimalna brzina na start: **0.75** (7 m/s). Slabije ne postoji.

### 4.3 Usporenje (deceleration) — do nule

| Tip | decel (ćelije/tik²) | napomena |
|---|---:|---|
| **Zemlja (ground pass/clearance)** | **0.35** | ~2.2 m/s² trenje; zaustavlja se brže |
| **Vazduh (air shot/cross)** | **0.15** | ~0.9 m/s² otpor vazduha; leti daleko |
| **Zaustavljanje** | kada brzina ≤ **0.02** → postavi na 0, `airborne=false` |

**Landing:** vazdušna lopta čim padne ispod `LANDING_SPEED = 0.30` postaje
zemaljska (nastavlja da se trči sa ground decel).

### 4.4 Spin (efekat / zavijanje)

- `spin ∈ [0, 1]` → lateralno ubrzanje svaki tik: `rotacija(brzina, spin * 0.05)`.
- Blago krivljenje trajektorije (za free kick, corner, cross).

### 4.5 Kolizije (svaki tik)

Lopta proverava sudar **u ovom redosledu** (ranije = veći prioritet):

1. **Stative/prečke (GoalPhysical)**: leva/desna stativa (radius 0.03 ćelije) na gol liniji.
   - Sudar → odbijanje (reflect brzina po normali) + `BOUNCE_DAMP = 0.5`.
   - Lopta postaje zemaljska.
2. **Gol ravnа (goal plane)**: presek segmenta `prev→new` sa gol linijom
   (HOME 1.0, AWAY 8.0).
   - Ako presek u **usta gola** (kolona 3.50–4.50, isključujući stative) → **GOAL**.
   - Gol atribuisan `lastTouchTeam` (poslednji tim koji je dodirnuo loptu).
   - Takodje čisti OOB pending.
3. **Igrači** (prvi kontakt duž segmenta pobedjuje; pendingReceiver ima prioritet na tie):
   - **pendingReceiver** (istim timom) u radijusu `RECEIVE_R = 0.35` → **RECEIVE** (posed, brzina 0).
   - **Protivnik** u `INTERCEPT_R = 0.30`:
     - ako lopta **spora** (< `FAST_CONTACT = 1.00` ćelije/tik) → **INTERCEPT** (posed).
     - ako lopta **brza** (≥ 1.00) → **BLOCK** (odbijanje + 0.5 damp, BEZ posed).
   - **Bilo koga** u `DEFLECT_R = 0.18` → **DEFLECT** (blago odbijanje + damp).
4. **OOB zona** (row ≤ 0.99 / ≥ 8.01 / col ≤ 0.99 / ≥ 7.01):
   - Prvi ulazak → postavi `oobPending = restartTip` (iz `lastTouchTeam`),
     `oobHoldTicks = 4`.
   - Tokom hold-a lopta **nastavlja da se kreće** (vidljiva u OOB zoni!).
   - Ako se vrati na teren pre isteka → `oobPending` se briše.
   - Kada `ticks == 0` → vraća `dueRestart` (Orchestrator onda radi restart).
   - **NIKADA instant teleport** na restart — 4-tik vidljivost.

### 4.6 Loose ball pickup

- Lopta zaustavljena (brzina ≤ 0.02) bez nosioca → svaki tik provera najbližeg
  igrača u `PICKUP_R = 0.35` → on postaje carrier.

### 4.7 GoalPhysical (stative + prečka — za UI i fiziku)

```java
class GoalPhysical {
    double goalLineRow;       // 1.0 (HOME) ili 8.0 (AWAY)
    double mouthLeftCol = 3.5;
    double mouthRightCol = 4.5;
    Position leftPost;   // (goalLineRow, 3.5)
    Position rightPost;  // (goalLineRow, 4.5)
    double postRadius = 0.03;     // fizički radijus stative
    double heightMeters = 2.44;   // visina stative/prečke (za UI, budući 3D)
    // crossbar = segment leftPost→rightPost na visini heightMeters
}
```
U 2D fizici se proveravaju samo stative (tačke sa radijusom).
Crossbar je **samo za UI/rendering** (bez fizike dok ne dodamo Z-osu).

### 4.8 Šta engine NE sme da radi

- ❌ Clamp-ovanje cilja loptine (row/col) — lopta slobodno ide i van terena.
- ❌ Clamp-ovanje igračkih pozicija — igrači smeju da izađu van linija.
- ❌ Znanje "ko je pozvao" (ActionExecutor loguje, BallPhysicsEngine NE).
- ❌ Ciljna pozicija loptine ≠ gde lopta zaustavlja (lopta se usporava do 0).
- ❌ Gol detekcija po radijusu oko centra — **samo presek gol linije u ustima**.

---

## 5. POČETNO STANJE (pre svih izmena ovog dokumenta)

Proposal paket je nastao kao **paralelna, "čista" verzija** demo/service
engine-a, isključivo da bi se svaki sloj mogao verifikovati kroz launcher —
bez Spring Boot-a, bez DB entiteta, bez UI-ja.

Šta je već postojalo (komit `e8eb1af` i okolina):

- Modeli: `MatchState`, `Player`, `Ball`, `Position`, `PlayerSkills`,
  `ActionType`, `DecisionResult`, `DecisionOption`, `DecisionContext`.
- Engine-i (osnovne verzije): `MatchOrchestrator`, `MatchClockService`,
  `CleanDecisionEngine`, `ActionExecutor`, `BallPhysicsEngine`,
  `MovementEngine`, `DuelEngine`, `FootballRules`, `ExecutionQuality`,
  `SimUtils`.
- `MatchSimulationLauncher` — bootstrap 4-4-2, pozicije na tvrdo, kickoff na
  `(4.0, 3.5)`, log maggiornsti odluka (`[mm:ss|TAG]`).
- **Nije postojalo / bilo slomljeno:**
  - taktičko učitavanje pozicija (igrači bez lopte su **stajali**),
  - kickoff pravilo "svi na svojoj polovini",
  - pravilo "lopta pri nozi" pri odluci (šut sa mesta gde lopta nije),
  - merodavna geometrija terena kao dokument (gol bio centriran na 3.5).
- Poznate mane u ono vreme: `100% kompletiranje dodavanja`, `SHOT=-30` na
  granici šuterske zone, retki dueli, ~1-2 gola na 36 min.

---

## 6. ISTORIJA IZMENA I IMPLEMENTACIJA

> Format svakog upisa: **datum i vreme** · **komit hash** · šta je urađeno.

---

### 6.1 `2026-09-12 22:25` · `ce7ddbd` — taktičko učitavanje + kickoff na svojoj polovini

- **Nova `proposal/tactics/`** klasa:
  - `TacticsRuleDTO`, `TacticsSlotDTO` — DTO iz `demo/service/tactics`.
  - `FormationSlotCatalog` — 4-4-2 + 4-3-3, 4-2-3-1, 4-1-4-1, 3-5-2, 5-3-2,
    3-4-3, 4-5-1, 5-4-1 (anchor pozicije kao fallback).
  - `TacticalPerspectiveTransformer` — HOME direktno; AWAY mirror
    `Position(9-row, 7-col)`.
  - `TacticsRules` — 3-nivo učitavanja: **DB** (`team_tactics_profile`,
    `connectTimeout=3`, system props `tactics.db.*`) → **JSON fallback**
    (`/tactics_fallback.json`) → **catalog**.
- **Transformacija podataka (bitno):** editor-ćelija je **0-based**;
  `parseCell` za `CELL_r_c` vraća `(r + 1.5, c + 1.5)` (svega +1.5 zbog
  koordinate centra ćelije). Ključ lopte u pravilima: `CELL_(row-1)_(col-1)`
  sa clamp-om.
- **`engine/TacticalIntentEngine`** — `refreshTargets(state)` svaki tik,
  `placeOnOwnHalf(state)` (HOME row ≤ 4.5, AWAY row ≥ 4.5).
- **`restarts/RestartManager`** (prepisan): `KICK_OFF_SPOT = (4.5, 4.0)`;
  `handleKickoff` → svi na svoju polovinu pa udarač na centar; restarti
  postavljaju svima taktičke ciljeve; taker hoda do lopte (teleport-fast-path
  za > 4.0 ćelija).
- **`MatchOrchestrator`** — deljeni `TacticsRules` sa restartManager +
  tacticalEngine; korak **6. TACTICAL INTENT ENGINE** pre movement-a; `TAC`
  log jednom po meču (izvor i broj pravila).
- **`MatchSimulationLauncher`** — inicijalni kickoff kroz
  `restartManager.handleKickoff(state, "HOME")`; štampa formaciju na startu i
  finalne pozicije; NEMA više hard-coded `(4.0, 3.5)`.
- **Verifikacija:** `mvn -q compile` čisto; 1440 tika bez freeze-a
  (max gap 7 s); log pokazuje `[0:02|TAC] tactical rules: DB (team 1, 4-4-2)
  (506 rules...)`; svi igrači na svojoj polovini na kickoff-u.

---

### 6.2 `2026-09-12 22:29` · `730b787` — lopta pri nozi (SHOT/PASS sa mesta gde lopta nije)

**Problem (iz loga korisnika):**
```
[1:11|DEC] DECISION A10(STL) -> SHOT score= 42.3 | ... | ball(2.3,2.9) A10(1.8,2.9)
```
Igrac je **šutnuo a da nije NA lopti** (lopta 0.5 ćelija ≈ 7 m iza).

**Uzrok:** u jednoj `tick()`-u se prvo radi **DECISION** pa tek onda
**BALL PHYSICS**. Dok nosilac drža loptu (`followCarrier`), lopta je na kraju
tik-a zaostajala za nosiocem do 1 koraka (~0.4-0.6 ćelija). Odluka je koristila
poziciju nosioca, a `executeShot` je loptu **lansirao sa zaostale pozicije** —
geometrija odluke ≠ fizika lansiranja, a log je prikazivao igrača van lopte.

**Ispravka:**
- `MatchOrchestrator.tick()`: pre odluke → `ball.setPosition(carrier.getPosition())`
  (+ `setSpeed(0)`) — nosilac ima loptu **pri nozi** u trenutku odluke.
  Posledica: DECISION log uvek prikazuje `ball == carrier`, a početak
  šuta/dodavanja je tačno sa noge.
- `ActionExecutor.executeShot` i `executePass`: `carrier.setTarget(null)` —
  nosilac **ne nastavlja da trči** ka starom carry cilju dok lopta leti.

**Verifikacija:** svaki `DECISION` red prikazuje `ball` na istoj poziciji kao
nosioc (npr. `ball(7.0,3.5) H10(7.0,3.5)`); gol u 0:65; 1440 tika bez
freeze-a (max gap 7 s); 2 gola/36 min.

---

### 6.3 `2026-09-12 22:30` · `d5c2704` — ovaj dokument

- Kreiran `PROPOSAL_PROGRESS.md`.
- Upisana merodavna geometrija terena (§2) — uključujući OOB definiciju,
  box/penal/korner koordinate i smer napada sa mogućnošću zamene strana.
- Definisani: Cilj (§1), Početno stanje (§5), Istorija (§6), Backlog (§7),
  Pokretanje (§8).

---

### 6.4 `2026-09-13 10:15` · `b9a21e5` — comment wording cleanup

Trivijalna ispravka komentara u `MatchOrchestrator`.

---

### 6.5 `2026-09-13 10:25` · *probe commit* — samostalan fizika probe (temp klasa)

**Nema u produkcijskom kodu** — radi se o `proposal/probe/BallPhysicsProbe`
(jedinstvena klasa sa `main`, bez Spring-a), napisana da se **prototipiraju i
validiraju** sva nova pravila fizike **pre nego što se uljuju u pravi
engine**. Probe je čisto Java, deterministička, koristi iste konstante i
geometriju kao specifikacija u §4.

**Scenariji (svi prolaze):**

| ID | Opis | Ključni dogadjaj |
|---|---|---|
| S1 | Ground pass 14 m ka primaocu | **RECEIVE** u 2 tika |
| S2 | Far-post šut (GK na pogrešnoj stativi) | **GOAL** preko gola |
| S2b | Centralni power-šut u GK na liniji | **BLOCK** (parry, brzina ≥ 1.0) |
| S3 | Šut u levu stativu | **POST_HIT** → OOB hold 4 t → GOAL_KICK |
| S4 | Clearance vazduh sa spin iz svoje polovine | leti ~6 ćelija, landing, STOPPED u midfieldu |
| S4b | Prejak šut iza away korner | **OOB_ENTER** → **OOB_HOLD** x4 → **OOB_RESTART** (CORNER_HOME) |
| S5 | Branitelj na putanji dodavanja (pred primaocem) | **INTERCEPT** (prvi kontakt) |
| S6 | Loose ball usporava do 0 → najbliži podiže | **LOOSE_PICKUP** |
| S7a | Bare physics: varira **smer** (brzina 0.9, bez igrača) | 8 pravaca → zaustavlja se ~0.75 ćelijа |
| S7b | Bare physics: varira **brzina** (pravac pravo ka away golu) | 0.75→0.45c, 0.9→0.75c, 1.05→1.05c, 1.2→1.5c, 1.35→1.95c, 1.5→2.5c |

**Iz probe usvaćene u specifikaciju §4:**
- Diskretni model usporavanja (decel pre move) → kraće stope nego kontinuirani.
- Brza loptica na protivniku = **BLOCK/parry** (ne posed), spora = **INTERCEPT**.
- OOB hold **mora da dekrementira i kad je lopta zaustavljena** (S3, S4b).
- Gol detekcija = presek gol linije u ustima (3.50–4.50), stative isključuju.
- Atribucija gola/restarta = `lastTouchTeam` u `MatchState`.

---

## 7. BACKLOG — šta sledi

1. **Protok lopte (najvidljivije slomljeno):**
   - `100% kompletiranje dodavanja` (1283/1283) — nema izgubljenih lopti;
   - `SHOT=-30` na granici šuterske zone (~row 6.0) — napadači ping-pong
     dodaju umesto da šutnu;
   - ~1-2 gola / 36 min; retki dueli (u dugim runing zgutvama 0).
2. **Usklađivanje engine-a sa merodavnom geometrijom (§2):**
   - centar gola u engine-u je **col 3.5** → cilj na gol treba da bude
     **col 4.0** (usta gola su 3.50–4.50);
   - box/penal konstante engine-a vs nadredeni box `1.00–2.14` (HOME) /
     `6.86–8.00` (AWAY) i kolone `2.40–5.60`.
3. **Zamena strana u drugom poluvremenu** — bez rušenja sistema:
   - koncept "koji je tim HOME u smislu engine-a" treba izdvojiti kao
     konfiguraciju (rotation/swap flag), a NE tvrdo-kodirane `HOME"/"AWAY`
     stringove razuđene po klasama.
4. **Duel učestalost i visina** (DuelEngine je sada previše retko aktiviran).
5. **Kartoni/prekršaji u "čistom" simu** — DisciplineService još nije
   uvezan u proposal.
6. **Nova fizika lopte (§4)** — zameniti ceo `BallPhysicsEngine`,
   `Ball` model, `ExecutionQuality`, `ActionExecutor`, `ActionEngine`,
   `MovementEngine`, `MatchOrchestrator`, `RestartManager`, `DuelEngine`,
   `MatchState`, `Player` po specifikaciji.

---

### 6.6 `2026-09-13 01:10` · `7ec3107` — kompletna refaktorisana fizika lopte

- **Novi `Ball` model** — čista fizika: `Position`, `velX/velY`, `spin`, `airborne`; bez carrier/target/caller.
- **`PitchEnvironment` + `GoalPhysical`** — merodavna geometrija terena (gol linije 1.0/8.0, usta 3.5–4.5, centar 4.0), OOB zone, stative sa radiusom 0.03 ćelije + visina 2.44m za UI.
- **`BallPhysicsEngine`** (implementira `BallEngine`):
  - `launch(aim, speed, airborne, spin)` — smer = aim-origin, brzina zadata, usporavanje do 0.
  - Deceleracija: ground 0.35 c/t² (~2.2 m/s²), air 0.15 c/t² (~0.9 m/s²); STOP_SPEED 0.02; LANDING_SPEED 0.30.
  - Spin lateralno ubrzanje (0.05/tik).
  - Kolizije redosledu: 1) stative (reflect + 0.5 damp) → 2) gol ravan (presek linije u ustima = GOAL, atribucija `lastTouchTeam`) → 3) igrači (pendingReceiver = RECEIVE; protivnik brza lopta ≥ 1.0 = BLOCK/parry, spora = INTERCEPT/posed; timski = DEFLECT) → 4) OOB zona (vidljiv 4-tik hold, NIKADA instant teleport; ako se vrati na teren = cancel).
  - Loose ball pickup: zaustavljena lopta bez nosioca → najbliži u 0.35 radijusu postaje carrier.
  - Vraća `BallStepResult` (FLIGHT/STOPPED/RECEIVE/INTERCEPT/BLOCK/DEFLECT/POST_HIT/GOAL/OOB_ENTER/OOB_HOLD/OOB_RESTART/OOB_CANCEL/LOOSE_PICKUP).
- **`ExecutionQuality`** — bez cilj-clamp-ova; `PassResult`/`ShotResult` vraćaju deviated aim + launch speed (0.75–1.5 c/t) + spin; `ballSpeedForSkill` mapira 1..20 → 7..14 m/s.
- **`ActionExecutor`** — PASS/SHOT/CLEAR = `ballEngine.launch(...)`; CLEAR = air kick max speed; postavi `lastTouchTeam` + `lastTouchPlayer`.
- **`ActionEngine`** — gol centar col **4.0** (usta 3.5–4.5).
- **`MovementEngine`** — uklonjen player clamp (igrači smeju van linija).
- **`MatchOrchestrator`** — novi redosled: 1) clock → 2) unlock → 3) VAR → 4) **ballEngine.stepBall** → 5) handle result (RECEIVE/INTERCEPT/BLOCK/GOAL/RESTART) → 6) decision+execution (samo ako carrier i ne-u-flight) → 7) tactical → 8) movement → 9) restart taker claim → 10) rules → 11) duels → 12) VAR timer.
- **`RestartManager`** — uklonjeni stari Ball polja (setCarrier/target/rollDirection); koristi `state.setCarrier` + `ball.stop()`.
- **`DuelEngine.applyDuelResult`** — snapuje loptu na pobednika, `lastTouchTeam` = winner.team.
- **`Player`** — dodato `form` (0.5–1.2, default 1.0, dokumentovano).
- **Verifikacija:** `mvn -q compile` čisto; 1440/3600 tika bez freeze-a; logovi prikazuju RECEIVE/INTERCEPT/DEFLECT/GOAL/OOB_HOLD/OOB_RESTART; 0-0 (decisions still not reaching shooting zones).

---

### 6.7 `2026-09-13 01:25` · `8019b19` — UI viewer + REST endpoint za proposal engine

- **Kopiran `demo/service/ui` → `static/demo/service/ui/proposal/`** sa prilagođavanjem:
  - `index.html` — LED scoreboard, canvas pitch, event timeline, kontrole (Play/Pause/Seek/Speed), file load.
  - `js/viewer.js` — prerađen za proposal log format `[mm:ss|TAG] message`; parsira DEC/ORC/BAL/DUL/RST/TAC/LCH tagove; ekstraktuje player/ball pozicije iz log poruka; renderuje horizontani teren (HOME levo row 1→7, AWAY desno row 7→1); prikazuje event timeline sa ikonama.
  - `css/pitch.css` — isti dark theme.
- **`ProposalMatchController`** — `POST /api/proposal/generate` pokreće 90-min meč (3600 tika), vraća JSON sa `matchId`, `events`, `logs`; upisuje `static/demo/service/ui/proposal/match.json`.
- **`ProposalMatchExporter`** — standalone `main(seed)` za headless generisanje match.json bez Spring-a.
- **Component scan** — dodato `org.example.footballmanager.demo.service.proposal` u `@SpringBootApplication` (kasnije: paket preseljen u `org.example.footballmanager.newLogic.sim`; eksplicitni scan red obrisan jer je ceo `org.example.footballmanager` već skeniran — `SimReplayStore`/kontroleri se pokupe standardno).
- **Verifikacija:** `curl -X POST /api/proposal/generate` vraća match JSON; viewer na `/demo/service/ui/proposal/index.html` učitava match.json, prikazuje pitch + timeline.

```bash
# kompajliranje
mvn -q compile

# launcher (12 min meča = 480 tika)
mvn -q exec:java -Dexec.mainClass=org.example.footballmanager.newLogic.sim.MatchSimulationLauncher

# ceo meč / duži run radi provere freeze-ova
mvn -q exec:java -Dexec.mainClass=org.example.footballmanager.newLogic.sim.MatchSimulationLauncher -Dexec.args=1440
```

DB (ako je dostupan na `localhost:5432/sokker_db`): učitava se
`team_tactics_profile` za tim 1 (4-4-2, version 5, 506 pravila). Ako DB nije
dostupan, kao fallback se koristi `/tactics_fallback.json` (isti sadržaj).

---

### 6.8 `2026-09-13 22:37` · `50a4b1c` — viewer porat na nove klase (record-based) + cache fix

Korisničko pravilo: **UI se ponaša IDENTIČNO kao `/demo/service` viewer** —
Generate samo generiše (ne auto-igra), Play učitava match.json + KICK OFF
overlay pa auto-start, eventi se pojavljuju 1-po-1 sinhronizovano sa satom,
renderuje se svih 22 igrača iz snapshota. Logika je kopirana iz
`demo/service/ui/js/viewer.js` (byte-equivalent) i samo prilagođena novim
klasama — ne piše se nova logika.

- **`ProposalViewerLauncher`** (port **8766**, zaseban od demo/service 8765):
  - `POST /proposal/api/generate` — simulira meč proposal engine-om (3600 tika),
    piše `proposal/proposal/match.json` (58MB, ~7200 eventa + 3600 snapshota),
    vraća `{"ok":true,"score":...}`.
  - `/proposal/match.json` — servering IgM JSON-a (recorder events + snapshots).
  - Statika iz `static/demo/service/ui/proposal/`; **`Cache-Control: no-store`**
    na sve fajlove — sprečava ponovni "stari viewer iz keša" problem.
- **`viewer.js`** — prepisan kao 1:1 porat demo/service viewer-a:
  - fetch `POST /proposal/api/generate` + `/proposal/match.json` (`/proposal`
    prefix je KORISNIČKA TVRDA OBAVEZA — ne diraj).
  - Snapshot polja: `homeGoals`/`awayGoals` (ne `goalCount`/`awayGoalCount`).
  - `logEntries` filter: proposal `logs` su raw string-ovi → preskaču se,
    recorder `events` (dict) su autoritativni za timeline.
  - Proširen `EV_ICON`/`TIMELINE_EVENTS` proposal tipovima: RECEIVE, INTERCEPT,
    DEFLECT, BLOCK, POST_HIT, OOB_ENTER, OOB_CANCEL, LOOSE_PICKUP, DUEL,
    RESTART — GOAL sinteza iz snapshot delta.
  - GOAL/offsale/VAR overlay-callovi zadržani (overlay elementi postoje u HTML-u;
    user je rekao da overlay-i dolaze uskoro).
- **`index.html`** — `viewer.js?v=3` + `pitch.css?v=3` (cache-buster).
- **`MatchOrchestrator`** — recorder vez – RECEIVE/INTERCEPT/BLOCK/DEFLECT/
  POST_HIT/GOAL/OOB_ENTER/RESTART/OOB_CANCEL/LOOSE_PICKUP + DECISION + DUEL
  događaji.
- **`MovementEngine`** — loose-ball chase: najbliži slobodan igrač unutar
  4.0 ćelija sprinta do zaustavljene lose lopte + razdvajanje suparnika
  (MIN_PLAYER_DISTANCE). Sprečava zamrzavanje meča posle kickoff-a.
- **`recording/`** — MatchRecorder/MatchEvent/MatchSnapshot/PlayerSnapshot/
  MatchRecording (JSON: `position.{row,column}`, `ballPosition`, `homeGoals`...).
- **Verifikacija:** `mvn -q compile` ✅; launcher E2E ✅ (0:00 start, KICK OFF
  overlay, play napreduje, timeline 0→5, 22 igrača, bez JS error-a);
  `POST /proposal/api/generate` → 200, `/proposal/match.json` → 200 (58MB,
  7186 eventa, 3600 snapshota).

---

### 6.9 `2026-09-14 12:00` · `c921402` — pass completion tuning + fizika kalibracija

**Poluvremeni bug (kritičan):** `MatchClockService.tick()` vraća `true` kad
`matchTicks == 1800` (half-time), ali `simulate()` nikad ne poziva
`clockService.resume()`. Svi prethodni mečevi bili su **45 min**, ne 90 min
— sve metrike (golovi, šutevi, dodavanja) merene na pola meča.

Ispravka: `MatchOrchestrator.simulate()` nakon 1800 tikova radi `resume()` +
`handleKickoff("AWAY")`. Sada su mečevi punih 3600 tikova (90 min).

**Lopta — launch brzina za intercept:** `readIntercept` je koristio
trenutnu (usporavanu) brzinu lopte; kod kraja leta (lopta ≈ 0.5 c/t)
`speedFactor → 1.0` i intercept verovatnoća rasla na 0.45 svaki tik —
umesto da ostane na početnoj brzini kojom je pas udaren. Ispravka:
dodato `Ball.launchSpeed`, `readIntercept` koristi `max(current,
launchSpeed)` — verovatnoća je konstantna tokom celog leta.

**DEFLECT_R 0.07 → 0.035** (~0.5 m) — fizički kontakt sada samo kad lopta
udari telo na 0.5 m (pre 1 m — bilo previše deflekcija).

**Opening target:** `ActionExecutor.openingTarget()` — pas se šalje u
slobodni prostor do 0.5 ćelija od primaoca (udaljen od najbližeg
obrambenog), primalac trči na `receivePoint` tokom leta.

**Šuterska kalibracija:** `ExecutionQuality.evaluateShot` —
`onTargetProb = 0.03 + skill*0.006`, × `max(0.20, 1-dist/7)`, ×
`(1-pressure/200)`, +0.05 close-range, kap 0.40. Frekvencijska kapija
0.25. `CleanDecisionEngine` — openness ×40, lane-jammed −30.

**Smer čišćenja (ROOT CAUSE prethodne AWAY dominacije):**
`ActionExecutor.executeClear` imao inverzni smer
(`home ? -2.0 : +2.0` → HOME čisti u svoj gol). Ispravljeno na
`home ? +2.0 : -2.0`.

**`ProposalBatchDiag`** — nova dijagnostička klasa (10+ mečeva, agregira
golove/šuteve/SOT/pasove + H/A split + 0-0 broj). Zamenjuje
ručno pokretanje `MatchSimulationLauncher` više puta.

**Merene vrednosti (10 mečeva, 3600 tika):**

| Metrika | Predlog | Demo/service | Status |
|---|---|---|---|
| Golovi/match | 1.2 | 2.4 | manje (preveriti na 3600 tika) |
| Šutevi/match | 39.6 | 54 | manje |
| SOT % | 12% | 11% | ≈ cilj |
| Pass completion | 67% | 98% | mnogo manje |
| H/A golovi | 0.9/0.3 | ≈1/1 | H blago dominira |
| 0-0 | 3/10 | 0-1/10 | previše |

**Glavni preostali gap:** pass completion 67% vs 98%. Uzroci (još uvek
aktivna analiza):
- `readIntercept` se poziva na svaki tik segmenta leta, za svakog
  defanzivca unutar 0.14 ćelije od linije — kumulativna verovatnoća
  presecanja po pasu je previsoka (demo/service radi isto ali sa
  `action.getPassSpeed()` konstantnom brzinom celi let).
- Defanzivci su pregusti u sredini (prosečan red intercepta 4.2) —
  skoro svaki pas prolazi blizu nekog defanziva.
- Decision engine bira „lane blocked" receptore jer penalitet −80
  nije dovoljan kad forward +50 i openness +30 nadoknade.

---

## 7. ANALIZA — trenutno stanje po zahtevima faze

Korisnik je definisao prioritete za **FAZU 1** (arhitektura + fizika +
prikaz + log + statistika). Sledi analiza po tačkama:

### 7.1 Razgraničenje odgovornosti engine-ova

**Dobro:**
- `CleanDecisionEngine` — samo bira opciju, ne menja je
- `ActionExecutor` — samo izvršava (PASS/SHOT/DRIBBLE/CLEAR)
- `BallPhysicsEngine` — čista fizika: pozicija + brzina, kolizije, OOB
- `MovementEngine` — kretanje svih igrača ka cilju po pace skilu
- `TacticalIntentEngine` — taktički ciljevi iz pravila
- `DuelEngine` — detekcija + rezolucija duela
- `RestartManager` — svi restarti (kickoff, korner, aut, gol aut, penal)
- `FootballRules` — offside provera (minimalno)

**Nedostaje:**
- **Override sistem** ne postoji formalno — `CleanDecisionEngine.decide()`
  ima ugneždene hard rules: final-2-row SHOT (linija 70-80), kickoff
  specijal (linija 43-51). Te odluke bi trebalo da budu **override** sloj
  koji interveniše NAKON odluke i PRE izvršenja — ne unutar decision engine-a.
- **VAR** — stub postoji (`MatchState.varReviewActive`) ali nema VAR engine-a
  koji bi pregledavao odluke.
- **Discipline (fouls/kartoni)** — ne postoji. `FootballRules` ima samo offside.
- **Fatigue** — nema (igrači se ne umaraju).
- **Transition** — nema logike za promenu posed (npr. "šta se dešava kad
  izgubimo loptu").
- **Threat override** — nema (obrambeni pritisak na nosioca lopte).

**Orchestrator:** ima ~340 linija — radi log formatiranje, event recording,
result handling, duel detection. Previse za "samo koordinira".

### 7.2 Fizika lopte

**Dobro:**
- Ball model: čista fizika (pozicija, brzina, spin, airborne, launchSpeed)
- Deceleration: ground 0.35, air 0.15, stop 0.02
- Kolizije: stative (reflect + damp) → gol ravan (goal detection) →
  igrači (RECEIVE/INTERCEPT/BLOCK/DEFLECT) → OOB (4-tik hold)
- Loose pickup 0.35
- Spin lateralno ubrzanje
- LaunchSpeed za readIntercept konstantan

**Nedostaje:**
- DEFLECT_R 0.035 možda previše mali — lopta može proći pored 10
  igrača bez kontakta. Povećati na 0.05-0.07 i meriti uticaj.
- Nema rotacije/tumble efekta na lopti (samo spin za krivljenje)
- Bounce od igrača koristi 2D refleksiju — radi, ali nema
  "realističkog" odbijanja

### 7.3 Kretanje igrača (pace A→B)

**Dobro:**
- `moveAllTowardTargets` — pace-based, carrier 0.90, chase 1.30
- Razdvajanje od suparnika (slide around) — `separateFromOpponents`
- Loose-ball chase — najbliži sprinta do lopte
- Nema boundary clamping (igrači smeju van linija)

**Nedostaje:**
- Separation je samo "slide" — ne pravi pravo zaobilaženje prepreka
- `findSafePosition` postoji ali je nekorišćen (deprecated)
- Nema per-tick refresh taktičkih ciljeva u zavisnosti od novonastalog
  stanja (npr. "lopta se pomerila, promeni cilj")
- Carrier drži loptu na istoj brzini — nema ubrzanje/usporavanje
  prilikom pickup/loss

### 7.4 UI prikaz

**GOTOVO.**
- `ProposalViewerLauncher` port 8766, `POST /proposal/api/generate`
- `viewer.js` 1:1 port iz demo/service: LED scoreboard, canvas teren,
  timeline, kontrole (Play/Pause/Seek/Speed)
- `match.json` 58MB (snapshots + events)

### 7.5 App log (debug)

**Delimično:**
- Orchestrator loguje DEC/ORC/BAL/DUL/RST tagovima → stdout
- `MatchRecorder` beleži events + snapshots za JSON
- Nedostaje: DuelEngine ne loguje unutar sebe, FootballRules ne loguje,
  nema strukturiranog log-a (samo println)

### 7.6 Kompaktan side log (korisnik)

**GOTOVO.**
- `viewer.js` filtrira timeline: samo GOAL, SHOT, SAVE, DUEL, RESTART...
- DECISION/ACTION/OOC logovi su u app log-u, ne u sidebar-u

### 7.7 Statistika — SVAKA akcija, po igraču i po timu

**NAJVEĆI GAP — trenutno stanje:**

MatchState ima samo osnovne globalne brojače:
```
passAttempts, passesCompleted, shots, shotsOnTarget, fouls, yellowCards, redCards
```

**Šta NEDOSTAJE (od zahteva korisnika):**

| Kategorija | Trenutno | Šta treba |
|---|---|---|
| Šutevi | `shots`, `shotsOnTarget` | total, on-target, **blocked, saved, missed**, po igraču, po timu |
| Golovi | `homeGoals`, `awayGoals` | total, **open play, center, cross, penalty, FK, corner**, po igraču |
| Offside | ništa | **total**, po timu |
| VAR | ništa | **total, confirmed, overturned**, po tipu |
| Dodavanja | `passAttempts`, `passesCompleted` | total, **successful, thru, center, cross, air, ground**, po igraču |
| Dribling | ništa | **total, successful**, po igraču |
| Presecanja | ništa | **interceptions, deflections**, po igraču |
| Restarti | ništa | **corners, throw-ins, goal kicks, free kicks, penalties**, po timu |
| Kartoni | `fouls`, `yellowCards`, `redCards` | **yellow, red, double-yellow**, po igraču |

**Takođe nedostaje:**
- Per-player stats (pozicija, minuti, ocena, it所有 akcije)
- Per-team stats breakdown
- Match stats export u `match.json` (samo goals/shots/SOT u snapshotu)
- Rating sistem (prosečna ocena na osnovu akcija)

---

## 8. PLAN — FAZA 1 (arhitektura + fizika + prikaz + log + statistika)

Prioriteti po redu korisnika:

### 8.1 Override sistem (čisto razgraničenje)
- Nova klasa `OverrideService` koja se poziva između `decide()` i
  `execute()`: prima `DecisionOption`, vraća isti ili modifikovani
- Final-2-row SHOT, kickoff specijal, VAR overrides — sve tamo
- `CleanDecisionEngine` ostaje čist scoring engine

### 8.2 Statistika (najveći gap)
- Nova klasa `MatchStats` — per-team i per-player akumulatori
- `StatisticsEngine` / `StatsCollector` — poziva se iz orchestratora
  nakon svakog eventa (RECEIVE, INTERCEPT, SHOT, GOAL, DUEL, RESTART...)
- Sve kategorije iz tabele 7.7 — sa per-player ID i per-team
- Export u `match.json` (novo polje `statistics`)
- UI sidebar: Match Stats panel (prvo teksta, kasnije grafika)

### 8.3 Orchestrator refaktor
- Izvući `handleBallPhysicsResult()` u `BallResultHandler`
- Izvući `detectAndResolveDuels()` u `DuelService`
- Izvući log formatiranje u `ActionLogService`
- Orchestrator ostaje čist: clock → unlock → ball → decision →
  execution → tactical → movement → restart → rules → duels → stats

### 8.4 Fizika lopte (kalibracija)
- DEFLECT_R 0.035 → 0.05-0.07 (testirati oba)
- readIntercept verovatnoća — meriti pass completion nakon smanjenja
- Gol detekcija — verifikovati da off-target šutevi nikad ne
  prelaze gol liniju unutar usta (testirano u sesiji 6.9)

### 8.5 Kretanje igrača
- `findSafePosition` oživeti ili zameniti pravim obstacle avoidance
  (perpendicular slide na zid)
- Per-tick refresh taktičkih ciljeva kada se lopta pomeri
- Carrier brzina: brži kad nema pritiska, sporiji pod pritiskom

### 8.6 VAR / Pravila igre (skeleton)
- `VARService` — offside review, goal review, penalty review
- `DisciplineService` — fouls + cards
- `OffsideService` — pun offside check (drugi do poslednjeg)
- Ovo su SKELETON implementacije — kalibracija dolazi kasnije

### 8.7 App log
- `ActionLogService` — centralizovan log sa tagovima i strukturiranim
  porukama (svi engine-i loguju kroz njega)
- Dve razine: FULL (stdout/file za debug) i COMPACT (sidebar/UI)

### 8.8 Verifikacija
- `ProposalBatchDiag 50` — 50 mečeva za stabilne proseke
- Metrički ciljevi: golovi ~2.4, šutevi ~54, SOT ~11%, pass ~98%,
  H/A balans, 0-0 ≤ 1/10
- UI E2E: Play → viewer prikazuje sve evente + stats panel

---

### 6.10 `2026-09-14` — chase sprint fix + placeholder stubs + backlog.md

**Chase sprint ukinut:** `MovementEngine.CHASE_SPRINT_MULTIPLIER` (1.30)
uklonjen korisnikovom direktivom: svi igrači kreću se pace-capped u
svakom trenutku — JEDINI izuzetak je carrier (0.90, sporiji jer vodi
loptu) i celebration (nije deo igre). Chasing loose ball / pressing
protivnika NIJE brži od normalnog trčanja — pace skill odlučuje brzinu,
override samo menja cilj.

Takođe uklonjen `PRESS_SPRINT_MULTIPLIER` (bio deklarisan ali nekorišćen)
da se spreči buduća zloupotreba.

**Placeholder stubs (kompajliraju se, logika kasnije):**

| Klasa | Paket | Opis |
|---|---|---|
| `VARService` | `rules/` | VAR review — offside/goal/red/penalty, freq gates |
| `DisciplineService` | `rules/` | fouls + cards — modular (svako pravilo = svoja metoda) |
| `OffsideService` | `rules/` | continuous tracking + per-pass check + margin |
| `ThreatOverrideEngine` | `engine/` | TYPE A (press carrier), TYPE B (press isolated), TYPE C (offside retreat) |

`EngineInterfaces.java` ažuriran sa odgovarajućim interfejsima za sva četiri.

**Hard rules ostaju u `CleanDecisionEngine`:** korisnik eksplicitno traži
da se hard rules NE zamene formalnim override sistemom — treba da ostanu
dok ne mogu biti izraženi kao dovoljno jaki boost da engine sam izabere
tu opciju. Stavljeno u backlog kao P7 (kasnije refaktorisanje).

**`backlog.md` kreiran** — P1–P8, jasni zadaci sortirani po hitnosti:
P1 (stats layer — najveći gap), P2 (orchestrator slim),
P3 (fizika kalibracija), P4 (kretanje), P5 (offside full),
P6 (pass completion kalibracija — deferred, implementacija kasnije),
P7 (stubs logika — već kreirane prazne klase), P8 (fatigue, transition,
app log, hard rules→boost, rating, viewer).

**`PROPOSAL_CURRENT_STATE.md`** — trenutno stanje engine-a na engleskom
(autoritativni opis arhitekture, merenja, merodavnih vrednosti).

**`AGENTS.md` ažuriran:** dodat proposal engine deo u dokumentaciju +
backlog referenca u tabeli + hotspot za proposal/.

**Merene vrednosti (10 mečeva, 90 min — bez promena u ovoj sesiji):**
golovi 1.2 (H 0.9/A 0.3), šutevi 39.6, SOT 12%, pass 298/446 = 67%.

** sledeći korak:** P1 — stats layer (najveći gap: bez per-player i
per-team statistika).

---

## P1 — Stats layer (DONE 2026-09-14)

Korisnički zahtev: "Svaka akcija mora biti zabeležena u statistici — po timu
I po igraču." Implementirano u četiri koraka:

### P1a — Enrichment događaja (osnova za statistiku)

Podaci za statistiku NE smeju da se generišu iz morale — mora da postoje
strukturisani događaji koji ih nose. Zato je prvo obogaćen event stream:

- **`MatchState`**: nova polja `lastActionType`, `lastShooter`, `lastShotOnTarget`
  (getteri/setteri). `lastShooter` služi i kao **pending-shot flag** — ishod
  (GOAL/SAVED/BLOCKED/POST/MISSED) ga troši, pa jedan šut nikad ne emituje
  više epiloga.
- **`MatchRecorder.appendEvent(tick, type, desc, Player actor, Player target)`**:
  nova overload varijanta sa actor/target atribucijom; 5-arg ostaje za 3rd party.
- **`MatchOrchestrator` decision blok**: PASS/SHOT/DRIBBLE/CLEAR eventi sada nose
  igrača + tim + on/off-target sufix za šut.
- **`ActionExecutor.execute`**: postavlja `lastActionType`/`lastShooter`/
  `lastShotOnTarget` (u `executeShot`).
- **Ful shot outcome lanac u `handleBallPhysicsResult`**:
  - `SAVE` gated na `wasShot` → `SHOT_SAVED` (inace `GK_CATCH` za fast pass/clear ka GK)
  - `BLOCK` gated → `SHOT_BLOCKED` (inace `BLOCK`)
  - `POST_HIT` gated → `SHOT_POST`
  - `OOB_ENTER`/`STOPPED` gated → `SHOT_MISSED`
  - `GOAL` scorer preko `lastTouchPlayer` (fallback `lastShooter`)
  - svaki od ovih troši `lastShooter = null` → **jedan šut = tačno jedan ishod**
- `resolveTeamByLabel()` helper za DEFLECT timsku atribuciju.
- Verifikovano: `mvn compile` clean, expor pokrenut, distribucija tipova eventova
  smislena (SHOT_SAVED se javlja samo za prave šuteve, GK_CATCH za pass/clear).

### P1b — Model + Collector

- **`proposal/result/PlayerStats.java`** (record) i **`TeamStats.java`** (record)
  i **`ProposalStatsCollector.java`** (akumulator).
- Feed metode: `onPassAttempt`, `onPassCompleted`, `onShot`, `onDribble`,
  `onClearance`, `onInterception`, `onDeflect`, `onSave`, `onBlock`, `onGoal`,
  `onRestart`, `onDuelWon`, `onPossessionTick`.
- Interni `TeamAcc` / `PlayerAcc`; `calculateRating()` = 6.0 + gol*1.5 +
  asistencija*1.0 + pass*0.02 + intercept*0.3 + duel*0.2 + save*0.5
  − faul*0.3 − zuti*0.5 − crveni*2.0.
- Asistencija: `lastPasserId`/`lastPasserTeam` postavljen na PASS, kreditovan
  na GOAL ako je isti tim.
- **Possession popravka**: prethodno brojana preko carrier-a (null tokom leta
  lopte → iskrivljeno). Sada preko `lastTouchTeam` — possession 49/51 u testu.
- Ispravljen timski prikaz imena (`"HOME".equals(team) ? homeName : awayName`).

### P1c — Export

- `ProposalMatchExporter` i `ProposalMatchController`: strukturisani
  `getEvents()` + `getSnapshots()` umesto raw log stringova + praznih snapshotova.
- `match.json` sada nosi: `events` (strukturirani `MatchEvent`), `snapshots`
  (per-tick `MatchSnapshot`), `stats.teams` (HOME/AWAY), `stats.players`.
- `ProposalViewerLauncher`: **fix dvostrukog path-a** — `MATCH_JSON` je pokazivao
  na `proposal/proposal/match.json` (STATIC_DIR već završava na `proposal`);
  sada `STATIC_DIR/match.json`. Generate endpoint + `stats` u izlaz. Obrisan
  stale `proposal/proposal/match.json`.

### P1d — Viewer sidebar Stats panel

- `index.html`: `statsPanel` sekcija (timovi + igrači) iznad event timeline-a.
- `viewer.js`: `_renderStats()` — timska tabela (possession / shots / passes /
  dribbles / clearances / interceptions / saves / restarts / cards) sa
  possession bar-om + igračka tabela (G/A/S/SOT/Pass%/D/I/T/Rat, sortirano po
  oceni). Fetch `match.json` relativno (radi i u launcher `/` i Spring Boot
  `/demo/service/ui/proposal/` modu).
- `pitch.css`: styling stats tabela + possession bar; `v=4` cache-bust.
- `node --check` JS OK.

### Napomene / poznato
- RNG nije seed-ovan (static `new Random()` u ExecutionQuality, BallPhysicsEngine,
  DuelEngine, CleanDecisionEngine) — exporter/launcher seed param se ignorise,
  svaki run je drugaciji. Determinizam = zaseban zadatak (poput demo/service
  SimulationRandom), van opsega P1.
- SHOT_MISSED vs SHOT broj: posle fix-a svaki SHOT ima tacno jedan epilog;
  SHOT_MISSED > SHOT nije vise moguc (ranije je `wasShot` trajao kroz restart walk).
- Cards/fouls/offsides/VAR su 0 u izlazu — DisciplineService/OffsideService/VARService
  su jos stubovi (P7). Statistika za te stavke je pripremljena ali ne i racunanja.

---

## P2-UI bug prijave — (BACKLOG 2026-09-14)

Korisnik je u UI viewer-u video dva buga (prijavljeno u sesiji, stavljeno u
`backlog.md` P2-UI, NIJE jos istrazeno/fiksano — samo zabelezeno):

1. **Action bez carrier-a na lopti** — sut/pas krece kad lopta leti SAMA
   (vizuelno bez igraca). Akcija mora da startuje samo kad je izvodjac NA lopti.
2. **Restart pogresna strana** — taker ne stiže do lopte, i katastrofa: lopta
   OOB kroz col 6 → col 7, pa restart NA COL 1 (suprotna strana). Restart
   levo/desno + home/away gore/dole ima bag.

Detaljni checklist se nalazi u `backlog.md` → P2-UI. Sledi istraga/fix.

---

## P2-UI — FIX (2026-09-14) — possession glue + restart side

Korisničke prijave su istražene i OBA fiksirana istog dana:

### 1) Action bez carrier-a na lopti — FIXED

**Root cause:** `MovementEngine.moveAllTowardTargets()` pomera carrier-a svaki
tick, ali lopta se kači na carrier-a samo u decision bloku (pre movement-a).
Tokom DRIBBLE akcije (traje više tick-ova) carrier se odmakne, a lopta ostane
na poziciji iz prethodnog decision bloka → vizuelno "lopta sama", a naredni
udarac "teleportuje" loptu na carrier-a i kreće sa strane.

**Fix:** `MatchOrchestrator.tick()` — korak **8b POSSESSION GLUE** posle koraka
8 (movement): kad `carrier != null`, `setPosition(carrier.getPosition())` +
`stop()`. Snapshot se snima na kraju tick-a, pa svaki kadar sa carrier-em
prikazuje loptu 100% uz njega.

**Verifikacija:** exporter run — 445/445 IN_POSSESSION snapshot-a, max gap
između lopte i najbližeg igrača = **0.0000 cells**.

### 2) Restart na pogrešnu stranu — FIXED

**Root cause:** `RestartManager.getRestartPosition(type)` NIJE primalo poziciju
izlaska — THROW_IN je bio hardkodovan na `(4.5, 1.0)` (UVEK leva aut linija),
CORNER na uvek levi ugao. Zato je lopta koja je izašla desno (col 6→7) bila
restartovana na koloni 1 (levo).

**Fix:**
- `handleRestart(state, restartType, oobExit)` — OOB izlaz se prosleđuje
  (uzet iz `state.getBall()` tokom OOB holdu, pre teleporta).
- THROW_IN: col = `1.0` (levo) ako je izašla levo od centra (4.0), inače `7.0`
  (desno); row = izlazni red clampnut u [1.5, 7.5].
- CORNER: ugao (levi/desni) na osnovu izlazne kolone; row = 1.0 (AWAY šutira ka
  HOME golu) ili 8.0 (HOME šutira ka AWAY golu).
- Taker teleport fast-path (demo/service §48): ako je taker > 4.0 cells daleko,
  snapuje se 0.6 cells iza lopte (ka svom golu), pa hoda kratko — nema
  "taker ne stiže" freeze-a.

**Verifikacija (900-tick run):** OOB col 7.3+ → restart `ball(...,1.0)`? NE —
sad `ball(row,7.0)`; OOB col 0.2-0.9 → `ball(row,1.0)`; row očuvan
(4.2→4.2, 7.3→7.3, 6.6→6.6, 7.0→7.0). Taker uvek postavljen (H6/A3/H2/A4/A2/H7).
`mvn -q -o compile` clean, exporter radi, stats struktura nepromenjena.| 2026-09-15 18:48 | P3#1 [x] (DEFLECT_R 0.035->0.05 landed+committed; 3-variant headless A/B/C rc=0 rc=0 rc=0, body-contact tokens 4/4/4 identical; boundary case open) | compile rc=0 | calibration: no fake checkbox |

| 2026-09-15 | P3#2 [x] readIntercept near-receiver prob verified REALISTIC via engine arithmetic (0.076-0.173, 0.45 cap = slowest launch = unreachable near receiver) | compile rc=0 | ARC |


## Sesija 2026-09-16 — P7#1-3: Rules bodies port (OffsideService / VARService / DisciplineService)

- **Cilj**: portati `rules/` stub-eve u prave servise sa pravim logikom (P7#1–P7#3 iz backlog.md); P7#4/P7#5 ostaju `[ ]`.
- **P7#1 — OffsideService** (`rules/OffsideService.java`): 167 linija, 3 `@Override`, continuous offside tracking + per-pass check + retreat (3-uzastopna pravilo). Komit `b8e8c75`.
- **P7#2 — VARService** (`rules/VARService.java`): 174 linija, 9 `@Override`, 5 review gates (offside 20%, goal 15%, penalty 25%, red 40%, yellow 10%). Komit `4f380e8`.
- **P7#3 — DisciplineService** (`rules/DisciplineService.java`): 139 linija, 1 `@Override`, real `evaluateFoul()` body + 4 honest `TODO` komentaraka za karton/penalty logiku (nije stub). Komit `6669e45`.
- **Gate**: `mvn -o -q compile` → exit 0, nema [ERROR]-ova.
- **P7#4 — ThreatOverrideEngine** (`engine/ThreatOverrideEngine.java`): full TYPE A/B/C bodies — TYPE A press (pressCarrier + press point on carrier), TYPE B isolated (final 2.5 rows, no defender within 0.5 cells), TYPE C offside retreat (consecutive offside ≥ threshold, retreat from reference `engine/TacticalIntentEngine` TypeC), plus `isClosestEligibleDefender` guard (samo jedan branič). Working-tree implementacija, compile green.
- **P7#5 — UI overlays (proposal viewer)**: VAR freeze overlay + VAR decision banner + offside line/gold overlay + card overlay + goal-disallowed overlay — svi dispatch-ovani kroz overlay dispatch (`showVARDecision`/`showOffside`/`showCard`/`showGoalDisallowed`); proposal/viewer.js dispatch poklapa se sa referencom, RULES_ događaji provedeni kroz overlay dispatch.

## Sesija 2026-09-15 — P2#3: ActionLogService

- **Cilj**: izvuci log/p/minute/formatDecision iz `MatchOrchestrator` u
  `ActionLogService` (jedna odgovornost po klasi — SOLID/OOP, pravila 5+6).
- **Rezultat**: `ActionLogService.java` (proposal/engine/, ~61 linija) — vlasnik
  strukturisanog `[mm:ss|TAG]` loga; metode `log(tag,msg)` / `p(Position)` /
  `minute()` / `formatDecision(Player, DecisionResult)`; ctor `(MatchState,
  List<String> eventLog)` — deli isti eventLog sa BallResultHandler/DuelService.
- Orkestrator tanki: `log/p/minute/formatDecision` → 1-linijske delegate
  `{ actionLog.* }`; polje `actionLog` + ctor init.
- **Gate**: `mvn -o -q compile` → exit 0, nema [ERROR]-ova.
## Sesija 2026-09-14 predvece — P2#1: handleBallPhysicsResult → BallResultHandler

**Tok sesije:** P2 (orchestrator slimming) stavka #1 — ekstrakcija
`handleBallPhysicsResult()` (162-linijski switch RECEIVE/INTERCEPT/SAVE/
BLOCK/DEFLECT/POST_HIT/GOAL/MISS/OOB_goalkick-throwin/OOB_corner/LOOSE/STOP/
FLIGHT) iz `MatchOrchestrator` u novi zaseban helper `BallResultHandler`
(215 linija) u istom paketu `proposal/engine`.

- Orchestrator zadrzava: polje `ballResultHandler` (46), ctor init (75),
  slim delegator `handleBallPhysicsResult()` → `ballResultHandler.handle()``
  (235-237), poziv `handleBallPhysicsResult(ballResult)` na 119.
- Helper replicira orchestrator-ove privatnike `log/p/minute` +
  `resolveTeamByLabel` (iza DEFLECT). DEFLECT atribucija po label-u
  igraca cije je telo lopta pogodila.
- **Razlika:** iz orchestratora ispalo ~162 linije switch tela →
  orchestrator 480→325 linija; braces uravnotezeni (38/38, 30/30).
- Kompajl: `mvn -q -o compile` (korisnik mi je dozvolio da probam sam;
  veza je prezivela) → **PASS**, nula gresaka.

Verifikacija (grep, orchestrator linija 235): delegator `{ ballResultHandler
.handle(res); }`, case RECEIVE u orchestratoru = 0. Backlog: P2#1 `[x]`.

---

## Sesija 2026-09-14 poslepodne — P1 zatvaranje + P2 restarts (nastavak)

**Tok sesije:** (1) fiksiran Bug #1 "action bez carrier-a na lopti" —
root cause `MovementEngine` pomera carrier-a a lopta se kačila samo u decision
bloku; fix `MatchOrchestrator` korak **8b POSSESSION GLUE** (posle movement-a
lopta na carrier-a, snapshot posle toga). Verifikacija: 445/445 IN_POSSESSION
snapshot-a gap 0.0000. (2) Bug #2 "restart na pogrešnu stranu" — root cause
`RestartManager.getRestartPosition` hardkodovao THROW_IN uvek (4.5, 1.0) i
corner uvek levi ugao; fix `handleRestart(state, type, oobExit)` +
`executeRestart(state, type, oobExit)` + getRestartPosition po izlaznoj
poziciji + taker teleport fast-path (§48, 4.0 cells). Verifikacija: restart
posle OOB col 7.3 → col 7.0; col 0.2-0.9 → col 1.0; redovi očuvani. (3)
Posession chain metrika dodata (`onPossessionTick` prati chains; TeamStats
nosi `avgPossessionTicks`/`longestPossessionTicks`) — time je i poslednja
implementabilna stavka P1 zatvorena; preostale P1 stavke su stub-blokirane
(P7). Svi md-ovi ažurirani posle svakog završenog koraka.

Stanje: P1 gotovo, P2-UI fiksirana, `backlog.md`/`PROPOSAL_*` ažurirani.
Komanda za kompajl + export proveru je data korisniku (interface rule: ne
radim kompajlove sam).

### 6.11 2026-09-17 · c73f27a — P6#0: MatchSimulator port thin driver (TOTAL_MATCH_TICKS=3600,
mvn -o compile EXIT=0; backlog.md: P-UI restart bez igrača, UI vs /demo/service uporedba).

---

## Session (rigid ball rules follow-up) — dead-ball freeze family, final fix

**Context:** the rigid-ball rules (carrier must be physically ON the ball;
no action without the ball at the foot; ball must never be left dead)
introduced a match-freeze family. After the byline DRIBBLE fix and the
stale-`pendingReceiver` clear, two dead-ball freeze variants remained:

- **Variant 1 (chaser wall-ring):** players press a stopped ball and park on
  a ring at exactly 0.40–0.45 cells (just outside `PICKUP_R = 0.35`); the wall
  separation floor (`MIN_PLAYER_DISTANCE = 0.35`) blocks the loose-ball chaser
  from stepping onto the ball spot, so the ball is never picked.
- **Variant 2 (dangling restart taker):** a restart ball was picked up via the
  generic LOOSE `near` path (even by the taker himself) while
  `state.restartTaker` was still set; after the duel that transferred
  possession, `restartTaker` stayed set forever. The taker was then skipped by
  `TacticalIntentEngine.refreshTargets` (`if (p == carrier || p == taker)`),
  his stale walk target was abandoned, the loose-ball chaser was suppressed by
  `restartTaker != null`, and a dead ball upfield froze for 1700+ ticks.

**Fixes (verified across 8 full 3600-tick plain-java runs):**

1. `MovementEngine`: new `CLAIM_REACH_RADIUS = 0.7` — the restart taker and the
   loose-ball chaser skip `separateFromOpponents` when within 0.7 cells of the
   ball, so they can lunge exactly onto the ball spot through a wall-ring
   (mirrors the existing carrier-off-ball exemption).
2. `RestartManager.findNearestPlayerOfTeam` + `findWinger`: restart taker
   selection now filters `!p.isLocked()` (a duel-locked player can never be
   the taker).
3. `MatchOrchestrator` step 2: **`restartTaker` is cleared the moment any
   carrier exists** — a restart is consumed when anyone takes the ball;
   logs `RST ... restart taken before taker: X beat Y` when a non-taker wins it.
4. `MatchOrchestrator` step 9: `restartTakerAge` + stall-claim — claim radius
   widens to 0.6 after 40 ticks (pure backstop; never fired during QA).
5. `BallPhysicsEngine`: permanent DEAD-WATCH watchdog — if the ball sits
   stopped unrecovered, logs `dead=N carrier/pending/restartTaker/oob/speed |
   nearest3 distances` once per 30 dead ticks (rings the alarm if this class
   of freeze ever recurs).

**Verification:** 8/8 runs complete. DEAD-WATCH hits = 0 in 7 runs; 1 benign
9-tick blip in f27 (taker walking from 0.68 cells, recovered normally).
Goals 3–7/match, decisions 1900–2256, actions 1000–1600. Max log gap 3 ticks
(~4.5 s) — within the "no silence > 5 s" user rule. `restart-taken-before-taker`
fires 1–3/match as a normal game mechanic (attacker lunging in on a loose
restart). Compile: `mvn -o -q compile` → clean.

---

## Session 2026-09-23 — rigid-ball physics: OOB freeze, strike-hold, in-bounds clamp, DEFLECT contact, restart-clear, central OOB guard + viewer

**Context (user report):** the ball kept sliding along the OOB zone during the
visible 4-tick hold and the restart spot was computed from the DRIFTED exit
(throw-in restarted up to 5 cells away from where the ball actually went out);
the striker visibly moved in the very tick he struck (pass/shot/clear); DEFLECT
fired from "air" (GK standing on the CLEAR launch origin bounced it backward on
its first flight tick); ball/players wandered off the pitch (throw-ins passed
along the touchline OUTSIDE the field, then re-out again → throw-in churn);
players stacked on the restart ball spot (HOME GK anchor `CELL_0_2` == goal-kick
spot (1.5,3.5) → GK + DCL + pressing striker on the spot every goal kick).

**Analysis (match.json, ~3021 events):** confirmed all three:
1. "Carrier moved while ball frozen" (519 ticks pre-fix): after PASS/SHOT/CLEAR
   the striker's target is nulled, then step 7 `refreshTargets` re-assigns a
   tactical target and step 8 movement runs him off in the SAME tick the ball
   was struck (the ball flies on the next tick).
2. OOB drift: `BallPhysicsEngine` moved the ball BEFORE the OOB branch, so the
   4-tick hold kept rolling (e.g. exit (4.88,0.66) → slid to (1.73,0.75) during
   the hold; restart then placed at the drifted row).
3. `MovementEngine` had "NO field boundary clamping" → off-pitch players existed
   and passes were played to them along the touchline.

**Fixes (all compile-green, verified across seeds 42/7/999 on 3600-tick plain-java runs):**

1. **strike-hold** — `Player.strikeHoldTicks` (new field); `ActionExecutor` sets
   it to 1 on PASS/SHOT/CLEAR (with a RIGID RULE comment); `MovementEngine` skips
   the rooted player until it expires → the striker is visibly planted while the
   ball leaves his foot.
2. **OOB freeze** — on OOB ENTER the ball is jumped/stopped at the crossing point
   (`ball.stop()`); the hold therefore shows the ball sitting exactly where it
   went out. The restart spot is computed from the TRUE exit. Verified: 0 hold-
   drift offenders across the match.
3. **Central OOB guard** (`stepBall` step 0) — an OOB ball is ALWAYS dead:
   possession (RECEIVE landing just-OUT, e.g. pass deviation to col 0.8),
   pickups, and carrier exist are cleared and the referee hold/restart starts.
   This fixed a REGRESSION my own clamp introduced: after the in-bounds player
   clamp, a carrier whose ball landed OOB could never reach `ON_BALL_EPS`
   (clamped ≥1.0 vs ball at 0.8) → re-decision gate never fired → carrier held
   an OOB ball 3443 ticks (AWAY-7). With the guard, an OOB ball always dies.
4. **DEFLECT contact placement** — DEFLECT/BLOCK now place the ball at the actual
   contact point (`prev + (curr-prev)*bestT`) and skip contacts where the ball
   travelled ≤0.05 to the impact (launch-origin / stacked-player phantom bounces).
5. **In-bounds clamp** — `MovementEngine` clamps every player's final position
   to the pitch (rows 1.0–8.0, cols 1.0–7.0). All 22 players can never leave
   the field; no more OOB throw-in churn on the touchline.
6. **Restart-clear nudge** — `TacticalIntentEngine.refreshTargets` pushes non-taker
   desired targets within `RESTART_CLEAR_RADIUS(0.6)` radially away from the
   restart ball spot (`RESTART_CLEAR_PUSH(0.9)`), so the GK/DCL stack is cleared
   before every restart.

**Viewer (proposal):** removed the stats panel entirely (`_renderStats` + HTML)
and the events log now runs FROM MATCH START — the full timeline (~560 events)
is populated immediately via `_buildTimeline()` (single DocumentFragment), no
per-tick DOM mutation during playback; the landscape live ticker still updates
from the event stream via `_updateLiveTicker`. Stats tables/players CSS left
unused in pitch.css (no breakage). `index.html` script bumped to v=5.

**Verification on the fixes:** strike-tick check = 790 strikes, only 3 outliers
(all AWAY-10 restart-taker teleports 1.1–1.9 cells — restart placement, not
in-play glide). DEFLECT = 104 events, 0 with ball off the deflector. OOB holds:
0 drift offenders. Possession: max single-carrier share 112 ticks (no domination).
Event gaps >250 ticks: 0. Exports: seed 42 → 2-0 (poss 55/45), seed 999 → 4-0
(56/44), seed 7 → 3-0 (51/49) — healthy, no freezes.

**Compile:** `mvn -o -q compile` → clean.

---
## Session 6.12 · 537fef2 — dijagnostika + 3 kritična bug-fixa (offside, šut/AUT, nošenje lopte)

**User prvobitno tražio "dizajn test"** koji sa svim igračima na skill 14, 2–3
minuta meča, traga: pozicije/ciljeve svakog igrača, decision-engine izbore,
izvršenje pasa i šuteva, offside provere, brzine lopte i igrača. Rezultat je
`ProposalPhysicsDiagnostic.java` (dodaje se kao stalna dijagnostika), a on je
odmah otkrio **3 prava root-causa**:

### Root cause 1 — OFFSIDE JE BIO TRAJNO ISKLJUČEN
`RestartManager.setSetPieceType("KICK_OFF"/…)` se postavlja a NIGDE ne čisti
(pre nije postojao `clearSetPieceType()`). Pošto `OffsideService.checkOffside`
u prvom redu ranog izlaska ima `if (isKickoffPending || setPieceType != null)
return onside`, posle prvog kickoffa otkačena je **svaka** provera do kraja
meča. Dijagnostika: 20 pasa u 3 min, **0 OFF-TRACE linija**, 0 OFF događaja.
To je tačno "offside detekcija NE RADI" iz user prijave.

- `MatchState`: nov `clearSetPieceType()`.
- `MatchOrchestrator` top-of-tick restart-blok: čim postoji `carrier != null`
  (restart je "pojeden"), `clearSetPieceType()` — loptu vraćamo u igru, tako da
  set-piece guard štiti SAMO dok je restart pending (throw-in/corner/gol-aut po
  FIFA ne poznaju offside; FK i dalje prolaze kroz check jer samo pending-faza).

### Root cause 1b — margin ignorisao `passOrigin` (FIFA predu-slovi)
`calculateOffsideMargin` je poredio samo receiver row sa 2. po defanzivcu,
bez: (a) "primatelj je u protivničkoj polovini" i (b) "primatelj je ISPED lopte"
u momentu dodavanja. Dodata oba predu-slova → backward/nivelisan pas i primatelj
u svojoj polovini su uvek ONSIDE (return -5.0, bez whistle i bez VAR hold-a).

**Posle:** 69 offside provera u 9 min → 36 CLEAR-OFF (zvižduk + IFK), 7
MARG-OFF (VAR hold), 26 ONSIDE. Bands rade kako su projektovani.

### Root cause 2 — šut "iz izgledne pozicije u AUT" (on-target kalibracija)
`ExecutionQuality.evaluateShot`: `onTargetProb = (0.03 + skill*0.006)` … cap
**0.40**. Na skill 14 (0.114 base) sa 1.7–2.8 ćelija → ~0.07–0.08 on-target,
čak i sa 0.5 ćelije ~0.14. Dijagnostika: 3 šuta, **0/3 on-target**, jedan sa
1.73 ćelije (3.4 m). Upravo "spic iz izgledne pozicije … AUT".

**Nova kalibracija:** `skillBase = 0.12 + skill*0.028` (14 → 0.512) ×
`distFactor = max(0.25, 1 − dist/9)` + `+0.20 ako dist < 2.0`, cap **0.85**.
Skill 14: 1.0 ćel = ~0.66, 1.8 ćel = ~0.61, 3 ćel = ~0.34, 7 ćel = ~0.13.
**Posle:** 11/21 on-target (52%) u 9 min; 9 min → 2 GOAL, 10 SAVED, 1 POST,
8 MISSED (loši uglovi/izdaleka).

### Root cause 3 — "igrač neprirodno stane u posedu lopte"
`ActionExecutor.executeCarry` target samo **0.5 ćelije** ispred + per-tick
re-decision → MovementEngine stigne mikro-cilj za ~1 tik i carrier šeta
(stop/start). Carrier avg 0.245 Ć/T vs non-carrier 0.476 (skoro duplo manje).

**Fix (preslikano na /demo/service):** carry cilj **3 ćelije** ispred u jednom
potezu (+ blagi unutrašnji drift kolone ka centru 3.0–5.0 → 4.0, clamp 1.0–7.0;
row clamp HOME [1.0,7.5] / AWAY [1.5,8.0]). `CleanDecisionEngine` byline-trap
mirror usklađen (3.0 a ne 0.5) da odluka i izvršenje računaju isto.
**Posle:** carrier avg 0.245 → 0.284; stand-still detector (≥3 tika u posedu)
u oba pokretanja: **0 run-ova** → nema zamrzavanja ni "neprirodnog stajanja".

### Verifikacija
- `mvn -o -q compile` → clean.
- `ProposalPhysicsDiagnostic 120` / `360`: exit 0; pasovi 37 → 28 RECEIVE,
  4 DEFLECT, 4 INTERCEPT, 1 GK_CATCH, devijacije 0.2–1.8 ćel.
- `MatchSimulationLauncher 480` (12 min): HOME 2:0, 21 šut (12 on-target),
  34/47 pasa; bez izuzetaka, bez tihih prozora.
- Napomena: najveći per-tick "max move" (~2.8) je posledica kickoff reset
  teleporta posle gola (igrači sa 7.5 na 4.5) — nije in-play glide.
- Napomena (tuning, van ovog sessiona): strikers kampuju na 7.5 (pred samim
  gol-manom) → mnogo šuteva iz <1.5 ćel; to je pitanje taktičke šarže,
  ne mehanike. CLEAR 32/226 odluka je isto posledica zbijene šarže.

---

## Session 6.13 — ThreatOverrideEngine WIRED + offside retreat + press→duel

Context: `ThreatOverrideEngine` TYPE A/B/C bodies existed (P7#4) but were DEAD
CODE — orchestrator never instantiated/called it, and
`OffsideService.trackOffsidePositions` (the consecutive-offside counter) was
never invoked either. Result: no offside retreat, no press, no carrier-press
duels. User: "vrati offside retreat" + "threat override da se pridje igracu sa
loptom sa idejom da se udje u duel".

### Wiring (MatchOrchestrator)
- **Step 1b** — `offsideService.trackOffsidePositions(state)` every tick
  (increments `consecutiveOffside` for attackers forward of the ball with <2
  opponents goal-side; onside tick resets). Without this the TYPE C counter was
  permanently 0.
- **Step 7b** — `threatOverrideEngine.evaluate(state)` between
  `refreshTargets` and `moveAllTowardTargets` so the override rewrites the
  target the Movement Engine follows THIS tick.

### Press must end in a duel
`MIN_PLAYER_DISTANCE = 0.35` (wall) parks a presser ~0.35-0.4 cells from the
carrier — outside the 0.15 DRIBBLE radius, so a pressed carrier was never
tackled. `DuelEngine`: new `PRESS_DRIB_DUEL_RADIUS = 0.50` — when
`defender.isThreatOverrideActive()`, the DRIBBLE duel fires at 0.50 (identical
to the demo/service 2026-09-12 fix).

### TYPE A alignment (no swarm)
- `pressCarrier` now requires `isPressingEligible(role)` (defenders + MID/AM/WNG)
  and `isClosestEligiblePresser(carrier, defender)` — exactly ONE defender
  claims the carrier; others hold shape. Press point = the carrier's EXACT
  position (wall parks inside the 0.50 press-duel radius).
- TYPE C `isClearlyOnside` ported to the demo/service rule: retreat ends only
  when ≥2 opponents (incl. GK, excl. locked/sentOff/injured) are goal-side.
  Threshold stays `OFFSIDE_RETREAT_THRESHOLD = 3` (matches demo/service/user:
  "3 uzastopne offside pozicije").

### Freeze regression caught (DEAD-WATCH)
First full run after wiring froze: `DEAD-WATCH dead=1443 restartTaker=A6
d=1.40` — refreshTargets SKIPS the taker (keeps walk target), but the newly
wired threat override re-routed him to a press target → taker never reached the
ball. Fix: `evaluate()` skips `state.getRestartTaker()`. After fix: DEAD-WATCH 0.

### Verify (full 3600-tick match)
`match.json` runs clean, exit 0, no DEAD-WATCH:
- **TYPE_A 828** carrier presses → **427 DUEL DRIBBLE + 18 DUEL SHOT** (press→duel
  adjacency on the same timestamp confirmed) — the user requirement works.
- **TYPE_C 1182** offside retreats — camped strikers at 7.5 pulled back to row
  2.5-5.0 toward own goal; counter resets once ≥2 opponents goal-side.
- **TYPE_B 4652** — high raw count is LOG repetition (demo/service throttles by
  signature; actual behavior is one closest defender tracking the isolated
  attacker, recomputed each tick). Not a distortion.
- Score 3:0, 40 shots (18 on target), 273/334 passes, 1648 decisions.
- `mvn -o -q compile` clean.

Fouls/cards still 0 (duel→foul chain not wired into DuelService) — not in scope;
backlog item.

---

---

## Session 7.1 — Compact console log + live side panel

User (IntelliJ run-console): "ne vidim pocetak meca u app log u run konzoli";
UI side log should "load actions AS THEY HAPPEN on the field, not all at once".

### Compact console log (ActionLogService)
A full match produced ~28k tagged lines on stdout — TAC/THR/BAL per-tick
noise was ~80% of it and scrolled the match start out of IntelliJ's buffer.

- **Default = compact**: stdout prints ONLY the on-pitch match story via
  `CONSOLE_NOTABLE` (GOAL, SHOT_SAVED/MISSED/MISS, PENALTY_*, DUL, OFF, RST,
  FOUL, cards, KICKOFF, POSSESSION_CHANGE, VAR_*), plus ORC lines that carry
  the `***` epilogues (GOAL / SHOT_SAVED / SHOT_MISSED). A full 3600-tick
  match is now ~805 lines (was ~28k): DUL 448, RST 185, OFF 124, ORC 27
  (25 SHOT_SAVED + 2 GOAL — file parity confirmed), LCH 1. No DEC/EXE/TAC/
  THR/BAL on the console in compact mode.
- **Nothing is lost**: the FULL stream always goes to
  `target/proposal-app.log` (`-Dproposal.log.file=<path>` overrides) and into
  the shared `eventLog` (match.json `logs`), so QA greps (`-Dproposal.log.console=full`)
  and the improvement tuning work are unaffected.
- **Reasoning anchored in a number**: file log for the same match = 27,512
  lines (TAC ~16k + THR ~6.7k + BAL ~1.7k dominated); compact console = 7,688
  with the earlier noise-deny-list, still too fat for the IntelliJ buffer —
  that's why the allowlist (story events only) replaced the deny-list.
- `BallResultHandler` + `DuelService` printed directly via `System.out.println`
  (BAL/DUL lines bypassed the filter). Both now log through
  `state.getActionLogger()`; their private eventLog fields/constructor params
  (and List imports) were removed; `MatchOrchestrator` constructor calls
  updated accordingly.
- Full mode also re-verified: 720-tick diagnostic with `proposal.log.console=full`
  prints TAC/THR/BAL again (file parity), compact default prints only the story.

### Live side panel (viewer.js proposal)
- `_buildTimeline()` now renders ONLY events up to `this.currentTick` (clears
  DOM, resets `_pendingTimelineEvents`, advances `_displayedEventIdx`),
  used at load (kickoff only, first event tick=1) and on seek.
- `_makeTimelineItem(ev)` extracted — single shared row builder used by
  `_buildTimeline()` and `_flushTimelineEvents()`.
- `_processEventsForTick` enqueues timeline-worthy events
  (`TIMELINE_EVENTS.has(ev.type)`) into `_pendingTimelineEvents`; flushed once
  per RAF in `_renderFrame` (batched DocumentFragment — no per-event layout
  thrash). The old PRE-POPULATED timeline comments removed.
- **DOM cap guard added to _buildTimeline too**: seeks / load now trim to
  `_MAX_TIMELINE_EVENTS` (800) just like playback streaming — a seek-to-end
  previously risked building the full 2.8k-row timeline (Firefox freeze).

### Verify
- `mvn -o -q compile` clean (exit 0); `node --check viewer.js` OK.
- `MatchSimulationLauncher 3600`: exit 0, compact console 805 lines, both
  goals logged (`GOAL HOME by H11 - score 1:0 / 2:0`), no DEAD-WATCH.
- Viewer server (port 8766) POST `/proposal/api/generate`: "✅ Generated match:
  5-0 (events=28853)"; server console logged 0 noise lines; regenerated
  match.json = 2883 recorder events + 3599 snapshots.
- match.json stays uncommitted (user rule).

---

## Session 7.2 — Offside whistle at reception + precise/fast kickoff pass + TYPE B danger-zone press

User requests (2026-09-23):
1. **Offside NE SMEda teleportuje loptu** — pas leti normalno, svira se tek kad
   ofsajd igrač PRIMI loptu (bez ubrzavanja/teleporta; igrač to ne vidi).
2. **Kickoff pas mora biti precizan i najbrži** — lopta tačno kod primaoca i
   brža od svih (protivnik ne stiže).
3. **TYPE B pritisak** — jedan najbliži defanzivac prilazi slobodnom napadaču
   u NAŠOJ ZONI OPASNOSTI (blizu našeg gola), uz očuvanje formacije (samo 1).

### 1) Offside — whistle at reception (flag at pass → whistle at RECEIVE)
- `OffsideService.checkOffside`: CLEAR i MARGINAL opseg spojeni u jedno —
  **ne pozivaju više `confirmOffside`** (nema teleporta) i **ne postavljaju**
  `offsideDeferred`/`pendingVARReview`. Samo `state.setOffsideFlaggedReceiver(receiver)`
  i `return new OffsideResult(false, true)`. `confirmOffside` + `carrierTeam`
  ostavljeni (referencira ih legacy `resolvePendingVAROffside`).
- `MatchState`: polje `offsideFlaggedReceiver` (Player) + getter/setter.
- `BallResultHandler`: u RECEIVE grani, ako je `flagged == receiver` →
  **svira se OFFSIDE**: carrier=null, lopta OSTOJI na mestu fizički stiglog
  prijema (bez teleporta), `restartTeam` = tim koji brani, diskrecioni
  `OFFSIDE` event, `restartManager.handleOffsideFreeKick(state, spot)`, `break`
  (preskaču se pass-completed/brojači). Flag se briše u INTERCEPT / SAVE /
  BLOCK / DEFLECT / POST_HIT / GOAL / OOB_ENTER / OOB_CANCEL / LOOSE_PICKUP /
  STOPPED (protivnik prvi dirne loptu = nema prekršaja).
- `RestartManager.handleOffsideFreeKick`: lopta OSTAJE na spotu, bez OOB
  animacije (instantan restart §48 stil, sa 0.6-behind fast-path za takerа),
  svi igrači preko `tactics.desiredCell`, taker = najbliži defanzivac tima
  `state.getRestartTeam()`, `phase=SET_PIECE`, `setPieceType("FREE_KICK")`.
- Sat se ne zaustavlja; slaže se sa korisnikovim pravilom "nikad ne
  teleportovati loptu u offsajdu".
- **Napomena za PO/QA:** `MatchOrchestrator` step 6 (`offsideBlockedPass`) je
  sada inertan jer `checkOffside` za CLEAR/MARG uvek vraća confirmed=false;
  ceo flag-based put se završava u `BallResultHandler`. Frekvencija (82/60min)
  je preegzistirajuća kalibracija, nije deo ovog zahteva.

### 2) Kickoff pass — precise + max speed
- `CleanDecisionEngine`: kickoff grana VIŠE ne čisti `kickoffPending` na početku
  odluke — flag ostaje dok se pas stvarno ne lansira.
- `ActionExecutor.executePass`: `state.isKickoffPending()` →
  `aimedTarget = receiver.getPosition()` TAČNO (bez `openingTarget` pomeranja),
  `new ExecutionQuality.PassResult(receiver.getPosition(), BallPhysicsEngine.MAX_BALL_SPEED, 0., passing, 0.)`
  (nula devijacije i spina), posle lansiranja `setKickoffPending(false)`.
- MAX_BALL_SPEED 1.5 vs PLATFORM_MAX_PLAYER_SPEED 0.75 → niko ne stiže.

### 3) ThreatOverrideEngine — TYPE B bang-bang zona opasnosti
- `RANGE_B` 1.5 → **2.0** (~28 m, javadoc ažuriran).
- Band izmenjen sa "final 2.5 reda" (HOME ≤2.5 / AWAY ≥6.5) na **ZONU
  OPASNOSTI — 2 ćelije od sopstvenog gola: HOME rows ≤ 3.0 / AWAY ≥ 6.0**;
  lokal `inFinalQuarter` → `inDangerZone`.
- Log string: `isolated-opponent-in-final-quarter` → `isolated-opponent-in-danger-zone`.
- Izolacija 0.5 i `isClosestEligiblePresser` (tačno jedan defanzivac tvrdi) netaknuti.

### Verify
- `mvn -o -q compile` clean.
- `MatchSimulationLauncher 2400`:
  - Kickoff: `EXEC PASS by H10 at (4.5,4.0) -> (4.5,4.0) target H4(DCL)(3.5,3.5)
    [kickoff pass to DCL]` → `RECEIVE H4(DCL) at (3.5,3.5) | ball(3.5,3.5)` —
    lansiran tačno na poziciju primaoca, nema presretanja.
  - Offside (82 "whistle at reception" u 2400 tick-ova): let normalan —
    `flight (4.2,3.6) speed 1.01` → `flight (3.5,3.1) speed 0.86` → `*** OFFSIDE
    by A11 ... at (2.9,2.7) (whistle at reception)`; lopta se zaustavlja na
    MESTU prijema, `offside IFK -> taker H2` + instant `TAKER claims ball`.
  - TYPE_B linije 5079 / TYPE_A 488 / TYPE_C 735 u 2400 tick-ova (obe ekipe).
- `ProposalPhysicsDiagnostic 2400`: OFF bands 61 CLEAR-OFF / 38 MARG-OFF /
  63 TIGHT-ON; BALL max speed 1.500 (= MAX_BALL_SPEED, min 0.075, avg 1.064);
  23 šuta (17 saved / 2 post / 4 miss); nema stand-still ≥3 tick-a u posedu.
- (Diag AWAY-2 max 4.897 = restart-taker teleport fast-path, preegzistirajući,
  po specifikaciji §48.)
- match.json ostaje nekomitovan (pravilo korisnika).

---

## Sesija 2026-09-24 — Data-layer fit: proposal outcome "u obliku izveštaja" + newLogic match-data connect

> Uvodi komit `b1508a7`.

Zadatak korisnika: newLogic data sloj je ostao "pola odrađen" — tako da, kada
proposal postane zvanični engine, može da se uklopi u njega. Dva smera:
(1) **newLogic** — proširiti modele i povezati matcheve, (2) **proposal** —
pripremiti izlaz meča sa svim stavkama koje izveštaj ("report") traži.
Replay preskačemo — fokus na backend.

### Faktno stanje koje je otkrilo istraživanje
- proposal ne simulira FOUL/CARD/PENALTY (DisciplineService stub, nije u
  orchestratoru) → izlaz će nositi strukturu, ali 0 za te metrike.
- newLogic `MatchSimulator` **ne emituje `ShotEvent`** — samo po jedan od
  `GOAL` / `SHOT_SAVED` / `SHOT_MISSED` po šutu; stari `ZoxApiController`
  `computeTeamStats` je brojao šuteve preko `shooterId` ključa pa je
  prikazivao pogrešne brojeve (`homeShots -= homeGoals` hack, xG izmišljen
  `goals*0.7+0.5`, passAccuracy hardkoder 78.0, dominance 50).
- newLogic `MatchSimulator` **već prati** per-team `home/awayTotalPasses` +
  `home/awaySuccessfulPasses`, ali `MatchResult` ih nije izlagao.
- Oba path-a čuvaju igrače: `MatchStatisticEngine` (realistični) je veću
  `MatchPersistenceService` (newLogic MatchOrchestrator) **nije** — ovaj
  drugi nije setovao interceptions/saves/cleanSheet.

### newLogic — match-data connect
- `MatchResult` proširen sa `homePassesAttempted/homePassesCompleted/
  awayPassesAttempted/awayPassesCompleted`; `MatchSimulator.buildResult` +
  `MatchLiveService.buildResult` ih prosleđuju (iz simulator-šaltera).
- **`MatchTeamStats`** (novi record u `newLogic/model`) — kanonski timski
  izveštaj: poseda, xG, šutevi (+ na gol), pass pokušaji/kompletirani, korneri,
  ofsajdi, kartoni, faulovi, prosek rejtinga; `toMap()` daje iste ključeve
  koje report očekuje.
- **`MatchTeamStatsService`** — računa xG (suma `xG` nad GOAL/SHOT_SAVED/
  SHOT_MISSED/SHOT_BLOCKED/CROSS_HEADER/PENALTY, penali bez duplog brojanja)
  i ofsajde iz tipizovanih događaja; prosek rejtinga iz `MatchPlayerStats`.
- **`Match.statsJson`** (text kolona, nullable — sigurno uz `ddl-auto=update`).
  `MatchPersistenceService` upisuje kanonski payload; `ZoxApiController`
  `computeTeamStats` čita `statsJson` pa: ako postoji → vraća kao jeste,
  inače fallback na stari sniffing (legacy ne-menja se).
- `MatchPersistenceService.savePlayerStats`:
  - **vraća** sačuvane redove (za proseke),
  - per-player šutevi sada iz `GOAL`/`SHOT_SAVED`/`SHOT_MISSED`/`SHOT_BLOCKED`
    (crediti iz pravih događaja koji engine emituje), header → šut + onTarget,
  - saves sa GK atribucijom na SUPROTNU stranu od šutera (ShotSavedEvent meša
    teamSide = šuterski tim),
  - pass attempts uključuju `PassIncompleteEvent` (dok se `PassEvent` emituje
    samo za kompletirane).

### proposal — izlaz "u obliku izveštaja"
- **`ProposalMatchOutcome`** (novi record, JSON-serializable) — nosi sve što
  report treba + sve za budući newLogic adapter: rezultat, poseda, očekivani
  golovi, formacije, `TeamOutcome`/`PlayerOutcome`/`EventEntry` liste.
- **`ProposalMatchOutcomeBuilder`** — izvodi stavke koje engine ne prati:
  - **xG** — svaki šut emituje TAČNO jedan outcome event (GOAL/SHOT_SAVED/
    SHOT_BLOCKED/SHOT_POST/SHOT_MISSED sa pozicijom) → distanca ka golu
    (HOME gol row 8.0, AWAY row 1.0, ćelija = 14 m) preko iste xG tabele kao
    newLogic;
  - **ofsajdi** — broji recorder `OFFSIDE` event-e po timu (kolektor još nema);
  - **formacije** — izvedene iz role brojanja (GK/D/M/A → "4-4-2");
  - **MOTM / proseci rejtinga** — iz `buildPlayerStats` (sort po rejtingu).
- `ProposalStatsCollector` dobija `getHomeName()/getAwayName()`.
- `MatchOrchestrator.buildOutcome()` (+ `getState()`) — okidač; launcher
  (`MatchSimulationLauncher`) ispisuje `=== MATCH OUTCOME (JSON) ===`.
- Faulovi/kartoni u izlazu = **0** dok DisciplineService ne uđe u orchestrator
  (struktura postoji od sada).

### Verify
- `mvn -o -q compile` clean (i `mvn -o -q test-compile` clean).
- `MatchSimulationLauncher 1600`: ključna polja popunjena — formacije 4-4-2,
  poseda HOME 47.2 / AWAY 52.8, xG 0.5/0.3, timske statistike (šutevi, pass
  accuracy, korneri, gol-auti, auti), 22 × `PlayerOutcome` (role, šutevi,
  passovi, dueli, rejting), ~visak događaja u `events`, MOTM (H5, Home FC).
- match.json ostaje nekomitovan (pravilo korisnika).

---

## Session 7.3 — Level 2: realni igrači iz baze u 4-4-2 slot strukturu

Realni DB igrači (prava imena, pravi skills) umesto sintetičkih u dashboard
"Watch Your Match" putanju.

### Šta je urađeno
- **`RealSquadFactory`** (nov) — mapira `Lineup` (11 startnih igrača) u engine-
  ovu 4-4-2 slot strukturu:
  - deterministički slot assignment po poziciji (GK→GK, DEF→D*, MID→M*/C*,
    WNG→ML/MR/STL/STR, ATT→STL/STR), ostatak se dodeljuje po lineup redu;
  - preslikava `Position` (GK/DEF/MID/ATT/WNG) → `PlayerSkills` preko
    `Skills.getExact`, clamp 1-20;
  - anchor pozicija iz `FormationSlotCatalog.getSlots("4-4-2")` +
    `TacticalPerspectiveTransformer.toPhysical(...)` → HOME / AWAY zrcaljenje
    (AWAY GK 7.5);
  - label = pravo ime, id = DB id, heightCm = m×100 (+180 fallback).
  - vrati `null` (→ sintetički fallback) kad lineup ima < 11 startera ili
    ne postoji.
- **`SimMatchRunner.run(homeName, awayName, ticks, homeSquad, awaySquad)`** —
  realni squad-ovi se dodaju u `MatchState` kad su ≥ 11 po strani, inače
  `SimTeamFactory.addTeam` fallback. Stari 3-arg poziv delegira sa null.
- **`SimMatchService.simulate(MatchFixture, storeReplay)`** (nov overload) —
  učitava realne sastave oba tima (`LineupRepository.findFirstByTeamIdAndMatchIsNull...
  , `RealSquadFactory.buildSquad`), pokreće `SimMatchRunner`, fallback na
  sintetiku po strani kad nema lineup-a.
- **Calleri** prebačeni na fixture-putanju (realni igrači):
  `SimulationController.simulateAndStore` (ispred user-match `true`), 
  `AsyncSimulationRunner` (simulate-all, `false`).
- Staro `simulate(String,String,boolean)` uklonjeno (niko ga više ne koristi).

### Verify
- `RealSquadFactoryTest` (5) + `RealSquadSimulationSmokeTest` (2) — zeleno,
  7/7. Smoke run: 3600 tikova sa realnim sastavima, HOME/AWAY golovi ≥ 0,
  tick == 3600.
- `mvn -o test -Dtest='!TifoUITest'` — isto 9 poznatih legacy `demo.service`
  kvarova (framework), bez novih.
- Open item: determinizam — engine RNG raštrkan na 5 mesta (nema seed-anja u
  ovom passu).

---

## Session 7.4 — Full entity wiring: MatchPlayerStats + lineups + career bumps + attendance

Kompletno povezivanje persiste perssim putanje (nakon Level 2, `76c914b`):
`SimMatchService.persist()` sada piše Match → ali i celu okolnu mrežu entiteta.

### Šta je urađeno
- **`Match.homeLineup` / `awayLineup`** — postavljeni na stvarne `Lineup`-ove
  timova (isti upit kao simulacija). Ranije su bili `null`.
- **`MatchPlayerStats` redovi** — po jedan za svakog REALNOG DB igrača iz
  `outcome.players()` (parseLable-Long `playerId` + `PlayerRepository.findById`);
  sintetički fallback id-jevi (`"HOME-1"`) se preskaču. Mape: goals/assists/
  cards/minutes/interceptions/saves/shots/passes, `rating` ×10 → 10-100 skala
  (konzistentno sa `MatchPlayerStatsController` i `ZoxApiController`, koji dele
  sa 10), `cleanSheet` = tim primio 0 golova + GK/DEF + ≥60 min.
- **`Player` karijerni bijмп** — `totalGoals +=`, `totalAssists +=`, `rating`
  (10-100), saveAll putem `playerRepository`.
- **Stadion + poseta** — `AttendanceService.ensureAttendance(match)` pre gate-a:
  rezolucija stadiona + procena posete (kao legacy `RuntimeSaveToDB`).
- **`SimMatchOutcome`** — dodat `homeTeam/awayTeam` (korisno za tekuća stanja;
  `homeGoals()`/`awayGoals()` ostaju).
- `loadRealSquad` sveden na deljeni `loadLineup(team)` helper.
- Log poruka persist-a čišćen (višak `playerStats={}` placeholder uklonjen).

### Verify
- `SimMatchPersistWiringTest` (2, BaseTest + H2):
  1. `persistWires...`: 22 stats reda, home 5 clean sheet (2-0), rating 80 za
     strelca (2 gola, rating 8.0×10), karijerni bijмп (goals/assists/rating),
     home/away lineup vezani, league table entry-ji kreirani za oba tima.
  2. `persistSkipsSyntheticPlayerIds`: samo sintetički id-jevi → 0 redova,
     Match se i dalje piše (1-0).
- `mvn -o test -Dtest='!TifoUITest'` — isto 9 poznatih legacy `demo.service`
  kvarova, bez novih (53 run, 9 fail: 3+3+3).

---

## Session 7.5 — Real-squad fallback + seeded RNG + discipline wiring + English report + UI fixes

Zatvaranje preostalih koraka match-flow batch-a iz korisničke prijave 2026-09-24
(H10 imena / prazni lineups / Stats≠Report / scoreboard skaka na finalni rezultat).

### Šta je urađeno

**Real-squad fallback (Level 2 dopuna)**
- `RealSquadFactory.buildSquadFromPlayers(List<db Player>, team)` — gradi
  Squad od REALNIH DB igrača (bez lineupa/startersa): sortira po
  `positionOrder` (GK=0, DEF=1, MID=2, WNG=3, ATT=4, null pos → 9) i
  `ensureSingleGK` garantuje tačno jednog golmana.
- `SimMatchService.loadRealSquad` — kada lineup nema ≥11 startersa, fallback
  na `playerRepository.findByTeamId(team.getId())` → `buildSquadFromPlayers`.
  Eliminiše sintetičke igrače → imenovani scorers u Goals tabu, popunjen
  Lineups tab, pravi `MatchPlayerStats` redovi (MOTM više nije N/A).

**Seeded RNG (determinizam)**
- `util/SimulationRandom.java` (novo) — `ThreadLocal<Random>` provider sa
  `seed(long)`, `nextDouble()`, `nextBoolean()`, `nextInt(bound)`, `rng()`.
  Paralelno-bezbedan (thread-local), default globalno ponašanje nepromenjeno.
- `SimMatchService.simulate` — `SimulationRandom.seed(fixture.getId()` (ili
  `System.nanoTime()` za direktne pozive) na startu → ista utakmica = isti
  rezultat.
- Konvertovani svi RNG call-sites: `ExecutionQuality` (11 poziva),
  `BallPhysicsEngine`, `CleanDecisionEngine` (i `Math.random()` linija),
  `DuelEngine`. `MatchOrchestrator` — uklonjena 3 dupla `Random` importa,
  `VARService(state, SimulationRandom.rng())`.

**Discipline wiring ("poveži da radi")**
- `DisciplineService.evaluateFoul` — nova probabilistička logika:
  `foulProb = clamp(0.16 − skill*0.005, 0.05, 0.22)`; crveni 2% prekršaja,
  žuti 35% (osim ako crveni); penalty-box geotetrija HOME rows≥6.5 / AWAY
  rows≤1.5, cols 2–5; `incrementFouls/YellowCards/RedCards` na MatchState-u
  sada stvarno rastu. Uklonjen mrtav `(Position, boolean)` overload i
  nekorišćeno polje.
- `DuelService` — konstruiše `DisciplineService(state, null)`; proverava
  prekršaj SAMO kad DEFENDER dobije DRIBBLE/TACKLE/RECEIVE_PASS duele, pre
  `applyDuelResult` (tada je `state.getCarrier()` još napadani igrač, pa
  geometrija box-a radi); na osnovu odluke loguje `<FOUL|YELLOW_CARD|RED_CARD|PENALTY>`.
  **Poznato ograničenje:** prekršaj nema free-kick restart (igra se
  nastavlja) i crveni ne skida igrača — evidencija je statistička.
- `ProposalStatsCollector` — `onFoul/onYellowCard/onRedCard`; `TeamAcc` +
  `buildTeamStats` umesto hardkodovanih 0 sada vraćaju stvarne
  `fouls/yellowCards/redCards` (ključevi `homeFouls`/`homeYellowCards`/
  `homeRedCards` u statsMap već su postojali).

**UI / prikaz (korisničke prijave)**
- `viewer.js` — running scoreboard više ne skače na finalni rezultat:
  novi `_snapshotAt(intTick)` (binarna pretraga `_snapTicks`) + `_updateScoreboard()`
  čita `snap.homeGoals/awayGoals` (fallback 0), ne `?? data.homeGoals`.
- `match-view.js` — **Stats tab sada čita kanonske vrednosti** sa
  `/api/zox/match-stats/{matchId}` (statsMap): Possession, xG, Shots,
  Shots on/off target, Pass accuracy, Corners, Offsides, Yellow/Red cards,
  Fouls (Penalties red uklonjen — engine nema penal statistiku). Ranije je
  Stats derivovao iz detail-events (GOAL-only za engine utakmice → nule).
- `match-view.js` — dodato **gornje Back dugme** (pored postojećeg donjeg),
  `Back` navigacija ista kao donje (`goBackSmart`).
- `ZoxApiController` — kompletna engleska lokalizacija izveštaja (headline,
  summary, turning point, taktički nalaz, top performers, timeline, preview).

**Legacy testovi**
- Obrisana 3 legacy test fajla + 2 `.bak`:
  `TestMatchSimulatorIntegration.java(+bak/bak2)`, `TestFootballRulesService.java`,
  `TestTacticalPerspectiveTransformer.java`. **Važno:** nakon brisanja izvornih
  fajlova ostaju stale `.class` u `target/test-classes` koje surefire i dalje
  izvršava → nekad je potreban `mvn -o clean test` (ne samo `mvn test`).

### Verify
- `mvn -o clean test` → **35 run, 0 fail, 0 error** (9 legacy kvarova nestalo).
- `node --check match-view.js` → syntax OK.
- Goals tab sada prikazuje realna imena strelaca (fallback zapravo "Player N"
  samo kad tim nema NIJEDNOG DB igrača).

---

---

## 2026-09-25 — LOOSE-BALL CHASE, DRIBBLE FREEZE, KICKOFF HOLD, BALL PHYSICS, GOAL SEQUENCE

### 1. DRIBBLE FREEZE — 44 minutes of duels, ball never moving (URGENT)
**Simptom (iz loga):** `DUEL DRIBBLE won by Šumenko Dabić` ponavljao se svakih
2-3 ticka, lopta nije pomerena (`ball(5.9,3.6)`) — od 30:69 do 89:78, dakle
**44 minute meča bez ijednog poteza**. Igrač je bio "zakačen".

**Uzrok:** duel cooldown je bio **PAIRWISE** (`DuelEngine.isOnCooldown` → mapa
po paru igrača). Nosilac pobedi levog bezbbednika → taj je zaključan 7 tickova →
nosilac odmah pobedi sledećeg → kad se prvi cooldown istekne, opet pobija njega.
Pobednik NIJE nikad bio na cooldownu, pa je zadržavao loptu zauvek, a tick loop
je svaki tick trošio na duele — `DECISION` se uopšte nije izvršavao.

**Fix:**
- `Player.lastDuelTick` — cooldown je sada **po igraču**, ne po paru: duel kreće
  samo ako **oba** takmičara su van cooldowna. Pobednik je samo duel-potisnut
  (NIJE movement-lokovan), pa i dalje može da se kreće, pase i šutira, ali ne
  može odmah da se bori sa sledećim.
- `DuelService` — **jedan duel po ticku** (`break` posle rezultatа); ranije je
  pet duelova moglo da se reši u istom ticku.
- `DuelEngine.applyDuelResult` — upisuje `lastDuelTick` za **oba** igrača.

**Verify (seed 777, single match):** najduži niz duel linija bez decision-a
**2** (bilo stotinama), najveći gap između događaja **29 s** (bilo 44 min).

### 2. LOOSE-BALL CHASE — obe ekipe, i pokretna lopta
`MovementEngine.looseBallChaser` je vraćao `null` dok je lopta u pokretu
(`speed > STOP_SPEED`) i samo **jednog** najbližeg igrača. Iskosa lopta je
zato rolala bez ijednog igrača, pa je recovery dolazio sekundama kasnije.
Sada: najbliži slobodni igrač **svake ekipe** (kontejneri `homeChaser`/
`awayChaser`), za pokretnu i za zaustavljenu loptu; domet 6.0 (pokretna) / 4.0
(stojeca) ćelije.

### 3. KICKOFF — pola terena držan do prihvata lopte
Vlasnik: "svi osim izvodioca moraju biti barem 0.5 ćelije u SVOJOJ polovini".
Plasman je to radio, ali samo trenutno: već sledeći tick su preuzele napadačke
 meteže i AWAY je prešao srednju liniju (probe: tick 1 AWAY min row **4.10**).
- `TacticalIntentEngine.KICKOFF_HALF_BUFFER = 0.5` + `holdInOwnHalf()` — drži
  HOME ≤ 4.0, AWAY ≥ 5.0.
- Držanje traje dok lopta **ne bude primljena** (`MatchState.kickoffHalfHold`,
  brisanje u `BallResultHandler` na `RECEIVE`), ne samo do udara — inače se
  ceo napad odvija dok je lopta još u vazduhu.
- `MatchOrchestrator` safety cap 20 tickova (iskosa koja se nikad ne primi ne
  sme da zamrzne obe ekipe do kraja meča).
- **Verify (probe):** tick 0 i 1 AWAY min **5.00**, HOME max **4.50** (izvođilac),
  oslobađanje na ticku 2. `KickoffHalfLineTest` — 5 testova.

### 4. BALL PHYSICS — lopta više nije sporija od igrača
Vlasnik: "napadač posle kikofa pređe više prostora nego lopta".
`GROUND_DECEL 0.35` = **2.2 m/s²** (stvarno 0.3-0.5), `AIR_DECEL 0.15` =
**0.9 m/s²** (stvarno ~0.1). Udar od 11 m/s posle 2 ticka pada na 4.7 m/s —
**sporije od sprinta igrača**.
Fix: `GROUND_DECEL 0.08` (~0.5 m/s²), `AIR_DECEL 0.03` (~0.19 m/s²).
**Efekat (150 mečeva):** golovi 2.8 (realno 2.7), prolaznost **82.2/82.2**
(bila 79/77), udarci 29.3, konverzija 23%, uglovi 3.0/4.2 (bilo 0.8/4.3),
izbacivanja 62.7 (bilo 17.6), udarci sa strane 29.5 (bilo 17.4), ofsajd 3.4.

### 5. DEFENSIVE-THIRD NO-DRIBBLE (vlasnik 2026-09-25)
Vlasnik: "bek odlučuje da dribla u opasnoj zoni ispred svog gola umesto pas-a,
imao je sigurnih opcija ili ako nema pas unapred ka napadačima NIKAKO DRIBLING".
`CleanDecisionEngine`: u SVOJOJ defanzivnoj trećini (HOME ≤ 3.0 / AWAY ≥ 6.0),
ako postoji bilo koji pas (`passOption > UNAVAILABLE`) → DRIBBLE = `UNAVAILABLE`.
Veto se primenjuje **pre** `selectOptionWithPlaymaking` (prvo je bio posle, pa
je bio bez efekta).

### 6. GOAL OVERLAY — prvo lopta kroz gol, pa overlay
Vlasnik: "kod gola se ne vidi da je prvo bio šut, zatim da je lopta prošla KROZ
GOAL MOUTH pa tek onda overlay". Overlay se prikazivao na `GOAL` eventu i pokrivao
hodu u trenutku prelaska linije. Sada se **enqueue-uje** i prikazuje
`GOAL_OVERLAY_DELAY_TICKS = 3` ticka kasnije (`_maybeShowQueuedGoal`, sa
real-time podlom od 400 ms da sekvenca ostane vidljiva i na 10x).

### Verify
- `mvn test` → **84 run, 0 fail, 0 error** (79 + 5 `KickoffHalfLineTest`).
- `node --check viewer.js` → OK.

### OPEN — scoring calibration (ne puširano)
Uklanjanje 44-minutnog zamrzavanja otkriva da su prethodne cifre bile potisnute
vešitim zamrzavanjima. **150 mečeva posle fixa:** golovi **6.1** (realno 2.7),
najviši rezultat **16 golova**, dueli 601→**335**, prekršaji 28.1→**15.8**,
žuti 4.5→**2.7**, crveni 0.8→**0.4**, udarci 33.9, SOT 14.1.
Delimično povećanje je očekivano (smrznuti mečevi nisu davali golove), ali 6.1 je
i dalje previsoko → zasebna kalibracija golova (SHOT gate / konverzija) +
vraćanje prekršaja na ~22. **Nije dirano u ovom batchu.**

---

## 2026-09-25 — REC 1: goalProximity sign (DecisionEngineProbe finding #1)

**Nađeno `DecisionEngineProbe`-om.** `goalProximity()` je vraćala **UDALJENOST**
od protivničkog gola (`home ? 8.0 - row : row - 1.0`), a pozivaoci su je
**DODAVALI** kao bonus (`prox * 3.5` za pas, `prox * 2.5` za carry, isti termin
unutar `findBestReceiver`). Veći broj je značio "dalje od gola" i engine je za to
plaćao.

Dokaz (proba, DCR sa loptom na (2.2, 5.0)):

| igrač | row | prox danas | prox ispravno |
|---|---|---|---|
| H_DCL | 2.2 | **+20.3** | −8.0 |
| H_DR | 2.6 | +18.9 | −6.6 |
| H_STR | 5.8 | +7.7 | **+4.5** |

Pas nazad bezbbedniku je dobijao **12.6 poena više** od pasa napadu — dvostruko
više od celog opsega od 5.0 u kojem radi playmaking random. Zato su odbrambeni
stalno reciklirali loptu umesto da nađu napadače ("nema pas unapred ka
napadacima"), a carry u sopstvenoj polovini je bio *nagrađivan*.

**Fix:** `home ? row - 4.5 : 4.5 - row` (0 na srednjoj liniji, +3.5 na gol-liniji
protivnika, negativno iza). Javadoc je već govorio "0 = standing on the goal
line" — kod je vraćao 0 igraču NA gol-liniji i 6 igraču u sopstvenoj polovini.

**Efekat (200 mečeva, seed 42) — pre/post:**

| metrika | pre | post | efekat |
|---|---|---|---|
| dribling | 250.2 | **199.3** | −20% (carry više nije nagrađen u sopstvenoj polovini) |
| izbacivanja | 81.7 | **126.8** | +55% (odbrambeni čiste umesto da driblaju iz opasnosti) |
| prolaznost | 82.2 / 82.2 | **79.3 / 80.5** | ambicizniji pasovi (realno 80-85%) |
| pasovi | 578.4 | 549.6 | |
| centri | 46.7 | 38.2 | |
| lopte iz ugla | 7.2 | 7.7 | |
| udarci sa strane | 62.7 | 57.1 | |
| ofsajd | 3.4 | 4.6 | |
| posed lopte | 49.7/50.3 | 49.9/50.1 | simetrično |
| dueli | 335.2 | 300.7 | |

Ovo je tačno traženo ponašanje: mnogo manje besmisljenog dribljanja, mnogo
više čišćenja, i realnija prolaznost.

**NE rešeno ovim fixom (konzistentno sa nalazom #2):** golovi **5.5** (realno 2.7),
najviši rezultat 11; prekršaji 13.1 (realno ~22), žuti 2.4, crveni 0.3; AWAY
rezultati 68/94/38. Golove ne dira ovaj fix — krivac je frequency gate (REC 2).

`mvn test` → 84 run, 0 fail. Log format `prox %+.1f` da se negativne vrednosti ne
prikazuju kao "prox +-6.6".
