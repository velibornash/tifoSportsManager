export function createTeamFeature(deps) {
    const { authFetch, getTeamId, getTeamName, renderPlayers } = deps;

    async function loadFirstTeam() {
        const teamId = getTeamId();
        const [response, milestonesResponse, medicalResponse] = await Promise.all([
            authFetch(`/teams/${teamId}/players`),
            authFetch(`/teams/${teamId}/milestones`).catch(() => null),
            authFetch(`/teams/${teamId}/medical`).catch(() => null)
        ]);
        if (!response.ok) {
            throw new Error(`Team players load failed: ${response.status}`);
        }
        const players = await response.json();
        const milestones = milestonesResponse?.ok ? await milestonesResponse.json() : null;
        const medicalOverview = medicalResponse?.ok ? await medicalResponse.json() : null;
        renderPlayers(players, 'First Team', {
            teamId,
            teamName: getTeamName?.() || 'First Team',
            milestones,
            medicalOverview
        });
    }

    return { loadFirstTeam };
}
