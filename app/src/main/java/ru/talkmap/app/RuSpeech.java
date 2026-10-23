package ru.talkmap.app;

/**
 * Подготовка озвучиваемых строк: склонение прилагательных "ресторан/ресторана",
 * "остановка/остановки" и т.п., чтобы TalkBack произносил фразу естественно.
 */
public final class RuSpeech {

    private RuSpeech() {
    }

    /**
     * Русские прилагательные: мужской род "Красная площадь, площадь" → "Красная площадь".
     * После "это" именительный падеж: "Это Красная площадь".
     */
    public static String constructPhrase(String spokenName, String spokenType) {
        if (spokenName == null || spokenName.isEmpty()) {
            return spokenType == null ? "" : "Это " + spokenType;
        }
        String typeGen = genitiveType(spokenType);
        if (typeGen == null || typeGen.isEmpty()) {
            return spokenName;
        }
        return spokenName + ", " + typeGen;
    }

    /**
     * Фраза направления улицы: "Улица Станиславского с севера на юг".
     * Для улиц, идущих по диагонали, выбирается доминирующее направление.
     */
    public static String streetDirectionPhrase(String spokenName, double startLat, double startLon,
                                               double endLat, double endLon) {
        double dLon = endLon - startLon;
        double dLat = endLat - startLat;
        double metersN = Math.abs(dLat) * 110574.0;
        double metersE = Math.abs(dLon) * 111320.0 * Math.cos(Math.toRadians((startLat + endLat) / 2));
        String from, to;
        if (metersN >= metersE) {
            from = dLat < 0 ? "с севера на юг" : "с юга на север";
        } else {
            from = dLon < 0 ? "с востока на запад" : "с запада на восток";
        }
        // Совпадает с ЦОС-стандартом: улица + направление.
        return spokenName + " " + from;
    }

    public static String announceCount(int n) {
        if (n >= 11 && n <= 14) {
            return " объектов";
        }
        int last = n % 10;
        if (last == 1) {
            return " объект";
        }
        if (last >= 2 && last <= 4) {
            return " объекта";
        }
        return " объектов";
    }

    /**
     * Именительный → родительный для типа, например "улица" → "улицы",
     * чтобы звучало "Улица Станиславского, улицы".
     */
    private static String genitiveType(String type) {
        if (type == null || type.isEmpty()) {
            return "";
        }
        String t = type.trim().toLowerCase();
        if (t.endsWith("ца")) {
            return type.substring(0, type.length() - 1) + "ы";
        }
        if (t.endsWith("я")) {
            return type.substring(0, type.length() - 1) + "и";
        }
        if (t.endsWith("ь")) {
            return type.substring(0, type.length() - 1) + "и";
        }
        if (t.endsWith("й")) {
            return type.substring(0, type.length() - 1);
        }
        if (t.endsWith("а")) {
            return type.substring(0, type.length() - 1) + "ы";
        }
        if (t.endsWith("ок") || t.endsWith("ец") || t.endsWith("к")) {
            return type + "а";
        }
        return type + "а";
    }
}