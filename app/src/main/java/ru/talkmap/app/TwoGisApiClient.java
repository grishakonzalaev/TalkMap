package ru.talkmap.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Загрузка объектов 2ГИС через парсинг веб-страницы (без официального API).
 *
 * <p>Сайт 2ГИС отдаёт полный каталог в виде встроенного
 * {@code var initialState = JSON.parse('...')} только настоящему браузерному
 * движку (обычный HTTP-клиент получает «museum»-заглушку). Поэтому здесь
 * используется скрытый {@link WebView}: он открывает страницу поиска, а на
 * {@code onPageFinished} через {@code evaluateJavascript} вычитывает
 * {@code window.initialState.data.entity.profile} — там лежит список фирм с их
 * {@code point{lat,lon}}, именем и рубрикой.</p>
 *
 * <p>2ГИС не поддерживает несколько категорий в одном URL ({@code |} даёт 404),
 * поэтому каждая категория — отдельная загрузка страницы. Чтобы при перемещении
 * карты не качать всё заново, результат кэшируется по сетке ячеек: новая ячейка
 * грузится один раз, при повторном выходе в неё объекты берутся из кэша.
 * Загрузки идут строго по одной (очередь), чтобы не заваливать сервер.</p>
 *
 * <p>Все операции WebView выполняются на главном потоке.</p>
 */
public class TwoGisApiClient {
    private static final String TAG = "TwoGisApiClient";
    /** Максимум объектов в итоговой выдаче (защита от бесконечного роста). */
    private static final int RESULT_LIMIT = 4000;
    /** Шаг сетки кэша в метрах (размер ячейки). */
    private static final double CELL_METERS = 900.0;
    /** Сколько категорий грузить на одну ячейку (ограничение нагрузки). */
    private static final int MAX_PAGES_PER_CELL = 80;
    /** Глубина пагинации 2ГИС по каждой категории (страница 0,1,2,...). */
    private static final int MAX_PAGES_PER_QUERY = 3;
    /** Категории для поиска; грузятся по одной. */
    private static final String QUERIES =
            // Питание и продукты
            "кафе|ресторан|столовая|бар|кофейня|доставка еды|пиццерия|пекарня|"
            + "продукты|магазин|супермаркет|мясо и полуфабрикаты|рыбный магазин|"
            + "овощи|хлеб|кондитерская|фастфуд|шаурма|суши|бургерная|"
            // Здоровье
            + "аптека|больница|поликлиника|клиника|стоматология|ветеринарная клиника|"
            + "медицинский центр|лаборатория|оптика|"
            // Услуги и финансы
            + "банк|банкомат|сбербанк|почта|салон красоты|парикмахерская|маникюр|барбершоп|"
            + "фитнес|спортзал|бассейн|хозяйственный магазин|цветы|"
            // Торговля и техника
            + "электроника|бытовая техника|одежда|обувь|стройматериалы|мебель|"
            + "хозтовары|автосервис|шиномонтаж|мойка|автозапчасти|"
            // Отдых и прочее
            + "парк|развлечения|кинотеатр|театр|музей|библиотека|детский сад|"
            + "школа|гостиница|отель|хостел|сауна|баня|заправка|парковка|"
            + "остановка|автовокзал|железнодорожный вокзал|аэропорт";
    /** Ячеек на один градус широты (сетка кэша). */
    private static final double COLS_PER_DEG = 110574.0 / CELL_METERS;

    /** Интервал опроса window.initialState (мс). */
    private static final int POLL_INTERVAL_MS = 400;
    /** Сколько раз опрашивать initialState до фолбэка на outerHTML. */
    private static final int MAX_POLL_ATTEMPTS = 25;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Кэш: ключ ячейки -> фирма-id -> объект (сохранён навсегда). */
    private final Map<String, Map<String, MapFeature>> cellCache = new HashMap<>();
    /** Ячейки с уже запланированными загрузками. */
    private final Set<String> loadingCells = new HashSet<>();
    /** Очередь загрузок «ячейка+категория». */
    private final java.util.ArrayDeque<CellQuery> loadQueue = new java.util.ArrayDeque<>();
    private CellQuery activeLoad;

    /** Последний вьюпорт-запрос: ему пересылаются обновления по мере догрузки. */
    private Request lastRequest;

    private WebView webView;
    private boolean webViewReady;

    public TwoGisApiClient(Context context) {
        // WebView требует Activity-контекст (не getApplicationContext), иначе
        // не может создать собственный WebView-процесс/тему.
        ctx = context;
    }

    public interface Callback {
        void onResult(List<MapFeature> features);

        void onError(Exception e);
    }

    private static final class CellQuery {
        final String cellKey;
        final String query;
        final int page;

        CellQuery(String cellKey, String query) {
            this(cellKey, query, 0);
        }

        CellQuery(String cellKey, String query, int page) {
            this.cellKey = cellKey;
            this.query = query;
            this.page = page;
        }
    }

    private static final class Request {
        final double north, south, east, west;
        final Callback callback;

        Request(double north, double south, double east, double west, Callback callback) {
            this.north = north;
            this.south = south;
            this.east = east;
            this.west = west;
            this.callback = callback;
        }
    }

