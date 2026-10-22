// pages/views/utils.js
// Pure utility functions extracted from pages.js

import { escapeHtml } from '../../ui/escape.js';

/**
 * Re-exported, not redefined.
 *
 * <p>This file used to carry its own copy of the escaper, which made three in the repository while
 * {@code ui/escape.js} exists precisely to be the only one — and AGENTS.md asserts it is the only one.
 * Every additional copy is somewhere a fix lands in one implementation and misses another, and an
 * escaping bug does not fail a test suite: it shows up as broken markup, or as a script injected
 * through a club name.
 */
export const htmlEscape = escapeHtml;

/**
 * A card that says what actually failed.
 *
 * <p>The router's catch-all used to write {@code buildEmptyState("API Error")}: one string, no status,
 * no code, no explanation, and it replaced whatever the page had already rendered. So a 403 that says
 * "Only the owning club can accept incoming offers" and a 500 that says the database was unreachable
 * reached the manager as the same two words.
 *
 * <p>The backend already says something useful. {@code ApiException} carries a {@code code} and a
 * {@code message} written for the person reading it, and {@code authFetch} puts both on the thrown
 * error. This renders them.
 *
 * <p>Everything interpolated here is escaped. The values come from a server, and an error message is
 * exactly the kind of string that ends up holding a club name.
 *
 * @param err    the thrown value, usually an {@code AuthFetchError}
 * @param context a short description of what was being loaded, e.g. "the transfer centre"
 */
export function buildErrorState(err, context) {
    const status = err?.status ?? null;
    const code = err?.code ?? null;
    const detail = err?.message ?? null;

    const title = errorTitle(status);
    const rows = [];
    if (context) rows.push(['Looking for', context]);
    if (code) rows.push(['Reported', code]);
    if (status) rows.push(['Status', `${status}`]);
    if (detail && detail !== title) rows.push(['What the server said', detail]);

    const body = rows.length
        ? `<table style="margin:16px auto 0; border-collapse:collapse; text-align:left;">
               ${rows.map(([k, v]) => `<tr>
                   <td style="padding:4px 14px 4px 0; opacity:0.65; vertical-align:top;">${htmlEscape(k)}</td>
                   <td style="padding:4px 0;">${htmlEscape(v)}</td>
                 </tr>`).join('')}
           </table>`
        : '';

    return `<div class="manager-card" style="padding:32px;">
        <h2 style="margin:0;">${htmlEscape(title)}</h2>
        <p class="fm-subtle" style="margin:8px 0 0;">Reload the page. If it keeps failing, the details below are what to report.</p>
        ${body}
    </div>`;
}

function errorTitle(status) {
    if (status === 401) return 'Your session has expired';
    if (status === 403) return 'You do not have access to that';
    if (status === 404) return 'That could not be found';
    if (status && status >= 500) return 'The server could not answer';
    return 'Something went wrong loading this page';
}

export function normalizeLeagueId(value) {
    const numeric = Number(value);
    return Number.isFinite(numeric) && numeric > 0 ? numeric : null;
}

/**
 * Whether a field carries a number.
 *
 * <p><b>`Number.isFinite(Number(value))` is not that test on its own.</b> `Number(null)` is
 * <b>zero</b> - and so is `Number('')` - so a field that is null passed as "a number", and the row
 * then printed the null itself.
 *
 * <p>It showed on the fixture row: the <em>league</em> round is null for every cup and every
 * international tie, because the league round only exists for a league, and the null was read as a
 * zero and then printed - "Rd null" - while the real round sat in the row, unused (owner,
 * 2026-10-22: *"za International meceve nije dobar round S1 · D1 · Wk 1 · Rd null, verujem i za
 * Cup, liga je dobra"*).
 *
 * <p>It also showed on the club page's summary: a result the manager has not seen yet arrives with
 * no goals, and the null read as a zero read as a 0-0 draw - so a hidden result was counted as a
 * draw in "Wins / Draws / Losses".
 */
export function hasNumber(value) {
    if (value === null || value === undefined || value === '') return false;
    return Number.isFinite(Number(value));
}

export function isLeaguePage(page) {
    return page === 'leagueTable' || page === 'leagueSchedule' || page === 'leagueMatches';
}

