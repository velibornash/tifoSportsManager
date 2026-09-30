#!/usr/bin/env bash
#
# Start the app from a shell without opening a browser (owner, 2026-09-29).
#
# WHY THIS EXISTS
#
# BrowserLauncher opens http://localhost:8080/login.html on startup, which is right when the owner
# runs main() from the IDE and wants the game in front of them. It is wrong when a shell starts the
# app: the page pops up over whatever the shell is doing, and it happens on every start.
#
# The application cannot tell those two cases apart. Both are the same JVM with the same properties,
# so "started from the IDE" and "started from a terminal" look identical from inside. The only
# difference is the argument, which means the default cannot be right for both and has to be chosen
# per call site. The app's default stays true for the IDE; every shell start goes through here.
#
# USAGE
#
#   ./run-app.sh                    foreground
#   ./run-app.sh --with-browser     opt in to the browser for this run
#   ./run-app.sh -DskipTests foo    any other Maven args pass straight through
#
# Playwright is unaffected: it launches its own dedicated Chrome and never used this launcher.

set -euo pipefail

OPEN_BROWSER=false

for arg in "$@"; do
    if [[ "$arg" == "--with-browser" ]]; then
        OPEN_BROWSER=true
    fi
done

# Strip the opt-in flag so Maven never sees it.
MAVEN_ARGS=()
for arg in "$@"; do
    if [[ "$arg" != "--with-browser" ]]; then
        MAVEN_ARGS+=("$arg")
    fi
done

if [[ "$OPEN_BROWSER" == "true" ]]; then
    echo "run-app.sh: starting with the browser (explicitly requested)"
else
    echo "run-app.sh: starting without a browser. Ctrl-C to stop."
fi

exec mvn spring-boot:run -Dspring-boot.run.arguments="--app.open-browser=${OPEN_BROWSER}" \
    ${MAVEN_ARGS[@]+"${MAVEN_ARGS[@]}"}
