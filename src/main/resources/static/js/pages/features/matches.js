export function createMatchesFeature(deps) {
    const { authFetch, getTeamId, renderMatches, renderFixtures, htmlEscape, buildClubActionsHtml } = deps;

    async function loadResults() {
        const teamId = getTeamId();
        const response = await authFetch(`/teams/${teamId}/matches`);
        // authFetch throws on a non-2xx, so the old `if (!response.ok) return` was unreachable and
        // the throw escaped to the page router, which replaced the whole page with "API Error". This
        // page was one of the 13 routed-but-unreachable ones, so nobody had seen it fail - and a
        // menu entry pointed at a page that renders a generic card is worse than no entry.
        if (!response.ok) {
            renderMatchesError(`Could not load results (${response.status}).`);
            return;
        }
        const matches = await response.json();
        const results = matches.sort((a, b) => new Date(b.matchDate) - new Date(a.matchDate));
        renderMatches(results, 'Results', { currentPage: 'results' });
    }

    function renderMatchesError(message) {
        const mainContent = document.getElementById('main-content');
        if (!mainContent) return;
        mainContent.innerHTML = `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">Results</div>
                            <h2>Results</h2>
                            <p class="fm-subtle">${htmlEscape(message)}</p>
                        </div>
                        ${buildClubActionsHtml('results')}
                    </div>
                </section>
            </div>`;
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