export function parseMatchDate(dateArr) {
    if (Array.isArray(dateArr)) {
        const [year, month, day, hour, minute, second, nano] = dateArr;
        const ms = nano ? Math.floor(nano / 1000000) : 0;
        return new Date(year, month - 1, day, hour, minute, second, ms);
    }
    return new Date(dateArr);
}

export function getImageFilename(name) {
    return name
        .normalize("NFD")
        .replace(/[\u0300-\u036f]/g, "")
        .replace(/đ/g, "dj")
        .replace(/Đ/g, "Dj")
        .replace(/\s+/g, '_')
        .replace(/[^a-zA-Z0-9_-]/g, '');
}

export function normalizeTeamKey(name) {
    return (name || "")
        .toLowerCase()
        .normalize("NFD")
        .replace(/[\u0300-\u036f]/g, "")
        .replace(/[^a-z0-9]/g, "");
}

export function normalizePlayerKey(name) {
    return (name || "")
        .toLowerCase()
        .normalize("NFD")
        .replace(/[\u0300-\u036f]/g, "")
        .replace(/[^a-z0-9]/g, "");
}

/**
 * The sentence an API failure should show the manager.
 *
 * <p>{@code authFetch} **throws** on a non-2xx response, carrying the server's own message. A caller
 * that wrote {@code const res = await authFetch(...); if (!res.ok) {...}} therefore never reaches
 * its own error branch: the promise rejects, the browser logs "Uncaught (in promise)" and the
 * manager sees nothing at all. The server usually explains itself perfectly - "Opening the junior
 * school costs 17466 and the club has 2537" - and that sentence is the whole point of the click.
 *
 * @param {*} error the thrown error
 * @param {string} [fallback] what to say when there is no message
 * @returns {string} a message for the UI
 */
export { apiErrorMessage } from '../../auth.js';

export function formatBudget(value) {
    // Capped at two decimals for the same reason percentages are: a budget that reaches the browser
    // as a double must not print as "1,234.5678". Whole amounts are unaffected.
    return `EUR ${Number(value || 0).toLocaleString(undefined, { maximumFractionDigits: 2 })}`;
}

/**
 * A money amount as ready-to-insert HTML whose sign is expressed as colour.
 *
 * <p>The amount strings themselves must stay escaped plain text — every house template wraps
 * {@link formatBudget} in {@code escapeHtml}, so returning a span from the formatter itself would
 * print the markup. This helper is for the specific surfaces where an amount can be negative
 * (budgets, net/balance lines): the value passes through the same escape as everywhere else, and
 * the only thing added is a class. Colour is an accent, the minus sign still carries the meaning.
 *
 * @param {*} value the raw amount
 * @param {object} [options]
 * @param {boolean} [options.positive=false] also tone positive amounts green - for net/balance
 *        lines where positive colour means "healthy", not for plain budget chips
 * @returns {string} HTML, e.g. `<span class="fm-money fm-money--neg">EUR -666.71</span>`
 */
export function moneySpan(value, { positive = false } = {}) {
    const num = Number(value || 0);
    const tone = num < 0 ? 'fm-money--neg' : (positive && num > 0 ? 'fm-money--pos' : '');
    return `<span class="fm-money${tone ? ' ' + tone : ''}">${htmlEscape(formatBudget(value))}</span>`;
}

/**
 * A percentage, for display.
 *
 * <p>Percentages reach the browser as raw doubles — a pitch at 67.26952925761245 is a number nobody
 * should be shown. Two decimals is the house rule for any decimal, and anything that is not a finite
 * number is a dash rather than "NaN%".
 *
 * @param {*} value the raw percentage
 * @param {number} [decimals=2] how many decimal places to show
 * @returns {string} e.g. "67.27%", or "—" when there is no value
 */
export function formatPercent(value, decimals = 2) {
    if (value == null || value === '') return '—';
    const num = Number(value);
    if (!Number.isFinite(num)) return '—';
    return `${num.toFixed(decimals)}%`;
}

export function formatMilestoneAttendanceValue(value) {
    const numeric = Number(value || 0);
    return numeric > 0 ? numeric.toLocaleString() : '—';
}

