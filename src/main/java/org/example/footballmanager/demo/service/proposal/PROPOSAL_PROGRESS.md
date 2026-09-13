# PROPOSAL_PROGRESS.md — demo/service/proposal

Dokument praćenja napretka za **čisti, samostalni sim autor utakmice** u
`demo/service/proposal` (paket ne uvozi ništa van svog stabla).

> **Pravilo rada:** pre svake izmene uvek se uradi `git commit` trenutnog
> stanja. Svaki upis ispod nosi **vreme** i **hash komita** koji ga uvodi.

---

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
- **Component scan** — dodato `org.example.footballmanager.demo.service.proposal` u `@SpringBootApplication`.
- **Verifikacija:** `curl -X POST /api/proposal/generate` vraća match JSON; viewer na `/demo/service/ui/proposal/index.html` učitava match.json, prikazuje pitch + timeline.

```bash
# kompajliranje
mvn -q compile

# launcher (12 min meča = 480 tika)
mvn -q exec:java -Dexec.mainClass=org.example.footballmanager.demo.service.proposal.MatchSimulationLauncher

# ceo meč / duži run radi provere freeze-ova
mvn -q exec:java -Dexec.mainClass=org.example.footballmanager.demo.service.proposal.MatchSimulationLauncher -Dexec.args=1440
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