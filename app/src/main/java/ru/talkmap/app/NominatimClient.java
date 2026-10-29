package ru.talkmap.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;

import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Поиск по всем объектам OpenStreetMap (адреса, объекты, координаты)
 * через Nominatim. Не ограничен радиусом карты — ищет по всему миру:
 * пользователь может найти город, улицу или дом в любой стране.
 *
 * <p>Поддерживает ввод координат в формате «широта, долгота» — тогда
 * результатом является одна точка из текста, без обращения к сети.</p>
 */
public class NominatimClient {

    private static final String TAG = "NominatimClient";
    private static final String API = "https://nominatim.openstreetmap.org/search";
    private static final int LIMIT = 8;
    private static final long MIN_INTERVAL_MS = 1100;

    /** Один результат поиска: точка с названием и типом. */
    public static final class SearchResult {
        public final double lat;
        public final double lon;
        public final String displayName;
        public final String type;

        SearchResult(double lat, double lon, String displayName, String type) {
            this.lat = lat;
            this.lon = lon;
            this.displayName = displayName;
            this.type = type;
        }

        /** Озвучиваемое имя: короткая часть названия (до запятой). */
        public String spokenName() {
            if (displayName == null) {
                return "";
            }
            int cut = displayName.indexOf(',');
            return cut > 0 ? displayName.substring(0, cut).trim() : displayName.trim();
        }
    }

    public interface Callback {
        void onResult(List<SearchResult> results);

        void onError(Exception e);
    }

    private final Context appContext;
    private final OkHttpClient http;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile long lastRequestAt = 0;

    public NominatimClient(Context context) {
        this.appContext = context.getApplicationContext();
        this.http = buildHttpClient();
    }

    /**
     * Пытается распознать в тексте координаты «широта, долгота».
     * Возвращает точку, либо null, если текст не похож на координаты.
     */
    public static GeoPoint parseCoordinates(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        // Нормализуем типографский минус и удаляем хвостовые буквы направления.
        s = s.replace('\u2212', '-').replace('\u2013', '-');
        s = s.replaceAll("(?i)[nsew]$", "").trim();

        double lat = Double.NaN;
        double lon = Double.NaN;

        // 1) «lat, lon» (или «lat lon») с десятичной точкой.
        String[] byComma = s.split(",");
        if (byComma.length == 2) {
            lat = parseCoordPart(byComma[0]);
            lon = parseCoordPart(byComma[1]);
        } else if (byComma.length >= 4) {
            // Русский формат с запятой как десятичным разделителем:
            // «55,030204, 82,920430» → 4 части.
            try {
                lat = Double.parseDouble(byComma[0].trim() + "." + byComma[1].trim());
                lon = Double.parseDouble(byComma[2].trim() + "." + byComma[3].trim());
            } catch (NumberFormatException ignored) {
                // не координаты — попробуем ниже
            }
        }
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            // 2) Пробельный разделитель: «55.030204 82.920430».
            String[] bySpace = s.trim().split("\\s+");
            if (bySpace.length == 2) {
                lat = parseCoordPart(bySpace[0]);
                lon = parseCoordPart(bySpace[1]);
            }
        }
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            return null;
        }
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            return null;
        }
        return new GeoPoint(lat, lon);
    }

    private static double parseCoordPart(String part) {
        String p = part.trim();
        if (p.isEmpty()) {
            return Double.NaN;
        }
        // Запятая как десятичный разделитель: «55,030204» → «55.030204».
        if (p.indexOf(',') >= 0 && p.indexOf('.') < 0) {
            p = p.replace(',', '.');
        }
        try {
            return Double.parseDouble(p);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Запускает поиск в фоне. Координаты распознаются локально, без сети. */
    public void search(String query, final Callback callback) {
        if (query == null || query.trim().isEmpty()) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    callback.onResult(Collections.<SearchResult>emptyList());
                }
            });
            return;
        }
        // Сначала пробуем распознать координаты — это самый быстрый и
        // предсказуемый путь, не зависящий от сети.
        final GeoPoint point = parseCoordinates(query.trim());
        if (point != null) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    List<SearchResult> one = new ArrayList<>();
                    one.add(new SearchResult(point.getLatitude(), point.getLongitude(),
                            String.format(java.util.Locale.US,
                                    "Координаты: %.5f, %.5f", point.getLatitude(), point.getLongitude()),
                            "coordinate"));
                    callback.onResult(one);
                }
            });
            return;
        }
        final int gen = generation.incrementAndGet();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<SearchResult> results = searchRemote(query.trim());
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != generation.get()) {
                                return;
                            }
                            callback.onResult(results);
                        }
                    });
                } catch (final Exception e) {
                    Log.w(TAG, "nominatim search failed: " + query, e);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (gen != generation.get()) {
                                return;
                            }
                            callback.onError(e);
                        }
                    });
                }
            }
        });
    }

    private List<SearchResult> searchRemote(String query) throws Exception {
        // Nominatim требует не чаще одного запроса в секунду.
        long wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt);
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
        String url = API + "?q=" + URLEncoder.encode(query, "UTF-8")
                + "&format=jsonv2&limit=" + LIMIT
                + "&accept-language=ru&addressdetails=0";
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "TalkMap/1.0 (Android; tactile map for visually impaired)")
                .build();
        List<SearchResult> out = new ArrayList<>();
        try (Response resp = http.newCall(request).execute()) {
            if (!resp.isSuccessful()) {
                throw new Exception("Nominatim HTTP " + resp.code());
            }
            String body = resp.body().string();
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length() && i < LIMIT; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                double lat = o.optDouble("lat", Double.NaN);
                double lon = o.optDouble("lon", Double.NaN);
                String display = o.optString("display_name", "").trim();
                if (Double.isNaN(lat) || Double.isNaN(lon) || display.isEmpty()) {
                    continue;
                }
                out.add(new SearchResult(lat, lon, display, o.optString("type", "")));
            }
        }
        Log.i(TAG, "nominatim results=" + out.size() + " for=" + query);
        return out;
    }

    // ------------------------------------------------------------------
    // Сеть. Так же, как в OsmApiClient: системный DNS, при неудаче — DoH.
    // ------------------------------------------------------------------
    private static final String[] DOH_SERVERS = {
            "https://dns.google/resolve",
            "https://cloudflare-dns.com/dns-query"
    };
    private static final java.util.Map<String, String> DOH_HOST_IP = new java.util.HashMap<>();

    static {
        DOH_HOST_IP.put("dns.google", "8.8.8.8");
        DOH_HOST_IP.put("cloudflare-dns.com", "1.1.1.1");
    }

    private OkHttpClient buildHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .dns(new Dns() {
                    @Override
                    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
                        String directIp = DOH_HOST_IP.get(hostname);
                        if (directIp != null) {
                            return Collections.singletonList(InetAddress.getByName(directIp));
                        }
                        try {
                            return Dns.SYSTEM.lookup(hostname);
                        } catch (UnknownHostException e) {
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
}