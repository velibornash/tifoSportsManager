// pages/views/utils.js
// Pure utility functions extracted from pages.js

export function htmlEscape(value) {
    return String(value ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/\"/g, "&quot;")
        .replace(/'/g, "&#39;");
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
    return `EUR ${Number(value || 0).toLocaleString()}`;
}

export function formatMilestoneAttendanceValue(value) {
    const numeric = Number(value || 0);
    return numeric > 0 ? numeric.toLocaleString() : '—';
}

export function buildMilestoneCardHtml(title, value, meta, extraClass = '') {
    const safeValue = value == null || value === '' ? '—' : htmlEscape(String(value));
    const safeMeta = meta == null || meta === '' ? 'No milestone logged yet.' : htmlEscape(String(meta));
    return `
        <article class="fm-milestone-card ${extraClass}">
            <div class="fm-milestone-kicker">${htmlEscape(String(title || 'Milestone'))}</div>
            <div class="fm-milestone-value">${safeValue}</div>
            <div class="fm-milestone-meta">${safeMeta}</div>
        </article>`;
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
                scorer?.playerName ? `${scorer.teamName || 'No team'} · ${Number(scorer.value || 0)} goals` : 'No goals filed yet.'
            )}
            ${buildMilestoneCardHtml(
                'Top assist',
                assist?.playerName || '—',
                assist?.playerName ? `${assist.teamName || 'No team'} · ${Number(assist.value || 0)} assists` : 'No assists filed yet.'
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
    const assists = Number(player?.assists || 0);
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
        buildRepeatedLineupBadge(assists, 'fm-badge-ast', '🎯', 'Assist'),
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
                <h2>${message}</h2>
            </div>`;
}

export async function fetchPlayerRatingSummary(playerId, authFetch) {
    try {
        const response = await authFetch(`/match-stats/player/${playerId}`);
        if (!response.ok) return { averageRating10: null, averageRating100: null, matchesPlayed: 0 };
        const payload = await response.json();
        return {
            averageRating10: payload.averageRating10 ?? null,
            averageRating100: payload.averageRating100 ?? null,
            matchesPlayed: payload.matchesPlayed ?? 0
        };
    } catch (err) {
        return { averageRating10: null, averageRating100: null, matchesPlayed: 0 };
    }
}
