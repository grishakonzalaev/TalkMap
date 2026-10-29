package ru.talkmap.app;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Marker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Карта, доступная незрячим пользователям.
 *
 * <p>Каждый загруженный объект OSM становится виртуальным узлом
 * (AccessibilityNodeProvider): TalkBack видит его как отдельный элемент,
 * а в режиме обводки пальцем экрана скринридер озвучивает объект под пальцем
 * (TYPE_VIEW_HOVER_ENTER по виртуальному узлу). Дополнительно при смене
 * объекта подаётся короткий тактильный сигнал (вибрация).</p>
 *
 * <p>Отдельно обрабатываются именованные улицы: если палец находится на
 * проезжей части (близко к геометрии дороги), озвучивается улица с
 * направлением — например, «Улица Станиславского с севера на юг».</p>
 */
public class AccessibleMapView extends MapView {

    private static final String TAG = "AccessibleMapView";
    private static final String EMPTY_HINT = "Свободно. Ведите пальцем дальше.";

    public interface Listener {
        void onStatus(int resId);

        void onCount(int count);

        /** Кнопка «моё местоположение»: повторить запрос свежего фикса. */
        void onLocationRequested();
    }

    private final List<MapFeature> features = new ArrayList<>();
    private final List<OsmApiClient.RoadInfo> roads = new ArrayList<>();
    private OsmApiClient api;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Point tmpPoint = new Point();
    private final int[] screenLocation = new int[2];

    private Listener listener;
    private Marker locationMarker;
    private GeoPoint userLocation;
    /** Виртуальный узел «Вы здесь» в точке местоположения (последний в features). */
    private MapFeature hereFeature;
    /** Фраза ближайшего объекта, если он совпадает с точкой «Вы здесь». */
    private String hereOverlapPhrase;
    /** Стартовый масштаб: 16 — видно улицы вокруг, а не один дом. */
    private static final double DEFAULT_ZOOM = 16.0;
    /** Предел радиуса загрузки: больше не влезает в лимиты OSM API по времени. */
    private static final double MAX_FETCH_RADIUS_METERS = 6000;
    private double userZoom = 16.0;
    private boolean firstFixDone = false;
    private long lastReloadAt = 0;
    private final Runnable reloadRunnable = new Runnable() {
        @Override
        public void run() {
            reloadFeatures();
        }
    };
    private int lastVirtualNode = -1;
    private long lastSpokenAt = 0;
    private String lastSpoken = "";
    private String lastStreetPhrase = null;
    /** Момент последней озвучки улицы — перехват клика кнопки как выбора улицы. */
    private long lastStreetPhraseAt = 0;
    /** Имя улицы из последней озвученной фразы (без слов «с севера на юг»). */
    private String lastStreetName = null;
    /** Время первого перехваченного клика улицы — гашение второго клика пары TalkMan. */
    private long lastConsumedStreetClickAt = 0;
    /** Следование по улице: имя улицы, по которой едет карта. */
    private String followedStreetName = null;
    /** Сегменты (way) следуемой улицы. */
    private final List<OsmApiClient.RoadInfo> followedSegments = new ArrayList<>();
    /** Шаг одного жеста вдоль улицы, метры. */
    private static final double FOLLOW_STEP_METERS = 350.0;
    /** Радиус стыковки двух сегментов одной улицы, метры. */
    private static final double CONTINUATION_TOLERANCE_METERS = 150.0;
    /** Минимальный вектор жеста (px), чтобы считать его скроллом. */
    private static final float FOLLOW_MIN_GESTURE_PX = 60f;
    /** Косинус угла «вдоль улицы»: меньше — считаем поперёк. */
    private static final double FOLLOW_ALIGN_COS = 0.5;
    /** Состояние жеста в режиме следования. */
    private float followTouchLastX;
    private float followTouchLastY;
    private float followTouchSumX;
    private float followTouchSumY;
    private boolean followTouchActive;
    /** Жест уже обработан (один шаг на ACTION_UP), до конца жеста не повторяем. */
    private boolean followGestureHandled;
    /** Последняя точка обводки — для клика по проезжей части. */
    private float lastHoverX = -1, lastHoverY = -1;
    /** Последний одиночный тап — fallback, если hover-событие не дошло. */
    private long lastTapUpTime;
    private float lastTapX = -1, lastTapY = -1;
    private float lastTapDownX = -1, lastTapDownY = -1;
    private static final long DOUBLE_TAP_MS = 400L;
    /** Окно пары кликов TalkMan: двойное нажатие может длиться до ~1.5 c. */
    private static final long DOUBLE_CLICK_MS = 1500L;
    /** Окно, в котором клик по кнопке считается выбором озвученной улицы.
     *  Незрячему пользователю нужно время, чтобы убрать палец с карты,
     *  нащупать кнопку/место и сделать двойной тап. 15 секунд покрывает
     *  этот интервал без ложных срабатываний (после выбора окно закрывается). */
    private static final long STREET_SELECTION_MS = 15000L;
    private static final float DOUBLE_TAP_DIST_PX = 100f;
    /**
     * Модифицированный TalkBack (TalkMan) шлёт двойное нажатие как ДВА
     * ACTION_CLICK подряд. Пара кликов по одному узлу = один логический клик.
     */
    private int lastClickNode = Integer.MIN_VALUE;
    private long lastClickAt = 0;

    public AccessibleMapView(Context context) {
        super(context);
        init();
    }

