export function createTeamFeature(deps) {
    const { authFetch, getTeamId, getTeamName, renderPlayers, renderAlbum } = deps;

    /**
     * Owner, 2026-10-16: milestones and the medical-centre panel came off the First Team page,
     * so there is no reason to fetch them for it: the page only needs the squad. `renderPlayers`
     * still accepts `milestones` and `medicalOverview`, but they are unused on this surface and
     * asking for them on every open was paying two round trips for nothing.
     */
    async function loadFirstTeam() {
        const teamId = getTeamId();
        const response = await authFetch(`/teams/${teamId}/players`);
        const players = await response.json();
        renderPlayers(players, 'First Team', {
            teamId,
            teamName: getTeamName?.() || 'First Team'
        });
    }

    /**
     * The album: the same squad, as a collection of cards (redesign.md R-16).
     *
     * <p>Same endpoint as the First Team page and for the same reason - the album is the squad, and
     * there is no reason to ask the server twice for the same list.
     */
    async function loadAlbum() {
        const teamId = getTeamId();
        const response = await authFetch(`/teams/${teamId}/players`);
        const players = await response.json();
        renderAlbum(players, {
            teamId,
            teamName: getTeamName?.() || 'Your club'
        });
    }

    return { loadFirstTeam, loadAlbum };
}
