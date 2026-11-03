package ru.talkmap.app;

import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.XYTileSource;

/**
 * Источники тайлов карты: OSM (по умолчанию), Яндекс Карты и 2ГИС.
 *
 * <p>Каждый источник — это {@link XYTileSource} (веб-тайлы XYZ в проекции
 * Web Mercator, как и у OSM), поэтому смена источника не меняет систему
 * координат: центр, масштаб, маркеры, объекты OSM-API и следование по
 * улицам продолжают работать в тех же географических координатах.</p>
 *
 * <p>Порядок значений enum совпадает с порядком пунктов меню выбора
 * источника (см. {@code mainActivity}).</p>
 */
public enum MapSource {
    /** OpenStreetMap — источник по умолчанию (тайлы MAPNIK). */
    OSM("OpenStreetMap", 3, 19,
            new String[]{
                    "https://tile.openstreetmap.org/"
            }),

    /** Яндекс Карты. */
    YANDEX("Яндекс Карты", 3, 19,
            new String[]{
                    // Публичные тайлы Яндекс.Карт (Web Mercator, XYZ).
                    "https://vec01.maps.yandex.net/tiles?l=map&v=4.201&x={x}&y={y}&z={z}"
            }),

    /** 2ГИС. */
    TWOGIS("2ГИС", 3, 19,
            new String[]{
                    // Публичные тайлы 2ГИС (Web Mercator, XYZ).
                    "https://tile2.maps.2gis.com/tiles?x={x}&y={y}&z={z}"
            });

    private final String displayName;
    private final int minZoom;
    private final int maxZoom;
    private final String[] baseUrls;

    MapSource(String displayName, int minZoom, int maxZoom, String[] baseUrls) {
        this.displayName = displayName;
        this.minZoom = minZoom;
        this.maxZoom = maxZoom;
        this.baseUrls = baseUrls;
    }

    /** Отображаемое имя источника (для кнопки и меню). */
    public String displayName() {
        return displayName;
    }

    /** Создаёт тайловый источник osmdroid для этого enum. */
    public ITileSource toTileSource() {
        // Имя источника используется как ключ кэша тайлов; берём укороченное
        // латинское имя (чтобы файлы кэша были корректными).
        String cacheName;
        switch (this) {
            case YANDEX:
                cacheName = "yandex";
                break;
            case TWOGIS:
                cacheName = "2gis";
                break;
            default:
                cacheName = "osm";
                break;
        }
        return new XYTileSource(
                cacheName, minZoom, maxZoom, 256, "© Wikimedia/OSM", baseUrls);
    }
}