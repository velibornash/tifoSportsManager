export function getQueryParam(name) {
    const params = new URLSearchParams(window.location.search);
    return params.get(name);
}
export function getPlayerName(ev) {
    return ev.playerName || ev.scorerName || ev.takerName ||
           ev.playerOutName || ev.playerInName || "";
}