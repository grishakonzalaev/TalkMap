package ru.talkmap.app;

import org.osmdroid.tileprovider.tilesource.ITileSource;
import org.osmdroid.tileprovider.tilesource.XYTileSource;

/**
 * Источники тайлов карты: OSM (по умолчанию) и 2ГИС.
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
    OSM("OpenStreetMap", true, false,
            new UrlTemplateTileSource("osm", 3, 19, 256,
                    "© OpenStreetMap contributors",
                    "https://tile.openstreetmap.org/{z}/{x}/{y}.png")),

    /** 2ГИС. */
    TWOGIS("2ГИС", false, true,
            new UrlTemplateTileSource("2gis", 3, 19, 256,
                    "© 2ГИС",
                    "https://tile2.maps.2gis.com/tiles?x={x}&y={y}&z={z}"));

    private final String displayName;
    private final boolean osmData;
    private final boolean twoGisData;
    private final ITileSource tileSource;

    MapSource(String displayName, boolean osmData, boolean twoGisData,
              ITileSource tileSource) {
        this.displayName = displayName;
        this.osmData = osmData;
        this.twoGisData = twoGisData;
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

    /** Озвучиваемые объекты берём из OpenStreetMap (Overpass). */
    public boolean usesOsmData() {
        return osmData;
    }

    /** Озвучиваемые объекты берём из 2ГИС (парсинг веб-страницы). */
    public boolean usesTwoGisData() {
        return twoGisData;
    }
}