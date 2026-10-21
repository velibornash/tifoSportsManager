#!/usr/bin/env bash
#
# Run the app locally through Docker (owner, 2026-10-21: "napravi da pokrecem lokalno iz dockera").
#
# WHY THIS EXISTS
#
# The owner runs the app from the IDE, and that run owns port 8080 and the class files under
# target/classes. This script is the other way to run it: the same code, built into an image and run
# in a container, so it is isolated from the IDE - it does not read target/classes, it cannot be
# broken by a build the IDE is doing, and it keeps its own port.
#
# It is one command instead of the five-line `docker run` it wraps, because the arguments are not
# obvious: the database hostname, the network, and the port the owner already decided on.
#
# WHAT IT USES
#
#   local.dockerfile   the LOCAL build - image.dockerfile is the server's build, and the server's
#                      build is not this line's to touch. The only difference that matters here is
#                      that local.dockerfile caches the Maven repository, so a rebuild after a code
#                      change is ~1 minute rather than ~10.
#   tifo-postgres      the database container, expected on the `tifo_default` network. The app
#                      reaches it by its container name, so nothing depends on the host's port.
#   tifo_default       the network both containers share.
#
# PORTS
#
# The container's 8080 is published on the host's 8081, deliberately: the IDE run stays on 8080 and
# the two can be up at the same time without one killing the other. The app is then at
#
#   http://localhost:8081/login.html
#
# USAGE
#
#   ./run-local-docker.sh              build the image if needed, start the container, wait, report
#   ./run-local-docker.sh --rebuild    force an image rebuild first (after a code change)
#   ./run-local-docker.sh --logs       follow the container's log
#   ./run-local-docker.sh --stop       stop and remove the container
#   ./run-local-docker.sh --status     say what is running
#   ./run-local-docker.sh --help       this text
#
# A NOTE ON THE WAIT
#
# The app is up when its own HTTP answers, not when Docker says the container started - the JVM
# inside is still wiring itself together for the better part of a minute, and a request in that
# window is answered by nothing. So the script polls /login.html rather than sleeping a guessed
# number of seconds, and says how long it took. Admin buttons still want a moment after that (the
# world is not loaded instantly), which is why the closing line says so.

set -euo pipefail

# ---------------------------------------------------------------------------------------------
# Settings. The names and the port are the owner's; the database credentials match tifo-postgres.
# ---------------------------------------------------------------------------------------------

IMAGE="tifo-sports-manager:local"
CONTAINER="tifo-app"
DB_CONTAINER="tifo-postgres"
NETWORK="tifo_default"
HOST_PORT=8081
CONTAINER_PORT=8080
DOCKERFILE="local.dockerfile"

DB_NAME="sokker_db"
DB_USER="postgres"
DB_PASSWORD="stojke"

# How long to wait for the app to answer before giving up. The JVM plus world wiring is well under a
# minute on this machine; the margin is here so a slow first start is not reported as a failure.
READY_TIMEOUT_SECONDS=180

URL="http://localhost:${HOST_PORT}/login.html"

# ---------------------------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------------------------

say() {
    echo "run-local-docker.sh: $*"
}

# Docker Desktop's CLI is not always on the shell's PATH. The owner's setup has it at /usr/local/bin.
export PATH="/usr/local/bin:${PATH}"

# The image, the network and the database container are all assumed to exist by name. This checks the
# two that this script cannot create for itself.
require_docker() {
    if ! command -v docker >/dev/null 2>&1; then
        say "docker was not found on PATH. Start Docker Desktop and try again."
        exit 1
    fi
    if ! docker info >/dev/null 2>&1; then
        say "the Docker daemon is not answering. Start Docker Desktop and try again."
        exit 1
    fi
}

require_database() {
    if ! docker ps --format '{{.Names}}' | grep -qx "${DB_CONTAINER}"; then
        say "the database container '${DB_CONTAINER}' is not running."
        say "start it with:  docker start ${DB_CONTAINER}"
        exit 1
    fi
    if ! docker network ls --format '{{.Name}}' | grep -qx "${NETWORK}"; then
        say "the network '${NETWORK}' does not exist."
        say "it is created with '${DB_CONTAINER}'. Start that container, or recreate the network."
        exit 1
    fi
}

