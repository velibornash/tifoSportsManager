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

### Running it in Docker

The same jar the server runs, next to a PostgreSQL of its own, on this machine.

```bash
docker compose up --build        # the game on http://localhost:8080
docker compose logs -f app       # what the game is saying
docker compose down              # stop it; the data stays
docker compose down -v           # stop it and throw the database away
```

The database lives in a named volume, so it survives a rebuild and a restart. So does the game's own
data directory — the world's default tactic (`var/`) and the backups (`backups/`) are written at
runtime.

Two things are configurable, both as environment variables, because the machine already has both
taken: the game's port on the host (`APP_PORT`, 8080 by default) and the database it talks to. To run
the game against the PostgreSQL that is already running on this machine instead of the one this file
starts, put this in a file called `.env` next to `docker-compose.yml`:

```
SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/sokker_db
```

The build is a separate file (`local.dockerfile`) rather than a change to `image.dockerfile`, because
`image.dockerfile` is the server's build. The only difference is that the local build keeps the Maven
repository in a cache, so a rebuild after a code change does not download the world again.

#### Rebuilding after a code change

The app in the container is the **jar inside the image**, so a code change is not live until the image
is rebuilt and the container is recreated. Docker Desktop shows the image and the container, but the
rebuild itself is a `docker` command — Docker Desktop has no "rebuild" button on an image, and its
"Start" on an existing container re-runs the **old** image.

```bash
cd /Users/velja/IdeaProjects/TifoManagerApp

docker compose up -d --build        # rebuild the image and recreate the container
```

That is the whole thing. What it does, and what Docker Desktop shows afterwards:

1. **The build** (`local.dockerfile`) compiles the current source into a new jar and bakes it into
   `tifo-sports-manager:local`. The Maven repository is a build cache, so this is about a minute and
   it does not download the world again.
2. **The recreate**: Compose stops the old `tifo-app` container and starts a new one from the new
   image. The database is a separate container and a named volume, so **the world is not touched**.
3. **In Docker Desktop**: the **Images** tab shows `tifo-sports-manager:local` with a new *Created*
   time — that is the proof the image was rebuilt — and the **Containers** tab shows `tifo-app` with
   a new *Created* time as well, because the container was recreated. The app answers on
   <http://localhost:8080> about 15 seconds later.

Two steps instead of one, when you want to see the image change before the container does:

```bash
docker build -f local.dockerfile -t tifo-sports-manager:local .   # the image only
docker compose up -d --force-recreate                              # the container on it
```

**Then reload the browser.** The app serves its own static files, and the service worker
(`/sw.js`) keeps a copy of the old ones, so the page can show the previous build after a rebuild.
A hard reload (Cmd-Shift-R) is what shows the new code.

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
