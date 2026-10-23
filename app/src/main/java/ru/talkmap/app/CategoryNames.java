package ru.talkmap.app;

import java.util.Locale;

/**
 * Словарь категорий: по ключу {@code key-value} возвращает озвучиваемый
 * читаемый тип ("гостиница", "аптека", "автобусная остановка", ...).
 * Неизвестные ключи остаются кириллицей, если они уже русские, иначе
 * транслитерируются латиницей для более удобной озвучки TalkBack.
 */
public final class CategoryNames {

    private CategoryNames() {
    }

    public static String humanize(String rawKey) {
        if (rawKey == null || rawKey.isEmpty()) {
            return "";
        }
        String key = rawKey.toLowerCase(Locale.ROOT);
        String known = KNOWN.get(key);
        if (known != null) {
            return known;
        }
        // У ключей вида amenity=parking интересна в основном вторая часть.
        int eq = key.indexOf('=');
        String main = eq >= 0 ? key.substring(eq + 1) : key;
        if (isCyrillic(main)) {
            return main;
        }
        return translit(main);
    }

    private static boolean isCyrillic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u0400' && c <= '\u04FF') {
                return true;
            }
        }
        return false;
    }

    private static String translit(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String r = TABLE.get(Character.toLowerCase(c));
            if (r != null) {
                sb.append(r);
            } else if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            } else {
                sb.append(' ');
            }
        }
        return sb.toString().trim().replaceAll("\\s+", " ");
    }

    private static final java.util.Map<String, String> KNOWN = new java.util.HashMap<>();

    static {
        put("amenity=restaurant", "ресторан");
        put("amenity=cafe", "кафе");
        put("amenity=bar", "бар");
        put("amenity=pub", "паб");
        put("amenity=fast_food", "фастфуд");
        put("amenity=school", "школа");
        put("amenity=university", "университет");
        put("amenity=kindergarten", "детский сад");
        put("amenity=library", "библиотека");
        put("amenity=hospital", "больница");
        put("amenity=clinic", "поликлиника");
        put("amenity=doctors", "медицинский центр");
        put("amenity=dentist", "стоматология");
        put("amenity=pharmacy", "аптека");
        put("amenity=bh", "поликлиника");
        put("amenity=bank", "банк");
        put("amenity=atm", "банкомат");
        put("amenity=post_box", "почтовый ящик");
        put("amenity=post_office", "почта");
        put("amenity=police", "полиция");
        put("amenity=fire_station", "пожарная часть");
        put("amenity=townhall", "администрация");
        put("amenity=courthouse", "суд");
        put("amenity=prison", "тюрьма");
        put("amenity=place_of_worship", "храм");
        put("amenity=theatre", "театр");
        put("amenity=cinema", "кинотеатр");
        put("amenity=arts_centre", "центр искусств");
        put("amenity=marketplace", "рынок");
        put("amenity=bus_station", "автовокзал");
        put("amenity=ferry_terminal", "речной вокзал");
        put("amenity=parking", "парковка");
        put("amenity=bicycle_parking", "велопарковка");
        put("amenity=bench", "скамейка");
        put("amenity=fountain", "фонтан");
        put("amenity=drinking_water", "питьевой фонтанчик");
        put("amenity=toilets", "туалет");
        put("amenity=waste_basket", "урна");
        put("amenity=recycling", "пункт переработки");
        put("amenity=charging_station", "зарядка для машин");
        put("amenity=fuel", "заправка");
        put("amenity=car_wash", "автомойка");
        put("amenity=social_facility", "соцучреждение");
        put("amenity=community_centre", "общественный центр");
        put("amenity=grave_yard", "кладбище");
        put("amenity=nursery", "ясли");
        put("amenity=college", "колледж");
        put("amenity=music_school", "музыкальная школа");
        put("amenity=nightclub", "ночной клуб");
        put("amenity=taxi", "такси");
        put("amenity=vending_machine", "торговый автомат");

        put("shop=supermarket", "супермаркет");
        put("shop=convenience", "магазин у дома");
        put("shop=department_store", "универмаг");
        put("shop=mall", "торговый центр");
        put("shop=bakery", "пекарня");
        put("shop=butcher", "мясной магазин");
        put("shop=greengrocer", "овощной магазин");
        put("shop=clothes", "магазин одежды");
        put("shop=shoes", "обувной магазин");
        put("shop=electronics", "магазин электроники");
        put("shop=mobile_phone", "салон связи");
        put("shop=books", "книжный магазин");
        put("shop=florist", "цветочный магазин");
        put("shop=jewelry", "ювелирный магазин");
        put("shop=hardware", "хозтовары");
        put("shop=doityourself", "стройматериалы");
        put("shop=shopping_centre", "торговый центр");
        put("shop=car", "автосалон");
        put("shop=car_repair", "автосервис");
        put("shop=stationery", "канцтовары");
        put("shop=hairdresser", "парикмахерская");
        put("shop=beauty", "салон красоты");
        put("shop=chemist", "бытовая химия");
        put("shop=pet", "зоомагазин");
        put("shop=optician", "оптика");
        put("shop=toys", "магазин игрушек");
        put("shop=sports", "спортивный магазин");
        put("shop=travel_agency", "турфирма");
        put("shop=alcohol", "алкогольный магазин");

        put("craft=clockmaker", "мастерская по ремонту часов");
        put("craft=watchmaker", "мастерская по ремонту часов");
        put("craft=shoemaker", "обувная мастерская");
        put("craft=hairdresser", "парикмахерская");
        put("craft=glaziery", "стекольная мастерская");
        put("craft=electronics_repair", "мастерская по ремонту электроники");
        put("craft=joiner", "столярная мастерская");
        put("craft=baker", "пекарня");
        put("craft=roofer", "кровельная мастерская");
        put("craft=locksmith", "слесарная мастерская");
        put("craft=key_cutter", "изготовление ключей");
        put("craft=photographer", "фотоателье");
        put("craft=tailor", "швейная мастерская");
        put("craft=confectionery", "кондитерская");
        put("craft=tinsmith", "жестяницкая мастерская");
        put("craft=sawmill", "пилорама");
        put("craft=carpenter", "столярная мастерская");
        put("craft=blacksmith", "кузница");

        put("tourism=hotel", "гостиница");
        put("tourism=hostel", "хостел");
        put("tourism=apartment", "апартаменты");
        put("tourism=attraction", "достопримечательность");
        put("tourism=museum", "музей");
        put("tourism=gallery", "галерея");
        put("tourism=artwork", "памятник");
        put("tourism=picnic_site", "зона пикника");
        put("tourism=viewpoint", "смотровая точка");
        put("tourism=information", "информационный стенд");
        put("tourism=zoo", "зоопарк");
        put("tourism=theme_park", "парк развлечений");

        put("highway=bus_stop", "автобусная остановка");
        put("highway=bus_station", "автовокзал");
        put("highway=platform", "остановка");
        put("highway=tram_stop", "трамвайная остановка");
        put("highway=steps", "лестница");
        put("highway=elevator", "лифт");
        put("highway=traffic_signals", "светофор");
        put("highway=crossing", "пешеходный переход");
        put("highway=footway", "пешеходная дорожка");
        put("highway=path", "тропа");
        put("highway=cycleway", "велодорожка");
        put("highway=living_street", "жилая улица");
        put("highway=pedestrian", "пешеходная улица");
        put("street", "улица");
        put("junction", "перекрёсток");
        put("addr=housenumber", "дом");

        put("railway=station", "вокзал");
        put("railway=halt", "остановка поезда");
        put("railway=subway_entrance", "вход в метро");
        put("railway=tram_stop", "трамвайная остановка");
        put("railway=level_crossing", "переезд");

        put("public_transport=station", "транспортный узел");
        put("public_transport=platform", "платформа");
        put("public_transport=stop_position", "остановка");

        put("leisure=park", "парк");
        put("leisure=garden", "сад");
        put("leisure=playground", "детская площадка");
        put("leisure=pitch", "спортивная площадка");
        put("leisure=stadium", "стадион");
        put("leisure=sports_centre", "спорткомплекс");
        put("leisure=swimming_pool", "бассейн");
        put("leisure=fitness_centre", "фитнес-клуб");
        put("leisure=water_park", "аквапарк");
        put("leisure=dog_park", "выгул собак");

        put("office=government", "госучреждение");
        put("office=bank", "банк");
        put("office=insurance", "страховая");
        put("office=lawyer", "юрист");
        put("office=company", "офис компании");

        put("natural=water", "водоём");
        put("natural=peak", "вершина");
        put("natural=beach", "пляж");

        put("man_made=tower", "башня");
        put("man_made=lighthouse", "маяк");
        put("man_made=water_tower", "водонапорная башня");

        put("building=yes", "здание");
        put("building=retail", "торговое здание");
        put("building=office", "офисное здание");
        put("building=hospital", "здание больницы");
        put("building=school", "здание школы");
        put("building=university", "здание университета");
        put("building=hotel", "здание гостиницы");
        put("building=supermarket", "здание супермаркета");
        put("building=transportation", "транспортное здание");
        put("building=public", "общественное здание");
        put("building=civic", "общественное здание");

        put("historic=monument", "памятник");
        put("historic=memorial", "мемориал");
        put("historic=castle", "замок");
        put("historic=church", "церковь");
        put("historic=fort", "крепость");

        put("addr=housenumber", "дом");

        put("place=city", "город");
        put("place=town", "город");
        put("place=village", "село");
        put("place=suburb", "район");
        put("place=neighbourhood", "квартал");
        put("place=square", "площадь");
        put("place=hamlet", "деревня");
    }

    private static void put(String key, String human) {
        KNOWN.put(key, human);
    }

    private static final java.util.Map<Character, String> TABLE = new java.util.HashMap<>();

    static {
        put('a', "a"); put('b', "b"); put('c', "c"); put('d', "d"); put('e', "e");
        put('f', "f"); put('g', "g"); put('h', "h"); put('i', "i"); put('j', "j");
        put('k', "k"); put('l', "l"); put('m', "m"); put('n', "n"); put('o', "o");
        put('p', "p"); put('q', "q"); put('r', "r"); put('s', "s"); put('t', "t");
        put('u', "u"); put('v', "v"); put('w', "w"); put('x', "x"); put('y', "y");
        put('z', "z");
    }

    private static void put(char c, String s) {
        TABLE.put(c, s);
    }
}