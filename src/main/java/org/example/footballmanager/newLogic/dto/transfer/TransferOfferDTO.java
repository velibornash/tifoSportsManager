package org.example.footballmanager.newLogic.dto.transfer;

import lombok.Data;

/**
 * One bid on a listed player, as the seller sees it.
 *
 * <p>This exists because the transfer centre used to show a seller a list of prose strings —
 * {@code "Rival FC offered EUR 900000"} — with no id in them. A seller could therefore read how many
 * clubs were interested but could not act on a <em>particular</em> one: there was nothing to put in
 * a button. The identity of a bid is the whole point of an auction, so it crosses the wire.
 */
@Data
public class TransferOfferDTO {

    private Long id;
    private Long buyerTeamId;
    private String buyerTeamName;
    /** The fee the buying club has committed to. */
    private Double fee;
    /** The weekly wage the buying club has offered the player. */
    private Double wage;
    private Integer contractYears;
    /** The agent's percentage of the fee, computed at {@code NegotiationService.agentFeeFor}. */
    private Double agentFee;
    /** What the seller actually receives: the fee less the agent's cut. */
    private Double netToSeller;
    /** {@code OfferStatus} as a string, so the UI can grey out a bid that is no longer live. */
    private String status;
    private Integer round;
}