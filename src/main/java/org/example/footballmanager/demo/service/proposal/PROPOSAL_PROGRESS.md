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
- Gol linija HOME: **row 1.00**; gol je od **1.00\|3.50 do 1.00\|4.50**
  (širina 1.0, centar col **4.00**).
- Gol linija AWAY: **row 8.00**; gol je od **8.00\|3.50 do 8.00\|4.50**.
- Centar terena: **4.50\|4.00**.
- Levica kaže: red 0 je **vizuelna OOB zona iza domaćeg gola** — lopta ulazi u
  taj red da korisnik vidno vidi da je van terena (gol-aut/korner).
- red 8 je **vizuelna OOB zona iza away gola**.

**Korneri:** HOME levi **1.0\|1.0**, HOME desni **1.0\|7.0**,
AWAY levi **8.0\|1.0**, AWAY desni **8.0\|7.0**.

**Penali:** domaći **1.79\|4.00**, gostujući **7.21\|4.00**.

**Šesnaesterci (box):**
- HOME: **1.00\|2.40, 1.00\|5.60, 2.14\|2.40, 2.14\|5.60**.
- AWAY: **8.00\|2.40, 8.00\|5.60, 6.86\|2.40, 6.86\|5.60**.

**Smer napada istorijski:** HOME na početku utakmice napada levo→desno
(od reda 1 ka redu 7); AWAY obrnuto. Za drugog poluvremena mora ostati
mogućnost zamene strana **bez rušenja sistema** (vidi §1 i "backlog").

> **Napomena o usklađivanju engine-a:** u ovom trenutku engine još uvek računa
> centar gola u koloni **3.5** (`ActionEngine.goalPositionFor`) i svoje
> vrednosti box-a/penala. Nadredene vrednosti iznad su merodavne; usklađivanje
> engine-a sa njima je u backlogu.

---

## 3. POČETNO STANJE (pre svih izmena ovog dokumenta)

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

## 4. ISTORIJA IZMENA I IMPLEMENTACIJA

> Format svakog upisa: **datum i vreme** · **komit hash** · šta je urađeno.

---

### 4.1 `2026-09-12 22:25` · `ce7ddbd` — taktičko učitavanje + kickoff na svojoj polovini

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

### 4.2 `2026-09-12 22:29` · `730b787` — lopta pri nozi (SHOT/PASS sa mesta gde lopta nije)

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
nosilac (npr. `ball(7.0,3.5) H10(7.0,3.5)`); gol u 0:65; 1440 tika bez
freeze-a (max gap 7 s); 2 gola/36 min.

---

### 4.3 `2026-09-12 22:31` · `(doc)` — ovaj dokument (termin finalizovan posle commit-a)

- Kreiran `PROPOSAL_PROGRESS.md`.
- Upisana merodavna geometrija terena (§2) — uključujući OOB definiciju,
  box/penal/korner koordinate i smer napada sa mogućnošću zamene strana.
- Definisani: Cilj (§1), Početno stanje (§3), Istorija (§4), Backlog (§5),
  Pokretanje (§6).

---

## 5. BACKLOG — šta sledi

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

---

## 6. POKRETANJE

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