package it.dhd.oxygencustomizer.weather;

import static androidx.preference.PreferenceManager.getDefaultSharedPreferences;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.job.JobParameters;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;

import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Granularity;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.common.util.concurrent.ListenableFuture;

import java.text.SimpleDateFormat;
import java.util.Locale;

public class WeatherWork extends ListenableWorker {
    final Context mContext;
    private static final String TAG = "WeatherWork";
    private static final boolean DEBUG = true;
    private static final String ACTION_BROADCAST = "it.dhd.oxygencustomizer.WEATHER_UPDATE";
    private static final String ACTION_ERROR = "it.dhd.oxygencustomizer.WEATHER_ERROR";

    private static final String EXTRA_ERROR = "error";

    private static final int EXTRA_ERROR_LOCATION = 1;
    private static final int EXTRA_ERROR_DISABLED = 2;

    private static final float LOCATION_ACCURACY_THRESHOLD_METERS = 50000;
    private static final long OUTDATED_LOCATION_THRESHOLD_MILLIS = 10L * 60L * 1000L; // 10 minutes
    private static final int RETRY_DELAY_MS = 5000;
    private static final int RETRY_MAX_NUM = 5;

    public static final int PERIODIC_UPDATE_JOB_ID = 0;
    public static final int ONCE_UPDATE_JOB_ID = 1;
    private FusedLocationProviderClient fusedLocationClient;

