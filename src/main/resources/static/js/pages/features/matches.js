export function createMatchesFeature(deps) {
    const { authFetch, getTeamId, renderMatches, renderFixtures, htmlEscape, buildClubActionsHtml } = deps;

    /**
     * Loads the club's results.
     *
     * <p>The failure here is **caught, not tested for**. `authFetch` throws on every non-2xx, so the
     * `if (!response.ok)` guard this used to carry was unreachable: the throw skipped it and escaped
     * to the page router, which replaced the whole page with "API Error". A previous edit had already
     * diagnosed that correctly and left the dead guard in place, so the page still did the thing the
     * comment said it no longer did. The Schedule page below never had a guard at all.
     *
     * <p>So both loaders catch, and both render a page that says what failed — a menu entry pointing
     * at a generic error card is worse than no entry.
     */
    async function loadResults() {
        const teamId = getTeamId();
        try {
            const response = await authFetch(`/teams/${teamId}/matches`);
            const matches = await response.json();
            const results = matches.sort((a, b) => new Date(b.matchDate) - new Date(a.matchDate));
            renderMatches(results, 'Results', { currentPage: 'results' });
        } catch (err) {
            renderMatchesError(`Could not load results (${err.message}).`);
        }
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

    /** The schedule, with the same catch — it had no guard at all, so a failure replaced the page. */
    async function loadFixtures() {
        const teamId = getTeamId();
        try {
            const response = await authFetch(`/teams/${teamId}/schedule`);
            const schedule = await response.json();
            const fixtures = (Array.isArray(schedule) ? schedule : [])
                .sort((a, b) => new Date(a?.matchDate || 0) - new Date(b?.matchDate || 0));
            renderFixtures(fixtures, 'Schedule', { currentPage: 'schedule' });
        } catch (err) {
            renderMatchesError(`Could not load the schedule (${err.message}).`);
        }
    }

    return { loadResults, loadFixtures };
}