container_exists() {
    docker ps -a --format '{{.Names}}' | grep -qx "${CONTAINER}"
}

container_running() {
    docker ps --format '{{.Names}}' | grep -qx "${CONTAINER}"
}

# Build only when there is no image, or when the caller asked for it. A plain `./run-local-docker.sh`
# that rebuilt every time would spend a minute proving nothing changed.
build_image() {
    local force="${1:-false}"
    if [[ "${force}" != "true" ]] && docker image inspect "${IMAGE}" >/dev/null 2>&1; then
        say "image ${IMAGE} is already built (use --rebuild after a code change)"
        return
    fi
    say "building ${IMAGE} from ${DOCKERFILE} ..."
    docker build -f "${DOCKERFILE}" -t "${IMAGE}" .
    say "image built"
}

# Poll the app's own HTTP. "The container started" and "the app answers" are different facts, and the
# second is the one that matters - the first is true about a JVM that is still loading.
wait_until_ready() {
    local waited=0
    say "waiting for the app to answer at ${URL} ..."
    while (( waited < READY_TIMEOUT_SECONDS )); do
        if curl -s -o /dev/null --max-time 3 "${URL}"; then
            say "the app answered after ${waited}s"
            return 0
        fi
        sleep 2
        waited=$(( waited + 2 ))
    done
    say "no answer after ${READY_TIMEOUT_SECONDS}s. The container is up; the app may still be"
    say "starting, or it may have failed. Check:  docker logs ${CONTAINER}"
    return 1
}

# ---------------------------------------------------------------------------------------------
# Actions
# ---------------------------------------------------------------------------------------------

action_stop() {
    if container_exists; then
        say "stopping ${CONTAINER} ..."
        docker rm -f "${CONTAINER}" >/dev/null
        say "stopped"
    else
        say "${CONTAINER} is not running"
    fi
}

action_status() {
    docker ps -a --filter "name=^/${CONTAINER}$" --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
    docker ps -a --filter "name=^/${DB_CONTAINER}$" --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
    if container_running; then
        say "the app is at ${URL}"
    fi
}

action_logs() {
    if ! container_exists; then
        say "${CONTAINER} does not exist - start it first"
        exit 1
    fi
    exec docker logs -f "${CONTAINER}"
}

action_start() {
    local rebuild="$1"

    require_docker
    require_database
    build_image "${rebuild}"

    # Recreate from scratch. `docker rm -f` on a running container, then run - this is the same
    # "stop, build, start" the app's own rule asks for, and it is the only way to be sure the new
    # image is the one running.
    if container_exists; then
        say "removing the existing ${CONTAINER} ..."
        docker rm -f "${CONTAINER}" >/dev/null
    fi

    say "starting ${CONTAINER} on host port ${HOST_PORT} ..."
    docker run -d \
        --name "${CONTAINER}" \
        --network "${NETWORK}" \
        -p "${HOST_PORT}:${CONTAINER_PORT}" \
        -e SPRING_PROFILES_ACTIVE=prod \
        -e SPRING_DATASOURCE_URL="jdbc:postgresql://${DB_CONTAINER}:5432/${DB_NAME}" \
        -e SPRING_DATASOURCE_USERNAME="${DB_USER}" \
        -e SPRING_DATASOURCE_PASSWORD="${DB_PASSWORD}" \
        -e APP_OPEN_BROWSER=false \
        "${IMAGE}" >/dev/null

    if wait_until_ready; then
        say "ready - open ${URL}"
        say "sign in as velibor@example.com / A12345!"
        say "give the world a moment before pressing an admin button; logs: docker logs -f ${CONTAINER}"
    else
        exit 1
    fi
}

# ---------------------------------------------------------------------------------------------
# Argument handling
# ---------------------------------------------------------------------------------------------

REBUILD=false

case "${1:-}" in
    ""|--start)
        action_start "${REBUILD}"
        ;;
    --rebuild)
        action_start true
        ;;
    --stop)
        action_stop
        ;;
    --status)
        action_status
        ;;
    --logs)
        action_logs
        ;;
    --help|-h)
        sed -n '2,48p' "$0" | sed 's/^# \{0,1\}//'
        ;;
    *)
        say "unknown option: $1"
        say "run ./run-local-docker.sh --help"
        exit 1
        ;;
esac