    private double cellStepDeg() {
        return 1.0 / COLS_PER_DEG;
    }

    private String cellKey(double lat, double lon) {
        double step = cellStepDeg();
        int li = (int) Math.floor(lat / step);
        int lo = (int) Math.floor(lon / step);
        return li + ":" + lo;
    }

    private double[] cellCenter(String key) {
        int p = key.indexOf(':');
        int li = Integer.parseInt(key.substring(0, p));
        int lo = Integer.parseInt(key.substring(p + 1));
        double step = cellStepDeg();
        return new double[]{(li + 0.5) * step, (lo + 0.5) * step};
    }

    /**
     * Запрашивает объекты в прямоугольнике [north, south, east, west].
     * <p>Сразу вызывает {@code callback.onResult} с закэшированными объектами в
     * этой области, а затем повторно — по мере догрузки новых ячеек (инкрементальное
     * пополнение при перемещении карты). Гарантируется вызов на главном потоке.</p>
     */
    public void fetchAsync(double north, double south, double east, double west,
                           Callback callback) {
        main.post(() -> {
            Request req = new Request(north, south, east, west, callback);
            lastRequest = req;

            Set<String> missing = new LinkedHashSet<>();
            List<MapFeature> cached = collect(req, missing);

            // Отдаём хотя бы то, что уже есть (в т.ч. пусто, если ни одной ячейки).
            callback.onResult(trim(cached));

            for (String key : missing) {
                enqueueCell(key);
            }
            pumpNext();
        });
    }

