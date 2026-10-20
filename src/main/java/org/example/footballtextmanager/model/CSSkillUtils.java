package org.example.footballtextmanager.model;

public class CSSkillUtils {
    public static String skillLabel(int value) {
        return CSSkillLevel.getLabel(value);
    }

    public static double calculateBMI(double height, double weight) {
        return weight / (height * height);
    }

    public static boolean isIdealBMI(double height, double weight) {
        double bmi = calculateBMI(height, weight);
        return bmi >= 22.0 && bmi <= 25.0;
    }
}