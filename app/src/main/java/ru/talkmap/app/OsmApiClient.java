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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
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

    private final ExecutorService executor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            // Очередь на 1 задачу: пока долгая загрузка висит, пользователь
            // двигает карту — старые запросы отбрасываются, остаётся самый
            // свежий. Раньше очередь росла неограниченно и «зависала» так,
            // что приходилось убивать приложение.
            new ArrayBlockingQueue<Runnable>(1),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    /** Пул для параллельной загрузки тайлов (4 квадранта одновременно). */
    private final ExecutorService tilePool = new ThreadPoolExecutor(
            4, 4, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(8),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context appContext;
    private final OkHttpClient http;
    /** Номер запроса: показываем только результат самого свежего. */
    private final java.util.concurrent.atomic.AtomicInteger fetchGeneration =
            new java.util.concurrent.atomic.AtomicInteger();

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
        final int generation = fetchGeneration.incrementAndGet();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Result result = fetchAll(lat, lon, radiusMeters);
                    if (!result.features.isEmpty() || !result.roads.isEmpty()) {
                        saveCache(result, lat, lon, radiusMeters);
                    }
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            // Пользователь мог уехать, пока запрос висел:
                            // устаревший результат не должен перетирать карту.
                            if (generation != fetchGeneration.get()) {
                                Log.d(TAG, "stale result ignored, gen=" + generation);
                                return;
                            }
                            callback.onResult(result.features, result.roads);
                        }
                    });
                } catch (final Exception e) {
                    Log.w(TAG, "Overpass request failed", e);
                    // Если сеть недоступна — отдаём последний удачный кэш,
                    // но только если он про текущее место: иначе под палец
                    // попадают объекты другой точки («пустые места»).
                    final Result cached = loadCache();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (generation != fetchGeneration.get()) {
                                return;
                            }
                            if (cached != null && cached.hasCenter
                                    && cachedMatches(cached, lat, lon, radiusMeters)) {
                                Log.i(TAG, "network failed, using cache for "
                                        + roundStr(cached.centerLat) + "," + roundStr(cached.centerLon)
                                        + " (delta " + (int) distanceMeters(cached.centerLat, cached.centerLon, lat, lon) + " m)");
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
        /** Официальный API OSM ответил (даже если объектов нет). */
        boolean osmResponded = false;
        /** Центр и радиус загрузки (для кэша): чтобы фолбэк отдавал объекты текущего места. */
        boolean hasCenter = false;
        double centerLat, centerLon, radiusMeters;
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
        if (Math.abs(lat) > 85.0 || Math.abs(lon) > 180.0) {
            // За пределами области данных OpenStreetMap. Не дёргаем сеть:
            // сервер ответит 400 на «неправильный» bbox, а Overpass будет
            // ждать таймауты — пусть это будет честный пустой результат.
            Log.w(TAG, "OSM: center outside map area, empty result");
            Result empty = new Result();
            empty.osmResponded = true;
            return empty;
        }
        Result result = fetchOnce(lat, lon, radius);
        if (result.osmResponded || !result.features.isEmpty() || !result.roads.isEmpty()) {
            // OSM ответил (в том числе пусто — так выглядит пустынная
            // местность) либо нашлись объекты: это и есть правда.
            return result;
        }
        throw new Exception("OSM and Overpass unavailable");
    }

    private Result fetchOnce(double lat, double lon, double radius) {
        Result r = new Result();
        // 1) Официальный API OpenStreetMap. У него жёсткий лимит: не более
        //    50 000 узлов на запрос, поэтому большую область режем сеткой
        //    тайлов — иначе при уменьшении масштаба bbox отклоняется (400)
        //    и на карте не появляется ничего нового.
        try {
            // На стыках тайлов одни и те же объекты приходят дважды — убираем дубли.
            Set<String> seen = new HashSet<>();
            for (String xml : fetchMapXml(lat, lon, radius)) {
                if (xml == null) {
                    continue;
                }
                // Хотя бы один тайл получен — OSM ответил, сеть в порядке.
                r.osmResponded = true;
                String json = apiXmlToJson(xml);
                for (MapFeature f : parseObjects(json, lat, lon)) {
                    if (seen.add(f.type + "|" + f.name + "|"
                            + String.format(Locale.US, "%.5f,%.5f", f.lat, f.lon))) {
                        r.features.add(f);
                    }
                }
                for (RoadInfo road : parseRoads(json)) {
                    boolean dup = false;
                    for (RoadInfo existing : r.roads) {
                        if (existing.name.equals(road.name)
                                && existing.geometry.size() == road.geometry.size()
                                && !existing.geometry.isEmpty()
                                && existing.geometry.get(0).equals(road.geometry.get(0))) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        r.roads.add(road);
                    }
                }
            }
            Log.i(TAG, "OSM API loaded features=" + r.features.size()
                    + " roads=" + r.roads.size() + " responded=" + r.osmResponded);
        } catch (Exception e) {
            Log.w(TAG, "OSM API failed: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
        // 2) Запасной вариант — Overpass. Ходим в него только если OSM не
        //    ответил вовсе (сеть/сервер). Пустая местность — это честный
        //    ответ 200 «объектов нет», Overpass там бесполезен и только
        //    подвесит загрузку на десятки секунд.
        if (!r.osmResponded && r.features.isEmpty() && r.roads.isEmpty()) {
            String q1 = buildObjectsQuery(lat, lon, radius);
            String q2 = buildRoadsQuery(lat, lon, radius);
            int tries = Math.min(2, OVERPASS_ENDPOINTS.length);
            for (int i = 0; i < tries; i++) {
                String endpoint = OVERPASS_ENDPOINTS[i];
                try {
                    r.features.addAll(parseObjects(post(endpoint, q1), lat, lon));
                    r.roads.addAll(parseRoads(post(endpoint, q2)));
                    Log.i(TAG, "loaded from " + endpoint);
                    break;
                } catch (Exception e) {
                    Log.w(TAG, "failed " + endpoint, e);
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
    /** Глубина дробления bbox при отказе сервера (макс. 4^3 = 64 запроса). */
    private static final int MAX_SPLIT_DEPTH = 3;
    /** Предел запросов на одну загрузку, чтобы не залить сервер при плотном центре. */
    private static final int MAX_TILE_REQUESTS = 24;
    /** Счётчик оставшихся запросов для текущей загрузки (см. fetchMapXml). */
    private final java.util.concurrent.atomic.AtomicInteger requestsLeft =
            new java.util.concurrent.atomic.AtomicInteger(MAX_TILE_REQUESTS);

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

    /**
     * Загружает XML для области, дробя её только при отказе сервера.
     * Официальный API отклоняет bbox плотнее 50 000 узлов, поэтому сначала
     * пробуем целиком (быстро, один запрос), а при отказе делим на 4 части
     * и повторяем рекурсивно до {@link #MAX_SPLIT_DEPTH}.
     */
    private List<String> fetchMapXml(double lat, double lon, double radius) {
        double dLat = radius / 110574.0;
        double cosLat = Math.cos(Math.toRadians(lat));
        double dLon = radius / (111320.0 * cosLat);
        requestsLeft.set(MAX_TILE_REQUESTS);
        // Зажимаем область в границы данных OSM: запрос «за краем» карты
        // отвечает 400, а без зажима это превращается в каскад из 24
        // запросов и загрузка «зависает».
        double minLat = Math.max(-85.0, lat - dLat);
        double maxLat = Math.min(85.0, lat + dLat);
        double minLon = Math.max(-180.0, lon - dLon);
        double maxLon = Math.min(180.0, lon + dLon);
        if (minLat >= maxLat || minLon >= maxLon) {
            Log.w(TAG, "OSM: requested area outside map bounds");
            return new ArrayList<>();
        }
        double midLat = (minLat + maxLat) / 2.0;
        double midLon = (minLon + maxLon) / 2.0;
        double[][] quads = {
                {minLat, minLon, midLat, midLon},
                {minLat, midLon, midLat, maxLon},
                {midLat, minLon, maxLat, midLon},
                {midLat, midLon, maxLat, maxLon}
        };
        List<Future<List<String>>> futures = new ArrayList<>();
        for (final double[] q : quads) {
            futures.add(tilePool.submit(new Callable<List<String>>() {
                @Override
                public List<String> call() {
                    List<String> part = new ArrayList<>();
                    fetchBoxXml(q[0], q[1], q[2], q[3], 1, part);
                    return part;
                }
            }));
        }
        List<String> out = new ArrayList<>();
        for (Future<List<String>> f : futures) {
            try {
                // Таймауты уже ограничены OkHttp (connect 6 с, read 30 с);
                // внешний get(90 с) на каждый квадрант складывался в 4×90 с
                // и «замораживал» загрузку при плохой сети.
                out.addAll(f.get());
            } catch (Exception e) {
                Log.w(TAG, "OSM tile group failed: " + e.getMessage());
            }
        }
        Log.i(TAG, "OSM tiles=" + out.size());
        return out;
    }

    private void fetchBoxXml(double minLat, double minLon, double maxLat,
                             double maxLon, int depth, List<String> out) {
        if (requestsLeft.get() <= 0) {
            return;
        }
        requestsLeft.decrementAndGet();
        BoxResponse resp = fetchApiMapBox(minLat, minLon, maxLat, maxLon);
        if (resp.xml != null) {
            out.add(resp.xml);
            return;
        }
        if (!resp.tooDense) {
            // Сеть/сервер недоступны — дробление не поможет, только добавит запросы.
            return;
        }
        if (depth >= MAX_SPLIT_DEPTH) {
            Log.w(TAG, "OSM tile skipped (still too dense at depth " + depth + ")");
            return;
        }
        double midLat = (minLat + maxLat) / 2.0;
        double midLon = (minLon + maxLon) / 2.0;
        fetchBoxXml(minLat, minLon, midLat, midLon, depth + 1, out);
        fetchBoxXml(minLat, midLon, midLat, maxLon, depth + 1, out);
        fetchBoxXml(midLat, minLon, maxLat, midLon, depth + 1, out);
        fetchBoxXml(midLat, midLon, maxLat, maxLon, depth + 1, out);
    }

    /** Ответ на запрос bbox: либо XML, либо причина отказа. */
    private static class BoxResponse {
        final String xml;
        final boolean tooDense;

        BoxResponse(String xml, boolean tooDense) {
            this.xml = xml;
            this.tooDense = tooDense;
        }
    }

    /**
     * Запрос одного прямоугольника. При отказе «слишком много узлов»
     * помечаем tooDense — вызывающий разделит участок и повторит.
     */
    private BoxResponse fetchApiMapBox(double minLat, double minLon,
                                       double maxLat, double maxLon) {
        // bbox: minlon,minlat,maxlon,maxlat (порядок OSM API)
        String bbox = String.format(Locale.US, "%.6f,%.6f,%.6f,%.6f",
                minLon, minLat, maxLon, maxLat);
        Request request = new Request.Builder()
                .url(API_MAP + "?bbox=" + bbox)
                .header("User-Agent", "TalkMap/1.0 (Android; tactile map for visually impaired)")
                .build();
        try (Response resp = http.newCall(request).execute()) {
            if (!resp.isSuccessful()) {
                Log.w(TAG, "OSM tile HTTP " + resp.code() + " for " + bbox);
                return new BoxResponse(null, resp.code() == 400 || resp.code() == 509);
            }
            return new BoxResponse(resp.body().string(), false);
        } catch (Exception e) {
            Log.w(TAG, "OSM tile failed " + bbox + ": " + e.getMessage());
            return new BoxResponse(null, false);
        }
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
            if (sb.length() > 32_000_000) {
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
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(12000);
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

    /** Расстояние по прямой (метры) между двумя точками. */
    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double cosLat = Math.cos(Math.toRadians((lat1 + lat2) / 2.0));
        double dLat = (lat2 - lat1) * 110574.0;
        double dLon = (lon2 - lon1) * 111320.0 * cosLat;
        return Math.hypot(dLat, dLon);
    }

    /** Кэш подходит текущему месту: центр близко и покрытие не меньше запрошенного. */
    private static boolean cachedMatches(Result cached,
                                         double lat, double lon, double radius) {
        double delta = distanceMeters(cached.centerLat, cached.centerLon, lat, lon);
        // Пользователь мог уехать максимум на 12% радиуса загрузки: офлайн-фолбэк
        // всё ещё покрывает видимый квадрат. Дальше — объектов вокруг нет.
        double maxDelta = Math.min(radius, cached.radiusMeters) * 0.12 + 60.0;
        return delta <= maxDelta && cached.radiusMeters >= radius;
    }

    private static String roundStr(double v) {
        return String.format(Locale.US, "%.5f", v);
    }


    // ------------------------------------------------------------------
    // Офлайн-кэш последней успешной загрузки. Если Overpass недоступен,
    // приложение показывает и озвучивает последние объекты, а не пустоту.
    // ------------------------------------------------------------------
    private File cacheFile() {
        return new File(appContext.getFilesDir(), "talkmap_cache.json");
    }

    private void saveCache(Result result, double lat, double lon, double radius) {
        try {
            JSONObject root = new JSONObject();
            root.put("lat", lat);
            root.put("lon", lon);
            root.put("radius", radius);
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
            result.centerLat = root.optDouble("lat", Double.NaN);
            result.centerLon = root.optDouble("lon", Double.NaN);
            result.radiusMeters = root.optDouble("radius", Double.NaN);
            result.hasCenter = !Double.isNaN(result.centerLat) && !Double.isNaN(result.centerLon)
                    && !Double.isNaN(result.radiusMeters);
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
            Log.i(TAG, "cache loaded, features=" + result.features.size()
                    + " center=" + result.centerLat + "," + result.centerLon
                    + " radius=" + result.radiusMeters);
            return !result.features.isEmpty() ? result : null;
        } catch (Exception e) {
            Log.w(TAG, "cache load failed", e);
            return null;
        }
    }
}