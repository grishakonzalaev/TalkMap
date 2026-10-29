package ru.talkmap.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.osmdroid.util.GeoPoint;

import java.util.ArrayList;
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
    private ImageButton btnSearch;
    private View searchBar;
    private EditText searchInput;
    private Button searchGo;
    private ListView searchResults;
    private SearchAdapter searchAdapter;
    private NominatimClient nominatim;
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
        btnSearch = findViewById(R.id.btn_search);
        searchBar = findViewById(R.id.search_bar);
        searchInput = findViewById(R.id.search_input);
        searchGo = findViewById(R.id.search_go);
        searchResults = findViewById(R.id.search_results);
        map.setListener(this);

        nominatim = new NominatimClient(this);
        searchAdapter = new SearchAdapter();
        searchResults.setAdapter(searchAdapter);
        setupSearch();

        btnMyLocation.setOnClickListener(v -> {
            // Если только что озвучена улица (обводка), а TalkMan держит фокус
            // на кнопке — его «двойное нажатие» приходит сюда. Это клик по
            // улице, а не по кнопке: включаем следование по улице.
            if (map.handleStreetSelectionClick()) {
                return;
            }
            map.centerOnUser();
        });
        btnZoomIn.setOnClickListener(v -> {
            if (map.handleStreetSelectionClick()) {
                return;
            }
            map.zoomIn();
        });
        btnZoomOut.setOnClickListener(v -> {
            if (map.handleStreetSelectionClick()) {
                return;
            }
            map.zoomOut();
        });
        btnZoomReset.setOnClickListener(v -> {
            if (map.handleStreetSelectionClick()) {
                return;
            }
            map.resetZoom();
        });

        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);

        // Резервная позиция сразу, чтобы карта была видна ещё до GPS-фикса.
        map.getController().setZoom(15.0);
        map.getController().setCenter(DEFAULT_CENTER);

        requestLocationOrFallback();
    }

    private void toggleSearchBar() {
        if (searchBar.getVisibility() == View.VISIBLE) {
            hideSearch();
            return;
        }
        showSearchBar();
    }

    private void showSearchBar() {
        searchBar.setVisibility(View.VISIBLE);
        searchResults.setVisibility(View.GONE);
        searchInput.requestFocus();
        openKeyboard();
        map.announceForAccessibility(getString(R.string.search_prompt));
    }

    private void hideSearch() {
        searchInput.clearFocus();
        searchInput.setText("");
        closeKeyboard();
        searchBar.setVisibility(View.GONE);
        searchResults.setVisibility(View.GONE);
        searchResults.setAdapter(searchAdapter);
        searchAdapter.clear();
        searchGo.setEnabled(true);
        searchGo.setText(R.string.search_go);
    }

    private void setupSearch() {
        btnSearch.setOnClickListener(v -> toggleSearchBar());
        // Тестовая иконка не нужна: активируем по кнопке.
        searchGo.setOnClickListener(v -> performSearch());
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                performSearch();
                return true;
            }
            return false;
        });
        searchResults.setOnItemClickListener((parent, view, position, id) -> {
            NominatimClient.SearchResult r = searchAdapter.get(position);
            if (r == null) {
                return;
            }
            hideSearch();
            map.centerOnSearchResult(r);
        });
    }

    private void performSearch() {
        String query = searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            renderNoResults(R.string.search_no_results);
            return;
        }
        searchGo.setEnabled(false);
        searchGo.setText(R.string.search_loading);
        searchResults.setVisibility(View.GONE);
        nominatim.search(query, new NominatimClient.Callback() {
            @Override
            public void onResult(List<NominatimClient.SearchResult> results) {
                searchGo.setEnabled(true);
                searchGo.setText(R.string.search_go);
                if (results == null || results.isEmpty()) {
                    renderNoResults(R.string.search_no_results);
                    return;
                }
                searchResults.setAdapter(searchAdapter);
                searchAdapter.set(results);
                searchResults.setVisibility(View.VISIBLE);
                searchResults.announceForAccessibility(
                        "Найдено: " + results.size() + ". Первый: " + results.get(0).spokenName());
            }

            @Override
            public void onError(Exception e) {
                searchGo.setEnabled(true);
                searchGo.setText(R.string.search_go);
                renderNoResults(R.string.search_error);
            }
        });
    }

    private void renderNoResults(int resId) {
        searchAdapter.clear();
        String msg = getString(resId);
        searchResults.setVisibility(View.VISIBLE);
        searchResults.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return 1;
            }

            @Override
            public Object getItem(int position) {
                return msg;
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView tv = convertView instanceof TextView ? (TextView) convertView : new TextView(MainActivity.this);
                tv.setText(msg);
                tv.setTextColor(Color.WHITE);
                tv.setPadding(24, 16, 24, 16);
                tv.setTextSize(16);
                return tv;
            }
        });
        searchResults.announceForAccessibility(msg);
    }

    private void openKeyboard() {
        searchInput.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void closeKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        }
    }

    private class SearchAdapter extends BaseAdapter {
        private final List<NominatimClient.SearchResult> items = new ArrayList<>();

        void set(List<NominatimClient.SearchResult> list) {
            items.clear();
            items.addAll(list);
            notifyDataSetChanged();
        }

        void clear() {
            items.clear();
            notifyDataSetChanged();
        }

        NominatimClient.SearchResult get(int position) {
            return position >= 0 && position < items.size() ? items.get(position) : null;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView tv = convertView instanceof TextView ? (TextView) convertView : new TextView(MainActivity.this);
            NominatimClient.SearchResult r = items.get(position);
            tv.setText(r.spokenName());
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(16);
            tv.setPadding(24, 18, 24, 18);
            tv.setContentDescription(r.displayName);
            tv.setClickable(false);
            return tv;
        }
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
    public void onLocationRequested() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            // Свежий фикс при каждом нажатии кнопки «моё местоположение»,
            // а не сохранённая точка с момента запуска приложения.
            requestSingleLocation();
        }
    }

    @Override
    public void onCount(int count) {
        status.setText(getString(R.string.status_objects_count, count));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // После возврата из фона или снятия блокировки карта могла
        // остаться без объектов: обновляем её, не дожидаясь жеста.
        map.showNoLocation();
    }

    @Override
    protected void onDestroy() {
        stopLocationUpdates();
        super.onDestroy();
    }
}