/**
 * @param meta plain text, escaped as usual - or an object `{ club, icons }`, where `club` is escaped
 *   and `icons` is markup this file built itself. **Never pass caller markup in `meta`.** It used to take
 *   one string and escape all of it, which is right and is why the icons have to arrive as a pair of
 *   parts: a pre-joined "<span>⚽</span> · club" string would render as visible tags.
 */
export function buildMilestoneCardHtml(title, value, meta, extraClass = '') {
    const safeValue = value == null || value === '' ? '—' : htmlEscape(String(value));
    const safeMeta = meta == null || meta === ''
        ? 'No milestone logged yet.'
        : typeof meta === 'object'
            ? `${safeTeam(meta.club)} · ${meta.icons}`
            : htmlEscape(String(meta));
    return `
        <article class="fm-milestone-card ${extraClass}">
            <div class="fm-milestone-kicker">${htmlEscape(String(title || 'Milestone'))}</div>
            <div class="fm-milestone-value">${safeValue}</div>
            <div class="fm-milestone-meta">${safeMeta}</div>
        </article>`;
}

/**
 * A count shown as repeated icons - "⚽ ⚽ ⚽" for three goals - reusing the badge classes the match
 * lineup already uses, so a goal looks the same wherever it appears in the app.
 */
function iconCount(badgeClass, icon, count) {
    const total = Math.max(0, Number(count) || 0);
    if (total === 0) return '0';
    return Array.from({ length: total }, () => (
        `<span class="fm-badge fm-badge-icon ${badgeClass}" aria-hidden="true">${icon}</span>`
    )).join('');
}

/** A club name, escaped. This file's milestone board used to interpolate the name raw. */
function safeTeam(value) {
    const name = String(value ?? '').trim();
    return name ? htmlEscape(name) : 'No team';
}

export function buildMilestoneBoardHtml(milestones) {
    const scorer = milestones?.topScorer || null;
    const assist = milestones?.topAssist || null;
    const biggestWin = milestones?.biggestWin || null;
    const biggestLoss = milestones?.biggestLoss || null;
    const attendance = milestones?.attendance || null;
    const trophies = Array.isArray(milestones?.trophies) ? milestones.trophies : [];

    return `
        <div class="fm-milestone-grid">
            ${buildMilestoneCardHtml(
                'Top scorer',
                scorer?.playerName || '—',
                scorer?.playerName ? { club: scorer.teamName, icons: iconCount('fm-badge-goal', '⚽', scorer.value) } : 'No goals filed yet.'
            )}
            ${buildMilestoneCardHtml(
                'Top assist',
                assist?.playerName || '—',
                assist?.playerName ? { club: assist.teamName, icons: iconCount('fm-badge-ast', '&#127984;', assist.value) } : 'No assists filed yet.'
            )}
            ${buildMilestoneCardHtml(
                'Biggest win',
                biggestWin?.summary || '—',
                biggestWin?.context || 'Waiting for a standout result.'
            )}
            ${buildMilestoneCardHtml(
                'Heaviest loss',
                biggestLoss?.summary || '—',
                biggestLoss?.context || 'No heavy defeat registered yet.'
            )}
            ${buildMilestoneCardHtml(
                'Attendance',
                formatMilestoneAttendanceValue(attendance?.averageAttendance),
                attendance?.averageAttendance
                    ? `High ${formatMilestoneAttendanceValue(attendance.highestAttendance)} (${attendance.highestMatchLabel || '—'}) · Low ${formatMilestoneAttendanceValue(attendance.lowestAttendance)} (${attendance.lowestMatchLabel || '—'}) · ${attendance.insight || ''}`
                    : (attendance?.insight || 'Crowd data will appear once played fixtures start filing gates.'),
                'attendance'
            )}
            ${buildHonoursCardHtml(trophies)}
        </div>`;
}

