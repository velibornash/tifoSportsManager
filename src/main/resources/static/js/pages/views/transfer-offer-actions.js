/**
 * The seller's bid buttons, in one place.
 *
 * <p>There were once a single "Accept best offer" button per <em>player</em>, and the backend chose
 * the bid: the transfer centre rendered offers as prose — "Rival FC offered EUR 900000" — with no
 * identifier in it, so there was nothing a button could carry. An auction the seller cannot answer is
 * not an auction, and the richest bid is not always the right one: the second club may simply be the
 * better fit for the squad.
 *
 * <p>Both screens that show a manager his bids (the Transfer Centre and the player page) render them
 * through here, so the two cannot drift apart.
 */
/**
 * @param transfer           anything carrying `playerId` and `offers`
 * @param options.escapeHtml the project's single escaping helper
 * @param options.actionName the `data-transfer-*` attribute the host screen dispatches on. The two
 *                           screens disagree — the Transfer Centre uses `transfer-action`, the player
 *                           page uses `transfer-panel-action` — so the caller states which, rather
 *                           than the buttons quietly firing on one of them.
 */
export function renderOfferButtons(transfer, { escapeHtml, actionName = 'transfer-action' }) {
    const offers = Array.isArray(transfer?.offers) ? transfer.offers.filter(Boolean) : [];
    if (offers.length === 0) return '';

    return offers.map(offer => `
        <button type="button" class="fm-action-btn"
                data-${actionName}="accept-named"
                data-player-id="${transfer.playerId}"
                data-offer-id="${offer.id}"
                title="${escapeHtml(`Accept ${offer.buyerTeamName || 'this club'}'s bid of EUR ${Math.round(offer.fee || 0)}`)}">
            Accept ${escapeHtml(offer.buyerTeamName || 'bid')} · ${formatFee(offer.fee)}
        </button>`).join('');
}

/**
 * What the seller actually receives, so the row is not just "a bid".
 *
 * <p>{@code netToSeller} is the fee less the agent's cut. Showing only the headline fee would
 * overstate every bid on the screen by 2–5%.
 */
export function renderOfferTerms(offer, { escapeHtml }) {
    const wage = offer?.wage ? `EUR ${Math.round(offer.wage).toLocaleString('en-GB')}/wk` : 'wage not offered';
    const years = offer?.contractYears ? `${offer.contractYears}y` : null;
    const terms = [wage, years].filter(Boolean).join(' · ');
    return `
        <div>
            <strong>${escapeHtml(offer.buyerTeamName || 'A club')}</strong>
            <div class="fm-subtle">${escapeHtml(terms)}</div>
            ${offer.agentFee
                ? `<div class="fm-subtle">You receive ${escapeHtml(formatFee(offer.netToSeller))} after the agent's cut</div>`
                : ''}
        </div>`;
}

function formatFee(fee) {
    return `EUR ${Math.round(fee || 0).toLocaleString('en-GB')}`;
}