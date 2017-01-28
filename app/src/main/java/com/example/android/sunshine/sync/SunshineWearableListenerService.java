package com.example.android.sunshine.sync;


import android.os.Bundle;
import android.util.Log;

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

import java.util.Random;

public class SunshineWearableListenerService extends WearableListenerService
        implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener{

    private GoogleApiClient mGoogleApiClient;

    private boolean mConnected = false;

    private static final String TAG = "Sunshine";

    private static final String FORECAST_PATH = "/forecast";
    private static final String HIGH_KEY = "high_temp";

    Random randomGenerator = new Random();

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
        Log.d(TAG, "onMessageReceived");
        if (messageEvent.getPath().equals(FORECAST_PATH)) {
            final String message = new String(messageEvent.getData());
            Log.d(TAG, "Message path received: " + messageEvent.getPath());
            Log.d(TAG, "Message received: " + message);

            // get forecast for received date and send to wearable
            sendTodayForecast();
        }
        else {
            super.onMessageReceived(messageEvent);
            Log.d(TAG, "onMessageReceived - wrong path");
        }
    }

    private void sendTodayForecast() {
        PutDataMapRequest dataMap = PutDataMapRequest.create(FORECAST_PATH);
        dataMap.getDataMap().putString(HIGH_KEY, getHigh());
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

    private String getHigh() {
        int high = randomGenerator.nextInt(100);
        Log.d(TAG, "High temp is " + high);
        return "" + high + "°";
    }
}
