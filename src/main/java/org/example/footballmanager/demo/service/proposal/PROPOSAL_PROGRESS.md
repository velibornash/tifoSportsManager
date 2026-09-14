# PROPOSAL_PROGRESS.md — demo/service/proposal

Dokument praćenja napretka za **čisti, samostalni sim autor utakmice** u
`demo/service/proposal` (paket ne uvozi ništa van svog stabla).

> **Pravilo rada:** pre svake izmene uvek se uradi `git commit` trenutnog
> stanja. Svaki upis ispod nosi **vreme** i **hash komita** koji ga uvodi.

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
`mvn -q -o compile` clean, exporter radi, stats struktura nepromenjena.