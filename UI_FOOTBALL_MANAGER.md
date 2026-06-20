# UI Football Manager — Uputstvo za AI agenta

## Status: ✅ U potpunosti implementiran (Full SPA)

## Opis

Ovo je **glavni** fudbalski menadžer — Single Page Application sa modernim UI,
grafikama, taktikama, treninzima, juniorima, transferima, i najnaprednijim meč engine-om.
Pokriva kompletnu srpsku fudbalsku piramidu sa 5 nivoa (Superliga → Zonske lige).

## Šta je urađeno

### Tim i igrači (⚽ Club)
- First Team — kompletan roster sa svim veštinama, formom, umorom
- Profil igrača — detaljne veštine (Pace, Shooting, Passing, Dribbling, Defense, Physical, Mental), forma, karijerna statistika
- Juniors — omladinska škola sa mladim igračima i razvojem
- **Tactics** (`formations-view.js`) — vizuelni editor sa postavljanjem startne postave, formacije i stila (drag & drop)
- **Tactic Editor** (`tactic-editor-view.js`) — napredni editor sa movement rules, set piece assignments, draft/version sistem
- Staff — coaching osoblje, fizioterapeuti, skauti
- Finances — budžet, plate, prihodi
- Medical Center — povrede, suspenzije, kondicija igrača
- Transfers — kupoprodaja igrača, pregovori

### Trening (🏋️ Training)
- Training Setup — konfiguracija nedeljnih treninga (generalni, pozicioni, specijalizovani, advanced)
- Training Reports — nedeljni izveštaji sa grafikonima napretka

### Lige i mečevi (🏆 League)
- Tabela — pozicija, bodovi, gol razlika, forma
- Raspored — mečevi po kolima
- Rezultati — istorijski rezultati
- Kup — nacionalni kup (Kup Srbije)
- Internacionalni mečevi — evropska takmičenja
- Prijateljske utakmice

### Meč Engine (⚡ Realistic)
- RealisticMatchEngine — tick-based (1080 tickova + injury time), poziciono svestan
- Live viewer + replay (`realisticDemo.html`)
- Match analytics — posed, šutevi, faulovi, korneri, ocene

### Država i reprezentacija (🌍 Country)
- Nacionalni tim (seniori) + U21 reprezentacija
- Internacionalni kalendar

### Community (💬 Community)
- Chat (STOMP WebSocket)
- **Admin alati**: DB Init/Reset, Registration (approve/reject), Advance Week

### Statistika (📊 Stats)
- Najbolji strelci, asistencije, analytics

## Frontend arhitektura

```
dashboard.html
  └── app.js          (DOM init, sidebar bootstrap)
  └── dashboard.js    (state management, section switching)
  └── pages.js        (router — 5200+ linija)
        ├── pages-renderers.js  (HTML helperi)
        ├── auth.js             (JWT)
        └── pages/
              ├── views/
              │   ├── formations-view.js     (basic tactics)
              │   ├── tactic-editor-view.js  (advanced tactics)
              │   ├── training-view.js
              │   ├── match-view.js
              │   └── ...
              └── features/
                  ├── community.js   (admin tools, registration)
                  ├── academy.js
                  ├── CTeam.js
                  ├── matches.js
                  └── ...
```

## Šta treba uraditi

### Srednji prioritet
- Promocija/relegacija između liga
- Sezonski auto-advance
- Bolji AI (AI timovi biraju taktike)
- Napredni trening (individualni fokus, mentoring)
- Skauting sistem
- Ugovori igrača i pregovori
- Press konferencije i moral

### Tehnički kratkoročno
- `pages.js` je 5200+ linija — razmisliti o splitu
- Match engine v2 (`newLogic/`) — samostalan, endpointi na `/api/v2/match/`

## Rute

Sve rute u `pages.js`:
`firstTeam`, `juniors`, `medicalCenter`, `formations`, `tactics`, `tacticEditor`,
`staff`, `finances`, `transfers`, `coaches`, `training`, `trainingSetup`,
`trainingReports`, `profile`, `upcoming`, `results`, `schedule`, `fixtures`,
`leagueTable`, `leagueSchedule`, `leagueMatches`, `cup`, `international`,
`friendlies`, `country`, `nationalTeam`, `u21Team`, `forum`, `chat`, `events`,
`playerStats`, `teamStats`, `topScorers`, `topAssists`, `analytics`
