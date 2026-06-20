# TIFO Text Manager — Uputstvo za AI agenta

## Status: ✅ Igra se (Teletext Edition — Full Season Management)

## Opis

TIFO Text Manager je **text-based** fudbalski menadžer inspirisan teletextom.
Nema grafike — sve je tekst: tabele, izveštaji, poruke.
**In-memory** — nema save-a. Namerno (no-cheat mod).

## Šta je urađeno

### Klub i tim
- Squad sa veštinama, formom, umorom
- Status ikonice: 🏥 povređen, ⚠️ suspendovan, 🔄 umoran, 😠 van forme
- Taktike: 9 formacija, 4 play style-a, starters + 7 rezervnih
- Menadžerova kancelarija (win rate, pozicija, budžet, job security)

### Lige i mečevi
- 20 timova, double round-robin (38 kola)
- Tabela, raspored, match detail (4 taba: Lineups, Events, Stats, Report)
- Tick-by-tick simulacija (golovi, kartoni, zamene, povrede, VAR)

### Transferi
- Pijaca, bidding, inbox ponude

### Inbox
- 17+ tipova poruka sa entity linkovima
- Filtering i sortiranje

### Internacionalni fudbal
- Internacionalni mečevi sa prozorima i tabelom

## Šta treba uraditi

### Srednji prioritet
- Trening sistem (intenzitet slider, development arrows, fatigue management)
- Kalendar (mesečni grid)
- Skauting (scout reports, pretraga, shortliste)
- Osoblje (coaches, scouts, physios)
- Press konferencije

### Dugoročno
- Omladinska škola (youth intake)
- Kup takmičenja
- Ugovori igrača (wage negotiation)
- Breadcrumb navigacija

## Tehnički detalji

| Komponenta | Lokacija |
|---|---|
| Kontroler | `cleanSheet.CleanSheetController` |
| Servis | `cleanSheet.CleanSheetService` |
| Engine | `CSMatchSimulator` |
| State | `CleanSheetGameState` (in-memory per session) |
| Frontend | `/static/js/tifo.js` (~3100 linija) |
| CSS | `/static/css/tifo.css` (teletext tema) |
| HTML | `/static/tifo.html` |

## Login
`velibor@example.com` / `A12345!`
Pristup: `/tifo.html` (ili TIFO TEXT BASED na home page-u)