/** The medals a club has won: a colour, the competition, and the season (owner, 2026-10-08). */
function buildHonoursCardHtml(trophies) {
    const medals = trophies.filter(t => MEDAL_ORDER.includes(t?.medal));
    const body = medals.length === 0
        ? '<div class="fm-milestone-meta">No medals yet.</div>'
        : `<ul class="fm-honours">${medals.map(t => `
            <li class="fm-honour fm-honour--${htmlEscape(String(t.medal).toLowerCase())}">
                <span class="fm-honour-medal" aria-hidden="true"></span>
                <span class="fm-honour-name">${htmlEscape(t.competitionName || 'Competition')}</span>
                <span class="fm-honour-season">Season ${htmlEscape(String(t.seasonYear ?? '—'))}</span>
            </li>`).join('')}</ul>`;
    return `
        <article class="fm-milestone-card fm-milestone-card--honours">
            <div class="fm-milestone-kicker">Honours</div>
            ${body}
        </article>`;
}

/** Gold before silver before bronze is the display order; the list arrives sorted by the repository. */
const MEDAL_ORDER = ['GOLD', 'SILVER', 'BRONZE'];

export function formatDateTimeLabel(value) {
    if (!value) return '-';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return htmlEscape(String(value));
    return `${date.toLocaleDateString()} ${date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
}

export function formatGoalDiff(value) {
    const number = Number(value || 0);
    return `${number > 0 ? "+" : ""}${number}`;
}

export function getRatingColor(rating) {
    const value = Number(rating);
    if (!Number.isFinite(value)) return "#9aa0a6";
    if (value >= 7.5) return "#4caf50";
    if (value >= 6.5) return "#ffd700";
    if (value >= 5.5) return "#ff9800";
    return "#f44336";
}

export function formatFormBadge(formValue) {
    const value = Number(formValue);
    if (!Number.isFinite(value)) return `<span class="form-badge neutral">-</span>`;
    if (value >= 7.8) return `<span class="form-badge hot">&#128293; ${value.toFixed(1)}</span>`;
    if (value <= 5.8) return `<span class="form-badge cold">&#129482; ${value.toFixed(1)}</span>`;
    return `<span class="form-badge neutral">${value.toFixed(1)}</span>`;
}

export function formatRatingBadge(ratingValue) {
    const value = Number(ratingValue);
    if (!Number.isFinite(value)) return `<span style="color:#9aa0a6;">-</span>`;
    return `<span style="color:${getRatingColor(value)}; font-weight:700;">${value.toFixed(1)}</span>`;
}

export function formatCompactPlayerName(value) {
    const safeName = String(value ?? '').trim();
    if (!safeName) return 'Unknown';
    const parts = safeName.split(/\s+/).filter(Boolean);
    if (parts.length <= 1) return safeName;
    return `${parts[0].charAt(0)}. ${parts[parts.length - 1]}`;
}

export function buildRepeatedLineupBadge(count, badgeClass, icon, label) {
    const total = Math.max(0, Number(count) || 0);
    return Array.from({ length: total }, () => (
        `<span class="fm-badge fm-badge-icon ${badgeClass}" title="${label}" aria-label="${label}">${icon}</span>`
    )).join('');
}

export function buildLineupEventBadges(player) {
    const goals = Number(player?.goals || 0);
    const isGoalkeeper = String(player?.position || '').toUpperCase() === 'GK';
    const assists = isGoalkeeper ? 0 : Number(player?.assists || 0);
    const rawYellowCards = Math.max(0, Number(player?.yellowCards || 0));
    const rawRedCards = Math.max(0, Number(player?.redCards || 0));
    let yellowCards = Math.min(rawYellowCards, 1);
    let redCards = Math.min(rawRedCards, 1);

    if (rawYellowCards >= 2 && redCards === 0) {
        yellowCards = 1;
        redCards = 1;
    }
    if (redCards > 0) {
        yellowCards = Math.min(yellowCards, 1);
    }

    const badges = [
        buildRepeatedLineupBadge(goals, 'fm-badge-goal', '⚽', 'Goal'),
        buildRepeatedLineupBadge(assists, 'fm-badge-ast', '&#127984;', 'Assist'),
        buildRepeatedLineupBadge(yellowCards, 'fm-badge-card-yellow', '🟨', 'Yellow card'),
        buildRepeatedLineupBadge(redCards, 'fm-badge-card-red', '🟥', 'Red card')
    ].filter(Boolean);
    return badges.length
        ? `<div class="fm-match-lineup-badges">${badges.join('')}</div>`
        : `<span class="fm-match-lineup-badges is-empty">—</span>`;
}

