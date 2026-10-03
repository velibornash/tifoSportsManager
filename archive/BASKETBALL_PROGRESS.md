# Basketball Manager — Uputstvo za AI agenta

## Status: ✅ Igra se (Core Complete — 31 liga, 310 timova)

## Opis

Basketball Manager je simulacija košarkaškog kluba sa kompletnom srpskom piramidom:
31 liga, svaka sa po 10 timova. Tvoj tim je KK Omladinac u Super Ligi.

## Šta je urađeno

### Tim i igrači
- Prvi tim sa rosterom, sortiranje po svim kolonama
- Profil igrača — veštine, sezonska/karijerna statistika
- 5 pozicija: PG, SG, SF, PF, C

### Lige i tabela
- Tabela sa W-L, PF/PA, point diff, form badges
- Najbolji strelci/skakači/asistenti
- Rezultati lige po kolima

### Mečevi
- Play Now, Play All Round
- Match viewer: scoreboard, live sat, četvrtine, play-by-play događaji, **sortabilne stats tabele**, TOTAL red
- Replay (klikom na odigran meč)

### Sezona
- Start Season + Reset Season (resetuje mečeve, statistike, umor; zadržava timove)
- Double round-robin (18 kola)

## Šta treba uraditi

### Srednji prioritet
- Promocija/relegacija
- Treninzi i razvoj igrača
- Transferi
- Sezonski auto-advance
- Plej-of / titula

## Tehnički detalji

| Komponenta | Lokacija |
|---|---|
| Kontroler | `BbController` |
| Engine | `BbMatchEngine` (100 poseda, skill-weighted) |
| API (FE) | `/static/basketballmanager/js/bb-data.js` |
| Page rendereri | `/static/basketballmanager/js/pages/bb-*.js` |
| CSS | `basketball.css` |
| Dashboard | `basketballmanager/dashboard.html` |

## Poznati problemi
- Nema trening sistema
- Nema transfera
- Nema auto-advance sezone

## Login
`velibor@example.com` / `A12345!`
Pristup: `/basketballmanager/dashboard.html` (ili AMERICAN FOOTBALL na home page-u)
