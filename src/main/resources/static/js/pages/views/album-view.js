import { htmlEscape } from './utils.js';
import { playerCardHtml, playerTier } from './player-card.js';

/**
 * The album: your squad as a collection of cards (redesign.md R-16).
 *
 * <p>The owner asked for the album view alongside the card itself: *"page + squad mini cards + album
 * view sounds marvellous"*. An album is a grid of cards with numbers, so that is what this is - and
 * the numbers are the point, because a collection without a number is just a list.
 *
 * <p>Two decisions inside this file, both mine to make:
 *
 * <ul>
 *   <li><b>Album order.</b> By position group, then by rating. A football album is arranged by
 *       position - goalkeepers, then defence, then midfield, then attack - and that is the order a
 *       manager reads a squad in anyway.</li>
 *   <li><b>Album numbers are fixed.</b> A player keeps his number when the filter changes, because
 *       a number that changes with the filter is not a number, it is a row index.</li>
 * </ul>
 *
 * <p>The filters are client-side on purpose: the squad is already loaded for the page, and a filter
 * that refetches would make the album feel like a search box rather than a collection.
 */

const POSITION_ORDER = ['GK', 'DEF', 'MID', 'ATT', 'WNG'];

const POSITION_LABELS = {
    GK: 'Goalkeepers',
    DEF: 'Defenders',
    MID: 'Midfield',
    ATT: 'Attackers',
    WNG: 'Wingers'
};

function positionGroup(player) {
    const raw = String(player?.position || '').toUpperCase();
    return POSITION_ORDER.includes(raw) ? raw : 'MID';
}

function compareAlbumOrder(a, b) {
    const groupDelta = POSITION_ORDER.indexOf(positionGroup(a)) - POSITION_ORDER.indexOf(positionGroup(b));
    if (groupDelta !== 0) return groupDelta;
    const overall = Number(b?.overall || 0) - Number(a?.overall || 0);
    if (overall !== 0) return overall;
    return String(a?.name || '').localeCompare(String(b?.name || ''));
}

/**
 * The album page.
 *
 * @param {Array<object>} players the squad
 * @param {object} options
 * @param {string} [options.teamName]
 * @param {string} [options.teamId]
 * @param {Function} options.loadPlayer called with a player id when a card is clicked
 * @returns {string} HTML
 */
export function albumHtml(players, options = {}) {
    const { teamName = 'Your club', teamId = '', loadPlayer = null } = options;
    const rows = Array.isArray(players) ? players.slice().sort(compareAlbumOrder) : [];

    if (!rows.length) {
        return `
            <div class="fm-page fm-page--club">
                <section class="fm-panel fm-club-hero">
                    <div class="fm-eyebrow">Album</div>
                    <h2>No players to collect yet</h2>
                    <p class="fm-subtle">The album fills itself as soon as the club has players on the books.</p>
                </section>
            </div>`;
    }

    // The number is the player's place in the album, and it does not move when the filter does.
    const numbers = new Map(rows.map((player, index) => [player?.id ?? `row-${index}`, index + 1]));

    const counts = POSITION_ORDER.map(group => ({
        group,
        count: rows.filter(player => positionGroup(player) === group).length
    })).filter(entry => entry.count > 0);

    const legends = rows.filter(player => playerTier(player).foil).length;

    const cards = rows.map(player => playerCardHtml(player, {
        albumNumber: numbers.get(player?.id ?? ''),
        clubName: teamName
    })).join('');

    return `
        <div class="fm-page fm-page--club fm-page--album">
            <section class="fm-panel fm-club-hero">
                <div class="fm-eyebrow">Album</div>
                <h2>${htmlEscape(teamName)}</h2>
                <p class="fm-subtle">
                    ${rows.length} card${rows.length === 1 ? '' : 's'} on the books
                    ${legends ? ` · ${legends} legend${legends === 1 ? '' : 's'}` : ''}
                    · arranged by position, then by rating
                </p>
                <div class="fm-album-filters" role="group" aria-label="Filter the album by position">
                    <button type="button" class="fm-album-chip is-active" data-album-filter="all">All</button>
                    ${counts.map(entry => `
                        <button type="button" class="fm-album-chip" data-album-filter="${entry.group}">
                            ${htmlEscape(POSITION_LABELS[entry.group])} (${entry.count})
                        </button>`).join('')}
                </div>
            </section>
            <div class="fm-album" data-album-grid>${cards}</div>
        </div>`;
}

/**
 * Wires the album: the filter chips, and the card clicks.
 *
 * <p>Split from the markup on purpose - a view that renders HTML and also owns behaviour is two
 * things at once, and the filter needs to re-render without rebuilding the page.
 *
 * @param {HTMLElement} container the element holding the album
 * @param {Array<object>} players the squad, in album order
 * @param {object} options
 * @param {string} [options.teamName]
 * @param {Function} [options.loadPlayer]
 */
export function bindAlbum(container, players, options = {}) {
    if (!container) return;
    const { teamName = 'Your club', loadPlayer = null } = options;
    const rows = Array.isArray(players) ? players.slice().sort(compareAlbumOrder) : [];
    const numbers = new Map(rows.map((player, index) => [player?.id ?? `row-${index}`, index + 1]));

    const paint = filter => {
        const grid = container.querySelector('[data-album-grid]');
        if (!grid) return;
        const visible = filter === 'all' ? rows : rows.filter(player => positionGroup(player) === filter);
        grid.innerHTML = visible.map(player => playerCardHtml(player, {
            albumNumber: numbers.get(player?.id ?? ''),
            clubName: teamName
        })).join('');
    };

    container.querySelectorAll('[data-album-filter]').forEach(chip => {
        chip.addEventListener('click', () => {
            container.querySelectorAll('[data-album-filter]').forEach(other => other.classList.remove('is-active'));
            chip.classList.add('is-active');
            paint(chip.dataset.albumFilter || 'all');
        });
    });

    if (typeof loadPlayer === 'function') {
        container.querySelectorAll('.fm-pcard').forEach(card => {
            card.addEventListener('click', () => {
                const playerId = Number(card.dataset.playerId);
                if (playerId) loadPlayer(playerId);
            });
        });
    }
}
