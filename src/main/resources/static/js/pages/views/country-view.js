// pages/views/country-view.js
import {
    htmlEscape, buildEmptyState, sortCountryLeagues, buildCountryFlagBadgeHtml,
    buildLeagueMetaLabel, formatSeasonShortLabel
} from './utils.js';

export function createCountryView(deps) {
    const {
        authFetch, loadPage, setActiveLeagueContext,
        getCurrentUserCountryIsoCode, getActiveLeagueCountryIsoCode,
        getCurrentUserCountryName, getSeasonYear,
        buildClubActionsHtml
    } = deps;

    async function openCountryLeague(leagueId, leagueName) {
        setActiveLeagueContext({
            leagueId,
            leagueName,
            countryIsoCode: getCurrentUserCountryIsoCode() || getActiveLeagueCountryIsoCode() || '',
            backTarget: 'country'
        });
        await loadPage('leagueTable', { preserveLeagueContext: true });
    }

    async function loadCountryPage() {
        const mainContent = document.getElementById('main-content');
        const countryIsoCode = getCurrentUserCountryIsoCode();
        if (!countryIsoCode) {
            mainContent.innerHTML = buildEmptyState('Country data is not available for this manager yet.');
            return;
        }

        try {
            const countryIso = String(countryIsoCode).toUpperCase();
            const [countriesResponse, leaguesResponse] = await Promise.all([
                authFetch('/countries'),
                authFetch(`/countries/${encodeURIComponent(countryIso)}/leagues`)
            ]);

            if (!leaguesResponse.ok) throw new Error(`Country leagues load failed: ${leaguesResponse.status}`);

            const countries = countriesResponse.ok ? await countriesResponse.json() : [];
            const leagues = await leaguesResponse.json();
            const sortedLeagues = sortCountryLeagues(leagues);
            const quickLeagues = sortedLeagues.slice(0, 2);
            const country = (Array.isArray(countries) ? countries : []).find(item => String(item?.isoCode || '').toUpperCase() === countryIso) || {
                name: getCurrentUserCountryName() || countryIso,
                isoCode: countryIso,
                flagImagePath: '',
                currencyCode: '',
                reputation: null,
                youthRating: null,
                seniorNationalTeam: null,
                u21NationalTeam: null
            };
            const countryName = country?.name || getCurrentUserCountryName() || countryIso;
            const countryTitle = htmlEscape(countryName);
            const countryBadgeHtml = buildCountryFlagBadgeHtml(country, countryName);

            mainContent.innerHTML = `
                <div class="fm-page fm-page--country">
                    <div class="fm-page-toolbar">
                        <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                        <div class="fm-page-title-block">
                            <div class="fm-eyebrow">Country overview</div>
                            <h2>${countryTitle}</h2>
                            <div class="fm-subtle">Browse your federation, jump into any league table, and keep the main League button tied to your club context.</div>
                        </div>
                    </div>

                    <div class="fm-grid-top fm-grid-top--country">
                        <section class="fm-panel fm-country-hero">
                            <div class="fm-country-hero-main">
                                ${countryBadgeHtml}
                                <div class="fm-country-meta">
                                    <div class="fm-eyebrow">Federation</div>
                                    <h3>${countryTitle}</h3>
                                    <div class="fm-subtle">ISO ${htmlEscape(country?.isoCode || countryIso)}${country?.currencyCode ? ` \u00B7 Currency ${htmlEscape(country.currencyCode)}` : ''}</div>
                                    <div class="fm-country-note">Season ${htmlEscape(formatSeasonShortLabel(getSeasonYear()))}</div>
                                </div>
                            </div>
                            <div class="fm-medical-stat-grid team-summary-grid fm-country-stat-grid">
                                <div><strong>${country?.reputation ?? '\u2014'}</strong><span>Reputation</span></div>
                                <div><strong>${country?.youthRating ?? '\u2014'}</strong><span>Youth rating</span></div>
                                <div><strong>${sortedLeagues.length}</strong><span>Leagues</span></div>
                                <div><strong>${htmlEscape(country?.currencyCode || '\u2014')}</strong><span>Currency</span></div>
                            </div>
                        </section>

                        <section class="fm-panel">
                            <div class="fm-panel-head">
                                <div>
                                    <h3>Quick leagues</h3>
                                    <p class="fm-subtle">Fast access for the top levels, plus a dropdown for the full league list.</p>
                                </div>
                                <span class="fm-panel-action">Country browse</span>
                            </div>
                            <div class="fm-country-league-grid">
                                ${quickLeagues.map(league => `
                                    <article class="fm-country-league-card">
                                        <div class="fm-milestone-kicker">${htmlEscape(buildLeagueMetaLabel(league))}</div>
                                        <div class="fm-update-title">${htmlEscape(league?.name || 'League')}</div>
                                        <div class="fm-update-meta">Open the same standings/fixtures/scorers shell used for your main league view.</div>
                                        <button type="button" class="fm-action-btn secondary fm-country-card-action" data-country-league-id="${league?.id || ''}" data-country-league-name="${htmlEscape(league?.name || 'League')}">Open table</button>
                                    </article>`).join('') || `<div class="fm-empty">No leagues found for this country yet.</div>`}
                            </div>
                            ${sortedLeagues.length ? `
                                <div class="fm-country-select-row">
                                    <label class="fm-season-select-wrap fm-country-select-control">
                                        <span>All leagues</span>
                                        <select id="country-league-select" class="fm-season-select">
                                            ${sortedLeagues.map(league => `<option value="${league?.id || ''}" data-league-name="${htmlEscape(league?.name || 'League')}">${htmlEscape(league?.name || 'League')} \u00B7 ${htmlEscape(buildLeagueMetaLabel(league))}</option>`).join('')}
                                        </select>
                                    </label>
                                    <button type="button" id="country-open-selected-league" class="fm-action-btn">Open selected league</button>
                                </div>` : ''}
                        </section>
                    </div>

                    <div class="fm-grid-bottom fm-grid-bottom--single">
                        <section class="fm-panel">
                            <div class="fm-panel-head">
                                <div>
                                    <h3>National teams</h3>
                                    <p class="fm-subtle">Navigation placeholders are ready now; backend data can be connected later.</p>
                                </div>
                                <span class="fm-panel-action">Placeholder</span>
                            </div>
                            <div class="fm-country-team-grid">
                                <article class="fm-country-team-card">
                                    <div class="fm-milestone-kicker">Senior</div>
                                    <div class="fm-update-title">${htmlEscape(country?.seniorNationalTeam?.name || `${country?.name || getCurrentUserCountryName() || 'Country'} National Team`)}</div>
                                    <div class="fm-update-meta">Top-level squad hub placeholder.</div>
                                    <button type="button" class="fm-action-btn secondary fm-country-card-action" data-country-placeholder="nationalTeam">Open</button>
                                </article>
                                <article class="fm-country-team-card">
                                    <div class="fm-milestone-kicker">U-21</div>
                                    <div class="fm-update-title">${htmlEscape(country?.u21NationalTeam?.name || `${country?.name || getCurrentUserCountryName() || 'Country'} U-21`)}</div>
                                    <div class="fm-update-meta">Youth national setup placeholder.</div>
                                    <button type="button" class="fm-action-btn secondary fm-country-card-action" data-country-placeholder="u21Team">Open</button>
                                </article>
                            </div>
                        </section>
                    </div>
                </div>`;

            mainContent.querySelectorAll('[data-country-league-id]').forEach(button => {
                button.addEventListener('click', () => {
                    openCountryLeague(Number(button.dataset.countryLeagueId), button.dataset.countryLeagueName || 'League');
                });
            });

            const countryLeagueSelect = document.getElementById('country-league-select');
            const openSelectedLeagueButton = document.getElementById('country-open-selected-league');
            if (countryLeagueSelect && openSelectedLeagueButton) {
                openSelectedLeagueButton.addEventListener('click', () => {
                    const selectedOption = countryLeagueSelect.options[countryLeagueSelect.selectedIndex];
                    const selectedLeagueId = Number(countryLeagueSelect.value);
                    if (!selectedLeagueId) return;
                    openCountryLeague(selectedLeagueId, selectedOption?.dataset?.leagueName || selectedOption?.textContent || 'League');
                });
            }

            mainContent.querySelectorAll('[data-country-placeholder]').forEach(button => {
                button.addEventListener('click', () => loadPage(button.dataset.countryPlaceholder || 'nationalTeam'));
            });
        } catch (err) {
            console.error('Failed to load country page:', err);
            mainContent.innerHTML = `
                <div class="manager-card">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                    <h2>Error</h2>
                    <p>Could not load your country overview.</p>
                </div>`;
        }
    }

    async function loadNationalTeamPlaceholder(level = 'senior') {
        const isU21 = level === 'u21';
        const mainContent = document.getElementById('main-content');
        const title = isU21
            ? `${getCurrentUserCountryName() || 'Country'} U-21`
            : `${getCurrentUserCountryName() || 'Country'} National Team`;
        const currentActionPage = isU21 ? 'u21Team' : 'nationalTeam';

        mainContent.innerHTML = `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero fm-placeholder-hero">
                    <button class="back-to-dashboard" data-nav-back="dashboard">Back</button>
                    <div class="fm-club-hero-main">
                        <div>
                            <div class="fm-eyebrow">National setup</div>
                            <h2>${htmlEscape(title)}</h2>
                            <p class="fm-subtle">This route is intentionally a frontend placeholder for now, so the action-row buttons already have a clean destination before BE national-team payloads are wired.</p>
                        </div>
                        ${buildClubActionsHtml(currentActionPage)}
                    </div>
                    <div class="fm-medical-stat-grid team-summary-grid">
                        <div><strong>${htmlEscape(getCurrentUserCountryName() || '\u2014')}</strong><span>Country</span></div>
                        <div><strong>${isU21 ? 'U-21' : 'Senior'}</strong><span>Level</span></div>
                        <div><strong>Placeholder</strong><span>Status</span></div>
                        <div><strong>Later</strong><span>Backend data</span></div>
                    </div>
                </section>
                <section class="fm-panel">
                    <div class="fm-empty">National-team squad, schedule, call-ups, and staff will be added here once backend endpoints are ready.</div>
                </section>
            </div>`;
    }

    return { loadCountryPage, loadNationalTeamPlaceholder, openCountryLeague };
}
