import { htmlEscape, getImageFilename } from './utils.js';

/**
 * The collectible player card (redesign.md R-16).
 *
 * <p>The owner asked for the Panini/FIFA-album grammar on a phone: *"pojedinacne kartice igrača kad
 * se otvore mogu biti widget, FIFA-Panini-Album like na mobu"*, and then fixed the layout himself:
 * four stats down the left rail, four down the right rail, and the card itself in the middle.
 *
 * <p>The rails are in the owner's order and they are the same order the player page already used for
 * its two-column skill table (owner, 2026-09-27):
 *
 * <pre>
 *   left   Stamina, Pace, Technique, Passing
 *   right  Goalkeeper, Defending, Playmaker, Striker
 * </pre>
 *
 * <p>So the card is not a new set of numbers: it is the same eight skills, moved next to the
 * picture and the rating, which is what a sticker does.
 *
 * <p>Two rules the card lives by:
 *
 * <ul>
 *   <li><b>It is a view, not a currency.</b> No packs, no gacha, nothing to buy. A tier marks what
 *       the manager has on the books, not what he may pay for.</li>
 *   <li><b>It must look deliberate without a photo.</b> There are real photographs for the manager's
 *       own club and none for the tens of thousands of generated players, so the picture falls back
 *       to the house silhouette rather than a broken image.</li>
 * </ul>
 */

/** The four skills on the left rail, top to bottom. The owner's order. */
const RAIL_LEFT = [
    { key: 'stamina', label: 'Sta', full: 'Stamina' },
    { key: 'pace', label: 'Pac', full: 'Pace' },
    { key: 'technique', label: 'Tec', full: 'Technique' },
    { key: 'passing', label: 'Pas', full: 'Passing' }
];

/** The four skills on the right rail, top to bottom. The owner's order. */
const RAIL_RIGHT = [
    { key: 'goalkeeper', label: 'Gk', full: 'Goalkeeper' },
    { key: 'defending', label: 'Def', full: 'Defending' },
    { key: 'playmaker', label: 'Ply', full: 'Playmaker' },
    { key: 'shooting', label: 'Str', full: 'Striker' }
];

/** Position group -> the two letters a sticker would carry. */
const POSITION_CODES = {
    GK: 'GK',
    DEF: 'DF',
    MID: 'MF',
    ATT: 'FW',
    WNG: 'WG'
};

const TIER_LEGEND = { key: 'legend', label: 'LEGEND', foil: true };
const TIER_STAR = { key: 'star', label: 'STAR', foil: false };
const TIER_PROSPECT = { key: 'prospect', label: 'PROSPECT', foil: false };
const TIER_REGULAR = { key: 'regular', label: 'REGULAR', foil: false };

/**
 * Which tier a player is on.
 *
 * <p>The owner's call was mine to make, so it is written down here rather than scattered across the
 * markup. The rules are football-first and only use what the server already returns:
 *
 * <ul>
 *   <li><b>LEGEND</b> (the only foil) - a world-class rating, or a lifetime of output in the club's
 *       books. Fifty goal contributions is a career, not a season.</li>
 *   <li><b>STAR</b> - an 80+ player: the level the club builds around.</li>
 *   <li><b>PROSPECT</b> - twenty or younger. The card says what he is before he is what he will be.</li>
 *   <li><b>REGULAR</b> - everyone else, and the card still says so rather than saying nothing.</li>
 * </ul>
 *
 * <p>The order matters: a 19-year-old with an 88 rating is a legend, not a prospect.
 *
 * @param {object} player the player payload
 * @returns {{key:string, label:string, foil:boolean}} the tier
 */
export function playerTier(player) {
    const overall = Number(player?.overall ?? 0);
    const goals = Number(player?.totalGoals ?? player?.goals ?? 0);
    const assists = Number(player?.totalAssists ?? player?.assists ?? 0);
    const age = Number(player?.age ?? 0);

    if (overall >= 88 || goals + assists >= 50) return TIER_LEGEND;
    if (overall >= 80) return TIER_STAR;
    if (age > 0 && age <= 20) return TIER_PROSPECT;
    return TIER_REGULAR;
}

/**
 * The photograph for a player, or the house silhouette.
 *
 * <p>Photographs exist for the manager's own club and the file name is the player's name with the
 * spaces turned into underscores. Everyone else gets the house image, which is what the player page
 * has always fallen back to.
 *
 * @param {object} player
 * @returns {string} an image URL
 */
export function playerPhotoUrl(player) {
    const filename = getImageFilename(player?.name || 'player');
    return filename ? `/images/${filename}.jpg` : '/images/player.jpg';
}

