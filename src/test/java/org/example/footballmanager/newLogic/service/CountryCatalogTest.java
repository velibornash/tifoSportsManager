package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.newLogic.model.CountryCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The country catalog (owner, 2026-09-28).
 *
 * <p>This exists because the 48 nations were written down twice and immediately drifted. The
 * registration form needs a list; the backend needs to validate a submitted code; and when those are
 * separate, they eventually disagree — which is exactly what had happened, with nine countries under
 * Serbian names and three non-standard codes.
 */
class CountryCatalogTest {

    @Nested
    @DisplayName("the list itself")
    class TheList {

        @Test
        @DisplayName("there are exactly 48, because the qualifying field is 48")
        void exactlyFortyEight() {
            assertEquals(48, CountryCatalog.all().size(),
                    "the World Cup qualifying field is top 48, so the world has to be 48 — the owner "
                            + "chose 47 named nations plus 'Other'");
        }

        @Test
        @DisplayName("codes are unique - a duplicate would silently merge two nations")
        void codesAreUnique() {
            List<String> codes = CountryCatalog.all().stream().map(CountryCatalog::code).toList();
            assertEquals(codes.size(), new HashSet<>(codes).size(), "duplicate codes: " + codes);
        }

        @Test
        @DisplayName("display names are unique and none is blank")
        void namesAreUniqueAndPresent() {
            List<String> names = CountryCatalog.all().stream()
                    .map(CountryCatalog::displayName).toList();
            assertEquals(names.size(), new HashSet<>(names).size(), "duplicate names: " + names);
            assertTrue(names.stream().noneMatch(n -> n == null || n.isBlank()));
        }

        @Test
        @DisplayName("every code is three letters")
        void codesAreThreeLetters() {
            for (CountryCatalog country : CountryCatalog.all()) {
                assertTrue(country.code().matches("[A-Z]{3}"),
                        country.displayName() + " has code '" + country.code() + "'");
            }
        }

        @Test
        @DisplayName("'Other Nations' is present - it holds the rest of the world, not a filler")
        void otherNationsExists() {
            assertTrue(CountryCatalog.isKnown("OTH"));
            assertNotNull(CountryCatalog.byCode("OTH").orElse(null));
        }
    }

    @Nested
    @DisplayName("code lookups")
    class Lookups {

        @Test
        @DisplayName("a code resolves regardless of case and surrounding space")
        void lookupIsForgiving() {
            // A select submits exactly what the option says, but a hand-typed API call or an
            // imported address book will not, and rejecting "srb" for being lowercase is a pointless
            // refusal.
            assertEquals(CountryCatalog.SERBIA, CountryCatalog.byCode("SRB").orElseThrow());
            assertEquals(CountryCatalog.SERBIA, CountryCatalog.byCode("srb").orElseThrow());
            assertEquals(CountryCatalog.SERBIA, CountryCatalog.byCode("  sRb  ").orElseThrow());
        }

        @Test
        @DisplayName("blank and unknown codes resolve to nothing rather than throwing")
        void unknownResolvesToEmpty() {
            assertTrue(CountryCatalog.byCode(null).isEmpty());
            assertTrue(CountryCatalog.byCode("").isEmpty());
            assertTrue(CountryCatalog.byCode("   ").isEmpty());
            assertTrue(CountryCatalog.byCode("XXX").isEmpty());
            assertFalse(CountryCatalog.isKnown("XXX"));
        }
    }

    @Nested
    @DisplayName("the codes that were wrong, pinned")
    class PinnedCorrections {

        @Test
        @DisplayName("Greece is GRE and Georgia is GEO - neither is GRU")
        void greeceAndGeorgiaAreNotConfused() {
            // The flag badge derives an emoji from the code, so a wrong code here shows the wrong
            // flag. This is the one pair in the list close enough to be confused.
            assertEquals("GRE", CountryCatalog.GREECE.code());
            assertEquals("GEO", CountryCatalog.GEORGIA.code());
            assertFalse(CountryCatalog.all().stream()
                    .anyMatch(c -> "GRU".equals(c.code())), "GRU is Greece's old code, not Georgia's");
        }

        @Test
        @DisplayName("England is ENG, not GBR - they are different football nations")
        void englandIsNotGreatBritain() {
            // Scotland and Northern Ireland are separately on the list, so a union-wide code would
            // conflate three distinct nations with their own rankings and their own national teams.
            assertEquals("ENG", CountryCatalog.ENGLAND.code());
            assertTrue(CountryCatalog.isKnown("SCO"));
            assertTrue(CountryCatalog.isKnown("NIR"));
            assertFalse(CountryCatalog.isKnown("GBR"));
        }

        @Test
        @DisplayName("the three non-standard codes in the database are corrected")
        void standardCodesReplaceTheOldOnes() {
            // These existed in the seeded data: Croatia HRV, Germany DEU, England GBR.
            assertEquals("CRO", CountryCatalog.CROATIA.code());
            assertEquals("GER", CountryCatalog.GERMANY.code());
            assertFalse(CountryCatalog.isKnown("HRV"));
            assertFalse(CountryCatalog.isKnown("DEU"));
        }
    }

    @Nested
    @DisplayName("the player's country list")
    class TheOwnersList {

        /** The list exactly as the owner wrote it, so a silent edit is caught. */
        private static final List<String> OWNER_ORDER = List.of(
                "SRB", "CRO", "BIH", "MNE", "MKD", "SVN", "HUN", "ROU", "BUL", "GRE", "ITA", "AUT",
                "FRA", "ESP", "POR", "SUI", "GER", "POL", "CZE", "SVK", "RUS", "NED", "BEL", "TUR",
                "ENG", "SCO", "NIR", "IRL", "DEN", "NOR", "SWE", "FIN", "USA", "CAN", "AUS", "BRA",
                "ARG", "URU", "CHN", "JPN", "MAR", "EGY", "IND", "MRI", "GEO", "KSA", "QAT", "OTH");

        @Test
        @DisplayName("the codes and their order match the owner's list exactly")
        void matchesTheOwnerList() {
            assertEquals(OWNER_ORDER, CountryCatalog.all().stream()
                            .map(CountryCatalog::code).toList(),
                    "the catalog has drifted from the owner's list — if this is intentional, change "
                            + "the list here too, not just in the test");
        }

        @Test
        @DisplayName("Serbia is first, because everything else was specified relative to it")
        void serbiaComesFirst() {
            assertEquals(CountryCatalog.SERBIA, CountryCatalog.all().get(0));
            // Set.copyOf, not Set.of(list): Set.of on a single collection argument builds a set
            // containing that one collection, so the comparison would be against a set of one.
            assertEquals(Set.copyOf(OWNER_ORDER), Set.copyOf(CountryCatalog.all().stream()
                    .map(CountryCatalog::code).toList()));
        }
    }
}
