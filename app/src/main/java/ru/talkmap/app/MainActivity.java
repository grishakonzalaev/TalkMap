package ru.talkmap.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.osmdroid.util.GeoPoint;

import java.util.List;

/**
 * Экран-карта. Запрашивает геолокацию, открывает карту в текущем положении
 * и показывает статусные сообщения.
 */
public class MainActivity extends AppCompatActivity
        implements AccessibleMapView.Listener {

    private static final int REQUEST_LOCATION = 100;
    private static final GeoPoint DEFAULT_CENTER = new GeoPoint(55.030204, 82.920430); // Новосибирск

    private AccessibleMapView map;
    private TextView status;
    private ImageButton btnMyLocation;
    private ImageButton btnZoomIn;
    private ImageButton btnZoomOut;
    private ImageButton btnZoomReset;
    private LocationManager locationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationListener locationListener;
    private boolean gotLocation = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        map = findViewById(R.id.map);
        status = findViewById(R.id.status);
        btnMyLocation = findViewById(R.id.btn_my_location);
        btnZoomIn = findViewById(R.id.btn_zoom_in);
        btnZoomOut = findViewById(R.id.btn_zoom_out);
        btnZoomReset = findViewById(R.id.btn_zoom_reset);
        map.setListener(this);

        btnMyLocation.setOnClickListener(v -> {
            map.centerOnUser();
        });
        btnZoomIn.setOnClickListener(v -> map.zoomIn());
        btnZoomOut.setOnClickListener(v -> map.zoomOut());
        btnZoomReset.setOnClickListener(v -> map.resetZoom());

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        // Резервная позиция сразу, чтобы карта была видна ещё до GPS-фикса.
        map.getController().setZoom(15.0);
        map.getController().setCenter(DEFAULT_CENTER);

        requestLocationOrFallback();
    }

    private void requestLocationOrFallback() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            onStatus(R.string.status_waiting_location);
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    REQUEST_LOCATION);
            return;
        }
        requestSingleLocation();
    }

    private void requestSingleLocation() {
        onStatus(R.string.status_waiting_location);
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                gotLocation = true;
                map.setUserLocation(new GeoPoint(location.getLatitude(), location.getLongitude()));
                onStatus(R.string.status_location_found);
                stopLocationUpdates();
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
            }
        };
        try {
            List<String> providers = locationManager.getProviders(true);
            boolean asked = false;
            for (String provider : providers) {
                if (provider.equals(LocationManager.GPS_PROVIDER)
                        || provider.equals(LocationManager.NETWORK_PROVIDER)) {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED
                            || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED) {
                        locationManager.requestLocationUpdates(provider, 1000L, 0f,
                                locationListener, Looper.getMainLooper());
                        asked = true;
                    }
                }
            }
            if (!asked) {
                useLastKnownOrDefault();
                return;
            }
        } catch (SecurityException e) {
            useLastKnownOrDefault();
            return;
        }
        // Если за 25 секунд GPS не дал фикс, берём последнее известное или центр по умолчанию.
        handler.postDelayed(() -> {
            if (!gotLocation) {
                useLastKnownOrDefault();
            }
        }, 25000);
    }

    private void useLastKnownOrDefault() {
        stopLocationUpdates();
        try {
            Location last = null;
            for (String provider : new String[]{LocationManager.NETWORK_PROVIDER,
                    LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                        || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED) {
                    Location l = locationManager.getLastKnownLocation(provider);
                    if (l != null && (last == null || l.getTime() > last.getTime())) {
                        last = l;
                    }
                }
            }
            if (last != null) {
                map.setUserLocation(new GeoPoint(last.getLatitude(), last.getLongitude()));
                onStatus(R.string.status_location_found);
                return;
            }
        } catch (SecurityException e) {
            // fall through
        }
        map.setUserLocation(DEFAULT_CENTER);
        onStatus(R.string.status_location_denied);
    }

    private void stopLocationUpdates() {
        if (locationListener != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {
            }
            locationListener = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION) {
            boolean granted = false;
            for (int r : grantResults) {
                if (r == PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                    break;
                }
            }
            if (granted) {
                requestSingleLocation();
            } else {
                useLastKnownOrDefault();
            }
        }
    }

    @Override
    public void onStatus(int resId) {
        status.setText(resId);
        status.announceForAccessibility(getString(resId));
    }

    @Override
    public void onCount(int count) {
        status.setText(getString(R.string.status_objects_count, count));
    }

    @Override
    protected void onDestroy() {
        stopLocationUpdates();
        super.onDestroy();
    }
}