package org.example.footballmanager.newLogic.util.players;

import java.util.List;
import java.util.Random;

public class NameGenerator {
    private static final List<String> FIRST_NAMES = List.of("Bata", "Batica","Bogdan","Bojan","Brka","Ćobra", "Danilo", "Deda","Dragan","Dragomir","Draža","Dušan","Džungla","Flud","Gelu","Goci","Gojko", "Goran","Goy","Guliver","Hrabri","Igor", "Ivan", "Lazar", "Marko", "Nikola", "Pavle", "Petar", "Remorker", "Velibor", "Zoran", "Đurađ");
    private static final List<String> LAST_NAMES = List.of("Bajić","Bandulaja", "Bojović", "Đetić", "Simić", "Mandžo", "Radovanović", "Kostić", "Mladenović", "Živadinović", "Rakić", "Jokić", "Petri", "Negovanović", "Ožegović", "Torbica", "Vacić");

    private static final Random random = new Random();

    public static String randomFirstName() {
        return FIRST_NAMES.get(random.nextInt(FIRST_NAMES.size()));
    }

    public static String randomLastName() {
        return LAST_NAMES.get(random.nextInt(LAST_NAMES.size()));
    }

    public static String fullName() {
        return randomFirstName() + " " + randomLastName();
    }
}