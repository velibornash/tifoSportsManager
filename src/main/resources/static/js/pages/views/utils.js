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

export function formatBudget(value) {
    // Capped at two decimals for the same reason percentages are: a budget that reaches the browser
    // as a double must not print as "1,234.5678". Whole amounts are unaffected.
    return `EUR ${Number(value || 0).toLocaleString(undefined, { maximumFractionDigits: 2 })}`;
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
                assist?.playerName ? { club: assist.teamName, icons: iconCount('fm-badge-ast', '🅰️', assist.value) } : 'No assists filed yet.'
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
        </div>`;
}

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
        buildRepeatedLineupBadge(assists, 'fm-badge-ast', '🅰️', 'Assist'),
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
        if (!response.ok) {
            console.error(`Rating summary for player ${playerId} returned ${response.status}.`);
            return unknown;
        }
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
