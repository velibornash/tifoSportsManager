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

> Uvodi komit `HASH`.

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