    public AccessibleMapView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    /** Подстраховка: если пользователь не двигал карту, но layout прошёл — запускаем загрузку. */
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed && getWidth() > 0 && features.isEmpty()) {
            Log.d(TAG, "onLayout -> scheduleReload");
            scheduleReload();
        }
    }

    private void init() {
        api = new OsmApiClient(getContext());
        setMultiTouchControls(true);
        setMaxZoomLevel(19.0);
        // Ниже 15 видимая высота экрана (~11 км на этом устройстве) больше
        // области, которую реально отдаёт OSM API, и сверху/снизу остаётся
        // пустая полоса. Поэтому ниже 15 не опускаемся.
        setMinZoomLevel(15.0);
        setTilesScaledToDpi(true);
        // Явно включаем view: с выключенным MapView скринридер не получает
        // hover-события и виртуальные узлы.
        setEnabled(true);
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        addMapListener(new MapListener() {
            @Override
            public boolean onScroll(ScrollEvent event) {
                scheduleReload();
                return true;
            }

            @Override
            public boolean onZoom(ZoomEvent event) {
                scheduleReload();
                return true;
            }
        });
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setUserLocation(GeoPoint point) {
        this.userLocation = point;
        if (locationMarker == null) {
            locationMarker = new Marker(this);
            locationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            try {
                locationMarker.setIcon(getResources().getDrawable(R.drawable.dot_location));
            } catch (Exception e) {
                Log.w(TAG, "no location icon", e);
            }
            locationMarker.setTitle("Вы здесь");
            locationMarker.setInfoWindow(null);
            setEnabled(true);
            setClickable(true);
            setFocusable(true);
            getOverlays().add(locationMarker);
        }
        locationMarker.setPosition(point);
        // При первом определении показываем окрестность (16), а не 17 —
        // иначе карта выглядит слишком приближённой и улицы не видно вокруг.
        if (!firstFixDone) {
            firstFixDone = true;
            userZoom = DEFAULT_ZOOM;
            getController().setZoom(userZoom);
        }
        getController().animateTo(point);
        invalidate();
        // Держим виртуальный узел «Вы здесь» в актуальной точке сразу,
        // не дожидаясь перезагрузки объектов (scheduleReload асинхронный).
        refreshHereFeature();
        Log.d(TAG, "setUserLocation -> " + point.getLatitude() + "," + point.getLongitude());
        scheduleReload();
    }

    /** Кнопка «моё местоположение»: запрашивает свежий фикс и двигает карту. */
    public void centerOnUser() {
        // Не анимируем к сохранённой точке: она могла быть получена при
        // запуске приложения, а пользователь уже переместился. Сначала
        // запрашиваем свежий фикс; карта поедет к нему в setUserLocation.
        if (userLocation != null) {
            announceForAccessibility("Запрашиваю ваше текущее местоположение…");
        } else {
            announceForAccessibility("Ваше местоположение ещё не определено.");
        }
        if (listener != null) {
            Log.d(TAG, "centerOnUser -> request fresh fix");
            listener.onLocationRequested();
        }
    }

    public void zoomIn() {
        getController().zoomIn();
        scheduleReload();
    }

    public void zoomOut() {
        getController().zoomOut();
        scheduleReload();
    }

    public void resetZoom() {
        // Только возвращаем масштаб; центр карты не трогаем,
        // иначе «возврат масштаба» неожиданно переносит к местоположению.
        IGeoPoint before = getMapCenter();
        getController().setZoom(userZoom);
        Log.d(TAG, "resetZoom center=" + (before == null ? "null" : before.getLatitude() + "," + before.getLongitude())
                + " zoom=" + getZoomLevelDouble());
        scheduleReload();
        announceForAccessibility("Масштаб возвращён к исходному.");
    }

    /**
     * Была ли озвучена улица в последние 4 секунды. TalkMan после озвучки
     * улицы держит фокус на последней нажатой кнопке (зум/местоположение),
     * виртуальные узлы он игнорирует, поэтому его двойной тап уходит кнопке.
     * Если улица только что озвучена — клик любой кнопки считаем выбором
     * этой улицы, а не нажатием кнопки.
     */
    public boolean isStreetRecentlySpoken() {
        return lastStreetName != null && lastStreetPhraseAt > 0
                && System.currentTimeMillis() - lastStreetPhraseAt <= STREET_SELECTION_MS;
    }

    /**
     * Обработка клика кнопки (зум/местоположение) из MainActivity. Возвращает
     * true, если клик уже обработан как выбор улицы (или как второй клик пары
     * TalkMan) — кнопку выполнять не нужно.
     */
    public boolean handleStreetSelectionClick() {
        long now = System.currentTimeMillis();
        // TalkMan шлёт «двойное нажатие» как два ACTION_CLICK подряд.
        // Первый клик пары мы превращаем в выбор улицы, второй обязан быть
        // проглочен, иначе после включения следования зум сработает снова.
        if (lastConsumedStreetClickAt > 0
                && now - lastConsumedStreetClickAt <= DOUBLE_CLICK_MS) {
            lastConsumedStreetClickAt = now;
            Log.d(TAG, "button pair click consumed");
            return true;
        }
        if (!isStreetRecentlySpoken()) {
            return false;
        }
        lastConsumedStreetClickAt = now;
        boolean ok = followLastStreet();
        Log.d(TAG, "street selection click ok=" + ok);
        // Даже если улица не загрузилась, кнопку не выполняем — пользователь
        // целился в улицу, а не в зум.
        return true;
    }

    /** Включает следование по последней озвученной улице. */
    public boolean followLastStreet() {
        if (!isStreetRecentlySpoken() || lastStreetName == null) {
            return false;
        }
        String name = lastStreetName;
        // Якорь — точка на улице, где палец был при обводке (а не GPS-позиция:
        // она может быть в стороне, и карта уехала бы к ней).
        GeoPoint anchor = null;
        if (lastHoverX >= 0 && lastHoverY >= 0) {
            for (OsmApiClient.RoadInfo r : roads) {
                if (!name.equals(r.name)) {
                    continue;
                }
                IGeoPoint proj = projectToRoad(r, lastHoverX, lastHoverY);
                if (proj != null) {
                    anchor = new GeoPoint(proj.getLatitude(), proj.getLongitude());
                    break;
                }
            }
        }
        if (anchor == null) {
            anchor = userLocation;
        }
        if (anchor == null) {
            IGeoPoint center = getMapCenter();
            if (center != null) {
                anchor = new GeoPoint(center.getLatitude(), center.getLongitude());
            }
        }
        boolean ok = tryFollowStreetName(name, anchor);
        Log.d(TAG, "followLastStreet name=" + name + " ok=" + ok);
        if (ok) {
            // Один выбор = одно окно перехвата. После включения следования
            // кнопки снова работают как обычно, иначе TalkMan не сможет
            // изменить масштаб, пока мы не уедем с улицы.
            lastStreetName = null;
            lastStreetPhraseAt = 0;
        }
        return ok;
    }

    public void showNoLocation() {
        scheduleReload();
    }

    /** Отложенная перезагрузка объектов: при перетаскивании карты не спамим Overpass. */
    public void scheduleReload() {
        long now = System.currentTimeMillis();
        if (now - lastReloadAt < 700) {
            handler.removeCallbacks(reloadRunnable);
            handler.postDelayed(reloadRunnable, 1100 - (now - lastReloadAt));
            lastReloadAt = now + 1000;
            return;
        }
        lastReloadAt = now;
        handler.removeCallbacks(reloadRunnable);
        handler.postDelayed(reloadRunnable, 1100);
    }

    private void reloadFeatures() {
        IGeoPoint center = getMapCenter();
        if (center == null) {
            return;
        }
        if (getWidth() <= 0 || getHeight() <= 0) {
            // Карта ещё не получила размер на экране (первый layout).
            // Через мгновение попробуем снова — иначе запрос не стартует.
            handler.removeCallbacks(reloadRunnable);
            handler.postDelayed(reloadRunnable, 300);
            return;
        }
        double radius = Math.min(visibleRadiusMeters(), MAX_FETCH_RADIUS_METERS);
        Log.d(TAG, "reloadFeatures center=" + center.getLatitude() + "," + center.getLongitude()
                + " zoom=" + getZoomLevelDouble() + " radius=" + (int) radius);
        if (listener != null) {
            listener.onStatus(R.string.status_loading);
        }
        final double lat = center.getLatitude();
        final double lon = center.getLongitude();
        api.fetchAsync(lat, lon, radius, new OsmApiClient.Callback() {
            @Override
            public void onResult(final List<MapFeature> newFeatures,
                                 final List<OsmApiClient.RoadInfo> newRoads) {
                // Страховка от «пустого» частичного ответа: если сервер вернул
                // дороги, но ни одного обычного объекта (remains только синтезированные
                // перекрёстки), не стираем уже загруженные объекты — обновляем только
                // геометрию улиц. Иначе объекты внезапно «пропадают», а перекрёстки
                // продолжают озвучиваться.
                if (countRealFeatures(newFeatures) == 0 && countRealFeatures(features) > 0) {
                    Log.w(TAG, "partial/empty result: keeping " + features.size()
                            + " features, updating roads=" + newRoads.size());
                    roads.clear();
                    roads.addAll(newRoads);
                    refreshFollowedSegments();
                    invalidate();
                } else {
                    setFeatures(newFeatures, newRoads);
                }
                if (listener != null) {
                    // «Вы здесь» — не объект карты, не считаем его.
                    int shown = features.size() - (hereFeature != null ? 1 : 0);
                    listener.onCount(shown);
                }
            }

            @Override
            public void onError(Exception e) {
                Log.w(TAG, "reload failed", e);
                if (listener != null) {
                    listener.onStatus(R.string.status_network_error);
                }
                // Overpass нестабилен: пробуем снова, пока объекты не загрузятся.
                handler.removeCallbacks(reloadRunnable);
                handler.postDelayed(reloadRunnable, 15000);
            }
        });
    }

    /**
     * Радиус загрузки: половина диагонали видимого прямоугольника плюс запас,
     * чтобы объекты покрывали весь экран, а не только окрестность центра.
     */
    private double visibleRadiusMeters() {
        IGeoPoint center = getMapCenter();
        if (center == null || getWidth() <= 0 || getHeight() <= 0) {
            return 1500;
        }
        Projection p = getProjection();
        if (p == null) {
            return 1500;
        }
        IGeoPoint topLeft = p.fromPixels(0, 0);
        IGeoPoint bottomRight = p.fromPixels(getWidth(), getHeight());
        if (topLeft == null || bottomRight == null) {
            return 1500;
        }
        double dLatM = Math.abs(topLeft.getLatitude() - bottomRight.getLatitude()) * 110574.0;
        double dLonM = Math.abs(bottomRight.getLongitude() - topLeft.getLongitude())
                * 111320.0 * Math.cos(Math.toRadians(center.getLatitude()));
        double halfDiagonal = Math.hypot(dLatM, dLonM) / 2.0;
        // Запас 40%: карта догружает окрестность, пока пользователь сдвигает её.
        return Math.max(600, halfDiagonal * 1.4);
    }

    /** Количество объектов, не являющихся синтезированными перекрёстками. */
    private int countRealFeatures(List<MapFeature> list) {
        int n = 0;
        for (MapFeature f : list) {
            if (!"junction".equals(f.type) && !"here".equals(f.type)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Держит виртуальный узел «Вы здесь» в точке местоположения.
     * Узел всегда последний в features, чтобы не сдвигать индексы объектов.
     */
    private void refreshHereFeature() {
        if (hereFeature != null) {
            int idx = features.indexOf(hereFeature);
            if (idx >= 0) {
                features.remove(idx);
            }
        }
        if (userLocation == null) {
            hereFeature = null;
            hereOverlapPhrase = null;
            return;
        }
        hereOverlapPhrase = computeHereOverlap();
        hereFeature = new MapFeature(userLocation.getLatitude(), userLocation.getLongitude(),
                "Вы здесь", "here", "Вы здесь", "", null);
        features.add(hereFeature);
    }

    /**
     * Фраза ближайшего объекта в точке «Вы здесь», если он совпадает с ней.
     * Возвращается «Название, тип» без «Вы здесь» — хвост добавляет phraseFor.
     */
    private String computeHereOverlap() {
        if (userLocation == null) {
            return null;
        }
        getProjection().toPixels(
                new GeoPoint(userLocation.getLatitude(), userLocation.getLongitude()), tmpPoint);
        float x = tmpPoint.x;
        float y = tmpPoint.y;
        MapFeature obj = nearestJunction(x, y);
        if (obj == null) {
            obj = nearestNamedFeature(x, y);
        }
        if (obj == null) {
            obj = nearestAddressOrSignal(x, y);
        }
        if (obj == null) {
            return null;
        }
        return phraseFor(obj);
    }

    private void setFeatures(List<MapFeature> newFeatures, List<OsmApiClient.RoadInfo> newRoads) {
        features.clear();
        features.addAll(newFeatures);
        roads.clear();
        roads.addAll(newRoads);
        // Узел «Вы здесь» в точке местоположения пользователя.
        refreshHereFeature();
        // Если пользователь следует по улице — обновляем её геометрию,
        // иначе при перезагрузке цепочка может остаться от старой загрузки.
        refreshFollowedSegments();
        lastVirtualNode = -1;
        invalidate();
        // Сообщаем TalkBack, что содержимое изменилось.
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    // ------------------------------------------------------------------
    // Поиск объекта под пальцем: сначала точечные объекты, затем улица,
    // на проезжей части которой находится палец.
    // ------------------------------------------------------------------
    private MapFeature featureNear(float x, float y) {
        // Точка «Вы здесь»: если палец на ней, озвучиваем (при совпадении с
        // объектом — «объект, вы здесь»). Проверяем первой, чтобы составная
        // фраза не перебивалась отдельным объектом на том же месте.
        if (hereFeature != null) {
            getProjection().toPixels(
                    new GeoPoint(hereFeature.lat, hereFeature.lon), tmpPoint);
            float hx = tmpPoint.x - x;
            float hy = tmpPoint.y - y;
            float hr = hitRadiusPx();
            if (hx * hx + hy * hy <= hr * hr) {
                return hereFeature;
            }
        }
        // Сначала перекрёсток: на многих перекрёстках вплотную стоят киоски,
        // остановки и магазины, которые «перебивали» перекрёсток. В его центре
        // пользователь должен слышать перекрёсток, а объекты — чуть в стороне.
        MapFeature f = nearestJunction(x, y);
        if (f != null) {
            return f;
        }
        f = nearestNamedFeature(x, y);
        if (f != null) {
            return f;
        }
        return nearestAddressOrSignal(x, y);
    }

    private MapFeature nearestJunction(float x, float y) {
        if (getWidth() <= 0 || getHeight() <= 0 || features.isEmpty()) {
            return null;
        }
        float radius = hitRadiusPx() * 1.25f; // перекрёсток чуть «шире» точки
        MapFeature best = null;
        float bestDist = radius * radius;
        for (MapFeature f : features) {
            if (f.type == null || !"junction".equals(f.type)) {
                continue;
            }
            GeoPoint gp = new GeoPoint(f.lat, f.lon);
            getProjection().toPixels(gp, tmpPoint);
            float dx = tmpPoint.x - x;
            float dy = tmpPoint.y - y;
            float d = dx * dx + dy * dy;
            if (d < bestDist) {
                bestDist = d;
                best = f;
            }
        }
        return best;
    }

    private MapFeature nearestNamedFeature(float x, float y) {
        if (getWidth() <= 0 || getHeight() <= 0 || features.isEmpty()) {
            return null;
        }
        float radius = hitRadiusPx();
        MapFeature best = null;
        float bestDist = radius * radius;
        for (MapFeature f : features) {
            if (f.type == null || f.type.startsWith("addr=") || f.spokenType == null
                    || f.spokenType.isEmpty() || "улица".equals(f.spokenType)) {
                continue;
            }
            GeoPoint gp = new GeoPoint(f.lat, f.lon);
            getProjection().toPixels(gp, tmpPoint);
            float dx = tmpPoint.x - x;
            float dy = tmpPoint.y - y;
            float d = dx * dx + dy * dy;
            if (d < bestDist) {
                bestDist = d;
                best = f;
            }
        }
        return best;
    }

    private MapFeature nearestAddressOrSignal(float x, float y) {
        if (getWidth() <= 0 || getHeight() <= 0 || features.isEmpty()) {
            return null;
        }
        float radius = hitRadiusPx();
        MapFeature best = null;
        float bestDist = radius * radius;
        for (MapFeature f : features) {
            if (f.type == null || !(f.type.startsWith("addr=")
                    || "highway=traffic_signals".equals(f.type)
                    || "highway=crossing".equals(f.type)
                    || "junction".equals(f.type))) {
                continue;
            }
            GeoPoint gp = new GeoPoint(f.lat, f.lon);
            getProjection().toPixels(gp, tmpPoint);
            float dx = tmpPoint.x - x;
            float dy = tmpPoint.y - y;
            float d = dx * dx + dy * dy;
            if (d < bestDist) {
                bestDist = d;
                best = f;
            }
        }
        return best;
    }

    /**
     * Если палец на проезжей части (в пределах widthPx от ближайшего отрезка
     * именованной улицы), возвращает виртуальный узел улицы с направлением.
     */
    private String streetPhraseAt(float x, float y) {
        if (roads.isEmpty()) {
            return null;
        }
        IGeoPoint center = getMapCenter();
        if (center == null) {
            return null;
        }
        OsmApiClient.RoadInfo best = null;
        double bestDist = streetRadiusPx();
        double[] bestSeg = null;
        for (OsmApiClient.RoadInfo road : roads) {
            for (int i = 0; i + 1 < road.geometry.size(); i++) {
                GeoPoint a = road.geometry.get(i);
                GeoPoint b = road.geometry.get(i + 1);
                double[] proj = projectToScreen(a, b, x, y);
                if (proj != null && proj[2] < bestDist) {
                    bestDist = proj[2];
                    best = road;
                    bestSeg = proj;
                }
            }
        }
        if (best == null) {
            return null;
        }
        // Направление берём по ориентации ближайшего отрезка (как он
        // оцифрован в OSM) — стабильно и соответствует «с севера на юг».
        return RuSpeech.streetDirectionPhrase(best.name,
                bestSeg[0], bestSeg[1],
                bestSeg[2], bestSeg[3]);
    }

    private double[] projectToScreen(GeoPoint a, GeoPoint b, float px, float py) {
        GeoPoint ca = new GeoPoint(a.getLatitude(), a.getLongitude());
        GeoPoint cb = new GeoPoint(b.getLatitude(), b.getLongitude());
        // Проецируем в экранные координаты через worldToPixels.
        getProjection().toPixels(ca, tmpPoint);
        int ax = tmpPoint.x, ay = tmpPoint.y;
        getProjection().toPixels(cb, tmpPoint);
        int bx = tmpPoint.x, by = tmpPoint.y;
        if (ax == bx && ay == by) {
            return null;
        }
        double vx = bx - ax, vy = by - ay;
        double len2 = vx * vx + vy * vy;
        double t = ((px - ax) * vx + (py - ay) * vy) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        double cx = ax + t * vx, cy = ay + t * vy;
        float dist = (float) Math.hypot(px - cx, py - cy);
        if (dist > streetRadiusPx()) {
            return null;
        }
        // Возвращаем координаты отрезка + точку проекции и lat/lon проекции.
        GeoPoint mid = new GeoPoint(ca.getLatitude() + (cb.getLatitude() - ca.getLatitude()) * t,
                ca.getLongitude() + (cb.getLongitude() - ca.getLongitude()) * t);
        return new double[]{ca.getLatitude(), ca.getLongitude(),
                cb.getLatitude(), cb.getLongitude(),
                (double) cx, (double) cy, (double) dist,
                mid.getLatitude(), mid.getLongitude()};
    }

    /**
     * Радиус, в котором палец «задевает» точку объекта.
     */
    private float hitRadiusPx() {
        float density = getResources().getDisplayMetrics().density;
        return Math.max(64f, 50f * density);
    }

    /**
     * Половина ширины улицы в пикселях, в пределах которой палец считаем
     * «на проезжей части».
     */
    private float streetRadiusPx() {
        float density = getResources().getDisplayMetrics().density;
        return Math.max(80f, 60f * density);
    }

    /** Рамка виртуального узла: всегда покрывает зону касания. */
    private float nodeRadiusPx() {
        float density = getResources().getDisplayMetrics().density;
        return Math.max(100f, 70f * density);
    }

    private int indexOfFeature(MapFeature f) {
        for (int i = 0; i < features.size(); i++) {
            if (features.get(i) == f) {
                return i;
            }
        }
        return -1;
    }

    private String phraseFor(MapFeature f) {
        // «Вы здесь» в точке местоположения: если там же стоит объект,
        // сначала называется название и тип объекта, затем «вы здесь».
        if ("here".equals(f.type)) {
            return (hereOverlapPhrase == null || hereOverlapPhrase.isEmpty())
                    ? "Вы здесь"
                    : hereOverlapPhrase + ", вы здесь";
        }
        // Именованные улицы озвучиваем просто названием: направление
        // добавляется отдельно, когда палец на проезжей части.
        if ("street".equals(f.type)) {
            return f.spokenName;
        }
        if ("junction".equals(f.type)) {
            return junctionPhrase(f);
        }
        return RuSpeech.constructPhrase(f.spokenName, f.spokenType);
    }

    /** «Перекрёсток: ...» + признаки рядом: светофор, пешеходный переход. */
    private String junctionPhrase(MapFeature f) {
        String base = (f.spokenName == null || f.spokenName.isEmpty())
                ? "Перекрёсток" : f.spokenName;
        if (hasNearbyType(f, "highway=traffic_signals")) {
            return base + ", со светофором";
        }
        if (hasNearbyType(f, "highway=crossing")) {
            return base + ", с пешеходным переходом";
        }
        return base;
    }

    private boolean hasNearbyType(MapFeature center, String type) {
        float radius = hitRadiusPx() * 1.25f;
        float dist2 = radius * radius;
        GeoPoint c = new GeoPoint(center.lat, center.lon);
        getProjection().toPixels(c, tmpPoint);
        float cx = tmpPoint.x;
        float cy = tmpPoint.y;
        for (MapFeature f : features) {
            if (f == center || f.type == null || !type.equals(f.type)) {
                continue;
            }
            GeoPoint gp = new GeoPoint(f.lat, f.lon);
            getProjection().toPixels(gp, tmpPoint);
            float dx = tmpPoint.x - cx;
            float dy = tmpPoint.y - cy;
            if (dx * dx + dy * dy <= dist2) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Обводка пальцем: TalkBack шлёт hover-события; озвучиваем объект под пальцем.
    // ------------------------------------------------------------------
    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        boolean handled = super.dispatchHoverEvent(event);
        int action = event.getActionMasked();
        Log.d(TAG, "HOVER action=" + action + " x=" + event.getX() + " y=" + event.getY());
        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE: {
                lastHoverX = event.getX();
                lastHoverY = event.getY();
                MapFeature f = featureNear(event.getX(), event.getY());
                if (f != null) {
                    int node = indexOfFeature(f);
                    if (node >= 0 && node != lastVirtualNode) {
                        lastVirtualNode = node;
                        lastStreetPhrase = null;
                        sendHoverEnter(node, phraseFor(f));
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    }
                    return true;
                }
                // Точечного объекта нет — возможно, палец на проезжей части улицы.
                // Метод сам ставит фокус на виртуальный узел улицы: иначе TalkMan
                // сохраняет фокус на последней кнопке (зум/местоположение) и
                // двойной тап активирует кнопку, а не улицу.
                if (announceStreetUnderFinger(event.getX(), event.getY())) {
                    return true;
                }
                if (lastVirtualNode >= 0) {
                    sendHoverExit();
                    lastVirtualNode = -1;
                }
                return true;
            }
            case MotionEvent.ACTION_HOVER_EXIT:
                sendHoverExit();
                lastVirtualNode = -1;
                lastStreetPhrase = null;
                return true;
            default:
                return handled;
        }
    }

    /** Озвучивает улицу с направлением, если палец на её проезжей части. */
    private boolean announceStreetUnderFinger(float x, float y) {
        String phrase = streetPhraseAt(x, y);
        if (phrase == null) {
            lastStreetPhrase = null;
            lastStreetName = null;
            return false;
        }
        if (!phrase.equals(lastStreetPhrase)) {
            lastStreetPhrase = phrase;
            lastStreetPhraseAt = System.currentTimeMillis();
            // Имя улицы без «с севера на юг» — для выбора по клику.
            int cut = phrase.indexOf(" с ");
            lastStreetName = cut > 0 ? phrase.substring(0, cut).trim() : phrase;
            // Отправляем Events раньше, чем TalkMan их прочитает. Если
            // после озвучки улицы придёт клик по кнопке (зум) — обработаем
            // его как выбор этой улицы: TalkMan игнорирует виртуальные узлы,
            // поэтому мы не можем дождаться performAction от узла.
            int node = findStreetFeatureNode(phrase);
            if (node >= 0) {
                sendAccessibilityFocus(node);
                if (node != lastVirtualNode) {
                    lastVirtualNode = node;
                    sendHoverEnter(node, phrase);
                }
            } else {
                sendAccessibilityFocus(AccessibilityNodeProvider.HOST_VIEW_ID);
                announceForAccessibility(phrase);
            }
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            Log.d(TAG, "street phrase=" + phrase);
        }
        return true;
    }

    /** Находит виртуальный узел улицы в features по имени из фразы. */
    private int findStreetFeatureNode(String phrase) {
        if (phrase == null) {
            return -1;
        }
        // Фраза вида «Вертковская улица с севера на юг» — берём название до
        // слова «с » («Вертковская улица») и ищем среди узлов type=street.
        String namePart = phrase;
        int cut = phrase.indexOf(" с ");
        if (cut > 0) {
            namePart = phrase.substring(0, cut).trim();
        }
        for (int i = 0; i < features.size(); i++) {
            MapFeature f = features.get(i);
            if ("street".equals(f.type)) {
                String fn = (f.spokenName == null || f.spokenName.isEmpty()) ? f.name : f.spokenName;
                if (fn != null && (fn.equalsIgnoreCase(namePart)
                        || fn.toLowerCase().contains(namePart.toLowerCase())
                        || namePart.toLowerCase().contains(fn.toLowerCase()))) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Режим следования: любые жесты (включая двухпальцевый скролл TalkMan)
     * двигают карту только если их направление совпадает с улицей.
     * Поперёк улицы и за её концом — карта не едет.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        long now = SystemClock.uptimeMillis();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                lastTapDownX = event.getX();
                lastTapDownY = event.getY();
                if (followedStreetName == null && now - lastTapUpTime <= DOUBLE_TAP_MS
                        && Math.hypot(event.getX() - lastTapX, event.getY() - lastTapY)
                                < DOUBLE_TAP_DIST_PX) {
                    Log.d(TAG, "doubleTap x=" + event.getX() + " y=" + event.getY());
                    if (tryFollowStreetAt(event.getX(), event.getY())) {
                        lastTapUpTime = 0;
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                lastTapUpTime = now;
                lastTapX = event.getX();
                lastTapY = event.getY();
                break;
            default:
                break;
        }
        if (followedStreetName == null) {
            return super.onTouchEvent(event);
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                followTouchLastX = event.getX(0);
                followTouchLastY = event.getY(0);
                followTouchSumX = 0;
                followTouchSumY = 0;
                followTouchActive = true;
                followGestureHandled = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!followTouchActive || followedChain.size() < 2) {
                    return super.onTouchEvent(event);
                }
                int n = event.getPointerCount();
                float avgX = 0, avgY = 0;
                for (int i = 0; i < n; i++) {
                    avgX += event.getX(i);
                    avgY += event.getY(i);
                }
                avgX /= n;
                avgY /= n;
                followTouchSumX += avgX - followTouchLastX;
                followTouchSumY += avgY - followTouchLastY;
                followTouchLastX = avgX;
                followTouchLastY = avgY;
                // Один жест — один шаг. После первого срабатывания
                // остальные MOVE этого жеста не двигают карту снова
                // (иначе за свайп проезжается 2 шага и улица "заканчивается").
                if (!followGestureHandled
                        && Math.hypot(followTouchSumX, followTouchSumY) >= FOLLOW_MIN_GESTURE_PX) {
                    followGestureHandled = true;
                    scrollGestureAlongStreet(followTouchSumX, followTouchSumY);
                    followTouchSumX = 0;
                    followTouchSumY = 0;
                    return true;
                }
                // Жест не по направлению улицы тоже полностью перехватываем:
                // если вернуть false, osmdroid проскроллит карту вбок.
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                followTouchActive = false;
                followTouchSumX = 0;
                followTouchSumY = 0;
                followGestureHandled = false;
                lastTapUpTime = now;
                lastTapX = event.getX();
                lastTapY = event.getY();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private void sendHoverEnter(int nodeId, String phrase) {
        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_VIEW_HOVER_ENTER);
        event.setSource(this, nodeId);
        event.setClassName(MapView.class.getName());
        event.setPackageName(getContext().getPackageName());
        event.setContentDescription(phrase);
        event.setEnabled(true);
        event.getText().add(phrase);
        sendAccessibilityEventUnchecked(event);
        // Некоторые скринридеры (особенно модифицированные сборки TalkBack)
        // игнорируют hover-события виртуальных узлов, но всегда произносят
        // announcements. Дублируем текст, чтобы объект озвучивался гарантированно.
        announceForAccessibility(phrase);
        Log.d(TAG, "sendHoverEnter node=" + nodeId + " phrase=" + phrase);
        lastSpoken = phrase;
        lastSpokenAt = System.currentTimeMillis();
    }

    /**
     * Явный перенос accessibility-фокуса на узел. Модифицированный TalkBack
     * (TalkMan) игнорирует hover виртуальных узлов и держит фокус на
     * последнем реальном элементе (кнопка зума/местоположения), поэтому
     * двойной тап уходит кнопке. TYPE_VIEW_ACCESSIBILITY_FOCUSED
     * побуждает его переключить фокус на узел.
     */
    private void sendAccessibilityFocus(int nodeId) {
        if (nodeId < 0) {
            return;
        }
        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
        event.setSource(this, nodeId);
        event.setClassName(MapView.class.getName());
        event.setPackageName(getContext().getPackageName());
        event.setContentDescription(nodeId == AccessibilityNodeProvider.HOST_VIEW_ID
                ? getResources().getString(R.string.map_description) : "");
        if (nodeId >= 0 && nodeId < features.size()) {
            MapFeature f = features.get(nodeId);
            if (f != null) {
                event.setContentDescription(phraseFor(f));
            }
        }
        event.getText().add(event.getContentDescription());
        sendAccessibilityEventUnchecked(event);
        Log.d(TAG, "sendAccessibilityFocus node=" + nodeId);
    }

    private void sendHoverExit() {
        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_VIEW_HOVER_EXIT);
        event.setSource(this, lastVirtualNode >= 0 ? lastVirtualNode : View.NO_ID);
        event.setClassName(MapView.class.getName());
        event.setPackageName(getContext().getPackageName());
        sendAccessibilityEventUnchecked(event);
    }

    private void announceCustom(String phrase, int virtualNode) {
        AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_ANNOUNCEMENT);
        event.setSource(this, virtualNode);
        event.setClassName(MapView.class.getName());
        event.setPackageName(getContext().getPackageName());
        event.setContentDescription(phrase);
        event.getText().add(phrase);
        sendAccessibilityEventUnchecked(event);
    }

    // ------------------------------------------------------------------
    // Виртуальные узлы для TalkBack. Каждый объект — отдельный узел:
    // при обводке пальцем скринридер читает его, при свайпе — навигация.
    // ------------------------------------------------------------------
    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return new MapNodeProvider();
    }

    private class MapNodeProvider extends AccessibilityNodeProvider {
        @Override
        public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
            AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
            info.setSource(AccessibleMapView.this, virtualViewId);
            info.setPackageName(getContext().getPackageName());
            info.setClassName(MapView.class.getName());
            info.setFocusable(true);
            info.setEnabled(true);
            info.setClickable(true);
            Log.d(TAG, "createNode id=" + virtualViewId + " features=" + features.size());

            if (virtualViewId == AccessibilityNodeProvider.HOST_VIEW_ID) {
                info.setContentDescription(getResources().getString(R.string.map_description));
                getLocationOnScreen(screenLocation);
                Rect bounds = new Rect(screenLocation[0], screenLocation[1],
                        screenLocation[0] + getWidth(), screenLocation[1] + getHeight());
                info.setBoundsInScreen(bounds);
                info.setVisibleToUser(true);
                for (int i = 0; i < features.size(); i++) {
                    info.addChild(AccessibleMapView.this, i);
                }
                // Позволяем скринридеру «сдвигать» карту свайпами.
                info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                // Клик по карте (пустому месту) — тоже вход в следование.
                info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
                return info;
            }

            if (virtualViewId < 0 || virtualViewId >= features.size()) {
                info.setContentDescription(EMPTY_HINT);
                return info;
            }
            MapFeature f = features.get(virtualViewId);
            String phrase = phraseFor(f);
            info.setContentDescription(phrase);
            info.setText(phrase);
            // Улица всегда «видима» в рамках загруженного радиуса: её узел —
            // точка на линии дороги, и по isOnScreen он мог быть за экраном,
            // из-за чего TalkMan не мог на него сфокусироваться.
            info.setVisibleToUser("street".equals(f.type) ? true : isOnScreen(f));
            info.setBoundsInScreen(boundsFor(f));
            info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
            return info;
        }

        @Override
        public boolean performAction(int virtualViewId, int action, android.os.Bundle arguments) {
            Log.d(TAG, "performAction id=" + virtualViewId + " action=" + action
                    + " follow=" + followedStreetName);
            long now = SystemClock.uptimeMillis();
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                // TalkMan (модифицированный TalkBack) шлёт «двойное нажатие»
                // как ДВА ACTION_CLICK подряд. Второй клик пары гасим:
                // действие выполняет первый. Одиночный клик стандартного
                // TalkBack тоже работает (первый клик пары). Узел второй
                // пары может быть соседним (TalkMan сдвигает фокус) —
                // гасим по времени, а не по совпадению id.
                boolean pairSecond = now - lastClickAt > 0
                        && now - lastClickAt <= DOUBLE_CLICK_MS;
                if (pairSecond) {
                    lastClickNode = Integer.MIN_VALUE;
                    // Не сбрасываем время в 0: все клики в окне пары гасятся,
                    // иначе третий клик TalkMan снова включит/выключит.
                    lastClickAt = now;
                    Log.d(TAG, "pair click consumed node=" + virtualViewId);
                    return true;
                }
                lastClickNode = virtualViewId;
                lastClickAt = now;

                MapFeature f = featureById(virtualViewId);
                if (virtualViewId == AccessibilityNodeProvider.HOST_VIEW_ID) {
                    if (followedStreetName != null) {
                        detachFollowStreet("Следование по улице "
                                + followedStreetName + " отключено.");
                        return true;
                    }
                    if (lastHoverX >= 0) {
                        tryFollowStreetAt(lastHoverX, lastHoverY);
                    }
                    return true;
                }
                // Узел улицы: включаем/выключаем следование ИМЕННО по ней —
                // не по ближайшей дороге (на перекрёстке может оказаться
                // поперечный переулок).
                if (f != null && "street".equals(f.type)) {
                    toggleFollowStreet(f);
                    return true;
                }
                // Дом/остановка на проезжей части: сначала пробуем улицу по
                // ИМЕНИ узла («улица Станиславского, дом 24» → «улица
                // Станиславского»). Так пользователь всегда попадает на улицу
                // из адреса, а не на поперечный переулок поблизости.
                if (f != null && followedStreetName == null) {
                    String fromName = streetNameOf(f);
                    if (fromName != null
                            && tryFollowStreetName(fromName, new GeoPoint(f.lat, f.lon))) {
                        return true;
                    }
                    getProjection().toPixels(new GeoPoint(f.lat, f.lon), tmpPoint);
                    if (roadNear(tmpPoint.x, tmpPoint.y) != null
                            && tryFollowStreetAt(tmpPoint.x, tmpPoint.y)) {
                        return true;
                    }
                }
                if (f != null) {
                    announceCustom(phraseFor(f), virtualViewId);
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
                return true;
            }
            if (virtualViewId == AccessibilityNodeProvider.HOST_VIEW_ID) {
                if (followedStreetName != null) {
                    // Как при обычном перетаскивании карты: свайп вверх
                    // (SCROLL_FORWARD) показывает юг, свайп вниз
                    // (SCROLL_BACKWARD) — север. Направление вдоль улицы
                    // определяем по географическому вектору, независимо
                    // от порядка точек в цепочке.
                    if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                        return moveAlongStreetGeo(0.0, -1.0);
                    }
                    if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                        return moveAlongStreetGeo(0.0, 1.0);
                    }
                    return false;
                }
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                    IGeoPoint c = getMapCenter();
                    getController().setCenter(new GeoPoint(c.getLatitude()
                            - screenStepLat(), c.getLongitude()));
                    return true;
                }
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                    IGeoPoint c = getMapCenter();
                    getController().setCenter(new GeoPoint(c.getLatitude()
                            + screenStepLat(), c.getLongitude()));
                    return true;
                }
                return false;
            }
            return false;
        }

        @Override
        public java.util.List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(
                String text, int virtualViewId) {
            java.util.List<AccessibilityNodeInfo> result = new java.util.ArrayList<>();
            if (text == null || text.isEmpty()) {
                return result;
            }
            String query = text.toLowerCase();
            for (int i = 0; i < features.size(); i++) {
                MapFeature f = features.get(i);
                if (f.name.toLowerCase().contains(query)
                        || f.spokenType.toLowerCase().contains(query)) {
                    result.add(createAccessibilityNodeInfo(i));
                }
            }
            return result;
        }

        @Override
        public AccessibilityNodeInfo findFocus(int focus) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Следование по улице: двойное нажатие на улицу, затем свайпы
    // вперёд/назад двигают карту ВДОЛЬ улицы, не давая ей уйти с центра.
    // Свайпы поперёк улицы и конец улицы — движения нет.
    // ------------------------------------------------------------------
    /** Геометрия следуемой улицы, склеенная из сегментов в одну линию. */
    private final List<GeoPoint> followedChain = new ArrayList<>();
    /** Точка клика (якорь) для сборки цепочки от места нажатия. */
    private double followAnchorLat = Double.NaN;
    private double followAnchorLon = Double.NaN;

    /** Находит улицу под точкой обводки и включает следование по ней. */
    private boolean tryFollowStreetAt(float x, float y) {
        OsmApiClient.RoadInfo road = roadNear(x, y);
        Log.d(TAG, "tryFollowStreetAt x=" + x + " y=" + y
                + " road=" + (road == null ? "null" : road.name)
                + " hover=" + lastHoverX + "," + lastHoverY);
        if (road == null || road.name == null || road.name.isEmpty()) {
            return false;
        }
        IGeoPoint projPre = projectToRoad(road, x, y);
        if (projPre != null) {
            followAnchorLat = projPre.getLatitude();
            followAnchorLon = projPre.getLongitude();
        }
        List<OsmApiClient.RoadInfo> segs = new ArrayList<>();
        for (OsmApiClient.RoadInfo r : roads) {
            if (road.name.equals(r.name)) {
                segs.add(r);
            }
        }
        if (segs.isEmpty()) {
            return false;
        }
        followedStreetName = road.name;
        followedSegments.clear();
        followedSegments.addAll(segs);
        rebuildFollowedChain();
        if (followedChain.size() < 2) {
            detachFollowStreet("Геометрия улицы " + road.name + " слишком короткая.");
            return true;
        }
        // Центрируем на точке обводки на проезжей части.
        IGeoPoint proj = projectToRoad(road, x, y);
        if (proj != null) {
            getController().setCenter(new GeoPoint(proj.getLatitude(), proj.getLongitude()));
        }
        announceForAccessibility("Следование по улице " + road.name + " включено. "
                + "Направление улицы: " + streetAxisName() + ". "
                + "Свайпы в этом направлении двигают карту, поперёк — нет. "
                + "Нажмите снова на улицу, чтобы отключить.");
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        return true;
    }

    private void toggleFollowStreet(MapFeature f) {
        String name = (f.spokenName == null || f.spokenName.isEmpty()) ? f.name : f.spokenName;
        if (tryFollowStreetName(name, new GeoPoint(f.lat, f.lon))) {
            return;
        }
        announceForAccessibility("Не удалось следовать по улице " + name
                + ": её геометрия не загружена.");
    }

    /** Пробует включить следование по улице с таким именем. */
    private boolean tryFollowStreetName(String name, GeoPoint anchor) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (followedStreetName != null && followedStreetName.equals(name)) {
            detachFollowStreet("Следование по улице " + name + " отключено.");
            return true;
        }
        List<OsmApiClient.RoadInfo> segs = new ArrayList<>();
        for (OsmApiClient.RoadInfo r : roads) {
            if (name.equals(r.name)) {
                segs.add(r);
            }
        }
        if (segs.isEmpty()) {
            return false;
        }
        followAnchorLat = anchor.getLatitude();
        followAnchorLon = anchor.getLongitude();
        followedStreetName = name;
        followedSegments.clear();
        followedSegments.addAll(segs);
        rebuildFollowedChain();
        if (followedChain.size() < 2) {
            detachFollowStreet("Геометрия улицы " + name + " слишком короткая.");
            return true;
        }
        if (anchor != null) {
            getController().setCenter(anchor);
        }
        // Двойная вибрация: TalkMan-звук может не озвучить announcement,
        // а короткий «двойной щелчок» всегда ощутим.
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
        }, 120);
        announceForAccessibility("Следование по улице " + name + " включено. "
                + "Направление улицы: " + streetAxisName() + ". "
                + "Свайпы в этом направлении двигают карту, поперёк — нет. "
                + "Нажмите снова на улицу, чтобы отключить.");
        Log.d(TAG, "follow started name=" + name + " segs=" + segs.size()
                + " chain=" + followedChain.size());
        return true;
    }

    /**
     * Имя улицы из узла-адреса: «улица Станиславского, дом 24» → «улица
     * Станиславского», «Вертковская улица, дом 40» → «Вертковская улица».
     */
    private String streetNameOf(MapFeature f) {
        String name = (f.spokenName == null || f.spokenName.isEmpty()) ? f.name : f.spokenName;
        if (name == null) {
            return null;
        }
        String lower = name.toLowerCase();
        int cut = name.indexOf(',');
        String prefix = cut > 0 ? name.substring(0, cut).trim() : name;
        if (prefix.isEmpty()) {
            return null;
        }
        for (OsmApiClient.RoadInfo r : roads) {
            if (r.name.equals(prefix)) {
                return r.name;
            }
            if (r.name.equalsIgnoreCase(prefix)) {
                return r.name;
            }
            if (r.name.toLowerCase().contains(prefix.toLowerCase())
                    || prefix.toLowerCase().contains(r.name.toLowerCase())) {
                return r.name;
            }
        }
        return null;
    }

    private void detachFollowStreet(String message) {
        followedStreetName = null;
        followedSegments.clear();
        followedChain.clear();
        announceForAccessibility(message);
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    /** Пересобирает линию улицы из свежих сегментов после перезагрузки. */
    private void refreshFollowedSegments() {
        if (followedStreetName == null) {
            return;
        }
        // При каждом сдвиге карты API возвращает дороги только своего
        // радиуса, и если просто заменить followedSegments — цепочка улицы
        // сжимается, а следование «заканчивается» на середине улицы.
        // Известные сегменты НАКАПЛИВАЕМ: добавляем новые, которых ещё нет.
        IGeoPoint center = getMapCenter();
        if (center != null) {
            followAnchorLat = center.getLatitude();
            followAnchorLon = center.getLongitude();
        }
        for (OsmApiClient.RoadInfo r : roads) {
            if (!followedStreetName.equals(r.name) || r.geometry.size() < 2) {
                continue;
            }
            boolean known = false;
            for (OsmApiClient.RoadInfo old : followedSegments) {
                if (sameRoadSegment(old, r)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                followedSegments.add(r);
            }
        }
        rebuildFollowedChain();
    }

    /** Два сегмента считаем одним и тем же, если совпадают концы геометрии. */
    private boolean sameRoadSegment(OsmApiClient.RoadInfo a, OsmApiClient.RoadInfo b) {
        if (a.geometry.isEmpty() || b.geometry.isEmpty()) {
            return false;
        }
        GeoPoint a0 = a.geometry.get(0);
        GeoPoint a1 = a.geometry.get(a.geometry.size() - 1);
        GeoPoint b0 = b.geometry.get(0);
        GeoPoint b1 = b.geometry.get(b.geometry.size() - 1);
        return (distanceMeters(a0, b0) < 1.0 && distanceMeters(a1, b1) < 1.0)
                || (distanceMeters(a0, b1) < 1.0 && distanceMeters(a1, b0) < 1.0);
    }

    private void rebuildFollowedChain() {
        followedChain.clear();
        if (followedSegments.isEmpty()) {
            return;
        }
        // Начинаем с сегмента, ближайшего к точке клика/центру. Тогда
        // цепочка всегда «растёт» от места, где стоит пользователь, и не
        // зависит от порядка сегментов в ответе API.
        List<OsmApiClient.RoadInfo> remaining = new ArrayList<>(followedSegments);
        int startIdx = 0;
        if (!Double.isNaN(followAnchorLon)) {
            double best = Double.MAX_VALUE;
            for (int i = 0; i < remaining.size(); i++) {
                GeoPoint p = remaining.get(i).geometry.get(0);
                double d = distanceMeters(p,
                        new GeoPoint(followAnchorLat, followAnchorLon));
                if (d < best) {
                    best = d;
                    startIdx = i;
                }
            }
        }
        List<GeoPoint> chain = new ArrayList<>(remaining.remove(startIdx).geometry);
        boolean added = true;
        int guard = 0;
        while (added && !remaining.isEmpty() && guard < 400) {
            added = false;
            guard++;
            GeoPoint head = chain.get(0);
            GeoPoint tail = chain.get(chain.size() - 1);
            int bestIdx = -1;
            int bestMode = -1; // 0: rn->tail, 1: r0->tail, 2: r0->head, 3: rn->head
            double bestDist = Double.MAX_VALUE;
            for (int i = 0; i < remaining.size(); i++) {
                OsmApiClient.RoadInfo r = remaining.get(i);
                GeoPoint r0 = r.geometry.get(0);
                GeoPoint rn = r.geometry.get(r.geometry.size() - 1);
                double d0 = distanceMeters(rn, tail);
                double d1 = distanceMeters(r0, tail);
                double d2 = distanceMeters(r0, head);
                double d3 = distanceMeters(rn, head);
                if (d0 <= CONTINUATION_TOLERANCE_METERS && d0 < bestDist) {
                    bestDist = d0; bestIdx = i; bestMode = 0;
                }
                if (d1 <= CONTINUATION_TOLERANCE_METERS && d1 < bestDist) {
                    bestDist = d1; bestIdx = i; bestMode = 1;
                }
                if (d2 <= CONTINUATION_TOLERANCE_METERS && d2 < bestDist) {
                    bestDist = d2; bestIdx = i; bestMode = 2;
                }
                if (d3 <= CONTINUATION_TOLERANCE_METERS && d3 < bestDist) {
                    bestDist = d3; bestIdx = i; bestMode = 3;
                }
            }
            if (bestIdx < 0) {
                break;
            }
            OsmApiClient.RoadInfo r = remaining.get(bestIdx);
            switch (bestMode) {
                case 0:
                    chain.addAll(r.geometry);
                    break;
                case 1: {
                    List<GeoPoint> rev = new ArrayList<>(r.geometry);
                    Collections.reverse(rev);
                    chain.addAll(rev);
                    break;
                }
                case 2: {
                    List<GeoPoint> rev = new ArrayList<>(r.geometry);
                    Collections.reverse(rev);
                    chain.addAll(0, rev);
                    break;
                }
                default:
                    chain.addAll(0, r.geometry);
                    break;
            }
            remaining.remove(bestIdx);
            added = true;
        }
        followedChain.addAll(chain);
        Log.d(TAG, "followedChain name=" + followedStreetName + " pts=" + chain.size()
                + " head=" + (chain.isEmpty() ? "-" : chain.get(0).getLatitude() + "," + chain.get(0).getLongitude())
                + " tail=" + (chain.isEmpty() ? "-" : chain.get(chain.size() - 1).getLatitude() + "," + chain.get(chain.size() - 1).getLongitude())
                + " remain=" + remaining.size());
    }

    /**
     * Движение одним жестом: dir +1 — к концу улицы, -1 — к началу.
     * Если улица закончилась, карта не едет и следование снимается.
     */
    private boolean moveAlongStreet(int dir) {
        Log.d(TAG, "moveAlongStreet dir=" + dir + " name=" + followedStreetName
                + " chain=" + followedChain.size() + " axis=" + streetAxisName());
        if (followedChain.size() < 2) {
            Log.d(TAG, "detachFollowStreet: chain<2, name=" + followedStreetName);
            announceForAccessibility("Улица " + followedStreetName
                    + " больше не загружена. Следование остановлено.");
            detachFollowStreetSilently();
            return true;
        }
        IGeoPoint center = getMapCenter();
        if (center == null) {
            return false;
        }
        double s0 = arcLengthOfNearestPoint(center);
        double total = chainLength();
        double target = dir > 0 ? s0 + FOLLOW_STEP_METERS : s0 - FOLLOW_STEP_METERS;
        if (target < 0 || target > total) {
            Log.d(TAG, "detachFollowStreet: end dir=" + dir + " s0=" + (int) s0
                    + " total=" + (int) total + " name=" + followedStreetName);
            announceForAccessibility("Улица закончилась. Следование остановлено.");
            detachFollowStreetSilently();
            return true;
        }
        GeoPoint p = pointAtArcLength(target);
        if (p == null) {
            return false;
        }
        getController().setCenter(p);
        scheduleReload();
        return true;
    }

    /**
     * Движение по улице с географически заданным направлением центра карты:
     * gx — метры на восток, gy — метры на север. Направление определяем по
     * касательной ближайшего к центру сегмента, поэтому оно не зависит от
     * порядка точек в followedChain (у OSM он произвольный). Как при обычном
     * перетаскивании карты: свайп вниз показывает север, вверх — юг,
     * вправо — запад, влево — восток.
     */
    private boolean moveAlongStreetGeo(double gx, double gy) {
        if (followedChain.size() < 2) {
            return false;
        }
        IGeoPoint center = getMapCenter();
        if (center == null) {
            return false;
        }
        double refLat = followedChain.get(0).getLatitude();
        double cosLat = Math.cos(Math.toRadians(refLat));
        double cx = center.getLongitude() * 111320.0 * cosLat;
        double cy = center.getLatitude() * 110574.0;
        double bestDist = Double.MAX_VALUE;
        double bestTx = 0, bestTy = 0;
        for (int i = 0; i + 1 < followedChain.size(); i++) {
            GeoPoint a = followedChain.get(i);
            GeoPoint b = followedChain.get(i + 1);
            double ax = a.getLongitude() * 111320.0 * cosLat;
            double ay = a.getLatitude() * 110574.0;
            double bx = b.getLongitude() * 111320.0 * cosLat;
            double by = b.getLatitude() * 110574.0;
            double vx = bx - ax, vy = by - ay;
            double len2 = vx * vx + vy * vy;
            if (len2 <= 0) {
                continue;
            }
            double t = ((cx - ax) * vx + (cy - ay) * vy) / len2;
            t = Math.max(0.0, Math.min(1.0, t));
            double qx = ax + t * vx, qy = ay + t * vy;
            double d = Math.hypot(cx - qx, cy - qy);
            if (d < bestDist) {
                bestDist = d;
                bestTx = vx / Math.sqrt(len2);
                bestTy = vy / Math.sqrt(len2);
            }
        }
        double along = gx * bestTx + gy * bestTy;
        if (Math.abs(along) < 1e-6) {
            Log.d(TAG, "moveAlongStreetGeo: perpendicular only (" + (int) gx
                    + "," + (int) gy + ")");
            return false;
        }
        return moveAlongStreet(along >= 0 ? +1 : -1);
    }

    /**
     * Движение жестом по направлению улицы. dx/dy — вектор жеста в
     * экранных пикселях (положительный dy = вниз). Улица должна иметь
     * доминирующую ось; проекция вектора на улицу должна быть не меньше
     * поперечной составляющей. Иначе жест игнорируется.
     */
    private boolean scrollGestureAlongStreet(float dx, float dy) {
        if (followedChain.size() < 2) {
            return false;
        }
        double len = Math.hypot(dx, dy);
        if (len < FOLLOW_MIN_GESTURE_PX) {
            return false;
        }
        double ang = localStreetAngle();
        double cosA = Math.cos(ang), sinA = Math.sin(ang);
        // Вектор жеста в «долгота/широта»: как при обычном перетаскивании
        // карта движется ПРОТИВ пальца — свайп вниз (dy>0) показывает север,
        // свайп вправо (dx>0) показывает запад. Поэтому gy = +dy, gx = -dx.
        double gx = -dx, gy = dy;
        double along = gx * cosA + gy * sinA;
        double across = -gx * sinA + gy * cosA;
        if (Math.abs(along) < Math.abs(across) * FOLLOW_ALIGN_COS) {
            Log.d(TAG, "gesture ignored: across=" + (int) across + " along=" + (int) along);
            return false;
        }
        return moveAlongStreetGeo(gx, gy);
    }

    private void detachFollowStreetSilently() {
        followedStreetName = null;
        followedSegments.clear();
        followedChain.clear();
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    /** Расстояние по прямой (метры) между двумя точками. */
    private static double distanceMeters(GeoPoint a, GeoPoint b) {
        double cosLat = Math.cos(Math.toRadians((a.getLatitude() + b.getLatitude()) / 2.0));
        double dLat = (b.getLatitude() - a.getLatitude()) * 110574.0;
        double dLon = (b.getLongitude() - a.getLongitude()) * 111320.0 * cosLat;
        return Math.hypot(dLat, dLon);
    }

    private OsmApiClient.RoadInfo roadNear(float x, float y) {
        OsmApiClient.RoadInfo best = null;
        double bestScore = Double.MAX_VALUE;
        double maxDist = streetRadiusPx() * 1.5;
        for (OsmApiClient.RoadInfo road : roads) {
            double roadLen = namedRoadLength(road.name);
            for (int i = 0; i + 1 < road.geometry.size(); i++) {
                double[] proj = projectToScreen(road.geometry.get(i), road.geometry.get(i + 1), x, y);
                if (proj != null && proj[2] < maxDist) {
                    double distance = proj[2];
                    double score = distance * Math.max(1.0, 60.0 / Math.sqrt(roadLen));
                    if (score < bestScore) {
                        bestScore = score;
                        best = road;
                    }
                }
            }
        }
        return best;
    }

    /** Суммарная длина всех сегментов улицы с данным именем, метры. */
    private double namedRoadLength(String name) {
        double total = 0;
        for (OsmApiClient.RoadInfo r : roads) {
            if (name.equals(r.name)) {
                total += r.totalLengthMeters;
            }
        }
        return Math.max(total, 100.0);
    }

    /** Проекция точки обводки на геометрию дороги (lat/lon). */
    private IGeoPoint projectToRoad(OsmApiClient.RoadInfo road, float x, float y) {
        double bestDist = Double.MAX_VALUE;
        double bestLat = Double.NaN, bestLon = Double.NaN;
        for (int i = 0; i + 1 < road.geometry.size(); i++) {
            double[] proj = projectToScreen(road.geometry.get(i), road.geometry.get(i + 1), x, y);
            if (proj != null && proj[2] < bestDist) {
                bestDist = proj[2];
                bestLat = proj[7];
                bestLon = proj[8];
            }
        }
        if (Double.isNaN(bestLat)) {
            return null;
        }
        return new GeoPoint(bestLat, bestLon);
    }

    /** Название доминирующего направления улицы для подсказки. */
    private String streetAxisName() {
        double metersN = 0, metersE = 0;
        double refLat = followedChain.get(0).getLatitude();
        double cosLat = Math.cos(Math.toRadians(refLat));
        for (int i = 0; i + 1 < followedChain.size(); i++) {
            GeoPoint a = followedChain.get(i);
            GeoPoint b = followedChain.get(i + 1);
            metersN += Math.abs(b.getLatitude() - a.getLatitude()) * 110574.0;
            metersE += Math.abs(b.getLongitude() - a.getLongitude())
                    * 111320.0 * cosLat;
        }
        return metersN >= metersE ? "с севера на юг" : "с запада на восток";
    }

    /** Угол главной оси улицы в экранных координатах (радианы). */
    private double localStreetAngle() {
        double refLat = followedChain.get(0).getLatitude();
        double cosLat = Math.cos(Math.toRadians(refLat));
        double sx = 0, sy = 0;
        for (int i = 0; i + 1 < followedChain.size(); i++) {
            GeoPoint a = followedChain.get(i);
            GeoPoint b = followedChain.get(i + 1);
            double ex = (b.getLongitude() - a.getLongitude()) * 111320.0 * cosLat;
            double ny = (b.getLatitude() - a.getLatitude()) * 110574.0;
            double len = Math.hypot(ex, ny);
            if (len < 1e-6) {
                continue;
            }
            sx += ex / len;
            sy += ny / len;
        }
        return Math.atan2(sy, sx);
    }

    private double chainLength() {
        double total = 0;
        for (int i = 0; i + 1 < followedChain.size(); i++) {
            total += distanceMeters(followedChain.get(i), followedChain.get(i + 1));
        }
        return total;
    }

    /** Длина дуги от начала улицы до ближайшей к центру точки. */
    private double arcLengthOfNearestPoint(IGeoPoint center) {
        double cx = center.getLongitude();
        double cy = center.getLatitude();
        double best = Double.MAX_VALUE;
        double bestS = 0;
        double s = 0;
        double refLat = followedChain.get(0).getLatitude();
        double cosLat = Math.cos(Math.toRadians(refLat));
        for (int i = 0; i + 1 < followedChain.size(); i++) {
            GeoPoint a = followedChain.get(i);
            GeoPoint b = followedChain.get(i + 1);
            double ax = a.getLongitude() * 111320.0 * cosLat;
            double ay = a.getLatitude() * 110574.0;
            double bx = b.getLongitude() * 111320.0 * cosLat;
            double by = b.getLatitude() * 110574.0;
            double px = center.getLongitude() * 111320.0 * cosLat;
            double py = center.getLatitude() * 110574.0;
            double vx = bx - ax, vy = by - ay;
            double len2 = vx * vx + vy * vy;
            if (len2 <= 0) {
                continue;
            }
            double t = ((px - ax) * vx + (py - ay) * vy) / len2;
            t = Math.max(0.0, Math.min(1.0, t));
            double qx = ax + t * vx, qy = ay + t * vy;
            double d = Math.hypot(px - qx, py - qy);
            if (d < best) {
                best = d;
                bestS = s + t * Math.sqrt(len2);
            }
            s += Math.sqrt(len2);
        }
        return bestS;
    }

    /** Точка на линии улицы на расстоянии s метров от её начала. */
    private GeoPoint pointAtArcLength(double s) {
        double acc = 0;
        double refLat = followedChain.get(0).getLatitude();
        double cosLat = Math.cos(Math.toRadians(refLat));
        for (int i = 0; i + 1 < followedChain.size(); i++) {
            GeoPoint a = followedChain.get(i);
            GeoPoint b = followedChain.get(i + 1);
            double len = distanceMeters(a, b);
            if (len <= 0) {
                continue;
            }
            if (acc + len >= s) {
                double t = (s - acc) / len;
                return new GeoPoint(
                        a.getLatitude() + (b.getLatitude() - a.getLatitude()) * t,
                        a.getLongitude() + (b.getLongitude() - a.getLongitude()) * t);
            }
            acc += len;
        }
        return null;
    }

    private double screenStepLat() {
        double zoom = getZoomLevelDouble();
        // Примерная высота сдвига в градусах при свайпе вниз/вверх.
        return 300.0 / (256.0 * Math.pow(2, zoom)) * 360.0;
    }

    private MapFeature featureById(int id) {
        return (id >= 0 && id < features.size()) ? features.get(id) : null;
    }

    private boolean isOnScreen(MapFeature f) {
        if (getWidth() <= 0) {
            return false;
        }
        getProjection().toPixels(new GeoPoint(f.lat, f.lon), tmpPoint);
        return tmpPoint.x >= 0 && tmpPoint.x <= getWidth()
                && tmpPoint.y >= 0 && tmpPoint.y <= getHeight();
    }

    private Rect boundsFor(MapFeature f) {
        getProjection().toPixels(new GeoPoint(f.lat, f.lon), tmpPoint);
        getLocationOnScreen(screenLocation);
        float radius = nodeRadiusPx();
        int cx = screenLocation[0] + tmpPoint.x;
        int cy = screenLocation[1] + tmpPoint.y;
        int r = (int) radius;
        // Узел улицы должен быть «широким» по всей проезжей части, иначе
        // скринридер при обводке длинной улицы не попадает в узел и фокус
        // остаётся на кнопке зума/местоположения. Расширяем прямоугольник
        // по диагонали улицы, если она загружена.
        if ("street".equals(f.type)) {
            String nm = (f.spokenName == null || f.spokenName.isEmpty()) ? f.name : f.spokenName;
            for (OsmApiClient.RoadInfo r0 : roads) {
                if (r0.name == null) {
                    continue;
                }
                if (r0.name.equals(nm) || (nm != null && r0.name.equalsIgnoreCase(nm))) {
                    Rect wide = geoBoundsToScreen(r0, cx, cy, r);
                    if (wide != null) {
                        return wide;
                    }
                    break;
                }
            }
        }
        return new Rect(cx - r, cy - r, cx + r, cy + r);
    }

    /**
     * Экранный прямоугольник, охватывающий геометрию дороги с запасом ±r.
     * Если точка узла улицы далеко от центра сегмента, прямоугольник всё
     * равно покрывает всю улицу на экране — TalkMan «видит» её целиком.
     */
    private Rect geoBoundsToScreen(OsmApiClient.RoadInfo road, int cx, int cy, float r) {
        if (road.geometry.isEmpty()) {
            return null;
        }
        int minX = cx - (int) r;
        int minY = cy - (int) r;
        int maxX = cx + (int) r;
        int maxY = cy + (int) r;
        for (GeoPoint p : road.geometry) {
            getProjection().toPixels(p, tmpPoint);
            int px = screenLocation[0] + tmpPoint.x;
            int py = screenLocation[1] + tmpPoint.y;
            minX = Math.min(minX, px);
            minY = Math.min(minY, py);
            maxX = Math.max(maxX, px);
            maxY = Math.max(maxY, py);
        }
        return new Rect(minX - (int) r, minY - (int) r, maxX + (int) r, maxY + (int) r);
    }

    public List<MapFeature> getFeatures() {
        return new ArrayList<>(features);
    }
}