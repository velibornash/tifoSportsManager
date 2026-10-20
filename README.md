# Tifo Sports Manager

A football management game you play in the browser: take a club, set the eleven, train the squad,
play the season, and watch the world of football around you move with every result.

The game is a Spring Boot application with a PostgreSQL world behind it and a plain-JavaScript
front end — no build step, no framework, the browser runs the files as they are.

> Live: <http://158.180.22.180:8080/>

---

## What is in it

**A world, not a fixture list.** Forty-eight countries, each with its own five-tier pyramid and its
own national team, rated on one Elo scale. Every club has a town, a stadium, a budget and a squad;
every player has a name, a position, a condition and a form.

**A match engine.** The match is simulated tick by tick — the ball, the passes, the duels, the shots,
the offside, the penalty, the substitutions — and what happens on the pitch is decided there, not by
a dice roll against the odds. The recording of a match can be replayed afterwards.

**A rating that means something.** One Elo system for clubs and one for nations, and a match rating
per player on a 2-to-10 scale: an ordinary game is a 6.5, a goal is worth a little, and a hat-trick
is a nine. A goalkeeper's rating knows how many goals went past him.

**Names that belong to their country.** Every country has a pool of club names, towns and people's
names that its clubs and its players are drawn from, and an administrator can change any of it.

**An interface that follows the light.** Four palettes, chosen per account, and a layout that works
on a phone and on a wide screen: the league table fits a 430px frame, the player page stacks, and
nothing is ever clipped silently.

**A community.** A forum, private messages, a manager-to-manager world, and an office where job
offers arrive.

---

## The stack

| | |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.3 (Web, Data/JPA, Security, Validation) |
| Database | PostgreSQL |
| Authentication | JWT (jjwt 0.12) |
| Front end | Plain HTML, CSS and ES modules — no bundler, no framework |
| Tests | JUnit 5, Mockito, Spring Boot Test, RestAssured |

---

## Running it

**You need:** JDK 21 and a PostgreSQL 14+ you can reach.

```bash
git clone <this repository>
cd tifoSportsManager

export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/sokker_db
export SPRING_DATASOURCE_USERNAME=postgres
export SPRING_DATASOURCE_PASSWORD=<your password>

mvn clean package -DskipTests   # build
./run-app.sh                    # start; it opens the browser on :8080
```

The application reads its database connection from the three environment variables above; nothing is
written down in the repository. On start-up it does **not** seed anything — the world is built from
the administrator's buttons in the admin panel, which is deliberate: a game that fills itself on
every boot is a game whose data nobody trusts.

To build and run the tests:

```bash
mvn test                       # the whole suite
mvn test -Dtest=RatingEngineTest    # one class
```

---

## The layout

```
src/main/java/org/tifo/
├── SportsManagerApplication.java     the entry point
├── commonmanager/                    accounts, security, the shared admin
├── footballmanager/                  the football game: the world, the season, the admin
├── footballtextmanager/              the text-based football game
├── americanfootballmanager/          the American football game
├── basketballmanager/                the basketball game
└── config/

src/main/resources/
├── application.properties
├── names/                            the per-country name pools (club names, towns, people)
└── static/                           the front end: HTML, CSS and ES modules

ops/                                  deployment notes and the maintenance page
footballForDelete/                     parked work, not built: the old demo game and the games
                                      that were retired (the footballtextmanager, the
                                      americanfootballmanager and the basketballmanager trees
                                      are still built and still live under src/main/java)
```

---

## Licence

All rights reserved. The code, the art and the writing are the owner's work; ask before you use
any of it.
