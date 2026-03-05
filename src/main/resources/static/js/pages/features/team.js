export function createTeamFeature(deps) {
    const { authFetch, getTeamId, renderPlayers } = deps;

    async function loadFirstTeam() {
        const teamId = getTeamId();
        const response = await authFetch(`/teams/${teamId}/players`);
        const players = await response.json();
        renderPlayers(players, 'First Team');
    }

    return { loadFirstTeam };
}
