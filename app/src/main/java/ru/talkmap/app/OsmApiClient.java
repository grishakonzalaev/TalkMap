package ru.talkmap.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;
import org.xmlpull.v1.XmlPullParser;

import android.util.Xml;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Загрузка объектов из Overpass API (данные OpenStreetMap) вокруг заданной точки:
 * подписанные объекты, адреса домов, светофоры/переходы, а также геометрия
 * именованных улиц (для озвучки направления и синтеза перекрёстков).
 */
public class OsmApiClient {
    private static final String TAG = "OsmApiClient";
    // Публичные Overpass-серверы: запасной вариант, если OSM API недоступен.
    private static final String[] OVERPASS_ENDPOINTS = {
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://overpass.osm.jp/api/interpreter"
    };
    private static final int RESULT_LIMIT = 5000;
    private static final int MAX_INTERSECTIONS = 1200;

    /** Именованная дорога с геометрией. */
    public static class RoadInfo {
        public final String name;
        public final List<GeoPoint> geometry = new ArrayList<>();

        RoadInfo(String name) {
            this.name = name;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context appContext;
    private final OkHttpClient http;

    public OsmApiClient(Context context) {
        this.appContext = context.getApplicationContext();
        this.http = buildHttpClient();
    }

    public interface Callback {
        void onResult(List<MapFeature> features, List<RoadInfo> roads);

        void onError(Exception e);
    }

    public void fetchAsync(final double lat, final double lon, final double radiusMeters,
                           final Callback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Result result = fetchAll(lat, lon, radiusMeters);
                    if (!result.features.isEmpty() || !result.roads.isEmpty()) {
                        saveCache(result);
                    }
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onResult(result.features, result.roads);
                        }
                    });
                } catch (final Exception e) {
                    Log.w(TAG, "Overpass request failed", e);
                    // Если сеть недоступна — отдаём последний удачный кэш,
                    // чтобы карта не оставалась пустой.
                    final Result cached = loadCache();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (cached != null && (!cached.features.isEmpty() || !cached.roads.isEmpty())) {
                                callback.onResult(cached.features, cached.roads);
                            } else {
                                callback.onError(e);
                            }
                        }
                    });
                }
            }
        });
    }

    private static class Result {
        final List<MapFeature> features = new ArrayList<>();
        final List<RoadInfo> roads = new ArrayList<>();
    }

    static String buildObjectsQuery(double lat, double lon, double radius) {
        String r = String.format(Locale.US, "%.0f", radius);
        String c = String.format(Locale.US, "%.6f,%.6f", lat, lon);
        return "[out:json][timeout:50];\n" +
                "(\n" +
                "  nwr[\"name\"](around:" + r + "," + c + ");\n" +
                "  nwr[\"addr:housenumber\"](around:" + r + "," + c + ");\n" +
                "  nwr[\"addr:housename\"](around:" + r + "," + c + ");\n" +
                "  node[\"highway\"=\"traffic_signals\"](around:" + r + "," + c + ");\n" +
                "  node[\"highway\"=\"crossing\"](around:" + r + "," + c + ");\n" +
                ");\n" +
                "out center tags;";
    }

    static String buildRoadsQuery(double lat, double lon, double radius) {
        String r = String.format(Locale.US, "%.0f", radius);
        String c = String.format(Locale.US, "%.6f,%.6f", lat, lon);
        return "[out:json][timeout:50];\n" +
                "way[\"highway\"][\"name\"](around:" + r + "," + c + ");\n" +
                "out geom;";
    }

    private Result fetchAll(double lat, double lon, double radius) throws Exception {
        Result result = fetchOnce(lat, lon, radius);
        if (result != null && (!result.features.isEmpty() || !result.roads.isEmpty())) {
            return result;
        }
        // Публичный Overpass часто мигает 502 — даём ему вторую попытку.
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ignored) {
        }
        result = fetchOnce(lat, lon, radius);
        if (result != null && (!result.features.isEmpty() || !result.roads.isEmpty())) {
            return result;
        }
        // Последняя надежда: меньший радиус — ответ короче, серверу легче.
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ignored) {
        }
        result = fetchOnce(lat, lon, Math.max(400, radius * 0.5));
        if (result != null && (!result.features.isEmpty() || !result.roads.isEmpty())) {
            return result;
        }
        throw new Exception("Overpass empty response");
    }

    private Result fetchOnce(double lat, double lon, double radius) {
        Result r = new Result();
        // 1) Официальный API OpenStreetMap. На телефоне доступен напрямую
        //    (проверено: 200), в отличие от публичных Overpass-серверов.
        //    Один запрос возвращает и объекты, и дороги (узлы/пути/теги).
        try {
            String xml = fetchApiMap(lat, lon, radius);
            String json = apiXmlToJson(xml);
            r.features.addAll(parseObjects(json, lat, lon));
            r.roads.addAll(parseRoads(json));
            Log.i(TAG, "OSM API loaded features=" + r.features.size()
                    + " roads=" + r.roads.size());
        } catch (Exception e) {
            Log.w(TAG, "OSM API failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
        // 2) Если OSM API пуст/недоступен — запасной вариант: Overpass.
        if (r.features.isEmpty() && r.roads.isEmpty()) {
            String q1 = buildObjectsQuery(lat, lon, radius);
            String q2 = buildRoadsQuery(lat, lon, radius);
            for (String endpoint : OVERPASS_ENDPOINTS) {
                try {
                    r.features.addAll(parseObjects(post(endpoint, q1), lat, lon));
                    Log.i(TAG, "objects loaded from " + endpoint);
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "objects failed " + endpoint, e);
                }
            }
            for (String endpoint : OVERPASS_ENDPOINTS) {
                try {
                    r.roads.addAll(parseRoads(post(endpoint, q2)));
                    Log.i(TAG, "roads loaded from " + endpoint);
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "roads failed " + endpoint, e);
                }
            }
        }
        int before = r.features.size();
        r.features.addAll(buildIntersections(r.roads, lat, lon, radius));
        Log.i(TAG, "intersections added=" + (r.features.size() - before));
        return r;
    }

    // ------------------------------------------------------------------
    // Официальный API OpenStreetMap: GET /api/0.6/map?bbox=...
    // ------------------------------------------------------------------
    private static final String API_MAP = "https://api.openstreetmap.org/api/0.6/map";

    // ------------------------------------------------------------------
    // Сеть. Системный DNS на телефоне нестабилен (приложение ходит в обход
    // VPN, и резолв api.openstreetmap.org падает с UnknownHostException).
    // Поэтому резолвим через DoH: сначала системный DNS, при неудаче —
    // DNS-запросы к Google/Cloudflare по прямому IP (работают всегда).
    // ------------------------------------------------------------------
    private static final String[] DOH_SERVERS = {
            "https://dns.google/resolve",
            "https://cloudflare-dns.com/dns-query"
    };
    private static final Map<String, String> DOH_HOST_IP = new HashMap<>();

    static {
        DOH_HOST_IP.put("dns.google", "8.8.8.8");
        DOH_HOST_IP.put("cloudflare-dns.com", "1.1.1.1");
    }

    private OkHttpClient buildHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .dns(new Dns() {
                    @Override
                    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
                        // DoH-хосты резолвим всегда напрямую в публичный IP,
                        // чтобы DNS-запрос не зависел от системного резолвера.
                        String directIp = DOH_HOST_IP.get(hostname);
                        if (directIp != null) {
                            return Collections.singletonList(InetAddress.getByName(directIp));
                        }
                        try {
                            return Dns.SYSTEM.lookup(hostname);
                        } catch (UnknownHostException e) {
                            // Системный DNS не сработал — пробуем DoH (Google/Cloudflare).
                            return resolveViaDoh(hostname);
                        }
                    }
                })
                .build();
    }

    private List<InetAddress> resolveViaDoh(String hostname) throws UnknownHostException {
        Exception last = null;
        for (String base : DOH_SERVERS) {
            try {
                List<InetAddress> list = dohLookup(base, hostname);
                if (!list.isEmpty()) {
                    Log.i(TAG, "DoH resolved " + hostname + " -> " + list.get(0).getHostAddress());
                    return list;
                }
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "DoH lookup failed " + base + ": " + e.getMessage());
            }
        }
        throw new UnknownHostException("DoH lookup failed for " + hostname
                + (last == null ? "" : ": " + last.getMessage()));
    }

    private List<InetAddress> dohLookup(String base, String hostname) throws Exception {
        String url = base + (base.contains("dns.google") ? "?name=" : "/dns-query?name=")
                + URLEncoder.encode(hostname, "UTF-8") + "&type=A";
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .header("User-Agent", "TalkMap/1.0 (Android; tactile map)")
                .build();
        // OkHttp подставит Host/SNI из URL (dns.google / cloudflare-dns.com),
        // а IP возьмёт из нашего Dns — то есть 8.8.8.8 / 1.1.1.1.
        try (Response resp = http.newCall(request).execute()) {
            if (!resp.isSuccessful()) {
                throw new Exception("DoH HTTP " + resp.code());
            }
            String body = resp.body().string();
            JSONObject root = new JSONObject(body);
            JSONArray answers = root.optJSONArray("Answer");
            List<InetAddress> out = new ArrayList<>();
            if (answers != null) {
                for (int i = 0; i < answers.length(); i++) {
                    String data = answers.optJSONObject(i).optString("data", "");
                    if (data.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
                        out.add(InetAddress.getByName(data));
                    }
                }
            }
            return out;
        }
    }

    private String fetchApiMap(double lat, double lon, double radius) throws Exception {
        double cosLat = Math.cos(Math.toRadians(lat));
        double dLat = radius / 110574.0;
        double dLon = radius / (111320.0 * cosLat);
        // bbox: minlon,minlat,maxlon,maxlat (порядок OSM API)
        String bbox = String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f",
                lon - dLon, lat - dLat, lon + dLon, lat + dLat);
        Request request = new Request.Builder()
                .url(API_MAP + "?bbox=" + bbox)
                .header("User-Agent", "TalkMap/1.0 (Android; tactile map for visually impaired)")
                .build();
        int code;
        String body;
        try (Response resp = http.newCall(request).execute()) {
            code = resp.code();
            body = resp.body().string();
        }
        if (code >= 300) {
            // 400 = слишком большая рамка (более 50 000 элементов) — уменьшаем радиус.
            if (code == 400 && radius > 250) {
                return fetchApiMap(lat, lon, radius / 2);
            }
            throw new Exception("OSM API HTTP " + code);
        }
        return body;
    }

    // ------------------------------------------------------------------
    // Разбор XML официального API в JSON-структуру {elements:[...]},
    // которую понимают parseObjects()/parseRoads().
    // ------------------------------------------------------------------
    private String apiXmlToJson(String xml) throws Exception {
        Map<Long, double[]> nodes = new LinkedHashMap<>();
        Map<Long, Map<String, String>> nodeTags = new LinkedHashMap<>();
        Map<Long, List<Long>> wayRefs = new LinkedHashMap<>();
        Map<Long, Map<String, String>> wayTags = new LinkedHashMap<>();

        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(new StringReader(xml));
        long curNode = -1, curWay = -1;
        int event;
        while ((event = p.next()) != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                String name = p.getName();
                if ("node".equals(name)) {
                    curNode = Long.parseLong(p.getAttributeValue(null, "id"));
                    double lat = Double.parseDouble(p.getAttributeValue(null, "lat"));
                    double lon = Double.parseDouble(p.getAttributeValue(null, "lon"));
                    nodes.put(curNode, new double[]{lat, lon});
                    nodeTags.put(curNode, new HashMap<>());
                } else if ("way".equals(name)) {
                    curWay = Long.parseLong(p.getAttributeValue(null, "id"));
                    wayRefs.put(curWay, new ArrayList<>());
                    wayTags.put(curWay, new HashMap<>());
                } else if ("nd".equals(name) && curWay > 0) {
                    wayRefs.get(curWay).add(Long.parseLong(p.getAttributeValue(null, "ref")));
                } else if ("tag".equals(name)) {
                    String k = p.getAttributeValue(null, "k");
                    String v = p.getAttributeValue(null, "v");
                    if (k == null || v == null) {
                        continue;
                    }
                    if (curWay > 0) {
                        wayTags.get(curWay).put(k, v);
                    } else if (curNode > 0) {
                        nodeTags.get(curNode).put(k, v);
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                String name = p.getName();
                if ("node".equals(name)) {
                    curNode = -1;
                } else if ("way".equals(name)) {
                    curWay = -1;
                }
            }
        }

        // JSON: узлы сразу, пути — с центром и геометрией из ссылок на узлы.
        JSONArray elements = new JSONArray();
        for (Map.Entry<Long, double[]> e : nodes.entrySet()) {
            JSONObject o = new JSONObject();
            o.put("type", "node");
            o.put("lat", e.getValue()[0]);
            o.put("lon", e.getValue()[1]);
            Map<String, String> tags = nodeTags.get(e.getKey());
            if (tags != null && !tags.isEmpty()) {
                o.put("tags", new JSONObject(tags));
            }
            elements.put(o);
        }
        for (Long wayId : wayRefs.keySet()) {
            List<Long> refs = wayRefs.get(wayId);
            List<double[]> pts = new ArrayList<>();
            for (Long ref : refs) {
                double[] n = nodes.get(ref);
                if (n != null) {
                    pts.add(n);
                }
            }
            if (pts.isEmpty()) {
                continue;
            }
            JSONObject o = new JSONObject();
            o.put("type", "way");
            double latSum = 0, lonSum = 0;
            for (double[] pt : pts) {
                latSum += pt[0];
                lonSum += pt[1];
            }
            JSONObject center = new JSONObject();
            center.put("lat", latSum / pts.size());
            center.put("lon", lonSum / pts.size());
            o.put("center", center);
            JSONArray geometry = new JSONArray();
            for (double[] pt : pts) {
                JSONObject g = new JSONObject();
                g.put("lat", pt[0]);
                g.put("lon", pt[1]);
                geometry.put(g);
            }
            o.put("geometry", geometry);
            Map<String, String> tags = wayTags.get(wayId);
            if (tags != null && !tags.isEmpty()) {
                o.put("tags", new JSONObject(tags));
            }
            elements.put(o);
        }
        JSONObject root = new JSONObject();
        root.put("elements", elements);
        return root.toString();
    }

    private String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (sb.length() > 8_000_000) {
                throw new Exception("OSM response too large");
            }
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    private String post(String endpoint, String query) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        try {
            byte[] body = ("data=" + java.net.URLEncoder.encode(query, "UTF-8")).getBytes(StandardCharsets.UTF_8);
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", "TalkMap/1.0 (Android; tactile map for visually impaired)");
            OutputStream out = conn.getOutputStream();
            out.write(body);
            out.close();
            int code = conn.getResponseCode();
            if (code >= 300) {
                throw new Exception("Overpass HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 8_000_000) {
                    throw new Exception("Overpass response too large");
                }
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    // ------------------------------------------------------------------
    // Объекты: подписанные точки, адреса, светофоры и переходы.
    // ------------------------------------------------------------------
    private List<MapFeature> parseObjects(String json, double centerLat, double centerLon) throws Exception {
        JSONArray elements = new JSONObject(json).optJSONArray("elements");
        List<MapFeature> named = new ArrayList<>();
        List<MapFeature> addresses = new ArrayList<>();
        List<MapFeature> small = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (elements == null) {
            return named;
        }
        for (int i = 0; i < elements.length(); i++) {
            JSONObject el = elements.optJSONObject(i);
            if (el == null) {
                continue;
            }
            JSONObject tags = el.optJSONObject("tags");
            if (tags == null) {
                continue;
            }
            double[] ll = coordOf(el);
            if (ll == null) {
                continue;
            }
            String name = tags.optString("name:ru", tags.optString("name", "")).trim();
            if (!name.isEmpty()) {
                String typeKey = pickTypeKey(tags);
                if (typeKey == null) {
                    continue;
                }
                String dedup = typeKey + "|" + name + "|" + fmt(ll);
                if (!seen.add(dedup)) {
                    continue;
                }
                String spokenType = CategoryNames.humanize(typeKey);
                named.add(new MapFeature(ll[0], ll[1], name, typeKey, name, spokenType));
                continue;
            }

            // Дом без названия, но с адресом: "улица Станиславского, дом 12".
            String house = tags.optString("addr:housenumber", "").trim();
            String street = tags.optString("addr:street", "").trim();
            String houseName = tags.optString("addr:housename", "").trim();
            if (!house.isEmpty() || !houseName.isEmpty()) {
                String addr;
                if (!houseName.isEmpty()) {
                    addr = street.isEmpty() ? houseName : street + ", " + houseName;
                } else {
                    addr = street.isEmpty() ? ("Дом " + house) : (street + ", дом " + house);
                }
                String dedup = "addr|" + addr + "|" + fmt(ll);
                if (!seen.add(dedup)) {
                    continue;
                }
                addresses.add(new MapFeature(ll[0], ll[1], addr, "addr=housenumber", addr, ""));
                continue;
            }

            // Светофоры и пешеходные переходы (без названия).
            String hw = tags.optString("highway", "");
            String typeKey = null;
            if ("traffic_signals".equals(hw)) {
                typeKey = "highway=traffic_signals";
            } else if ("crossing".equals(hw)) {
                typeKey = "highway=crossing";
            }
            if (typeKey != null) {
                String dedup = typeKey + "|" + fmt(ll);
                if (!seen.add(dedup)) {
                    continue;
                }
                String spokenType = CategoryNames.humanize(typeKey);
                small.add(new MapFeature(ll[0], ll[1], "", typeKey, "", spokenType));
            }
        }
        // Приоритет: подписанные объекты, затем адреса домов.
        // Светофоры/переходы добавляем после обрезки лимита, чтобы они
        // не терялись в плотной застройке (иначе перекрёсток «со светофором»
        // не сможет озвучить признак).
        List<MapFeature> out = new ArrayList<>();
        out.addAll(named);
        out.addAll(addresses);
        if (out.size() > RESULT_LIMIT) {
            out = new ArrayList<>(out.subList(0, RESULT_LIMIT));
        }
        out.addAll(small);
        return out;
    }

    private double[] coordOf(JSONObject el) {
        String kind = el.optString("type", "");
        double lat;
        double lon;
        if ("node".equals(kind)) {
            lat = el.optDouble("lat", Double.NaN);
            lon = el.optDouble("lon", Double.NaN);
        } else {
            JSONObject center = el.optJSONObject("center");
            if (center == null) {
                return null;
            }
            lat = center.optDouble("lat", Double.NaN);
            lon = center.optDouble("lon", Double.NaN);
        }
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            return null;
        }
        return new double[]{lat, lon};
    }

    private String fmt(double[] ll) {
        return String.format(Locale.US, "%.5f,%.5f", ll[0], ll[1]);
    }

    private String pickTypeKey(JSONObject tags) {
        String[] keys = {"amenity", "shop", "craft", "tourism", "leisure", "office", "historic",
                "railway", "public_transport", "highway", "place", "natural", "man_made",
                "building", "addr"};
        for (String key : keys) {
            String v = tags.optString(key, "");
            if (!v.isEmpty()) {
                if ("highway".equals(key) && ROAD_VALUES.contains(v)) {
                    return "street";
                }
                return key + "=" + v;
            }
        }
        return null;
    }

    private static final Set<String> ROAD_VALUES = new HashSet<>(Arrays.asList(
            "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
            "residential", "service", "living_street", "pedestrian", "footway", "path",
            "cycleway", "track", "road", "motorway_link", "trunk_link", "primary_link",
            "secondary_link", "tertiary_link", "raceway"));

    // ------------------------------------------------------------------
    // Дороги: геометрия именованных улиц для направления.
    // ------------------------------------------------------------------
    private List<RoadInfo> parseRoads(String json) throws Exception {
        JSONArray elements = new JSONObject(json).optJSONArray("elements");
        List<RoadInfo> roads = new ArrayList<>();
        if (elements == null) {
            return roads;
        }
        for (int i = 0; i < elements.length(); i++) {
            JSONObject el = elements.optJSONObject(i);
            if (el == null || !"way".equals(el.optString("type", ""))) {
                continue;
            }
            JSONObject tags = el.optJSONObject("tags");
            if (tags == null || !tags.has("highway")) {
                continue;
            }
            String name = tags.optString("name:ru", tags.optString("name", "")).trim();
            if (name.isEmpty()) {
                continue;
            }
            JSONArray geom = el.optJSONArray("geometry");
            if (geom == null || geom.length() < 2) {
                continue;
            }
            RoadInfo road = new RoadInfo(name);
            for (int j = 0; j < geom.length(); j++) {
                JSONObject g = geom.optJSONObject(j);
                if (g != null) {
                    road.geometry.add(new GeoPoint(g.optDouble("lat", 0), g.optDouble("lon", 0)));
                }
            }
            if (road.geometry.size() >= 2) {
                roads.add(road);
            }
        }
        return roads;
    }

    // ------------------------------------------------------------------
    // Перекрёстки: пересечения центров именованных улиц.
    // ------------------------------------------------------------------
    private List<MapFeature> buildIntersections(List<RoadInfo> roads,
                                                double lat0, double lon0, double radius) {
        List<MapFeature> out = new ArrayList<>();
        if (roads.size() < 2) {
            return out;
        }
        double cosLat = Math.cos(Math.toRadians(lat0));
        // Ключ — упорядоченная пара названий улиц: одна точка на каждое
        // реальное пересечение. Это убирает дубли из-за множественных
        // сегментов одной улицы и не даёт лидеру списка «съедать» лимит.
        Map<String, int[]> byPair = new LinkedHashMap<>();
        Map<String, String[]> pairNames = new LinkedHashMap<>();
        for (int i = 0; i < roads.size(); i++) {
            RoadInfo a = roads.get(i);
            double[] pa = toLocal(roads.get(i).geometry, lat0, lon0, cosLat);
            for (int j = i + 1; j < roads.size(); j++) {
                RoadInfo b = roads.get(j);
                if (a.name.equals(b.name)) {
                    continue;
                }
                double[] pb = toLocal(b.geometry, lat0, lon0, cosLat);
                for (int sa = 0; sa + 3 < pa.length; sa += 2) {
                    double x1 = pa[sa], y1 = pa[sa + 1];
                    double x2 = pa[sa + 2], y2 = pa[sa + 3];
                    for (int sb = 0; sb + 3 < pb.length; sb += 2) {
                        double x3 = pb[sb], y3 = pb[sb + 1];
                        double x4 = pb[sb + 2], y4 = pb[sb + 3];
                        double denom = (y4 - y3) * (x2 - x1) - (x4 - x3) * (y2 - y1);
                        if (Math.abs(denom) < 1e-9) {
                            continue;
                        }
                        double t = ((x4 - x3) * (y1 - y3) - (y4 - y3) * (x1 - x3)) / denom;
                        double u = ((x2 - x1) * (y1 - y3) - (y2 - y1) * (x1 - x3)) / denom;
                        if (t < 0 || t > 1 || u < 0 || u > 1) {
                            continue;
                        }
                        double px = x1 + t * (x2 - x1);
                        double py = y1 + t * (y2 - y1);
                        if (px * px + py * py > radius * radius * 1.8) {
                            continue;
                        }
                        String k1 = a.name + "|" + b.name;
                        String k2 = b.name + "|" + a.name;
                        String prior = byPair.containsKey(k1) ? k1
                                : (byPair.containsKey(k2) ? k2 : null);
                        if (prior != null) {
                            int[] p = byPair.get(prior);
                            // Если второй раз пересекаются не на той же точке —
                            // улицы пересекаются в нескольких местах (не бывает),
                            // оставляем уже найденное.
                            continue;
                        }
                        byPair.put(k1, new int[]{(int) Math.round(px), (int) Math.round(py)});
                        pairNames.put(k1, new String[]{a.name, b.name});
                    }
                }
            }
        }
        for (Map.Entry<String, int[]> e : byPair.entrySet()) {
            if (out.size() >= MAX_INTERSECTIONS) {
                break;
            }
            int[] p = e.getValue();
            double lat = lat0 + p[1] / 110574.0;
            double lon = lon0 + p[0] / (111320.0 * cosLat);
            String[] names = pairNames.get(e.getKey());
            String spoken = "Перекрёсток: " + names[0] + " и " + names[1];
            out.add(new MapFeature(lat, lon, names[0] + " и " + names[1],
                    "junction", spoken, ""));
        }
        return out;
    }

    private double[] toLocal(List<GeoPoint> geom, double lat0, double lon0, double cosLat) {
        double[] pts = new double[geom.size() * 2];
        for (int i = 0; i < geom.size(); i++) {
            GeoPoint g = geom.get(i);
            pts[i * 2] = (g.getLongitude() - lon0) * 111320.0 * cosLat;
            pts[i * 2 + 1] = (g.getLatitude() - lat0) * 110574.0;
        }
        return pts;
    }

    // ------------------------------------------------------------------
    // Офлайн-кэш последней успешной загрузки. Если Overpass недоступен,
    // приложение показывает и озвучивает последние объекты, а не пустоту.
    // ------------------------------------------------------------------
    private File cacheFile() {
        return new File(appContext.getFilesDir(), "talkmap_cache.json");
    }

    private void saveCache(Result result) {
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (MapFeature f : result.features) {
                JSONObject o = new JSONObject();
                o.put("lat", f.lat);
                o.put("lon", f.lon);
                o.put("name", f.name);
                o.put("type", f.type);
                o.put("spokenName", f.spokenName);
                o.put("spokenType", f.spokenType);
                arr.put(o);
            }
            root.put("features", arr);
            File file = cacheFile();
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
            fos.close();
            Log.i(TAG, "cache saved, features=" + result.features.size());
        } catch (Exception e) {
            Log.w(TAG, "cache save failed", e);
        }
    }

    private Result loadCache() {
        try {
            File file = cacheFile();
            if (!file.exists()) {
                return null;
            }
            FileInputStream fis = new FileInputStream(file);
            byte[] buf = new byte[(int) file.length()];
            int read = fis.read(buf);
            fis.close();
            if (read <= 0) {
                return null;
            }
            JSONObject root = new JSONObject(new String(buf, 0, read, StandardCharsets.UTF_8));
            JSONArray arr = root.optJSONArray("features");
            if (arr == null) {
                return null;
            }
            Result result = new Result();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                result.features.add(new MapFeature(
                        o.optDouble("lat", 0), o.optDouble("lon", 0),
                        o.optString("name", ""), o.optString("type", ""),
                        o.optString("spokenName", ""), o.optString("spokenType", "")));
            }
            Log.i(TAG, "cache loaded, features=" + result.features.size());
            return !result.features.isEmpty() ? result : null;
        } catch (Exception e) {
            Log.w(TAG, "cache load failed", e);
            return null;
        }
    }
}