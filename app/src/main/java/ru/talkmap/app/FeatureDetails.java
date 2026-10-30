package ru.talkmap.app;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.HashSet;

/**
 * Человекочитаемые подробности объекта карты на основе тегов OpenStreetMap.
 * Для контекстного меню «Показать подробности»: имя, тип, часы работы,
 * контактная информация, адрес и остальные теги, переведённые в понятный вид
 * (например, «пекарня», а не «shop=bakery»).
 */
public final class FeatureDetails {

    private FeatureDetails() {
    }

    public static String format(MapFeature f) {
        if (f == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String name = (f.spokenName != null && !f.spokenName.isEmpty()) ? f.spokenName : f.name;
        if (name != null && !name.isEmpty()) {
            sb.append(name).append("\n");
        }
        String type = CategoryNames.humanize(f.type);
        if (type != null && !type.isEmpty()) {
            sb.append(type).append("\n");
        }
        Map<String, String> tags = f.tags;
        if (tags == null || tags.isEmpty()) {
            if (f.type != null && !"junction".equals(f.type)) {
                appendCoords(sb, f);
            }
            return sb.toString().trim();
        }

        String hours = firstOf(tags, "opening_hours", "opening_hours:url");
        if (hours != null) {
            sb.append("Часы работы: ").append(humanizeHours(hours)).append("\n");
        }
        String phone = firstOf(tags, "phone", "contact:phone", "tel");
        if (phone != null) {
            sb.append("Телефон: ").append(phone).append("\n");
        }
        String site = firstOf(tags, "website", "contact:website", "url");
        if (site != null) {
            sb.append("Сайт: ").append(site).append("\n");
        }
        String email = firstOf(tags, "email", "contact:email");
        if (email != null) {
            sb.append("Почта: ").append(email).append("\n");
        }
        String operator = firstOf(tags, "operator", "brand");
        if (operator != null) {
            sb.append("Оператор: ").append(operator).append("\n");
        }
        String address = buildAddress(tags);
        if (address != null) {
            sb.append("Адрес: ").append(address).append("\n");
        }
        appendHumanTags(sb, tags, f.type);
        appendCoords(sb, f);
        return sb.toString().trim();
    }

    private static void appendCoords(StringBuilder sb, MapFeature f) {
        sb.append(String.format(java.util.Locale.US, "Координаты: %.6f, %.6f", f.lat, f.lon))
                .append("\n");
    }

    private static String firstOf(Map<String, String> tags, String... keys) {
        for (String k : keys) {
            String v = tags.get(k);
            if (v != null && !v.trim().isEmpty()) {
                return v.trim();
            }
        }
        return null;
    }

    private static String buildAddress(Map<String, String> tags) {
        String street = tags.get("addr:street");
        String house = tags.get("addr:housenumber");
        String city = tags.get("addr:city");
        StringBuilder sb = new StringBuilder();
        if (street != null && !street.isEmpty()) {
            sb.append(street);
        }
        if (house != null && !house.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(", дом ");
            } else {
                sb.append("Дом ");
            }
            sb.append(house);
        }
        if (city != null && !city.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(city);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Остальные теги объекта — в человекочитаемом виде. Ключи, уже показанные
     * выше (имя, часы, контакты, адрес, тип), пропускаем, чтобы не дублировать.
     */
    private static void appendHumanTags(StringBuilder sb, Map<String, String> tags, String typeKey) {
        String typeBase = typeKey;
        int eq = typeBase == null ? -1 : typeBase.indexOf('=');
        String typeBaseKey = eq >= 0 ? typeBase.substring(0, eq) : typeBase;

        Set<String> skip = new HashSet<>(SKIP_TAGS);

        // Сортируем для стабильного, читаемого порядка.
        Set<String> keys = new TreeSet<>(tags.keySet());
        for (String k : keys) {
            if (skip.contains(k) || k.startsWith("addr:") || k.startsWith("contact:")) {
                continue;
            }
            String v = tags.get(k);
            if (v == null || v.trim().isEmpty()) {
                continue;
            }
            if (k.equals(typeBaseKey)) {
                // Тип уже озвучен заголовком — не повторяем его тег отдельной строкой.
                if (CategoryNames.humanize(k + "=" + v.trim()).equals(typeKeyHuman(typeKey))) {
                    continue;
                }
            }
            String human = CategoryNames.humanize(k + "=" + v.trim());
            sb.append(humanReadableKey(k)).append(": ").append(enrichValue(k, v.trim())).append("\n");
        }
    }

    private static String typeKeyHuman(String typeKey) {
        return typeKey == null ? "" : CategoryNames.humanize(typeKey);
    }

    private static String enrichValue(String key, String value) {
        if ("website".equals(key) || "url".equals(key)
                || "phone".equals(key) || "email".equals(key)) {
            return value;
        }
        String lower = value.toLowerCase();
        String yes = YES.get(key);
        if (yes != null) {
            if ("yes".equals(lower) || "true".equals(lower) || "1".equals(lower)) {
                return yes;
            }
            if ("no".equals(lower) || "false".equals(lower) || "0".equals(lower)) {
                return NO;
            }
            if ("limited".equals(lower)) {
                return "частично";
            }
        }
        return value;
    }

    private static final String NO = "нет";
    private static final Map<String, String> YES = new java.util.LinkedHashMap<>();

    static {
        YES.put("wheelchair", "доступно");
        YES.put("smoking", "курение разрешено");
        YES.put("fee", "платно");
        YES.put("internet_access", "бесплатный WiFi");
        YES.put("outdoor_seating", "есть");
        YES.put("delivery", "есть");
        YES.put("takeaway", "есть");
        YES.put("payment:cash", "принимаются");
        YES.put("payment:cards", "принимаются");
        YES.put("drive_through", "есть");
        YES.put("opening_hours", "круглосуточно");
    }

    /** Ключ тега в читаемом виде: «cuisine» → «кухня», «fee» → «плата» и т.п. */
    private static String humanReadableKey(String key) {
        String known = TAG_KEY_HUMAN.get(key);
        if (known != null) {
            return known;
        }
        if (key == null || key.isEmpty()) {
            return key;
        }
        return CategoryNames.humanize(key);
    }

    /**
     * Bазовый перевод часов работы. OSM-формат сложный, поэтому переводим
     * только частые случаи: «24/7» → «круглосуточно», «off» → «закрыто»,
     * дни недели → русские сокращения, а остальное оставляем как есть.
     */
    static String humanizeHours(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        if (s.equalsIgnoreCase("24/7")) {
            return "круглосуточно";
        }
        String t = s;
        // Кавычки/пробелы нормализуем.
        t = t.replace("\"", "").trim();
        // День недели: Mo → пн, Tu → вт, We → ср, Th → чт, Fr → пт, Sa → сб, Su → вс.
        for (Map.Entry<String, String> e : DAYS.entrySet()) {
            // Заменяем целое слово-аббревиатуру (с границами, чтобы не задеть «Sun» в слове).
            t = t.replaceAll("(?i)(?<![A-Za-z])" + e.getKey() + "(?![A-Za-z])", e.getValue());
        }
        t = t.replaceAll("(?i)\\boff\\b", "закрыто");
        return t.trim();
    }

    private static final Map<String, String> DAYS = new java.util.LinkedHashMap<>();
    private static final Set<String> SKIP_TAGS = new HashSet<>();
    private static final Map<String, String> TAG_KEY_HUMAN = new java.util.LinkedHashMap<>();

    static {
        DAYS.put("Mo", "пн");
        DAYS.put("Tu", "вт");
        DAYS.put("We", "ср");
        DAYS.put("Th", "чт");
        DAYS.put("Fr", "пт");
        DAYS.put("Sa", "сб");
        DAYS.put("Su", "вс");

        java.util.Collections.addAll(SKIP_TAGS,
                "name", "name:ru", "name:en", "name:prefix", "name:suffix",
                "opening_hours", "opening_hours:url",
                "phone", "contact:phone", "tel", "mobile",
                "website", "contact:website", "url",
                "email", "contact:email",
                "operator", "brand", "brand:wikidata",
                "addr:street", "addr:housenumber", "addr:city", "addr:postcode",
                "addr:full", "addr:housename", "addr:place",
                "source", "created_by", "source:geo", "source:name",
                "ref", "ref:ru", "old_ref", "note", "note:ru", "description",
                "wikidata", "wikipedia", "wikimedia_commons", "image", "image:panorama",
                "attribution", "ele", "layer", "z_order",
                "geometry", "id", "type", "version", "timestamp", "changeset", "user", "uid");

        TAG_KEY_HUMAN.put("amenity", "тип");
        TAG_KEY_HUMAN.put("shop", "тип");
        TAG_KEY_HUMAN.put("craft", "тип");
        TAG_KEY_HUMAN.put("tourism", "тип");
        TAG_KEY_HUMAN.put("leisure", "тип");
        TAG_KEY_HUMAN.put("office", "тип");
        TAG_KEY_HUMAN.put("historic", "тип");
        TAG_KEY_HUMAN.put("building", "тип");
        TAG_KEY_HUMAN.put("cuisine", "кухня");
        TAG_KEY_HUMAN.put("diet:vegetarian", "вегетарианское меню");
        TAG_KEY_HUMAN.put("operator:type", "тип оператора");
        TAG_KEY_HUMAN.put("payment:cash", "оплата наличными");
        TAG_KEY_HUMAN.put("payment:cards", "оплата картами");
        TAG_KEY_HUMAN.put("wheelchair", "доступность");
        TAG_KEY_HUMAN.put("smoking", "курение");
        TAG_KEY_HUMAN.put("fee", "плата");
        TAG_KEY_HUMAN.put("capacity", "вместимость");
        TAG_KEY_HUMAN.put("level", "этаж");
        TAG_KEY_HUMAN.put("floor", "этаж");
        TAG_KEY_HUMAN.put("opening_hours", "часы работы");
        TAG_KEY_HUMAN.put("phone", "телефон");
        TAG_KEY_HUMAN.put("website", "сайт");
        TAG_KEY_HUMAN.put("internet_access", "интернет");
        TAG_KEY_HUMAN.put("outdoor_seating", "есть летняя терраса");
        TAG_KEY_HUMAN.put("delivery", "доставка");
        TAG_KEY_HUMAN.put("takeaway", "еда с собой");
    }
}