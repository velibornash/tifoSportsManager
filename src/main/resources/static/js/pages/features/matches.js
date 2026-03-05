export function createMatchesFeature(deps) {
    const { authFetch, getTeamId, renderMatches, renderFixtures } = deps;

    async function loadResults() {
        const teamId = getTeamId();
        const response = await authFetch(`/teams/${teamId}/matches`);
        const matches = await response.json();
        const results = matches.sort((a, b) => new Date(b.matchDate) - new Date(a.matchDate));
        renderMatches(results, 'Results');
    }

    async function loadFixtures() {
        const teamId = getTeamId();
        const response = await authFetch(`/demo/matches/teams/${teamId}/fixtures`);
        const fixtures = await response.json();
        renderFixtures(fixtures, 'Fixtures');
    }

    return { loadResults, loadFixtures };
}