    private List<MapFeature> collect(Request req, Set<String> missingCells) {
        double step = cellStepDeg();
        int minLi = (int) Math.floor(req.south / step);
        int maxLi = (int) Math.floor(req.north / step);
        int minLo = (int) Math.floor(req.west / step);
        int maxLo = (int) Math.floor(req.east / step);
        List<MapFeature> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int li = minLi; li <= maxLi; li++) {
            for (int lo = minLo; lo <= maxLo; lo++) {
                String key = li + ":" + lo;
                Map<String, MapFeature> cell = cellCache.get(key);
                if (cell == null) {
                    if (!loadingCells.contains(key)) {
                        missingCells.add(key);
                    }
                    continue;
                }
                for (MapFeature f : cell.values()) {
                    if (addIfSeen(seen, f)) {
                        out.add(f);
                    }
                }
            }
        }
        return out;
    }

    private boolean addIfSeen(Set<String> seen, MapFeature f) {
        return seen.add(f.type + "|" + f.name + "|"
                + String.format(Locale.US, "%.5f,%.5f", f.lat, f.lon));
    }

    private List<MapFeature> trim(List<MapFeature> in) {
        if (in.size() <= RESULT_LIMIT) {
            return in;
        }
        return new ArrayList<>(in.subList(0, RESULT_LIMIT));
    }

    private void enqueueCell(String cellKey) {
        loadingCells.add(cellKey);
        int n = 0;
        for (String q : QUERIES.split("\\|")) {
            if (q.trim().isEmpty()) {
                continue;
            }
            loadQueue.add(new CellQuery(cellKey, q.trim()));
            n++;
            if (n >= MAX_PAGES_PER_CELL) {
                break;
            }
        }
    }

    private void pumpNext() {
        main.post(() -> {
            if (activeLoad != null || loadQueue.isEmpty()) {
                return;
            }
            activeLoad = loadQueue.poll();
            loadPage(activeLoad);
        });
    }

    /**
     * URL-путь страницы 2ГИС. Страницы нумеруются с «2»: первая — базовый
     * {@code /search/q}, вторая — {@code /search/q/page/2}, третья —
     * {@code /page/3}. Пути {@code /page/1} не существует (2ГИС редиректит на
     * базовый), поэтому его пропускаем — иначе каждая категория тратит ~10с на
     * ожидание несуществующей страницы и результаты дублируются.
     */
    private static String pagePath(String encQuery, int page) {
        if (page <= 0) {
            return "/search/" + encQuery;
        }
        return "/search/" + encQuery + "/page/" + (page + 1);
    }

    private void loadPage(CellQuery cq) {
        ensureWebView(() -> {
            double[] c = cellCenter(cq.cellKey);
            String qPath = pagePath(enc(cq.query), cq.page);
            String url = "https://2gis.ru" + qPath + "?m="
                    + String.format(Locale.US, "%.6f,%.6f/16", c[1], c[0]);
            Log.d(TAG, "loadPage query=" + cq.query + " page=" + cq.page + " url=" + url);
            webView.loadUrl(url);
        });
    }

    private void ensureWebView(Runnable run) {
        if (webViewReady) {
            run.run();
            return;
        }
        main.post(() -> {
            if (webViewReady) {
                run.run();
                return;
            }
            try {
                webView = new WebView(ctx);
                webView.getSettings().setJavaScriptEnabled(true);
                webView.getSettings().setLoadsImagesAutomatically(false);
                webView.getSettings().setUserAgentString(
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
                webView.addJavascriptInterface(new Bridge(), "TwoGisBridge");
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        // 2ГИС отдаёт анти-бот «museum»-страницу headless-WebView.
                        // На ней есть acceptRiskButton («Перейти на сайт»). Если она
                        // есть — кликаем её, затем дождёмся новой загрузки и извлечём
                        // initialState уже с реальной страницы.
                        maybeClickMuseumAccept(view);
                    }
                });
                webViewReady = true;
            } catch (Throwable t) {
                Log.w(TAG, "WebView init failed", t);
                webViewReady = false;
                finishActiveWithError(new Exception("WebView init failed", t));
                return;
            }
            run.run();
        });
    }

    /** Если 2ГИС отдал анти-бот «museum»-страницу — кликаем «Перейти на сайт». */
    private void maybeClickMuseumAccept(WebView view) {
        String js = "(()=>{var b=document.getElementById('acceptRiskButton');"
                + "if(b){b.click();return true;}return false;})();";
        view.evaluateJavascript(js, v -> {
            boolean clicked = "true".equals(v);
            if (clicked) {
                Log.d(TAG, "museum page: clicked acceptRiskButton");
            } else {
                // Не музей -> извлекаем initialState.
                extractInitialState();
            }
        });
    }

    private void extractInitialState() {
        // После обхода museum-страницы это реальная страница 2ГИС. Данные каталога
        // лежат в window.initialState. Сразу после onPageFinished JS-приложение ещё
        // не успело записать результаты ТЕКУЩЕЙ категории в initialState (они
        // приходят асинхронно): без ожидания все категории возвращают один и тот же
        // устаревший набор, и после первой категории добавление объектов = 0.
        // Поэтому опрашиваем initialState до тех пор, пока не появится ненулевой
        // profile категории — это дожидается перерисовки под текущий запрос без
        // фиксированной задержки, а по таймауту фолбэчимся на outerHTML.
        CellQuery cq = activeLoad;
        if (cq == null) {
            return;
        }
        pollInitialState(0, cq);
    }

    private void pollInitialState(int attempt, CellQuery cq) {
        if (webView == null) {
            finishActiveWithError(new Exception("2ГИС: WebView закрыт"));
            return;
        }
        if (cq == null) {
            return;
        }
        // Этот load уже завершён (activeLoad обнулён) или заменён следующим —
        // останавливаем устаревший поллинг, иначе он таймаутит и задерживает
        // следующую загрузку.
        if (activeLoad != cq) {
            return;
        }
        String qPath = pagePath(enc(cq.query), cq.page);
        // Запрашиваем: реальный URL страницы, загрузился ли profile категории и
        // число ключей. evaluateJavascript сериализует объект как JSON-объект.
        String js = "(()=>{try{"
                + "var nowUrl=window.location.href;"
                + "var s=window.initialState;"
                + "var p=null,n=0;"
                + "var globalCloseIdLen=-1;"
                + "var dataKeys=[],entityKeys=[],searchKeys=[],searchContextKeys=[],searchProfileKeys=[];"
                + "var searchLen=-1,ctxLen=-1,searchProfileLen=-1,searchProfileIsArr=false;"
                + "var searchProfileType='-',paginationType='-';"
                + "if(s&&typeof s==='object'){"
                + "if(s.data){dataKeys=Object.keys(s.data);}"
                + "if(s.data&&s.data.entity){entityKeys=Object.keys(s.data.entity);}"
                + "if(s.data&&s.data.search){searchKeys=Object.keys(s.data.search);searchLen=JSON.stringify(s.data.search).length;}"
                + "if(s.data&&s.data.searchContext){searchContextKeys=Object.keys(s.data.searchContext);ctxLen=JSON.stringify(s.data.searchContext).length;}"
                + "var sp=null; try{sp=s.data.search.profile;}catch(e){sp=null;}"
                + "if(sp!==undefined&&sp!==null){searchProfileType=(typeof sp);if(Array.isArray(sp)){searchProfileIsArr=true;searchProfileLen=sp.length;}else if(typeof sp==='object'){searchProfileLen=Object.keys(sp).length;searchProfileKeys=Object.keys(sp);}else{searchProfileLen=String(sp).length;}}"
                + "try{p=s.data.entity.profile;}catch(e){p=null;}"
                + "n=(p&&typeof p==='object')?Object.keys(p).length:0;"
                + "}"
                + "var searchProfileJson='';"
                + "var sp=null; try{sp=s.data.search.profile;}catch(e){sp=null;}"
                + "if(sp){try{searchProfileJson=JSON.stringify(sp).slice(0,1800);}catch(e){searchProfileJson='';}}"
                + "return {url:nowUrl,ready:n>0,n:n,dataKeys:dataKeys,entityKeys:entityKeys,searchKeys:searchKeys,searchLen:searchLen,searchContextKeys:searchContextKeys,ctxLen:ctxLen,searchProfileKeys:searchProfileKeys,searchProfileLen:searchProfileLen,searchProfileIsArr:searchProfileIsArr,searchProfileType:searchProfileType,searchProfileJson:searchProfileJson,raw:(n>0?JSON.stringify(s):'')};"
                + "}catch(e){return {url:window.location.href,ready:false,n:0,raw:''};}})();";
        webView.evaluateJavascript(js, value -> {
            PollState st = parsePollState(value);
            if (st == null) {
                // Парсинг не удался — пробуем ещё раз; по таймауту фолбэк.
                if (attempt + 1 >= MAX_POLL_ATTEMPTS) {
                    readOuterHtmlFallback();
                } else {
                    main.postDelayed(() -> pollInitialState(attempt + 1, cq),
                            POLL_INTERVAL_MS);
                }
                return;
            }
            boolean onRightPage = st.url != null && st.url.contains(qPath);
            if (!st.ready || !onRightPage) {
                if (attempt + 1 >= MAX_POLL_ATTEMPTS) {
                    Log.d(TAG, "initialState timeout: ready=" + st.ready
                            + " n=" + st.n + " url=" + st.url + " want=" + qPath);
                    readOuterHtmlFallback();
                } else {
                    main.postDelayed(() -> pollInitialState(attempt + 1, cq),
                            POLL_INTERVAL_MS);
                }
                return;
            }
            Log.d(TAG, "initialState профиль категории готов: n=" + st.n
                    + " url=" + st.url + " попытка=" + attempt
                    + " dataKeys=" + st.dataKeys + " entityKeys=" + st.entityKeys
                    + " searchKeys=" + st.searchKeys + " searchLen=" + st.searchLen
                    + " searchContextKeys=" + st.searchContextKeys + " ctxLen=" + st.ctxLen
                    + " searchProfileKeys=" + st.searchProfileKeys
                    + " searchProfileLen=" + st.searchProfileLen
                    + " searchProfileIsArr=" + st.searchProfileIsArr
                    + " searchProfileType=" + st.searchProfileType
                    + " searchProfileJson=" + st.searchProfileJson);
            parsePageHtml(st.raw);
        });
    }

    private static PollState parsePollState(String value) {
        try {
            Object v = new JSONTokener(value).nextValue();
            if (!(v instanceof JSONObject)) {
                return null;
            }
            JSONObject o = (JSONObject) v;
            PollState st = new PollState();
            st.url = o.optString("url", "");
            st.ready = o.optBoolean("ready", false);
            st.n = o.optInt("n", 0);
            st.raw = o.optString("raw", "");
            st.dataKeys = o.optString("dataKeys", "");
            st.entityKeys = o.optString("entityKeys", "");
            st.searchKeys = o.optString("searchKeys", "");
            st.searchLen = o.optInt("searchLen", -1);
            st.searchContextKeys = o.optString("searchContextKeys", "");
            st.ctxLen = o.optInt("ctxLen", -1);
            st.searchProfileKeys = o.optString("searchProfileKeys", "");
            st.searchProfileLen = o.optInt("searchProfileLen", -1);
            st.searchProfileIsArr = o.optBoolean("searchProfileIsArr", false);
            st.searchProfileType = o.optString("searchProfileType", "-");
            st.searchProfileJson = o.optString("searchProfileJson", "");
            return st;
        } catch (Exception e) {
            return null;
        }
    }

    private static final class PollState {
        String url;
        boolean ready;
        int n;
        String raw;
        String dataKeys = "";
        String entityKeys = "";
        String searchKeys = "";
        int searchLen = -1;
        String searchContextKeys = "";
        int ctxLen = -1;
        String searchProfileKeys = "";
        int searchProfileLen = -1;
        boolean searchProfileIsArr = false;
        String searchProfileType = "-";
        String searchProfileJson = "";
    }

    private void readOuterHtmlFallback() {
        if (webView == null) {
            finishActiveWithError(new Exception("2ГИС: WebView закрыт"));
            return;
        }
        String js = "(()=>{try{return document.documentElement.outerHTML;"
                + "}catch(e){return '';}})();";
        webView.evaluateJavascript(js, value -> parsePageHtml(value));
    }

    private void parsePageHtml(String value) {
        if (value == null || value.equals("null")) {
            finishActiveWithError(new Exception("2ГИС: HTML не получен"));
            return;
        }
        // evaluateJavascript возвращает результат, сериализованный как JSON-литерал
        // (обычно строку в кавычках с экранированием). Декодируем его корректно,
        // включая переносы строк, \" и \\, — иначе multiline-строки JSON ломают парсинг.
        Object decoded;
        try {
            decoded = new JSONTokener(value).nextValue();
        } catch (Exception e) {
            Log.w(TAG, "decode evaluateJavascript result failed", e);
            finishActiveWithError(e);
            return;
        }
        if (decoded instanceof JSONObject) {
            // window.initialState вернулся как объект (JSON.stringify не сработал/упростился).
            String raw = decoded.toString();
            String profile = extractProfileObject(raw,
                    raw.indexOf("\"data\":{\"entity\":{\"profile\":") + "\"data\":{\"entity\":{\"profile\":".length());
            if (profile == null) {
                finishActiveWithError(new Exception("2ГИС: в initialState нет profile"));
                return;
            }
            try {
                finishActive(new JSONObject(profile));
            } catch (Exception e) {
                Log.w(TAG, "parse profile (object) failed", e);
                finishActiveWithError(e);
            }
            return;
        }
        if (!(decoded instanceof String)) {
            finishActiveWithError(new Exception("2ГИС: неожиданный результат " + decoded));
            return;
        }
        String text = (String) decoded;
        // Если это уже JSON-текст initialState (начинается с '{') — вырезаем profile
        // прямо из него; иначе это HTML, из которого достаём initialState регексом.
        if (!text.isEmpty() && text.charAt(0) == '{') {
            String profile = extractProfileObject(text,
                    text.indexOf("\"data\":{\"entity\":{\"profile\":") + "\"data\":{\"entity\":{\"profile\":".length());
            if (profile == null) {
                finishActiveWithError(new Exception("2ГИС: в initialState нет profile"));
                return;
            }
            try {
                finishActive(new JSONObject(profile));
            } catch (Exception e) {
                Log.w(TAG, "parse initialState JSON failed", e);
                finishActiveWithError(e);
            }
            return;
        }
        String initialState = extractInitialStateJson(text);
        if (initialState == null) {
            finishActiveWithError(new Exception("2ГИС: initialState не найден"));
            return;
        }
        try {
            JSONObject profile = new JSONObject(initialState);
            finishActive(profile);
        } catch (Exception e) {
            Log.w(TAG, "parse profile failed", e);
            finishActiveWithError(e);
        }
    }

    /**
     * Вырезает JSON каталога из {@code var initialState = JSON.parse('...')}.
     * Возвращает сам объект профилей (data.entity.profile) или null.
     */
    private static String extractInitialStateJson(String html) {
        if (html == null) {
            return null;
        }
        int keyStart = html.indexOf("var initialState = JSON.parse('");
        if (keyStart < 0) {
            // ищем альтернативную запись без пробелов
            keyStart = html.indexOf("var initialState=JSON.parse('");
        }
        if (keyStart < 0) {
            // пробуем по window.initialState =
            keyStart = html.indexOf("initialState = JSON.parse('");
        }
        if (keyStart < 0) {
            return null;
        }
        int jsonStart = html.indexOf('\'', keyStart) + 1;
        int jsonEnd = findJsStringEnd(html, jsonStart);
        if (jsonEnd < 0) {
            return null;
        }
        String full = html.substring(jsonStart, jsonEnd);
        // full = {"bss":...} — там внутри data.entity.profile.
        int profStart = full.indexOf("\"data\":{\"entity\":{\"profile\":");
        if (profStart < 0) {
            return null;
        }
        // Возвращаем каталог как есть: вызывающий создаст JSONObject и возьмёт profile.
        // Но проще вернуть сам объект profile — найдём его балансом скобок.
        return extractProfileObject(full, profStart + "\"data\":{\"entity\":{\"profile\":".length());
    }

    /** Ищет конец JS-строки после jsonStart, учитывая экранированные кавычки. */
    private static int findJsStringEnd(String s, int from) {
        boolean esc = false;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                esc = false;
                continue;
            }
            if (c == '\\') {
                esc = true;
                continue;
            }
            if (c == '\'') {
                return i;
            }
        }
        return -1;
    }

    /** Вырезает сбалансированный JSON-объект profile, начиная с указателя на '{'. */
    private static String extractProfileObject(String s, int start) {
        if (start >= s.length() || s.charAt(start) != '{') {
            return null;
        }
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private void parseProfile(String value) {
        // Оставлен на случай, если initialState вдруг станет глобальной переменной.
        if (value == null || value.equals("null")) {
            finishActiveWithError(new Exception("2ГИС: initialState не найден"));
            return;
        }
        String json = value;
        if (json.length() >= 2 && json.startsWith("\"") && json.endsWith("\"")) {
            json = json.substring(1, json.length() - 1);
            json = json.replace("\\\\", "\\").replace("\\\"", "\"");
        }
        if (json.isEmpty()) {
            finishActiveWithError(new Exception("2ГИС: пустой initialState"));
            return;
        }
        try {
            JSONObject profile = new JSONObject(json);
            finishActive(profile);
        } catch (Exception e) {
            Log.w(TAG, "parse profile failed", e);
            finishActiveWithError(e);
        }
    }

    private void finishActive(JSONObject profile) {
        CellQuery cq = activeLoad;
        if (cq == null) {
            return;
        }
        Map<String, MapFeature> cell = cellCache.get(cq.cellKey);
        if (cell == null) {
            cell = new LinkedHashMap<>();
            cellCache.put(cq.cellKey, cell);
        }
        int added = 0;
        List<String> sample = new ArrayList<>();
        java.util.Iterator<String> ids = profile.keys();
        while (ids.hasNext()) {
            String fid = ids.next();
            JSONObject entry = profile.optJSONObject(fid);
            if (entry == null) {
                continue;
            }
            JSONObject data = entry.optJSONObject("data");
            if (data == null) {
                continue;
            }
            MapFeature f = parseFeature(data, fid);
            if (f != null && !cell.containsKey(fid)) {
                cell.put(fid, f);
                added++;
                if (sample.size() < 5) {
                    sample.add(f.name + " [" + (f.spokenType == null ? "" : f.spokenType) + "]");
                }
            }
        }
        Log.d(TAG, "finishActive query=" + cq.query + " cell=" + cq.cellKey
                + " added=" + added + " profileKeys=" + profile.length()
                + " sample=" + sample);
        // Дома, улицы и перекрёстки не отдаются 2ГИС отдельными объектами, но
        // их можно восстановить из адресов организаций: у записи есть
        // address.components[] с type=street_number {street, number}. Из набора
        // улиц строим дома (точки организаций) и перекрёстки (пары улиц).
        addDerivedFromProfile(cell, profile, cq.cellKey);
        activeLoad = null;
        // Пагинация: грузим следующую страницу этой же категории сразу после
// текущей (addFirst), пока не достигли MAX_PAGES_PER_QUERY. Иначе page=1/2
// встают в самый конец очереди после всех категорий и «Мясо шэф» — за
// пределами первой страницы — приходит слишком поздно. Эта категория не
// на экране, но именно она и нужна пользователю.
        if (cq.page + 1 < MAX_PAGES_PER_QUERY) {
            loadQueue.addFirst(new CellQuery(cq.cellKey, cq.query, cq.page + 1));
        }
        // Если для этой ячейки больше нет запланированных запросов — она
        // полностью загружена, снимаем флаг «в процессе».
        if (!hasQueuedFor(cq.cellKey)) {
            loadingCells.remove(cq.cellKey);
        }
        renotifyLast();
        pumpNext();
    }

    /**
     * Восстанавливает дома и улицы из адресов организаций, потому что 2ГИС
     * не отдаёт их отдельными объектами.
     *
     * <p>У каждой организации с адресом есть
     * {@code data.address.components[]} с {@code type=street_number} и полями
     * {@code street} (улица) и {@code number} (номер дома). Из них строим:</p>
     * <ul>
     *   <li>дом — точка организации (фирма стоит в этом доме), имя
     *       «{улица}, дом {номер}», тип {@code addr=housenumber};</li>
     *   <li>улицу — имя «{улица}», тип {@code street} (одна точка на каждую
     *       встреченную улицу, в точке первого адреса).</li>
     * </ul>
     *
     * <p>Перекрёстки отсюда не строим: у 2ГИС нет геометрии дорог, а точки
     * организаций разбросаны по разным улицам (реальные расстояния между
     * домами разных улиц — сотни метров), поэтому «пересечение» по точкам
     * даёт ложные результаты. Точки перекрёстков берутся из геометрии OSM.</p>
     */
    private void addDerivedFromProfile(Map<String, MapFeature> cell,
                                       JSONObject profile, String cellKey) {
        try {
            // Дом: key "street|number" -> точка организации.
            Map<String, MapFeature> houses = new LinkedHashMap<>();
            // Улица: key street -> первая точка + имя.
            Map<String, MapFeature> streets = new LinkedHashMap<>();

            java.util.Iterator<String> ids = profile.keys();
            while (ids.hasNext()) {
                String fid = ids.next();
                JSONObject entry = profile.optJSONObject(fid);
                if (entry == null) {
                    continue;
                }
                JSONObject data = entry.optJSONObject("data");
                if (data == null) {
                    continue;
                }
                JSONObject point = data.optJSONObject("point");
                if (point == null) {
                    continue;
                }
                double lat = point.optDouble("lat", Double.NaN);
                double lon = point.optDouble("lon", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon)) {
                    continue;
                }
                JSONObject addr = data.optJSONObject("address");
                if (addr == null) {
                    continue;
                }
                JSONArray comps = addr.optJSONArray("components");
                if (comps == null) {
                    continue;
                }
                for (int i = 0; i < comps.length(); i++) {
                    JSONObject comp = comps.optJSONObject(i);
                    if (comp == null) {
                        continue;
                    }
                    if (!"street_number".equals(comp.optString("type", ""))) {
                        continue;
                    }
                    String street = comp.optString("street", "").trim();
                    String number = comp.optString("number", "").trim();
                    if (street.isEmpty() && number.isEmpty()) {
                        continue;
                    }
                    // Дом: имя «улица, дом номер».
                    String houseName = street.isEmpty() ? ("Дом " + number)
                            : (street + ", дом " + number);
                    String houseKey = street + "|" + number;
                    if (!houses.containsKey(houseKey)) {
                        MapFeature h = new MapFeature(lat, lon, houseName,
                                "addr=housenumber", houseName, "дом", null, null);
                        houses.put(houseKey, h);
                    }
                    // Улица: держим первую точку (для направления не нужна,
                    // геометрии нет — лишь точка-подпись).
                    if (!street.isEmpty() && !streets.containsKey(street)) {
                        MapFeature s = new MapFeature(lat, lon, street,
                                "street", street, "улица", null, null);
                        streets.put(street, s);
                    }
                }
            }

            // Кладём дома и улицы в кэш ячейки.
            for (MapFeature h : houses.values()) {
                cell.putIfAbsent("addr:" + h.name, h);
            }
            for (MapFeature s : streets.values()) {
                cell.putIfAbsent("street:" + s.name, s);
            }
        } catch (Exception e) {
            Log.w(TAG, "addDerivedFromProfile failed for " + cellKey, e);
        }
    }

    private boolean hasQueuedFor(String cellKey) {
        for (CellQuery cq : loadQueue) {
            if (cq.cellKey.equals(cellKey)) {
                return true;
            }
        }
        return false;
    }

    private void finishActiveWithError(Exception e) {
        CellQuery cq = activeLoad;
        activeLoad = null;
        if (cq != null) {
            loadingCells.remove(cq.cellKey);
        }
        if (lastRequest != null && lastRequest.callback != null) {
            lastRequest.callback.onError(e);
        }
        pumpNext();
    }

    /** Пересылает текущему вьюпорт-запросу полный набор объектов в его области. */
    private void renotifyLast() {
        if (lastRequest == null || lastRequest.callback == null) {
            return;
        }
        Set<String> ignore = new LinkedHashSet<>();
        List<MapFeature> features = collect(lastRequest, ignore);
        lastRequest.callback.onResult(trim(features));
    }

    private MapFeature parseFeature(JSONObject data, String id) {
        try {
            JSONObject point = data.optJSONObject("point");
            if (point == null) {
                return null;
            }
            double lat = point.optDouble("lat", Double.NaN);
            double lon = point.optDouble("lon", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) {
                return null;
            }
            String name = data.optString("name", "").trim();
            if (name.isEmpty()) {
                name = id;
            }
            String poi = data.optString("poi_category", "");
            String typeKey = "2gis:" + (poi.isEmpty() ? "org" : poi);
            // Естественный озвучиваемый тип: берём имя первичной рубрики
            // (там уже по-русски, напр. «Кафе»), иначе — из poi_category.
            String spokenType = primaryRubricName(data);
            if (spokenType.isEmpty()) {
                spokenType = CategoryNames.humanize(typeKey);
            }
            String address = data.optString("address_name", "");
            Map<String, String> tags = new HashMap<>();
            tags.put("source", "2gis");
            tags.put("firm_id", id);
            if (!poi.isEmpty()) {
                tags.put("2gis_category", poi);
            }
            if (!address.isEmpty()) {
                tags.put("address", address);
            }
            append2gisDetails(data, tags);
            return new MapFeature(lat, lon, name, typeKey, name, spokenType, null, tags);
        } catch (Exception e) {
            Log.w(TAG, "parse feature failed: " + id, e);
            return null;
        }
    }

    /**
     * Выдёргивает из профиля 2ГИС максимум доступных деталей в {@code tags},
     * чтобы «Показать подробности» показывало для 2ГИС так же много, как для OSM:
     * бренд/сеть, рубрики, часы работы, описание, рейтинг/отзывы, фотографии,
     * дополнительные атрибуты (кухня, услуги, оплата и т.д.).
     */
    private static void append2gisDetails(JSONObject data, Map<String, String> tags) {
        // Бренд и расширение: name_ex.primary — «Сбербанк», extension — «отделение».
        JSONObject nameEx = data.optJSONObject("name_ex");
        if (nameEx != null) {
            String primary = nameEx.optString("primary", "").trim();
            String ext = nameEx.optString("extension", "").trim();
            if (!primary.isEmpty()) {
                tags.put("brand", primary);
            }
            if (!ext.isEmpty()) {
                tags.put("extension", ext);
            }
        }
        // Сеть/организация-владелец с количеством филиалов.
        JSONObject org = data.optJSONObject("org");
        if (org != null) {
            String orgName = org.optString("name", "").trim();
            if (!orgName.isEmpty()) {
                tags.put("org", orgName);
            }
            if (org.has("branch_count")) {
                tags.put("branch_count", String.valueOf(org.optInt("branch_count", 0)));
            }
        }
        // Рубрики: primary — тип, additional — дополнительные направления.
        StringBuilder rub = new StringBuilder();
        JSONArray rubrics = data.optJSONArray("rubrics");
        if (rubrics == null) {
            rubrics = data.optJSONArray("rubrics");
        }
        if (rubrics != null) {
            int n = rubrics.length();
            for (int i = 0; i < n; i++) {
                JSONObject r = rubrics.optJSONObject(i);
                if (r == null) {
                    continue;
                }
                String rn = r.optString("name", "").trim();
                if (rn.isEmpty()) {
                    continue;
                }
                if ("primary".equals(r.optString("kind", ""))) {
                    tags.putIfAbsent("2gis_primary_rubric", rn);
                } else {
                    if (rub.length() > 0) {
                        rub.append(", ");
                    }
                    rub.append(rn);
                }
            }
        }
        if (rub.length() > 0) {
            tags.put("additional_rubrics", rub.toString());
        }
        // Часы работы по дням недели (2ГИС отдаёт schedule {Пн:{working_hours:[{from,to}]}}).
        StringBuilder hours = new StringBuilder();
        JSONObject schedule = data.optJSONObject("schedule");
        if (schedule != null) {
            List<String> days = new ArrayList<>(java.util.Arrays.asList(
                    "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"));
            for (String d : days) {
                JSONObject day = schedule.optJSONObject(d);
                if (day == null) {
                    continue;
                }
                JSONArray wh = day.optJSONArray("working_hours");
                if (wh == null || wh.length() == 0) {
                    continue;
                }
                String range = null;
                for (int i = 0; i < wh.length(); i++) {
                    JSONObject w = wh.optJSONObject(i);
                    if (w == null) {
                        continue;
                    }
                    String from = w.optString("from", "");
                    String to = w.optString("to", "");
                    if (range == null) {
                        range = from + "–" + to;
                    } else {
                        range = range + "; " + from + "–" + to;
                    }
                }
                if (range != null) {
                    if (hours.length() > 0) {
                        hours.append(", ");
                    }
                    hours.append(DAY_HUMAN.get(d)).append(" ").append(range);
                }
            }
            if (schedule.optBoolean("is_24x7", false)) {
                tags.put("opening_hours", "круглосуточно");
            } else if (hours.length() > 0) {
                tags.put("opening_hours", hours.toString());
            }
        }
        // Описание.
        JSONObject summary = data.optJSONObject("summary");
        if (summary != null) {
            String text = summary.optString("text", "").trim();
            if (!text.isEmpty()) {
                tags.put("description", text);
            }
        }
        // Рейтинг и число отзывов.
        JSONObject reviews = data.optJSONObject("reviews");
        if (reviews != null) {
            double rating = reviews.optDouble("general_rating", Double.NaN);
            int count = reviews.optInt("general_review_count", 0);
            if (!Double.isNaN(rating)) {
                tags.put("rating", String.format(Locale.US, "%.1f", rating));
            }
            if (count > 0) {
                tags.put("review_count", String.valueOf(count));
            }
        }
        // Количество фотографий (суммируем по всем альбомам).
        JSONArray ext = data.optJSONArray("external_content");
        if (ext != null) {
            int photoCount = 0;
            for (int i = 0; i < ext.length(); i++) {
                JSONObject a = ext.optJSONObject(i);
                if (a != null) {
                    photoCount += a.optInt("count", 0);
                }
            }
            if (photoCount > 0) {
                tags.put("photo_count", String.valueOf(photoCount));
            }
        }
        // Ближайшее метро (расстояние в метрах).
        JSONObject links = data.optJSONObject("links");
        if (links != null) {
            JSONArray metro = links.optJSONArray("nearest_metro");
            if (metro != null && metro.length() > 0) {
                int d = metro.optJSONObject(0) == null ? 0 : metro.optJSONObject(0).optInt("distance", 0);
                if (d > 0) {
                    tags.put("nearest_metro", d + " м");
                }
            }
        }
        // Дополнительные атрибуты (кухня, услуги, оплата, вместимость и т.д.) —
        // объединяем по группам, отбрасывая «Данные актуальны».
        StringBuilder attrs = new StringBuilder();
        JSONArray groups = data.optJSONArray("attribute_groups");
        if (groups != null) {
            for (int g = 0; g < groups.length(); g++) {
                JSONObject grp = groups.optJSONObject(g);
                if (grp == null) {
                    continue;
                }
                JSONArray attrsArr = grp.optJSONArray("attributes");
                if (attrsArr == null) {
                    continue;
                }
                for (int i = 0; i < attrsArr.length(); i++) {
                    JSONObject a = attrsArr.optJSONObject(i);
                    if (a == null) {
                        continue;
                    }
                    String an = a.optString("name", "").trim();
                    if (an.isEmpty() || "Данные актуальны".equals(an)) {
                        continue;
                    }
                    if (attrs.length() > 0) {
                        attrs.append(", ");
                    }
                    attrs.append(an);
                }
            }
        }
        if (attrs.length() > 0) {
            tags.put("attributes", attrs.toString());
        }
    }

    /** Короткие названия дней недели для расписания 2ГИС. */
    private static final Map<String, String> DAY_HUMAN = new HashMap<>();
    static {
        DAY_HUMAN.put("Mon", "пн");
        DAY_HUMAN.put("Tue", "вт");
        DAY_HUMAN.put("Wed", "ср");
        DAY_HUMAN.put("Thu", "чт");
        DAY_HUMAN.put("Fri", "пт");
        DAY_HUMAN.put("Sat", "сб");
        DAY_HUMAN.put("Sun", "вс");
    }

    private static String primaryRubricName(JSONObject data) {
        JSONArray rubrics = data.optJSONArray("rubrics");
        if (rubrics == null) {
            return "";
        }
        for (int i = 0; i < rubrics.length(); i++) {
            JSONObject r = rubrics.optJSONObject(i);
            if (r == null) {
                continue;
            }
            if ("primary".equals(r.optString("kind", ""))) {
                String n = r.optString("name", "").trim();
                if (!n.isEmpty()) {
                    return n;
                }
            }
        }
        // Нет явно primary — берём первую непустую рубрику.
        for (int i = 0; i < rubrics.length(); i++) {
            JSONObject r = rubrics.optJSONObject(i);
            if (r == null) {
                continue;
            }
            String n = r.optString("name", "").trim();
            if (!n.isEmpty()) {
                return n;
            }
        }
        return "";
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    /** Мост JS->Java (зарезервирован; данные идут через evaluateJavascript). */
    private class Bridge {
        @JavascriptInterface
        public void onDone(String payload) {
        }
    }
}