/**
 * The two-letter position code for the card.
 *
 * @param {object} player
 * @param {string} [override] a more specific label the caller already has (e.g. "ST")
 * @returns {string}
 */
export function playerPositionCode(player, override) {
    if (override) return String(override).toUpperCase().slice(0, 3);
    const raw = String(player?.position || '').toUpperCase();
    return POSITION_CODES[raw] || raw.slice(0, 2) || '—';
}

function skillValue(player, key) {
    const value = Number(player?.[key]);
    return Number.isFinite(value) ? String(Math.round(value)) : '—';
}

function railHtml(player, entries) {
    return entries.map(entry => `
        <span class="fm-pcard-stat" title="${htmlEscape(entry.full)}">
            <b>${htmlEscape(skillValue(player, entry.key))}</b>
            <i>${htmlEscape(entry.label)}</i>
        </span>`).join('');
}

/**
 * The card itself.
 *
 * <p>One renderer for every place a card appears: the player page, the squad list and the album.
 * A second copy would be a second set of numbers, and the numbers are the point.
 *
 * @param {object} player the player payload
 * @param {object} [options]
 * @param {string} [options.positionLabel] a more specific position label ("ST")
 * @param {string} [options.clubName] the club, printed on the card
 * @param {number} [options.albumNumber] the number this player carries in the album
 * @param {string} [options.skillIdPrefix] ids for the eight skills, so a page that counts them up
 *        (the junior reveal) can still find them
 * @returns {string} HTML
 */
export function playerCardHtml(player, options = {}) {
    const {
        positionLabel = '',
        clubName = '',
        albumNumber = null,
        skillIdPrefix = ''
    } = options;

    const tier = playerTier(player);
    const overall = Number(player?.overall);
    const name = player?.name || 'Unknown';
    const code = playerPositionCode(player, positionLabel);
    const number = albumNumber != null ? `#${albumNumber}` : '';

    const skillId = key => (skillIdPrefix ? ` id="${htmlEscape(skillIdPrefix + key)}-val"` : '');

    return `
        <article class="fm-pcard fm-pcard--${tier.key}${tier.foil ? ' fm-pcard--foil' : ''}" data-player-id="${htmlEscape(String(player?.id ?? ''))}" data-player-name="${htmlEscape(name)}">
            <div class="fm-pcard-rail fm-pcard-rail--left">
                ${RAIL_LEFT.map(entry => `
                    <span class="fm-pcard-stat" title="${htmlEscape(entry.full)}">
                        <b${skillId(entry.key)}>${htmlEscape(skillValue(player, entry.key))}</b>
                        <i>${htmlEscape(entry.label)}</i>
                    </span>`).join('')}
            </div>
            <div class="fm-pcard-core">
                <div class="fm-pcard-top">
                    <span class="fm-pcard-ovr">${Number.isFinite(overall) ? overall : '—'}</span>
                    <span class="fm-pcard-pos">${htmlEscape(code)}</span>
                    ${clubName ? `<span class="fm-pcard-club">${htmlEscape(clubName)}</span>` : ''}
                </div>
                <div class="fm-pcard-picture">
                    <img src="${htmlEscape(playerPhotoUrl(player))}" onerror="this.src='/images/player.jpg'"
                         alt="${htmlEscape(name)}">
                </div>
                <div class="fm-pcard-name">${htmlEscape(name)}</div>
                <div class="fm-pcard-foot">
                    <span class="fm-pcard-tier">${htmlEscape(tier.label)}</span>
                    ${number ? `<span class="fm-pcard-num">${htmlEscape(number)}</span>` : ''}
                </div>
            </div>
            <div class="fm-pcard-rail fm-pcard-rail--right">
                ${RAIL_RIGHT.map(entry => `
                    <span class="fm-pcard-stat" title="${htmlEscape(entry.full)}">
                        <b${skillId(entry.key)}>${htmlEscape(skillValue(player, entry.key))}</b>
                        <i>${htmlEscape(entry.label)}</i>
                    </span>`).join('')}
            </div>
        </article>`;
}

/**
 * The small card for a squad row: the picture and the rating, which is what a row of a table can
 * carry without turning into a table of pictures.
 *
 * @param {object} player
 * @returns {string} HTML
 */
export function playerMiniCardHtml(player) {
    const tier = playerTier(player);
    const overall = Number(player?.overall);
    return `
        <span class="fm-pcard-mini fm-pcard-mini--${tier.key}">
            <img class="fm-pcard-mini-photo" src="${htmlEscape(playerPhotoUrl(player))}"
                 onerror="this.src='/images/player.jpg'" alt="">
            <span class="fm-pcard-mini-ovr">${Number.isFinite(overall) ? overall : '—'}</span>
        </span>`;
}
