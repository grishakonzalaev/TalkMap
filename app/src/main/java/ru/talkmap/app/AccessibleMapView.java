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
import android.widget.TextView;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.provider.Settings;

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

    /** Custom accessibility actions, matching context-menu items. */
    public static final int ACTION_SHOW_DETAILS = 0x10001;
    public static final int ACTION_COPY_COORDS = 0x10002;
    public static final int ACTION_ROUTE = 0x10003;

    public interface Listener {
        void onStatus(int resId);

        void onCount(int count);

        /** Загруженные объекты обновились: перестроить реальный список. */
        void onFeaturesUpdated();

        /** Кнопка «моё местоположение»: повторить запрос свежего фикса. */
        void onLocationRequested();

        /**
         * Долгое нажатие/меню объекта: показать контекстное меню
         * (подробности, копировать координаты, проложить маршрут).
         */
        void onFeatureMenu(MapFeature f);

        /** Двойной тап по точечному объекту: проложить маршрут. */
        void onRoute(MapFeature f);

        /** Действие скринридера «Показать подробности». */
        void onFeatureDetails(MapFeature f);

        /** Действие скринридера «Копировать координаты». */
        void onFeatureCopyCoords(MapFeature f);
    }

    private final List<MapFeature> features = new ArrayList<>();
    private final List<OsmApiClient.RoadInfo> roads = new ArrayList<>();
    private OsmApiClient api;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Point tmpPoint = new Point();
    private final int[] screenLocation = new int[2];

    private Listener listener;
    private Marker locationMarker;
    private Marker searchMarker;
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
    /** Узел, на котором сейчас accessibility-фокус (экспонируется в findFocus). */
    private int lastFocusedNode = -1;
    /** Кэш определения модифицированного TalkBack (см. isModifiedTalkback). */
    private Boolean modifiedTalkback = null;
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
    private long lastTapDownTime = 0;
    private static final long DOUBLE_TAP_MS = 400L;
    /** Долгое нажатие (мс) — открытие контекстного меню объекта. */
    private static final long LONG_PRESS_MS = 550L;
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

    /**
     * Определяет, работает ли модифицированная сборка TalkBack (TalkMan/Backtalk),
     * которая игнорирует hover-события виртуальных узлов. Для таких сборок нужны
     * обходные пути (дублирование announcement'ом + принудительный перенос фокуса).
     * Обычный стоковый TalkBack читает узел сам из TYPE_VIEW_HOVER_ENTER, и эти
     * обходные пути лишь создают задержку чтения и «слетание» фокуса на карту.
     */
    private boolean isModifiedTalkback() {
        // Результат кэшируем: hover-события шлются десятки раз в секунду,
        // а это чтение из ContentResolver. Переключатель скринридера обычно
        // перезапускает активность, кэш при этом пересоздаётся.
        if (modifiedTalkback != null) {
            return modifiedTalkback;
        }
        boolean result;
        if (getContext() == null) {
            result = false;
        } else {
            try {
                String enabled = Settings.Secure.getString(
                        getContext().getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                // Строка вида "package/service,package2/service2". Модификацию
                // определяем по ПАКЕТУ компонента (до '/'), а не по имени класса:
                // у TalkMan/Backtalk класс лежит в com.google.android.marvin.talkback,
                // а пакет — другой (com.nirenr.talkman). Стоковый TalkBack — это
                // com.google.android.marvin.talkback или com.android.talkback.
                boolean stock = false;
                boolean anyEnabled = false;
                if (enabled != null) {
                    for (String svc : enabled.split(":")) {
                        String comp = svc.trim();
                        if (comp.isEmpty()) {
                            continue;
                        }
                        anyEnabled = true;
                        String pkg = comp;
                        int slash = comp.indexOf('/');
                        if (slash > 0) {
                            pkg = comp.substring(0, slash);
                        }
                        if (pkg.equalsIgnoreCase("com.google.android.marvin.talkback")
                                || pkg.equalsIgnoreCase("com.android.talkback")) {
                            stock = true;
                            break;
                        }
                    }
                }
                // Модифицированная сборка = есть включённые сервисы, но среди
                // них нет стокового TalkBack.
                result = anyEnabled && !stock;
            } catch (Exception e) {
                result = false;
            }
        }
        modifiedTalkback = result;
        Log.d(TAG, "isModifiedTalkback=" + result
                + " (enabled=" + safeEnabledServices() + ")");
        return result;
    }

    private String safeEnabledServices() {
        try {
            return Settings.Secure.getString(
                    getContext().getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        } catch (Exception e) {
            return null;
        }
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

    /**
     * Перемещает карту к найденному результату поиска (город, улица, адрес,
     * объект или координаты). Ставит маркер и озвучивает название, чтобы
     * пользователь понял, куда приехала карта.
     */
    public void centerOnSearchResult(NominatimClient.SearchResult r) {
        if (r == null) {
            return;
        }
        GeoPoint point = new GeoPoint(r.lat, r.lon);
        if (searchMarker == null) {
            searchMarker = new Marker(this);
            searchMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            searchMarker.setTitle(r.spokenName());
            searchMarker.setInfoWindow(null);
            try {
                searchMarker.setIcon(getResources().getDrawable(R.drawable.dot_location));
            } catch (Exception e) {
                Log.w(TAG, "no search icon", e);
            }
            getOverlays().add(searchMarker);
        }
        searchMarker.setPosition(point);
        // Найденное место открываем на масштабе города (около 15), чтобы
        // вокруг была видна зона, куда приехали.
        getController().setZoom(15.0);
        getController().animateTo(point);
        invalidate();
        // Сообщаем TalkMan, куда приехала карта.
        announceForAccessibility("Найдено: " + r.spokenName() + ". "
                + "Карта перемещена к этому месту.");
        Log.d(TAG, "centerOnSearchResult -> " + r.lat + "," + r.lon
                + " name=" + r.spokenName());
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

    /**
     * Обработка ДОЛГОГО нажатия кнопки (зум/местоположение/поиск/навигация)
     * как вызова контекстного меню объекта, по которому пользователь вёл палец
     * по карте. Возвращает true, если долгое нажатие обработано как вызов меню —
     * кнопку выполнять не нужно.
     *
     * Решает баг «после касания кнопки меню не открывается»: TalkBack/Jieshuo
     * держат фокус ввода на последней активированной кнопке, поэтому долгий тап
     * по объекту уходит кнопке, а не карте. Здесь мы перехватываем долгое
     * нажатие кнопки и открываем меню объекта под точкой обводки (lastHoverX/Y).
     * Обычный (короткий) клик кнопки продолжает выполнять своё действие.
     */
    public boolean handleMenuLongPress() {
        if (lastHoverX < 0 || lastHoverY < 0) {
            return false;
        }
        MapFeature f = actionTargetFeature();
        if (f == null) {
            return false;
        }
        if ("street".equals(f.type) || "here".equals(f.type)) {
            return false;
        }
        if (listener != null) {
            Log.d(TAG, "handleMenuLongPress -> onFeatureMenu " + f.name);
            listener.onFeatureMenu(f);
        }
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
                    listener.onFeaturesUpdated();
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
        IGeoPoint hereGeo = getProjection().fromPixels((int) x, (int) y);
        double tLat = hereGeo.getLatitude();
        double tLon = hereGeo.getLongitude();
        MapFeature obj = nearestJunction(x, y);
        if (obj == null) {
            obj = nearestNamedFeature(x, y, tLat, tLon);
        }
        if (obj == null) {
            obj = nearestAddressOrSignal(x, y, tLat, tLon);
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
        // Гео-координаты пальца: для проверки попадания ВНУТРЬ полигона
        // здания/магазина (иначе большой объект «промахивается» по центроиду
        // и победителем становится соседний дом).
        IGeoPoint touchGeo = getProjection().fromPixels((int) x, (int) y);
        double tLat = touchGeo.getLatitude();
        double tLon = touchGeo.getLongitude();
        // Сначала перекрёсток: на многих перекрёстках вплотную стоят киоски,
        // остановки и магазины, которые «перебивали» перекрёсток. В его центре
        // пользователь должен слышать перекрёсток, а объекты — чуть в стороне.
        MapFeature f = nearestJunction(x, y);
        if (f != null) {
            return f;
        }
        f = nearestNamedFeature(x, y, tLat, tLon);
        if (f != null) {
            return f;
        }
        return nearestAddressOrSignal(x, y, tLat, tLon);
    }

    /**
     * Объект верхней метки под пальцем — ТОТ, который скринридер фокусирует
     * (а не точечный hit-test featureNear с приоритетами). Возвращаем его,
     * чтобы озвучка совпадала со сфокусированным объектом: иначе скринридер
     * проговаривает один объект, а выделяет/читает по символам соседний.
     */
    private MapFeature featureAtTop(float x, float y) {
        Rect r = new Rect();
        // Идём СВЕРХУ вниз: последний добавленный вью рисуется поверх всех,
        // значит и фокусируется скринридером первым. Совпадение озвучки и
        // фокуса — это выбор самого верхнего перекрывающего объекта.
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View v = getChildAt(i);
            if (!(v instanceof TextView)) {
                continue;
            }
            Object tag = v.getTag();
            if (!(tag instanceof MapFeature)) {
                continue;
            }
            v.getHitRect(r);
            if (r.contains((int) x, (int) y)) {
                return (MapFeature) tag;
            }
        }
        return null;
    }

    /**
     * Фокусирует реальное вью объекта — ТОТ же элемент, что и выбранная фича.
     * Так TalkBack озвучит, выделит и отдаст действия одному и тому же вью.
     * Без этого читалка независимо фокусирует верхний перекрывающий вью
     * (соседний объект), и речь/фокус/действия расходятся.
     */
    private void focusObjectView(MapFeature f) {
        if (f == null) {
            return;
        }
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View v = getChildAt(i);
            if (!(v instanceof TextView)) {
                continue;
            }
            Object tag = v.getTag();
            if (tag == f) {
                AccessibilityEvent event = AccessibilityEvent.obtain(
                        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED);
                event.setSource(v);
                event.setClassName(View.class.getName());
                event.setPackageName(getContext().getPackageName());
                event.setContentDescription(v.getContentDescription());
                event.getText().add(v.getContentDescription());
                sendAccessibilityEventUnchecked(event);
                return;
            }
        }
        // Вью не найдено — даём читалке самой выбрать (не мешаем).
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

    private MapFeature nearestNamedFeature(float x, float y, double tLat, double tLon) {
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
            // Попадание внутрь полигона — почти нулевая дистанция:
            // большой объект (магазин, здание) не должен проигрывать соседнему
            // по центроиду. Если палец внутри контура — объект победил.
            if (f.hasPolygon() && f.contains(tLat, tLon)) {
                return f;
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

    private MapFeature nearestAddressOrSignal(float x, float y, double tLat, double tLon) {
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
            if (f.hasPolygon() && f.contains(tLat, tLon)) {
                return f;
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
                // Объект под пальцем: сначала верхняя метка — ТА, которую
                // скринридер фокусирует (иначе озвучивается соседний объект,
                // и чтение по символам/меню уходят на него). Для улиц/пустых
                // мест без метки — точечный hit-test featureNear.
                MapFeature f = featureAtTop(event.getX(), event.getY());
                if (f == null) {
                    f = featureNear(event.getX(), event.getY());
                }
                if (f != null) {
                    int node = indexOfFeature(f);
                    if (node >= 0 && node != lastVirtualNode) {
                        lastVirtualNode = node;
                        lastStreetPhrase = null;
                        // Объекты — это РЕАЛЬНЫЕ вью на карте. Фокусируем именно
                        // то вью, которое соответствует выбранной фиче: тогда
                        // TalkBack озвучит, выделит и отдаст действия ОДНОМУ и
                        // тому же объекту. Иначе читалка фокусирует соседний
                        // перекрывающий вью (code «Степная 25Б»), а приложение
                        // выбирало другой («Быстроном») — рассинхрон речи/фокуса.
                        focusObjectView(f);
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
                // Фокус/озвучку уже обрабатывает sendHoverEnter (по типу читалки).
                if (node != lastVirtualNode) {
                    lastVirtualNode = node;
                    sendHoverEnter(node, phrase);
                }
            } else {
                // Для модифицированных сборок (TalkMan) announcement обязателен,
                // для стокового TalkBack — announcement ведёт в очередь, поэтому
                // здесь, без узла, используем focus на хост-контейнер (ненаправленный).
                if (isModifiedTalkback()) {
                    announceForAccessibility(phrase);
                }
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
                lastTapDownTime = now;
                if (followedStreetName == null && now - lastTapUpTime <= DOUBLE_TAP_MS
                        && Math.hypot(event.getX() - lastTapX, event.getY() - lastTapY)
                                < DOUBLE_TAP_DIST_PX) {
                    Log.d(TAG, "doubleTap x=" + event.getX() + " y=" + event.getY());
                    if (handleDoubleTap(event.getX(), event.getY())) {
                        lastTapUpTime = 0;
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                // Долгое нажатие без движения — контекстное меню объекта.
                if (now - lastTapDownTime >= LONG_PRESS_MS
                        && Math.hypot(event.getX() - lastTapDownX, event.getY() - lastTapDownY)
                                < DOUBLE_TAP_DIST_PX) {
                    Log.d(TAG, "longPress x=" + event.getX() + " y=" + event.getY());
                    if (handleLongPress(event.getX(), event.getY())) {
                        lastTapUpTime = 0;
                        return true;
                    }
                }
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
        // announcement — ЕДИНСТВЕННЫЙ канал, который гарантированно озвучивает
        // объекты ЛЮБЫМ скринридером (и стоковым TalkBack, и Jieshuo/TalkMan,
        // и Backtalk). Проверено на устройстве:
        //   * announcement => объекты озвучиваются (но с очередью);
        //   * фокус на виртуальный узел => стоковый TalkBack произносит ТОЛЬКО
        //     описание контейнера («карта города»), т.к. не умеет рендерить
        //     виртуальные узлы, и сваливается на contentDescription вью;
        //   * ничего => полная тишина.
        // Поэтому announcement шлём ВСЕГДА. Чтобы не было ОЧЕРЕДИ (TalkBack
        // дочитывает announcement-события по одному), перед новой фразой
        // ПРЕРЫВАЕМ предыдущую речь через AccessibilityManager.interrupt().
        // Так чтение идёт как в Poco Launcher: каждый новый объект озвучивается
        // сразу, прерывая предыдущий.
        interruptSpeech();
        announceForAccessibility(phrase);
        // НЕ переносим фокус на узел автоматически: это ломает доставку
        // ACTION_LONG_CLICK/ACTION_CLICK от TalkBack — меню перестаёт открываться,
        // а фокус «разгуливается». TalkBack сам управляет фокусом по bounds.
        // Правильный выбор объекта для меню/маршрута делаем в performAction
        // по точке обводки (lastHoverX/Y) — она точнее, чем virtualViewId.
        Log.d(TAG, "sendHoverEnter node=" + nodeId + " phrase=" + phrase
                + " modified=" + isModifiedTalkback());
        lastSpoken = phrase;
        lastSpokenAt = System.currentTimeMillis();
    }

    /** Прерывает текущую/зацикленную речь скринридера (сброс очереди announcement). */
    private void interruptSpeech() {
        try {
            AccessibilityManager am =
                    (AccessibilityManager) getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null && am.isEnabled()) {
                am.interrupt();
            }
        } catch (Exception e) {
            Log.w(TAG, "interruptSpeech failed", e);
        }
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
        // Фокус на контейнер (HOST) всегда беззвучный: иначе TalkBack
        // произносит «карта города» вместо/вместе с объектом и фокус слетает
        // на неё. Описание на узле объекта берём из phraseFor.
        event.setContentDescription("");
        if (nodeId >= 0 && nodeId < features.size()) {
            MapFeature f = features.get(nodeId);
            if (f != null) {
                event.setContentDescription(phraseFor(f));
            }
        }
        event.getText().add(event.getContentDescription());
        sendAccessibilityEventUnchecked(event);
        lastFocusedNode = nodeId;
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
        // Возвращаем null: карта переходит на СТАНДАРТНОЕ дерево доступности
        // ViewGroup, чтобы TalkBack/TalkMan видели реальные дочерние вью
        // объектов (см. rebuildObjectViews). Пока здесь отдавался
        // MapNodeProvider, Android «виртуализировал» всё поддерево и реальные
        // вью не попадали в дерево доступности.
        return null;
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
                // ХОСТ-узел — корень виртуального дерева. Он ОБЯЗАН быть
                // focusable, иначе скринридер не может в него войти и добраться
                // до виртуальных узлов объектов (стоковый TalkBack тогда просто
                // произносит описание контейнера — «карта города»). Описание
                // оставляем пустым, чтобы фокус на контейнере был беззвучным,
                // а читался именно узел объекта под пальцем.
                info.setFocusable(true);
                info.setClickable(true);
                info.setContentDescription(null);
                getLocationOnScreen(screenLocation);
                Rect bounds = new Rect(screenLocation[0], screenLocation[1],
                        screenLocation[0] + getWidth(), screenLocation[1] + getHeight());
                info.setBoundsInScreen(bounds);
                info.setVisibleToUser(true);
                for (int i = 0; i < features.size(); i++) {
                    info.addChild(AccessibleMapView.this, i);
                }
                // Горизонтальные свайпы на карте — перемещение (не фокус-стоп).
                info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                // Действия на самом ХОСТ-узле (реального вью карты). Эта
                // прошивка TalkBack (со стоковым и TalkMan) НЕ обходит
                // виртуальные узлы объектов — возвращается к кнопкам, а карту
                // читает как единый элемент. Поэтому, чтобы действия вообще
                // появились в штатном меню, вешаем их на узел карты, который
                // TalkBack фокусирует гарантированно. Какой объект обработать —
                // определяем по последней точке обводки (actionTargetFeature).
                info.addAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        ACTION_SHOW_DETAILS,
                        getContext().getString(R.string.menu_details)));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        ACTION_COPY_COORDS,
                        getContext().getString(R.string.menu_copy_coords)));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        ACTION_ROUTE,
                        getContext().getString(R.string.menu_route)));
                Log.d(TAG, "createNode HOST actions ATTACHED"
                        + " details=" + ACTION_SHOW_DETAILS
                        + " copy=" + ACTION_COPY_COORDS
                        + " route=" + ACTION_ROUTE);
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
            // Родитель — хост-узел: без setParent у некоторых сборок
            // TalkBack виртуальный узел не встраивается в дерево, и штатное
            // меню действий (ACTION_ACCESSIBILITY_FOCUS -> список действий)
            // на нём не появляется.
            info.setParent(AccessibleMapView.this, AccessibilityNodeProvider.HOST_VIEW_ID);
            // Улица всегда «видима» в рамках загруженного радиуса: её узел —
            // точка на линии дороги, и по isOnScreen он мог быть за экраном,
            // из-за чего TalkMan не мог на него сфокусироваться.
            info.setVisibleToUser("street".equals(f.type) ? true : isOnScreen(f));
            info.setBoundsInScreen(boundsFor(f));
            info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
            // Для точечных объектов — контекстное меню по долгому нажатию
            // (TalkBack: двойной тап с удержанием).
            if (!"street".equals(f.type) && !"here".equals(f.type)) {
                info.addAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
                // Отдельные действия скринридера, совпадающие с пунктами
                // контекстного меню: пользователь может открыть их напрямую
                // жестом (свайп-меню/двойной тап с выбором), не открывая диалог.
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        ACTION_SHOW_DETAILS,
                        getContext().getString(R.string.menu_details)));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        ACTION_COPY_COORDS,
                        getContext().getString(R.string.menu_copy_coords)));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        ACTION_ROUTE,
                        getContext().getString(R.string.menu_route)));
                Log.d(TAG, "createNode actions ATTACHED id=" + virtualViewId
                        + " type=" + f.type
                        + " details=" + ACTION_SHOW_DETAILS
                        + " copy=" + ACTION_COPY_COORDS
                        + " route=" + ACTION_ROUTE);
            }
            return info;
        }

        @Override
        public boolean performAction(int virtualViewId, int action, android.os.Bundle arguments) {
            Log.d(TAG, "performAction id=" + virtualViewId + " action=" + action
                    + " follow=" + followedStreetName);
            long now = SystemClock.uptimeMillis();
            // Accessibility-фокус: TalkBack ставит фокус на виртуальный узел,
            // чтобы показать его действия. Без этого узлы объектов не получают
            // фокус и штатное меню действий на них не появляется.
            if (action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) {
                lastFocusedNode = virtualViewId;
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS) {
                if (lastFocusedNode == virtualViewId) {
                    lastFocusedNode = -1;
                }
                return true;
            }
            // Долгое нажатие по точечному объекту — контекстное меню.
            if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) {
                // Объект определяем по точке обводки (lastHoverX/Y), а не по
                // virtualViewId: TalkBack может прислать id соседнего узла (их
                // bounds перекрываются), из-за чего меню открывалось «не того
                // объекта». featureNear(lastHoverX,lastHoverY) — точный хит-тест,
                // как при озвучке, поэтому меню открывается для того объекта,
                // на который смотрел пользователь.
                MapFeature f = actionTargetFeature();
                if (f != null && !"street".equals(f.type) && !"here".equals(f.type)) {
                    if (listener != null) {
                        listener.onFeatureMenu(f);
                    }
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    return true;
                }
                return false;
            }
            // Собственные действия скринридера, совпадающие с пунктами меню.
            if (action == ACTION_SHOW_DETAILS || action == ACTION_COPY_COORDS
                    || action == ACTION_ROUTE) {
                MapFeature f = actionTargetFeature();
                Log.d(TAG, "performAction CUSTOM id=" + virtualViewId + " action=" + action
                        + " type=" + (f != null ? f.type : "null"));
                if (f != null && !"street".equals(f.type) && !"here".equals(f.type)) {
                    switch (action) {
                        case ACTION_SHOW_DETAILS:
                            if (listener != null) {
                                listener.onFeatureDetails(f);
                            }
                            break;
                        case ACTION_COPY_COORDS:
                            if (listener != null) {
                                listener.onFeatureCopyCoords(f);
                            }
                            break;
                        case ACTION_ROUTE:
                            if (listener != null) {
                                listener.onRoute(f);
                            }
                            break;
                    }
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    return true;
                }
                return false;
            }
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
                // Точечный объект (здание, магазин, адрес, перекрёсток и т.п.)
                // — двойное нажатие прокладывает маршрут. Улицы обработаны выше.
                if (f != null && !"here".equals(f.type)) {
                    if (listener != null) {
                        listener.onRoute(f);
                    }
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    return true;
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
            if ((focus == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY
                    || focus == AccessibilityNodeInfo.FOCUS_INPUT)
                    && lastFocusedNode >= 0 && lastFocusedNode < features.size()) {
                return createAccessibilityNodeInfo(lastFocusedNode);
            }
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

    /**
     * Двойной тап по карте: улица — включить следование; точечный объект
     * (не улица) — проложить маршрут. Возвращает true, если жест обработан.
     */
    private boolean handleDoubleTap(float x, float y) {
        // 1) Точечный объект под пальцем (здание, магазин, адрес, светофор,
        //    перекрёсток — но НЕ улица и не «вы здесь»): прокладываем маршрут.
        //    Приоритет точке: по требованию двойной тап по объекту — это
        //    маршрут, а не следование по улице.
        MapFeature f = featureNear(x, y);
        if (f != null && !"street".equals(f.type) && !"here".equals(f.type)) {
            if (listener != null) {
                listener.onRoute(f);
            }
            return true;
        }
        // 2) Объекта нет (или это улица/«вы здесь») — пустое место или
        //    проезжая часть: включаем следование по улице.
        if (tryFollowStreetAt(x, y)) {
            return true;
        }
        return false;
    }

    /**
     * Долгое нажатие по карте: открыть контекстное меню объекта.
     * Возвращает true, если под пальцем был объект и меню показано.
     */
    private boolean handleLongPress(float x, float y) {
        // Объект по ВЕРХНЕЙ метке под пальцем — тот же, что фокусируется и
        // озвучивается (featureAtTop). Иначе долгий тап/контекстное меню берут
        // геометрический сосед (featureNear) — и меню открывается «не того»
        // объекта, хотя символы/подробности показывают правильный.
        MapFeature f = featureAtTop(x, y);
        if (f == null) {
            f = featureNear(x, y);
        }
        if (f == null) {
            return false;
        }
        // «Вы здесь» — синтетическая метка, меню для неё не нужно. Но если
        // пользователь стоит ВНУТРИ реального объекта (здание/магазин), меню
        // открываем для него — иначе на «Вы здесь» долгий тап молчит.
        if ("here".equals(f.type)) {
            f = featureUnderHere();
            if (f == null) {
                return false;
            }
        }
        if ("street".equals(f.type)) {
            // Для улиц меню не нужно — озвучим, что это не объект.
            return false;
        }
        if (listener != null) {
            listener.onFeatureMenu(f);
        }
        return true;
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

    /**
     * Объект-цель для ACTION_LONG_CLICK/ACTION_CLICK: приоритет — реальная
     * точка обводки (lastHoverX/Y) через точный хит-тест featureNear, затем
     * fallback на virtualViewId. Так меню/маршрут открываются для того объекта,
     * на который указывает пользователь, а не для соседнего узла с
     * перекрывающимся bounds (который мог прислать TalkBack).
     */
    private MapFeature actionTargetFeature() {
        if (lastHoverX >= 0 && lastHoverY >= 0) {
            // Сначала верхняя метка — ТА, которую фокусирует скринридер. Если
            // брать featureNear (точечный hit-test с приоритетами), при
            // перекрытии меток выбирается сосед и меню открывается «не того»
            // объекта. featureAtTop совпадает с фокусом.
            MapFeature top = featureAtTop(lastHoverX, lastHoverY);
            if (top != null) {
                if ("here".equals(top.type)) {
                    return featureUnderHere();
                }
                return top;
            }
            MapFeature near = featureNear(lastHoverX, lastHoverY);
            if (near != null) {
                // «Вы здесь» — синтетическая метка, а не объект: меню для неё
                // бессмысленно. Если палец на «Вы здесь», меню открываем для
                // того реального объекта, в котором стоит пользователь
                // (здание/магазин), как при обычном долгом нажатии.
                if ("here".equals(near.type)) {
                    return featureUnderHere();
                }
                return near;
            }
        }
        return null;
    }

    /** Реальный объект (не «Вы здесь»), совпадающий с точкой местоположения. */
    private MapFeature featureUnderHere() {
        if (userLocation == null) {
            return null;
        }
        getProjection().toPixels(
                new GeoPoint(userLocation.getLatitude(), userLocation.getLongitude()), tmpPoint);
        float x = tmpPoint.x;
        float y = tmpPoint.y;
        IGeoPoint hereGeo = getProjection().fromPixels((int) x, (int) y);
        double tLat = hereGeo.getLatitude();
        double tLon = hereGeo.getLongitude();
        MapFeature obj = nearestJunction(x, y);
        if (obj == null) {
            obj = nearestNamedFeature(x, y, tLat, tLon);
        }
        if (obj == null) {
            obj = nearestAddressOrSignal(x, y, tLat, tLon);
        }
        if (obj != null && !"here".equals(obj.type)) {
            return obj;
        }
        return null;
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
        // Площадной объект (полигон): рамка должна покрывать ВСЁ здание на
        // экране, иначе скринридер при обводке не находит узел под пальцем
        // (центроид большого полигона далеко от края/входа). Не найдя узел,
        // читалка либо слетает на родителя (карту), либо берёт соседний
        // объект с ближайшей маленькой рамкой — отсюда «меню не того объекта».
        if (f.hasPolygon()) {
            int minX = cx - r, minY = cy - r, maxX = cx + r, maxY = cy + r;
            for (GeoPoint p : f.polygon) {
                getProjection().toPixels(p, tmpPoint);
                int px = screenLocation[0] + tmpPoint.x;
                int py = screenLocation[1] + tmpPoint.y;
                minX = Math.min(minX, px);
                minY = Math.min(minY, py);
                maxX = Math.max(maxX, px);
                maxY = Math.max(maxY, py);
            }
            return new Rect(minX - r, minY - r, maxX + r, maxY + r);
        }
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

    /** Объект по индексу (без «Вы здесь»), либо null. */
    public MapFeature getFeature(int index) {
        if (index < 0 || index >= features.size()) {
            return null;
        }
        MapFeature f = features.get(index);
        return "here".equals(f.type) ? null : f;
    }

    /** Количество реальных объектов (без узла «Вы здесь»). */
    public int getFeatureCount() {
        int n = features.size() - (hereFeature != null ? 1 : 0);
        return Math.max(0, n);
    }

    /** Озвучиваемая фраза объекта — real contentDescription для списка. */
    public String describe(MapFeature f) {
        return f == null ? "" : phraseFor(f);
    }

    // ------------------------------------------------------------------
    // Слой реальных вью объектов на карте. В отличие от виртуальных узлов
    // (которые TalkBack/TalkMan на этой прошивке не обходят) это обычные
    // дочерние TextView, лежащие В ТОЧКЕ объекта на карте. osmdroid сам
    // держит их на месте при панорамировании (MapView.myOnLayout ставит
    // вью по GeoPoint). Скринридер читает их как реальные элементы и может
    // показать меню действий. Кладём только видимые на экране объекты.
    // ------------------------------------------------------------------
    public void rebuildObjectViews() {
        // Снять провайдер виртуальных узлов: пока он есть, Android «забирает»
        // всё поддерево и реальные дочерние вью не попадают в дерево
        // доступности (их не видит ни один скринридер).
        removeObjectViews();
        if (features.isEmpty() || getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        List<MapFeature> visible = new ArrayList<>();
        for (MapFeature f : features) {
            if ("here".equals(f.type)) {
                continue;
            }
            // Экранные координаты объекта: за пределами экрана не кладём.
            if (isOnScreen(f)) {
                visible.add(f);
            }
        }
        for (MapFeature f : visible) {
            addObjectView(f);
        }
        if (!visible.isEmpty()) {
            Log.i(TAG, "object views placed=" + visible.size());
        }
    }

    private void addObjectView(MapFeature f) {
        // Вью объекта — НЕВИДИМАЯ метка. Текст оставляем в `setText`, чтобы у вью
        // был РЕАЛЬНЫЙ размер (bounds) — по нему скринридер точно попадает под
        // палец и фокусирует ИМЕННО этот объект, а не соседнюю кнопку. Но цвет
        // текста полностью прозрачный (alpha=0): зримо подпись не рисуется, и
        // визуальный слой карты остаётся как есть. Скринридеру объект доступен
        // через contentDescription и AccessibilityDelegate.
        TextView tv = new TextView(getContext());
        String phrase = phraseFor(f);
        tv.setText(phrase);
        // Полностью прозрачный текст: занимает место (bounds) но не виден.
        tv.setTextColor(0x00FFFFFF);
        // Прозрачный фон — никакой плашки под текстом.
        tv.setBackgroundColor(0x00000000);
        tv.setPadding(8, 4, 8, 4);
        tv.setTextSize(13);
        tv.setContentDescription(phrase);
        // НЕ делаем кликабельным: иначе подпись перехватывает жест перетаскивания
        // карты (объекты плотные, вертикальный свайп умирает на подписи). Действия
        // приходят скринридеру через AccessibilityDelegate ниже (ACTION_CLICK /
        // ACTION_LONG_CLICK / три пункта меню), а не через физический тап.
        tv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        tv.setClickable(false);
        MapFeature feature = f;
        // Метка на карте — её и фокусирует скринридер (верхняя под пальцем).
        // Сохраняем сам объект, чтобы озвучка совпадала со сфокусированным.
        tv.setTag(feature);
        tv.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setFocusable(true);
                info.setClickable(true);
                info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
                info.addAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        ACTION_SHOW_DETAILS, getContext().getString(R.string.menu_details)));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        ACTION_COPY_COORDS, getContext().getString(R.string.menu_copy_coords)));
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        ACTION_ROUTE, getContext().getString(R.string.menu_route)));
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, android.os.Bundle args) {
                long now = SystemClock.uptimeMillis();
                // TalkMan шлёт «двойное нажатие» как ДВА ACTION_CLICK подряд —
                // гасим второй, как в виртуальных узлах (lastClickAt).
                if (action == AccessibilityNodeInfo.ACTION_CLICK && now - lastClickAt <= DOUBLE_CLICK_MS) {
                    lastClickAt = now;
                    Log.d(TAG, "pair click consumed (object view)");
                    return true;
                }
                if (action == AccessibilityNodeInfo.ACTION_CLICK) {
                    lastClickAt = now;
                    // Пока идёт следование по улице, двойной тап отключает его —
                    // а НЕ открывает маршрут. Раньше сюда попадал соседний объект
                    // (адрес «улица X, дом N» лежит поверх подписи улицы, его type
                    // не «street») — и вместо отключения открывался выбор навигатора.
                    if (followedStreetName != null) {
                        if ("street".equals(feature.type)) {
                            // Если это та же улица — отключим; если другая — переключимся.
                            toggleFollowStreet(feature);
                        } else {
                            detachFollowStreet("Следование по улице "
                                    + followedStreetName + " отключено.");
                        }
                        return true;
                    }
                    if ("street".equals(feature.type)) {
                        toggleFollowStreet(feature);
                    } else {
                        if (listener != null) {
                            listener.onRoute(feature);
                        }
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    }
                    return true;
                }
                if (action == AccessibilityNodeInfo.ACTION_LONG_CLICK) {
                    if (!"street".equals(feature.type) && listener != null) {
                        listener.onFeatureMenu(feature);
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                        return true;
                    }
                    return false;
                }
                if (action == ACTION_SHOW_DETAILS) {
                    fireDetails(feature);
                    return true;
                }
                if (action == ACTION_COPY_COORDS) {
                    fireCopyCoords(feature);
                    return true;
                }
                if (action == ACTION_ROUTE) {
                    fireRoute(feature);
                    return true;
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });
        MapView.LayoutParams lp = new MapView.LayoutParams(
                MapView.LayoutParams.WRAP_CONTENT, MapView.LayoutParams.WRAP_CONTENT,
                new GeoPoint(f.lat, f.lon), MapView.LayoutParams.CENTER, 0, 0);
        addView(tv, lp);
    }

    private void removeObjectViews() {
        // Удаляем только наши объектные вью; кнопки/маркеры не трогаем.
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View v = getChildAt(i);
            if (v instanceof TextView && v.getContentDescription() != null
                    && !v.getContentDescription().toString().isEmpty()
                    && !(v instanceof android.widget.Button)
                    && !(v instanceof android.widget.ImageButton)) {
                removeViewAt(i);
            }
        }
    }

    private void fireDetails(MapFeature f) {
        if (listener != null) {
            listener.onFeatureDetails(f);
        }
    }

    private void fireCopyCoords(MapFeature f) {
        if (listener != null) {
            listener.onFeatureCopyCoords(f);
        }
    }

    private void fireRoute(MapFeature f) {
        if (listener != null) {
            listener.onRoute(f);
        }
    }
}