    private volatile HandlerThread mHandlerThread;
    private Handler mHandler;
    private static final SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);


    public WeatherWork(@NonNull Context appContext, @NonNull WorkerParameters workerParams) {
        super(appContext, workerParams);
        mContext = appContext;
    }

    private static final LocationRequest sLocationRequest;

    static {
        sLocationRequest = new LocationRequest.Builder(Priority.PRIORITY_LOW_POWER, 1000 * 60 * 120)
                .setMinUpdateIntervalMillis(1000 * 60 * 60)
                .build();
    }

    @NonNull
    @Override
    public ListenableFuture<Result> startWork() {

        if (mHandlerThread == null) {
            synchronized (WeatherWork.class) {
                if (mHandlerThread == null) {
                    mHandlerThread = new HandlerThread("WeatherService Thread");
                    mHandlerThread.start();
                    mHandler = new Handler(mHandlerThread.getLooper());
                }
            }
        }

        if(Config.isEnabled(mContext))
            updateWeatherFromAlarm();

        return CallbackToFutureAdapter.getFuture(completer -> {
            completer.set(doCheckLocationEnabled() ? Result.success() : Result.retry());
            return completer;
        });
    }

    private void updateWeatherFromAlarm() {
        Config.setUpdateError(mContext, false);

        if (!Config.isEnabled(mContext)) {
            Log.w(TAG, "Service started, but not enabled ... stopping");
            Intent errorIntent = new Intent(ACTION_ERROR);
            errorIntent.putExtra(EXTRA_ERROR, EXTRA_ERROR_DISABLED);
            mContext.sendBroadcast(errorIntent);
            return;
        }

        Config.clearLastUpdateTime(mContext);

        if (DEBUG) Log.d(TAG, "call updateWeather from updateWeatherFromAlarm");
        updateWeather();
    }

    private boolean doCheckLocationEnabled() {
        LocationManager locationManager = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        boolean gpsEnabled = false;
        boolean networkEnabled = false;

        try {
            gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception ex) {
            Log.d(TAG, "doCheckLocationEnabled: " + ex.getMessage());
        }

        try {
            networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception ex) {
            Log.d(TAG, "doCheckLocationEnabled: " + ex.getMessage());
        }

        return gpsEnabled || networkEnabled;
    }

    @SuppressLint("MissingPermission")
    private Location getCurrentLocation() {
        FusedLocationProviderClient fusedLocationClient = LocationServices.getFusedLocationProviderClient(mContext);

        if (!doCheckLocationEnabled()) {
            Log.w(TAG, "locations disabled");
            return null;
        }

        LocationManager lm = (LocationManager) mContext.getSystemService(Context.LOCATION_SERVICE);
        Location location = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
        if (DEBUG) Log.d(TAG, "Current location is " + location);

        if (location != null && location.getAccuracy() > LOCATION_ACCURACY_THRESHOLD_METERS) {
            Log.w(TAG, "Ignoring inaccurate location");
            location = null;
        }

        boolean needsUpdate = location == null;
        if (location != null) {
            long delta = System.currentTimeMillis() - location.getTime();
            needsUpdate = delta > OUTDATED_LOCATION_THRESHOLD_MILLIS;
            if (needsUpdate) {
                Log.w(TAG, "Ignoring too old location from " + dayFormat.format(location.getTime()));
                location = null;
            }
        }

        if (needsUpdate) {
            CurrentLocationRequest currentLocationRequest = new CurrentLocationRequest.Builder()
                    .setGranularity(Granularity.GRANULARITY_COARSE)
                    .setMaxUpdateAgeMillis(3600000)  // Max age of 1 hour
                    .build();

            fusedLocationClient.getCurrentLocation(currentLocationRequest, null)
                    .addOnSuccessListener(mContext.getMainExecutor(), locationResult -> {
                        if (locationResult != null) {
                            if (DEBUG) Log.d(TAG, "Got valid location now update");
                            startWork();
                        } else {
                            Log.w(TAG, "Failed to retrieve location");
                            Intent errorIntent = new Intent(ACTION_ERROR);
                            errorIntent.putExtra(EXTRA_ERROR, EXTRA_ERROR_LOCATION);
                            mContext.sendBroadcast(errorIntent);
                            Config.setUpdateError(mContext, true);
                        }
                    });
        }

        return location;
    }

    private void updateWeather() {
        mHandler.post(() -> {
            WeatherInfo w = null;
            try {
                AbstractWeatherProvider provider = Config.getProvider(mContext);
                int i = 0;
                // retry max 3 times
                while (i < RETRY_MAX_NUM) {
                    if (!Config.isCustomLocation(mContext)) {
                        if (checkPermissions()) {
                            Location location = getCurrentLocation();
                            if (location != null) {
                                w = provider.getLocationWeather(location, Config.isMetric(mContext));
                            } else {
                                Log.w(TAG, "no location yet");
                                // we are outa here
                                break;
                            }
                        } else {
                            Log.w(TAG, "no location permissions");
                            // we are outa here
                            break;
                        }
                    } else if (Config.getLocationId(mContext) != null) {
                        w = provider.getCustomWeather(Config.getLocationId(mContext), Config.isMetric(mContext));
                    } else {
                        Log.w(TAG, "no valid custom location");
                        // we are outa here
                        break;
                    }
                    if (w != null) {
                        Config.setWeatherData(w, mContext);
                        WeatherContentProvider.updateCachedWeatherInfo(mContext);
                        Log.d(TAG, "Weather updated updateCachedWeatherInfo");
                        //WeatherAppWidgetProvider.updateAllWidgets(WeatherUpdateService.this);
                        // we are outa here
                        break;
                    } else {
                        if (!provider.shouldRetry()) {
                            // some other error
                            break;
                        } else {
                            Log.w(TAG, "retry count =" + i);
                            try {
                                Thread.sleep(RETRY_DELAY_MS);
                            } catch (InterruptedException ignored) {
                            }
                        }
                    }
                    i++;
                }
            } finally {
                if (w == null) {
                    // error
                    if (DEBUG) Log.d(TAG, "error updating weather");
                    Config.setUpdateError(mContext, true);
                }
                // send broadcast that something has changed
                Intent updateIntent = new Intent(ACTION_BROADCAST);
                mContext.sendBroadcast(updateIntent);
            }
        });
    }

    private boolean checkPermissions() {
        return mContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                mContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

}

