package ru.talkmap.app;

/**
 * Подготовка озвучиваемых строк: «Имя, тип» в именительном падеже
 * («Пятёрочка, магазин», «улица Станиславского, трамвайная остановка»).
 * Словарь типов уже хранит правильную форму, поэтому досклонять и
 * добавлять окончания нельзя — «магазин у дома» превращалось в
 * «магазин у домы», «стройматериалы» в «стройматериалыа».
 */
public final class RuSpeech {

    private RuSpeech() {
    }

    /**
     * Фраза объекта: «Название, тип». Без названия — «Это тип».
     */
    public static String constructPhrase(String spokenName, String spokenType) {
        if (spokenName == null || spokenName.isEmpty()) {
            return spokenType == null ? "" : "Это " + spokenType;
        }
        if (spokenType == null || spokenType.isEmpty()) {
            return spokenName;
        }
        return spokenName + ", " + spokenType;
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
}