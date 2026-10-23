package ru.talkmap.app;

import android.app.Application;

import org.osmdroid.config.Configuration;

/**
 * Настраивает osmdroid (кэш тайлов, пользовательский агент) до первого использования карты.
 */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE));
        Configuration.getInstance().setUserAgentValue(getString(R.string.app_name));
        Configuration.getInstance().setOsmdroidBasePath(getCacheDir());
        Configuration.getInstance().setOsmdroidTileCache(getCacheDir());
    }
}