export function getPendingJuniorReveal(playerId) {
    try {
        const raw = sessionStorage.getItem("junior_promotion_reveal");
        if (!raw) return null;
        const payload = JSON.parse(raw);
        if (!payload || Number(payload.playerId) !== Number(playerId)) return null;
        return payload;
    } catch (e) {
        return null;
    }
}

export function delay(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

export function formatPlayerSkill(exact, visible) {
    if (exact != null && Number.isFinite(Number(exact))) return Number(exact).toFixed(2);
    if (visible != null && Number.isFinite(Number(visible))) return Number(visible).toFixed(2);
    return "-";
}

export function clampPercent(value) {
    const number = Number(value);
    if (!Number.isFinite(number)) return 0;
    return Math.max(0, Math.min(100, Math.round(number)));
}

export function getPlayerConditionPercent(player) {
    const fatigue = Number(player?.fatigue);
    if (!Number.isFinite(fatigue)) return 100;
    return clampPercent(100 - fatigue);
}

export function getPlayerPositionInfo(position) {
    const raw = String(position ?? '').trim();
    const upper = raw.toUpperCase();
    const active = new Set();

    if (/GK|GOALKEEPER/.test(upper)) active.add('GK');
    if (/(LB|DL|LWB|LEFT BACK)/.test(upper)) active.add('DL');
    if (/(RB|DR|RWB|RIGHT BACK)/.test(upper)) active.add('DR');
    if (/(CB|DC|STOPPER|DEFENDER)/.test(upper)) active.add('DC');
    if (/(DM|CDM|DMC)/.test(upper)) active.add('DM');
    if (/(CM|MC|MIDFIELDER)/.test(upper) && !/(AMC|AMR|AML|DM)/.test(upper)) active.add('MC');
    if (/(CAM|AMC|AM)/.test(upper)) active.add('AMC');
    if (/(LM|LW|AML|ML|LEFT WING)/.test(upper)) active.add('WL');
    if (/(RM|RW|AMR|MR|RIGHT WING)/.test(upper)) active.add('WR');
    if (/(ST|CF|FC|FW|STRIKER|FORWARD)/.test(upper)) active.add('ST');

    if (!active.size) {
        if (/KEEPER/.test(upper)) active.add('GK');
        else if (/BACK|DEF/.test(upper)) active.add('DC');
        else if (/WING/.test(upper)) active.add('WL');
        else if (/ATT/.test(upper)) active.add('AMC');
        else active.add('MC');
    }

    const primary = active.has('GK') ? 'GK'
        : active.has('ST') ? 'ST'
        : active.has('AMC') ? 'AMC'
        : active.has('MC') ? 'MC'
        : active.has('DM') ? 'DM'
        : active.has('DC') ? 'DC'
        : active.has('DL') ? 'DL'
        : active.has('DR') ? 'DR'
        : active.has('WL') ? 'WL'
        : active.has('WR') ? 'WR'
        : 'MC';

    return {
        raw,
        primary,
        items: [
            { key: 'GK', label: 'GK', top: '86%', left: '50%' },
            { key: 'DL', label: 'DL', top: '69%', left: '20%' },
            { key: 'DC', label: 'DC', top: '68%', left: '50%' },
            { key: 'DR', label: 'DR', top: '69%', left: '80%' },
            { key: 'DM', label: 'DM', top: '54%', left: '50%' },
            { key: 'WL', label: 'WL', top: '40%', left: '18%' },
            { key: 'MC', label: 'MC', top: '40%', left: '50%' },
            { key: 'WR', label: 'WR', top: '40%', left: '82%' },
            { key: 'AMC', label: 'AMC', top: '24%', left: '50%' },
            { key: 'ST', label: 'ST', top: '11%', left: '50%' }
        ].map(item => ({
            ...item,
            active: active.has(item.key),
            primary: item.key === primary
        }))
    };
}

export function formatTransferMoney(value) {
    if (value == null || !Number.isFinite(Number(value))) return '—';
    return htmlEscape(formatBudget(Math.round(Number(value))));
}

export function getTransferInterestedTeams(transferStatus) {
    if (!transferStatus) return [];
    if (Array.isArray(transferStatus.interestedTeams)) return transferStatus.interestedTeams.filter(Boolean);
    return Object.values(transferStatus.interestedTeams || {}).filter(Boolean);
}

export function formatSeasonShortLabel(seasonYear) {
    const startYear = Number(seasonYear);
    if (!Number.isFinite(startYear)) return 'Current season';
    return `${startYear}/${String((startYear + 1) % 100).padStart(2, '0')}`;
}

const alpha3ToAlpha2CountryCode = {
    SRB: 'RS', BIH: 'BA', MNE: 'ME', HRV: 'HR', SVN: 'SI',
    MKD: 'MK', DEU: 'DE', GBR: 'GB', BRA: 'BR'
};

export function countryFlagEmojiFromIso(isoCode) {
    const normalized = String(isoCode || '').trim().toUpperCase();
    const alpha2 = /^[A-Z]{2}$/.test(normalized)
        ? normalized
        : alpha3ToAlpha2CountryCode[normalized] || '';
    if (!/^[A-Z]{2}$/.test(alpha2)) return '';
    return Array.from(alpha2)
        .map(letter => String.fromCodePoint(127397 + letter.charCodeAt(0)))
        .join('');
}

export function getCountryFlagImagePath(country) {
    const explicitPath = String(country?.flagImagePath || '').trim();
    if (explicitPath) return explicitPath;
    return String(country?.isoCode || '').trim().toUpperCase() === 'SRB'
        ? '/images/serbiaflag.png'
        : '';
}

export function buildCountryFlagBadgeHtml(country, countryName) {
    const imagePath = getCountryFlagImagePath(country);
    if (imagePath) {
        return `<div class="fm-country-badge fm-country-badge--image"><img src="${htmlEscape(imagePath)}" alt="${htmlEscape(countryName)} flag"></div>`;
    }
    const flagEmoji = countryFlagEmojiFromIso(country?.isoCode);
    return `<div class="fm-country-badge">${flagEmoji || '🌍'}</div>`;
}

export function sortCountryLeagues(leagues) {
    return [...(Array.isArray(leagues) ? leagues : [])].sort((left, right) => {
        const tierDiff = Number(left?.tier || 999) - Number(right?.tier || 999);
        if (tierDiff !== 0) return tierDiff;
        const divisionDiff = Number(left?.divisionLevel || 999) - Number(right?.divisionLevel || 999);
        if (divisionDiff !== 0) return divisionDiff;
        return String(left?.name || '').localeCompare(String(right?.name || ''), undefined, { sensitivity: 'base' });
    });
}

export function buildLeagueMetaLabel(league) {
    const bits = [];
    const tier = Number(league?.tier);
    const divisionLevel = Number(league?.divisionLevel);
    if (Number.isFinite(tier)) bits.push(`Tier ${tier}`);
    if (Number.isFinite(divisionLevel) && divisionLevel > 1) bits.push(`Division ${divisionLevel}`);
    return bits.join(' · ') || 'League';
}

export function buildEmptyState(message) {
    return `<div class="manager-card" style="text-align:center; padding:40px;">
                <h2>${htmlEscape(message)}</h2>
            </div>`;
}

/**
 * Reads a JSON body, or throws with the status in the message.
 *
 * <p><b>Why this exists rather than an `if (!response.ok)` at each call site.</b> `authFetch` throws on
 * every non-2xx, so an unguarded `await response.json()` never reaches the guard that was written for it:
 * the throw skips it and escapes to whatever called the loader. Six places had exactly that shape, and
 * five of them reported *"API Error"* — a page that says two words about a 403 saying *"Only the owning
 * club can accept incoming offers"*.
 *
 * <p>Use this where the screen genuinely cannot be drawn without the payload. The message names what
 * failed, so the page says which panel is empty rather than that the application is broken.
 */
export async function readJsonOrThrow(response, label) {
    if (!response || !response.ok) {
        throw new Error(`${label} failed (${response ? response.status : 'no response'})`);
    }
    return response.json();
}

/**
 * Reads a JSON body, or returns a fallback.
 *
 * <p>For the loads that fetch several panels at once. A missing country catalogue must not blank a
 * transfer screen that already has its market rows, and one unreadable leaderboard must not take the
 * scorers table with it.
 *
 * <p>The fallback is returned rather than thrown because <b>some of these panels are legitimately
 * absent</b> — a competition with nothing played has no milestones, and that is a fact about the world
 * rather than a failure. Throwing there would replace an honest empty panel with an error card.
 */
export async function readJsonOr(response, fallback, label) {
    if (!response || !response.ok) {
        console.warn(`${label} unavailable (${response ? response.status : 'no response'}); showing the panel empty.`);
        return fallback;
    }
    try {
        return await response.json();
    } catch (err) {
        console.warn(`${label} returned a body that is not JSON; showing the panel empty.`, err);
        return fallback;
    }
}

/**
 * The player's appearance count and average rating.
 *
 * <p><b>authFetch is not optional and must be passed.</b> It used to be called with one argument from
 * both player-view.js and league-view.js, so `authFetch` was undefined and the call threw a TypeError
 * which this catch turned into `matchesPlayed: 0`. The profile then showed "MC 0 matches" and "Apps 0"
 * for a player who had started, scored and assisted - while the very same page showed his goal and his
 * assist, because those come from a different column.
 *
 * <p>So `matchesPlayed` is now null on failure rather than 0. Zero means "played no matches" and that is a
 * fact about the player; null means "we could not find out", and a caller that prints null is visibly
 * broken instead of confidently wrong.
 */
export async function fetchPlayerRatingSummary(playerId, authFetch) {
    const unknown = { averageRating10: null, averageRating100: null, matchesPlayed: null, loaded: false };
    if (typeof authFetch !== 'function') {
        console.error('fetchPlayerRatingSummary called without authFetch; appearance count is unknown.');
        return unknown;
    }
    try {
        const response = await authFetch(`/match-stats/player/${playerId}`);
        const payload = await response.json();
        return {
            averageRating10: payload.averageRating10 ?? null,
            averageRating100: payload.averageRating100 ?? null,
            matchesPlayed: payload.matchesPlayed ?? null,
            loaded: true
        };
    } catch (err) {
        console.error(`Rating summary for player ${playerId} failed.`, err);
        return unknown;
    }
}

/**
 * Asks whether this club's next match has already been decided.
 *
 * <p>An hour before kickoff the match is computed and everything a manager could still change about it
 * is a fiction. The server refuses those writes with 423; this asks first, so the page can say so with
 * the opponent and the kickoff hour instead of letting a manager discover it by saving something.
 *
 * <p>Never throws. A page that cannot learn whether it is locked must still draw — a banner is worth
 * having, a blank tactic editor because the lock call failed is not.
 *
 * @returns {Promise<{locked: boolean, reason?: string, opponent?: string, kickoffHour?: number}>}
 */
export async function fetchMatchLock(authFetch, teamId) {
    if (!teamId) return { locked: false };
    try {
        const response = await authFetch(`/teams/${teamId}/match-lock`);
        if (!response || !response.ok) return { locked: false };
        const lock = await response.json();
        return lock && typeof lock === 'object' ? lock : { locked: false };
    } catch (_) {
        return { locked: false };
    }
}

/**
 * The banner a locked page shows instead of an unexplained disabled button.
 *
 * <p>Names the opponent and the kickoff hour, because a manager who cannot change his lineup for the
 * hour before kickoff deserves to know which match and how long he has, and "locked" on its own reads
 * as a bug rather than as the game's own rule.
 *
 * @returns {string} HTML, empty when the club is not locked
 */
export function renderMatchLockBanner(lock) {
    if (!lock || !lock.locked) return '';
    const when = typeof lock.kickoffHour === 'number'
        ? ` Kick-off is day ${lock.day} at ${lock.kickoffHour}:00.`
        : '';
    return `<div class="fm-panel" style="border-left:4px solid #b8860b; margin-bottom:16px;">
                <div style="padding:14px 16px;">
                    <strong>This match is closed.</strong>
                    ${htmlEscape(lock.reason || 'Your match has already been decided.')}${when}
                    Tactic changes, the eleven and the orders can be set again after the match.
                </div>
            </div>`;
}

/**
 * Whether a save button on a locked page should be inert.
 *
 * <p>The server refuses these writes with 423 regardless, so this is presentation and not security — but
 * a button that looks live and fails on click is the exact thing the banner is meant to prevent.
 *
 * @returns {boolean} true when the page must not offer to save
 */
export function isPageLocked(lock) {
    return !!(lock && lock.locked);
}

/**
 * The chip that says "this row is yours".
 *
 * <p>The row mark itself (`is-own`) answers "is this mine?" when you are looking at the row. It does
 * not answer "which row was that?" when you have scrolled away, and on a phone the table is wider
 * than the screen. The chip travels with the club name for that reason.
 *
 * <p>One implementation, used by every table that marks a row: a second copy in a second table is
 * how "YOUR CLUB" ends up spelled three ways.
 *
 * @param {string} [label] chip text; the owner asked for YOUR CLUB
 * @returns {string} HTML
 */
export function ownClubChipHtml(label = 'YOUR CLUB') {
    return `<span class="fm-own-chip">${htmlEscape(label)}</span>`;
}

/**
 * A "this is you" card for a long list, with a jump into it.
 *
 * <p>One convention, one meaning (owner 2026-10-14; designAnalysis.md §9 rule 3). Marking your row in a
 * list is not enough when the list is 278 ties long and the round you care about is the third screen
 * down: you still have to find it. So the mark and the way to it travel together.
 *
 * <p>Used by the national cup and the three continental club cups, which is the point — one answer to
 * "where am I in this" rather than one per competition.
 *
 * @param {object} options
 * @param {Array<{label:string, value:string}>} options.facts one-line summary (round, week, score…)
 * @param {string} [options.jumpLabel] text on the jump button; omit for no button
 * @param {string} [options.target] DOM id the jump scrolls to
 * @param {string} [options.kicker] heading above the club name; "YOUR CLUB" by default
 * @returns {string} HTML, or '' when there is nothing to say
 */
export function ownClubSummaryCard({ facts = [], kicker = 'Your club', jumpLabel = '', target = '', name = '' } = {}) {
    if (!name && facts.length === 0) return '';
    const rows = facts
        .filter(row => row && row.value !== undefined && row.value !== null && row.value !== '')
        .map(row => `<span class="fm-own-fact"><span class="fm-own-fact-key">${htmlEscape(row.label)}</span> `
            + `<span class="fm-own-fact-value">${htmlEscape(row.value)}</span></span>`)
        .join('');
    const jump = jumpLabel && target
        ? `<button type="button" class="fm-action-btn secondary fm-own-jump" data-jump-to="${htmlEscape(target)}">${htmlEscape(jumpLabel)}</button>`
        : '';
    return `
        <section class="fm-panel fm-own-summary">
            <div class="fm-own-summary-head">
                <span class="fm-milestone-kicker">${htmlEscape(kicker)}</span>
                ${name ? `<strong class="fm-own-summary-name">${htmlEscape(name)}</strong>` : ''}
            </div>
            ${rows ? `<div class="fm-own-summary-facts">${rows}</div>` : ''}
            ${jump}
        </section>`;
}

/**
 * Scrolls to an element by id and puts the keyboard focus there.
 *
 * <p>The id, not the element, because the jump button is rendered before the list it points at and the
 * list is re-rendered whenever the tab is switched. Focusing as well as scrolling is what makes it
 * usable from the keyboard; a button that scrolls and drops focus is not a control, it is a decoration.
 */
export function wireJumpButtons(root, doc = document) {
    root.querySelectorAll('[data-jump-to]').forEach(button => {
        button.addEventListener('click', () => {
            const target = doc.getElementById(button.dataset.jumpTo);
            if (!target) return;
            target.scrollIntoView({ behavior: 'smooth', block: 'center' });
            if (typeof target.focus === 'function') {
                target.setAttribute('tabindex', target.getAttribute('tabindex') ?? '-1');
                target.focus({ preventScroll: true });
            }
        });
    });
}
