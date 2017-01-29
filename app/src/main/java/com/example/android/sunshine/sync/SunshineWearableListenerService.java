package com.example.android.sunshine.sync;


import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.example.android.sunshine.data.WeatherContract;
import com.example.android.sunshine.utilities.SunshineDateUtils;
import com.example.android.sunshine.utilities.SunshineWeatherUtils;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi.DataItemResult;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

public class SunshineWearableListenerService extends WearableListenerService
        implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener{

    private GoogleApiClient mGoogleApiClient;
    private boolean mConnected = false;

    private static final String TAG = "Sunshine";

    // DataMap
    private static final String FORECAST_PATH   = "/forecast";
    private static final String MAX_TEMP_KEY    = "max_temp";
    private static final String MIN_TEMP_KEY    = "min_temp";
    private static final String WEATHER_ID_KEY  = "weather_id";
    private static final String HUMIDITY_KEY    = "humidity";
    private static final String WIND_KEY        = "wind_speed_and_direction";

    // Forecast data columns
    public static final String[] FORECAST_PROJECTION = {
            WeatherContract.WeatherEntry.COLUMN_DATE,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_HUMIDITY,
            WeatherContract.WeatherEntry.COLUMN_WIND_SPEED,
            WeatherContract.WeatherEntry.COLUMN_DEGREES,
    };

    // Forecast indices
    public static final int INDEX_WEATHER_DATE          = 0;
    public static final int INDEX_WEATHER_MAX_TEMP      = 1;
    public static final int INDEX_WEATHER_MIN_TEMP      = 2;
    public static final int INDEX_WEATHER_ID            = 3;
    public static final int INDEX_WEATHER_HUMIDITY      = 4;
    public static final int INDEX_WEATHER_WIND_SPEED    = 5;
    public static final int INDEX_WEATHER_DEGREES       = 6;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Wearable service created");

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
        mGoogleApiClient.connect();
    }

    @Override
    public void onConnected(Bundle connectionHint) {
        Log.d(TAG, "Connected to Google API client");
        mConnected = true;
    }

    @Override
    public void onConnectionSuspended(int cause) {
        Log.d(TAG, "Connection to Google API client was suspended");

    }

    @Override
    public void onConnectionFailed(ConnectionResult result) {
        Log.d(TAG, "Connection to Google API client has failed");
        mConnected = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if(mConnected)
            mGoogleApiClient.disconnect();
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        Log.d(TAG, "data events: " + dataEvents);
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {

        if (messageEvent.getPath().equals(FORECAST_PATH)) {
            // message can be ignored, any message is a forecast data request
            final String message = new String(messageEvent.getData());

            // get forecast for today and send to wearable
            sendTodaysForecast();
        }
        else {
            super.onMessageReceived(messageEvent);
            Log.d(TAG, "onMessageReceived - wrong path");
        }
    }

    private void sendTodaysForecast() {
        PutDataMapRequest dataMap = loadTodaysForecast();
        PutDataRequest request = dataMap.asPutDataRequest();

        Wearable.DataApi.putDataItem(mGoogleApiClient, request)
                .setResultCallback(new ResultCallback<DataItemResult>() {
                    @Override
                    public void onResult(DataItemResult dataItemResult) {
                        Log.d(TAG, "Sending forecast was successful: " + dataItemResult.getStatus()
                                .isSuccess());
                    }
                });

    }

    private PutDataMapRequest loadTodaysForecast() {
        PutDataMapRequest dataMap = PutDataMapRequest.create(FORECAST_PATH);

        long dateInMilliseconds = System.currentTimeMillis();
        Uri todaysWeatherUri = WeatherContract.WeatherEntry
                .buildWeatherUriWithDate(SunshineDateUtils.normalizeDate(dateInMilliseconds));

        Cursor todaysWeatherCursor = getApplicationContext().getContentResolver().query(
                todaysWeatherUri,
                FORECAST_PROJECTION,
                null,
                null,
                null);

        if(todaysWeatherCursor != null && todaysWeatherCursor.moveToNext()) {
            // max and min temperatures
            dataMap.getDataMap().putString(MAX_TEMP_KEY,
                    SunshineWeatherUtils.formatTemperature(getApplicationContext(),
                            todaysWeatherCursor.getDouble(INDEX_WEATHER_MAX_TEMP)));
            dataMap.getDataMap().putString(MIN_TEMP_KEY,
                    SunshineWeatherUtils.formatTemperature(getApplicationContext(),
                            todaysWeatherCursor.getDouble(INDEX_WEATHER_MIN_TEMP)));

            // weather icon
            dataMap.getDataMap().putInt(WEATHER_ID_KEY,
                    todaysWeatherCursor.getInt(INDEX_WEATHER_ID));

            // humidity
            dataMap.getDataMap().putString(HUMIDITY_KEY,
                    todaysWeatherCursor.getString(INDEX_WEATHER_HUMIDITY));

            // wind
            dataMap.getDataMap().putString(WIND_KEY,
                    SunshineWeatherUtils.getFormattedWind(getApplicationContext(),
                            todaysWeatherCursor.getFloat(INDEX_WEATHER_WIND_SPEED),
                            todaysWeatherCursor.getFloat(INDEX_WEATHER_DEGREES)));

            todaysWeatherCursor.close();
        }

        return dataMap;
    }
}
