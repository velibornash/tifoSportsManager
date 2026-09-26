# American Football Manager — Uputstvo za AI agenta

## Status: ✅ Igra se (Core Complete — 31 liga, 310 timova)

## Opis

American Football Manager je simulacija američkog fudbala sa kompletnom srpskom piramidom:
31 liga, svaka sa po 10 timova. Tvoj tim je AF Omladinac.

## Šta je urađeno

### Tim i igrači
- Prvi tim, grupisan po pozicijama (QB, RB, WR, TE, OL, DE, DT, LB, CB, S, K, P)
- Profil igrača — atribute grid, sezonska statistika
- 53 igrača po timu

### Lige i tabela
- Tabela sa W-L, PF/PA, point diff, form badges
- Najbolji u ligi (passing/rushing/receiving yards, tackles, INTs, sacks)
- Double round-robin

### Mečevi
- Play Now, Play All Round
- Match viewer: **live sequential playback** (kao košarka), sortabilne stats, quarter scores, play-by-play
- Replay iz Recent Matches
- Drive-based engine (4Q + OT)

### Sezona
- 🏈 Start Season + 🔄 Reset Season (dugmad na dashboardu)
- League Table dugme na dashboardu

## Šta treba uraditi

### Srednji prioritet
- Promocija/relegacija
- Transferi/trading
- Depth chart / roster management
- Sezonski auto-advance
- Plej-of / Super Bowl

### Tehnički kratkoročno
- **Match engine balance**: trenutno previše yarda po meču (1310 passing yds za 1 meč) — first down resetuje downs, drive traje predugo
- **Event format**: backend join separator popravljen (`||` umesto `|`), ali stari mečevi u bazi imaju pokvaren format

## Poznati problemi
- Match engine generiše previše playeva (1000+ passing yds po meču)
- Nema PAT/2PC/Safety eventova u engine-u (samo u frontend scoring logici)
- Tabela nema formu (W/L badges su tu ali form strip nedostaje)
- Nema upravljanja rosterom (depth chart, rezanje igrača)

## Tehnički detalji

| Komponenta | Lokacija |
|---|---|
| Kontroler | `AfController` |
| Engine | `AfMatchEngine` (drive-based) |
| Frontend API | `/static/americanfootballmanager/js/af-data.js` |
| Page rendereri | `/static/americanfootballmanager/js/pages/af-*.js` |
| CSS | `americanfootball.css` |
| Dashboard | `americanfootballmanager/dashboard.html` |
| Match viewer | `af-match-viewer.js` (live playback + sort) |

## Login
`velibor@example.com` / `A12345!`
Pristup: `/americanfootballmanager/dashboard.html` (ili AMERICAN FOOTBALL na home page-u)
