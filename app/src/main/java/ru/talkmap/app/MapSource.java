package ru.talkmap.app;

import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.XYTileSource;

/**
 * Источники тайлов карты: OSM (по умолчанию), Яндекс Карты и 2ГИС.
 *
 * <p>Все источники работают в системе координат Web Mercator (XYZ), как и
 * OSM, поэтому смена источника не меняет географические координаты: центр,
 * масштаб, маркеры, объекты OSM-API и следование по улицам продолжают
 * работать в тех же координатах.</p>
 *
 * <p>Порядок значений enum совпадает с порядком пунктов меню выбора
 * источника (см. {@code MainActivity}).</p>
 */
public enum MapSource {
    /** OpenStreetMap — источник по умолчанию (тайлы MAPNIK). */
    OSM("OpenStreetMap", 3, 19,
            new UrlTemplateTileSource("osm", 3, 19, 256,
                    "© OpenStreetMap contributors",
                    "https://tile.openstreetmap.org/{z}/{x}/{y}.png")),

    /** Яндекс Карты. */
    YANDEX("Яндекс Карты", 3, 19,
            new UrlTemplateTileSource("yandex", 3, 19, 256,
                    "© Яндекс",
                    "https://core-renderer-tiles.maps.yandex.net/tiles?l=map&x={x}&y={y}&z={z}")),

    /** 2ГИС. */
    TWOGIS("2ГИС", 3, 19,
            new UrlTemplateTileSource("2gis", 3, 19, 256,
                    "© 2ГИС",
                    "https://tile2.maps.2gis.com/tiles?x={x}&y={y}&z={z}"));

    private final String displayName;
    private final ITileSource tileSource;

    MapSource(String displayName, int minZoom, int maxZoom, ITileSource tileSource) {
        this.displayName = displayName;
        this.tileSource = tileSource;
    }

    /** Отображаемое имя источника (для кнопки и меню). */
    public String displayName() {
        return displayName;
    }

    /** Готовый тайловый источник osmdroid для этого enum. */
    public ITileSource toTileSource() {
        return tileSource;
    }
}