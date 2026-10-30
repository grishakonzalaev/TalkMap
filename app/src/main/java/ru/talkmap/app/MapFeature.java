package ru.talkmap.app;

import java.util.List;
import java.util.Map;

import org.osmdroid.util.GeoPoint;

/**
 * Один объект карты OpenStreetMap, найденный через Overpass API.
 * Точка с именем/типом, показываемая как озвучиваемый виртуальный узел.
 */
public final class MapFeature {
    public final double lat;
    public final double lon;
    public final String name;
    public final String type; // внутренний идентификатор категории, например building, amenity-hotel
    public final String spokenName;
    public final String spokenType;

    /** Направление улицы ("с севера на юг"), если объект — именованная улица. */
    public final String direction;

    /** Оригинальные теги OSM (key→value) для контекстного меню и подробностей. */
    public final Map<String, String> tags;

    /**
     * Внешний контур полигона для площадных объектов (здания, крупные магазины).
     * Для точечных объектов (узел) — null. Хранится, чтобы хит-тест мог
     * определить попадание пальца ВНУТРЬ объекта, а не по центроиду.
     */
    public final List<GeoPoint> polygon;

    public MapFeature(double lat, double lon, String name, String type,
                      String spokenName, String spokenType) {
        this(lat, lon, name, type, spokenName, spokenType, null, null, null);
    }

    public MapFeature(double lat, double lon, String name, String type,
                      String spokenName, String spokenType, String direction) {
        this(lat, lon, name, type, spokenName, spokenType, direction, null, null);
    }

    public MapFeature(double lat, double lon, String name, String type,
                      String spokenName, String spokenType, String direction,
                      Map<String, String> tags) {
        this(lat, lon, name, type, spokenName, spokenType, direction, tags, null);
    }

    public MapFeature(double lat, double lon, String name, String type,
                      String spokenName, String spokenType, String direction,
                      Map<String, String> tags, List<GeoPoint> polygon) {
        this.lat = lat;
        this.lon = lon;
        this.name = name;
        this.type = type;
        this.spokenName = spokenName;
        this.spokenType = spokenType;
        this.direction = direction;
        this.tags = tags;
        this.polygon = polygon;
    }

    /** Площадной ли объект (имеет внешний контур). */
    public boolean hasPolygon() {
        return polygon != null && polygon.size() >= 3;
    }

    /**
     * Попадает ли точка (lat, lon) внутрь полигона объекта.
     * Алгоритм — ray casting (чёт/нечёт пересечений луча).
     */
    public boolean contains(double plat, double plon) {
        if (!hasPolygon()) {
            return false;
        }
        int n = polygon.size();
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = polygon.get(i).getLongitude();
            double yi = polygon.get(i).getLatitude();
            double xj = polygon.get(j).getLongitude();
            double yj = polygon.get(j).getLatitude();
            boolean intersect = (yi > plat) != (yj > plat)
                    && plon < (xj - xi) * (plat - yi) / (yj - yi) + xi;
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * Расстояние от точки до центра объекта в метрах (для сортировки,
     * когда палец вне полигона, но ближе всего к этому объекту).
     */
    public double distanceMeters(double plat, double plon) {
        double dx = (plat - lat) * 110574.0;
        double dy = (plon - lon) * 6361000.0
                * Math.cos(Math.toRadians((lat + plat) / 2.0));
        return Math.sqrt(dx * dx + dy * dy);
    }
}