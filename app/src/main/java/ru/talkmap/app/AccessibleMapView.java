package ru.talkmap.app;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
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
import org.osmdroid.views.overlay.Marker;

import java.util.ArrayList;
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
    private double userZoom = 17.0;
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
        userZoom = 17.0;
        getController().setZoom(userZoom);
        getController().animateTo(point);
        invalidate();
        Log.d(TAG, "setUserLocation -> " + point.getLatitude() + "," + point.getLongitude());
        scheduleReload();
    }

    /** Возвращает карту в точку текущего местоположения. */
    public void centerOnUser() {
        if (userLocation != null) {
            getController().animateTo(userLocation);
            String msg = "Карта возвращена к вашему местоположению.";
            announceForAccessibility(msg);
        } else {
            announceForAccessibility("Ваше местоположение ещё не определено.");
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
        Log.d(TAG, "reloadFeatures center=" + center.getLatitude() + "," + center.getLongitude()
                + " zoom=" + getZoomLevelDouble());
        double zoom = getZoomLevelDouble();
        double radius;
        if (zoom >= 19) {
            radius = 500;
        } else if (zoom >= 17.5) {
            radius = 900;
        } else if (zoom >= 16) {
            radius = 1500;
        } else {
            radius = 2000;
        }
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
                    invalidate();
                } else {
                    setFeatures(newFeatures, newRoads);
                }
                if (listener != null) {
                    listener.onCount(features.size());
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

    /** Количество объектов, не являющихся синтезированными перекрёстками. */
    private int countRealFeatures(List<MapFeature> list) {
        int n = 0;
        for (MapFeature f : list) {
            if (!"junction".equals(f.type)) {
                n++;
            }
        }
        return n;
    }

    private void setFeatures(List<MapFeature> newFeatures, List<OsmApiClient.RoadInfo> newRoads) {
        features.clear();
        features.addAll(newFeatures);
        roads.clear();
        roads.addAll(newRoads);
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
                if (announceStreetUnderFinger(event.getX(), event.getY())) {
                    lastVirtualNode = -1;
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
            return false;
        }
        if (!phrase.equals(lastStreetPhrase)) {
            lastStreetPhrase = phrase;
            announceForAccessibility(phrase);
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            Log.d(TAG, "street phrase=" + phrase);
        }
        return true;
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
            info.setClickable(false);
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
            info.setVisibleToUser(isOnScreen(f));
            info.setBoundsInScreen(boundsFor(f));
            info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
            return info;
        }

        @Override
        public boolean performAction(int virtualViewId, int action, android.os.Bundle arguments) {
            if (virtualViewId == AccessibilityNodeProvider.HOST_VIEW_ID) {
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
            if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                MapFeature f = featureById(virtualViewId);
                if (f != null) {
                    announceCustom(phraseFor(f), virtualViewId);
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    return true;
                }
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
        return new Rect(cx - r, cy - r, cx + r, cy + r);
    }

    public List<MapFeature> getFeatures() {
        return new ArrayList<>(features);
    }
}