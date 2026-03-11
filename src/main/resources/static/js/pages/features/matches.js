export function createMatchesFeature(deps) {
    const { authFetch, getTeamId, renderMatches, renderFixtures } = deps;

    async function loadResults() {
        const teamId = getTeamId();
        const response = await authFetch(`/teams/${teamId}/matches`);
        const matches = await response.json();
        const results = matches.sort((a, b) => new Date(b.matchDate) - new Date(a.matchDate));
        renderMatches(results, 'Results', { currentPage: 'schedule' });
    }

    async function loadFixtures() {
        const teamId = getTeamId();
        const response = await authFetch(`/teams/${teamId}/schedule`);
        const schedule = await response.json();
        const fixtures = (Array.isArray(schedule) ? schedule : [])
            .sort((a, b) => new Date(a?.matchDate || 0) - new Date(b?.matchDate || 0));
        renderFixtures(fixtures, 'Schedule', { currentPage: 'schedule' });
    }

    return { loadResults, loadFixtures };
}
