package org.example.footballmanager.newLogic.dto;

import lombok.Data;

@Data
public class RegisterRequestDTO {
    private String username;
    private String email;
    private String password;

    /**
     * The nation chosen on the form, as a three-letter code (owner, 2026-09-28).
     *
     * <p>Required. The club is reserved from this country's leagues rather than from "the first free AI
     * club", which was always a club in Serbia no matter what the applicant wanted.
     */
    private String countryCode;
}
