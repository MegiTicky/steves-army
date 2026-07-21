package com.stevesarmy.util;

import net.minecraft.util.RandomSource;

import java.util.List;

public class SoldierNameGenerator {
    private static final List<String> FIRST_NAMES = List.of(
        "John", "James", "Robert", "Michael", "William", "David", "Richard", "Joseph", "Thomas", "Charles",
        "Christopher", "Daniel", "Matthew", "Anthony", "Mark", "Donald", "Steven", "Paul", "Andrew", "Joshua",
        "Kenneth", "Kevin", "Brian", "George", "Edward", "Ronald", "Timothy", "Jason", "Jeffrey", "Ryan",
        "Jacob", "Gary", "Nicholas", "Eric", "Jonathan", "Stephen", "Larry", "Justin", "Scott", "Brandon",
        "Benjamin", "Samuel", "Raymond", "Gregory", "Frank", "Alexander", "Patrick", "Jack", "Dennis", "Jerry"
    );

    private static final List<String> LAST_NAMES = List.of(
        "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis", "Rodriguez", "Martinez",
        "Hernandez", "Lopez", "Gonzalez", "Wilson", "Anderson", "Thomas", "Taylor", "Moore", "Jackson", "Martin",
        "Lee", "Perez", "Thompson", "White", "Harris", "Sanchez", "Clark", "Ramirez", "Lewis", "Robinson"
    );

    public static String generate(RandomSource random) {
        return FIRST_NAMES.get(random.nextInt(FIRST_NAMES.size()))
            + " " + LAST_NAMES.get(random.nextInt(LAST_NAMES.size()));
    }
}