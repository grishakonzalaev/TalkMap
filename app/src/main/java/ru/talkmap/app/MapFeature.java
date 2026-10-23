package ru.talkmap.app;

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

    public MapFeature(double lat, double lon, String name, String type,
                      String spokenName, String spokenType) {
        this(lat, lon, name, type, spokenName, spokenType, null);
    }

    public MapFeature(double lat, double lon, String name, String type,
                      String spokenName, String spokenType, String direction) {
        this.lat = lat;
        this.lon = lon;
        this.name = name;
        this.type = type;
        this.spokenName = spokenName;
        this.spokenType = spokenType;
        this.direction = direction;
    }
}