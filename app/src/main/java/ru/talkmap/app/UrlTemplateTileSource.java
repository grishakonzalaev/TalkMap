package ru.talkmap.app;

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.MapTileIndex;

/**
 * Тайловый источник, который строит URL по шаблону с плейсхолдерами
 * {@code {z}}, {@code {x}}, {@code {y}} (и опционально {@code {-y}}).
 *
 * <p>В отличие от {@link org.osmdroid.tileprovider.tilesource.XYTileSource},
 * который просто дописывает {@code zoom/x/y} к базовому URL, этот класс
 * корректно подставляет координаты тайла в шаблон. Это нужно для источников
 * вроде Яндекс Карт и 2ГИС, где тайл адресуется как
 * {@code ...?z={z}&x={x}&y={y}}.</p>
 */
public class UrlTemplateTileSource extends OnlineTileSourceBase {

    private final String urlTemplate;

    /**
     * @param name        имя источника (ключ кэша)
     * @param minZoom     минимальный зум
     * @param maxZoom     максимальный зум
     * @param tileSizePx  размер тайла в пикселях (обычно 256)
     * @param copyright   копирайт
     * @param baseUrl     шаблон URL с плейсхолдерами {z},{x},{y}
     */
    public UrlTemplateTileSource(String name, int minZoom, int maxZoom,
                                 int tileSizePx, String copyright, String baseUrl) {
        super(name, minZoom, maxZoom, tileSizePx, copyright, new String[]{baseUrl}, ".png");
        this.urlTemplate = baseUrl;
    }

    @Override
    public String getTileURLString(long pIndex) {
        int zoom = MapTileIndex.getZoom(pIndex);
        int x = MapTileIndex.getX(pIndex);
        int y = MapTileIndex.getY(pIndex);
        String url = urlTemplate
                .replace("{z}", Integer.toString(zoom))
                .replace("{x}", Integer.toString(x))
                .replace("{y}", Integer.toString(y))
                .replace("{-y}", Integer.toString((1 << zoom) - y - 1));
        return url;
    }
}