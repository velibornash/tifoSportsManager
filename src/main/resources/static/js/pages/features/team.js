export function createTeamFeature(deps) {
    const { authFetch, getTeamId, getTeamName, renderPlayers } = deps;

    /**
     * Owner, 2026-10-16: milestones and the medical-centre panel came off the First Team page,
     * so there is no reason to fetch them for it: the page only needs the squad. `renderPlayers`
     * still accepts `milestones` and `medicalOverview`, but they are unused on this surface and
     * asking for them on every open was paying two round trips for nothing.
     */
    async function loadFirstTeam() {
        const teamId = getTeamId();
        const response = await authFetch(`/teams/${teamId}/players`);
        if (!response.ok) {
            throw new Error(`Team players load failed: ${response.status}`);
        }
        const players = await response.json();
        renderPlayers(players, 'First Team', {
            teamId,
            teamName: getTeamName?.() || 'First Team'
        });
    }

    return { loadFirstTeam };
}
