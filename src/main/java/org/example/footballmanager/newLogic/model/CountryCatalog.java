package org.example.footballmanager.newLogic.model;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The 48 nations the game will run (owner list, 2026-09-28).
 *
 * <p>Exactly 48: **47 named nations plus "Other Nations"**, which is a real entry rather than a
 * filler — it holds the rest of the world, is ranked and fielded like any other nation, and simply
 * has no domestic league generating players.
 *
 * <h2>Why this is an enum and not a seeded table</h2>
 *
 * <p>The registration form has to offer these before anyone has logged in, and the backend has to
 * validate a submitted code. If the list lived in the database the form would need a public endpoint
 * that cannot fail, and if the two were written separately — a Java array and a hand-typed
 * {@code <select>} — they would drift. They already had: the database contains nine countries under
 * <b>Serbian</b> names with three non-standard codes.
 *
 * <p>So the names and codes are declared once, here, and the database is populated <i>from</i> this
 * enum by {@code DatabaseInitializer}. The country list is data that every part of the application
 * must agree on, which makes it one of the few things genuinely worth having a single definition of.
 *
 * <p>Country <b>records</b> still live in the database — they carry reputation, youth rating and the
 * national-team references, and those change. Only the identity of a country is fixed here.
 *
 * <h2>Codes</h2>
 *
 * <p>Three-letter, and deliberately not all FIFA. **Greece is {@code GRE} and Georgia is
 * {@code GEO}** — neither may be {@code GRU}, and the flag badge derives an emoji from the code, so a
 * wrong one shows the wrong flag. **England is {@code ENG}, not {@code GBR}**, because England,
 * Scotland and Northern Ireland are separate football nations and all three are on this list.
 */
public enum CountryCatalog {

    SERBIA("SRB", "Serbia"),
    CROATIA("CRO", "Croatia"),
    BOSNIA_AND_HERZEGOVINA("BIH", "Bosnia and Herzegovina"),
    MONTENEGRO("MNE", "Montenegro"),
    NORTH_MACEDONIA("MKD", "North Macedonia"),
    SLOVENIA("SVN", "Slovenia"),
    HUNGARY("HUN", "Hungary"),
    ROMANIA("ROU", "Romania"),
    BULGARIA("BUL", "Bulgaria"),
    GREECE("GRE", "Greece"),
    ITALY("ITA", "Italy"),
    AUSTRIA("AUT", "Austria"),
    FRANCE("FRA", "France"),
    SPAIN("ESP", "Spain"),
    PORTUGAL("POR", "Portugal"),
    SWITZERLAND("SUI", "Switzerland"),
    GERMANY("GER", "Germany"),
    POLAND("POL", "Poland"),
    CZECHIA("CZE", "Czechia"),
    SLOVAKIA("SVK", "Slovakia"),
    RUSSIA("RUS", "Russia"),
    NETHERLANDS("NED", "Netherlands"),
    BELGIUM("BEL", "Belgium"),
    TURKEY("TUR", "Turkey"),
    ENGLAND("ENG", "England"),
    SCOTLAND("SCO", "Scotland"),
    NORTHERN_IRELAND("NIR", "Northern Ireland"),
    IRELAND("IRL", "Ireland"),
    DENMARK("DEN", "Denmark"),
    NORWAY("NOR", "Norway"),
    SWEDEN("SWE", "Sweden"),
    FINLAND("FIN", "Finland"),
    UNITED_STATES("USA", "United States"),
    CANADA("CAN", "Canada"),
    AUSTRALIA("AUS", "Australia"),
    BRAZIL("BRA", "Brazil"),
    ARGENTINA("ARG", "Argentina"),
    URUGUAY("URU", "Uruguay"),
    CHINA("CHN", "China"),
    JAPAN("JPN", "Japan"),
    MOROCCO("MAR", "Morocco"),
    EGYPT("EGY", "Egypt"),
    INDIA("IND", "India"),
    MAURITIUS("MRI", "Mauritius"),
    GEORGIA("GEO", "Georgia"),
    SAUDI_ARABIA("KSA", "Saudi Arabia"),
    QATAR("QAT", "Qatar"),

    /**
     * Every other nation, as one entry.
     *
     * <p>Exists so the qualifying field is exactly 48. A team here is ranked and fielded normally;
     * it simply has no domestic league feeding it players, which means its squad is drawn from
     * wherever its players can be found.
     */
    OTHER("OTH", "Other Nations");

    private final String code;
    private final String displayName;

    CountryCatalog(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String code() {
        return code;
    }

    /** The English name shown in the country picker. */
    public String displayName() {
        return displayName;
    }

    /**
     * Every country, <b>in the owner's listed order</b>.
     *
     * <p>Which needs saying because of how it was written first. {@code List.of(values())} is the
     * obvious one-liner and it is <b>wrong</b>: {@code List.of} on an array treats the array as a
     * collection of elements rather than as a sequence, so the result is unordered and the 48 came
     * back shuffled. Nothing crashed, and a test asserting only the count would have passed.
     * A stream over the values keeps encounter order, which is the declaration order.
     */
    public static List<CountryCatalog> all() {
        return Arrays.stream(values()).toList();
    }

    /** Looks a country up by its three-letter code, case-insensitively. */
    public static Optional<CountryCatalog> byCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String needle = code.trim().toUpperCase(java.util.Locale.ROOT);
        return Arrays.stream(values())
                .filter(c -> c.code.equals(needle))
                .findFirst();
    }

    /**
     * Whether a submitted code names a real country.
     *
     * <p>Separate from {@link #byCode} so validation reads as validation at the call site rather than
     * as a null check on an Optional.
     */
    public static boolean isKnown(String code) {
        return byCode(code).isPresent();
    }
}
