/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {
    private static final String TAG = "SunshineWatchFace";

    private static final Typeface NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF,
            Typeface.NORMAL);
    private static final Typeface MONOSPACE_TYPEFACE = Typeface.create(Typeface.MONOSPACE,
            Typeface.NORMAL);

    // DataMap
    private static final String FORECAST_PATH   = "/forecast";
    private static final String MAX_TEMP_KEY    = "max_temp";
    private static final String MIN_TEMP_KEY    = "min_temp";
    private static final String WEATHER_ID_KEY  = "weather_id";
    private static final String HUMIDITY_KEY    = "humidity";
    private static final String WIND_KEY        = "wind_speed_and_direction";

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFaceService.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine
            implements GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener {

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextClockPaint;
        Paint mTextHighTempPaint;
        Paint mTextLowTempPaint;
        Paint mTextDatePaint;
        Paint mTextDateWhitePaint;
        Paint mGrayPaint;

        Bitmap mSunshineLogoBitmap;
        Bitmap mWeatherIconBitmap;
        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;
        float mXOffsetLogo;
        float mYOffsetLogo;
        float mXOffsetDate;
        float mYOffsetDate;
        float mYOffsetWeather;
        float mXOffsetWeather;
        float mXOffsetHighTemp;
        float mXOffsetLowTemp;
        float mYOffsetHighTemp;
        float mYOffsetLowTemp;

        // default forecast place holders
        String mHighTemp = "--\u00B0";
        String mLowTemp = "--\u00B0";
        String mHumidityAndWind = getString(R.string.humidity_and_wind_not_available);
        int mWeatherIcon = R.drawable.ic_clear;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        /**
         * Whether the display supports burn-in protection
         */
        boolean mBurnInProtection;

        GoogleApiClient mGoogleApiClient =
                new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFaceService.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextClockPaint = createTextPaint(resources.getColor(R.color.digital_text),
                    NORMAL_TYPEFACE);

            mTextHighTempPaint = createTextPaint(resources.getColor(R.color.digital_text),
                    MONOSPACE_TYPEFACE);
            mTextLowTempPaint = createTextPaint(resources.getColor(R.color.sunshine_primary_light),
                    MONOSPACE_TYPEFACE);

            mTextDatePaint = createTextPaint(resources.getColor(R.color.sunshine_accent),
                    MONOSPACE_TYPEFACE);
            // needed for ambient mode
            mTextDateWhitePaint = createTextPaint(resources.getColor(R.color.digital_text),
                    MONOSPACE_TYPEFACE);

            mSunshineLogoBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.ic_logo);
            mWeatherIconBitmap = BitmapFactory.decodeResource(getResources(), mWeatherIcon);

            mCalendar = Calendar.getInstance();

            // needed for gray weather icon
            mGrayPaint = new Paint();
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);
            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
            mGrayPaint.setColorFilter(filter);
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            mYOffset = resources.getDimension(isRound
                    ? R.dimen.digital_y_offset_round: R.dimen.digital_y_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            mTextClockPaint.setTextSize(textSize);

            mXOffsetLogo = resources.getDimension(isRound
                    ? R.dimen.logo_x_offset_round : R.dimen.logo_x_offset);
            mYOffsetLogo = resources.getDimension(isRound
                    ? R.dimen.logo_y_offset_round : R.dimen.logo_y_offset);

            mXOffsetDate = resources.getDimension(isRound
                    ? R.dimen.date_x_offset_round : R.dimen.date_x_offset);
            mYOffsetDate = resources.getDimension(isRound
                    ? R.dimen.date_y_offset_round : R.dimen.date_y_offset);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.date_text_size_round : R.dimen.date_text_size);
            mTextDatePaint.setTextSize(dateTextSize);
            mTextDateWhitePaint.setTextSize(dateTextSize);

            mXOffsetWeather = resources.getDimension(isRound
                    ? R.dimen.weather_x_offset_round : R.dimen.weather_x_offset);
            mYOffsetWeather = resources.getDimension(isRound
                    ? R.dimen.weather_y_offset_round : R.dimen.weather_y_offset);

            float weatherInfoTextSize = resources.getDimension(isRound
                    ? R.dimen.weather_text_size_round : R.dimen.weather_text_size);
            mTextHighTempPaint.setTextSize(weatherInfoTextSize);
            mTextLowTempPaint.setTextSize(weatherInfoTextSize);

            mXOffsetHighTemp = resources.getDimension(isRound
                    ? R.dimen.high_temp_x_offset_round : R.dimen.high_temp_x_offset);
            mYOffsetHighTemp = resources.getDimension(isRound
                    ? R.dimen.high_temp_y_offset_round : R.dimen.high_temp_y_offset);

            mXOffsetLowTemp = resources.getDimension(isRound
                    ? R.dimen.low_temp_x_offset_round : R.dimen.low_temp_x_offset);
            mYOffsetLowTemp = resources.getDimension(isRound
                    ? R.dimen.low_temp_y_offset_round : R.dimen.low_temp_y_offset);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextClockPaint.setAntiAlias(!inAmbientMode);
                    mTextDatePaint.setAntiAlias(!inAmbientMode);
                    mTextDateWhitePaint.setAntiAlias(!inAmbientMode);
                    mTextHighTempPaint.setAntiAlias(!inAmbientMode);
                    mTextLowTempPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user can request to see additional weather information
                    // Humidity and wind speed direction
                    new RequestForecastTask().execute();
                    Toast.makeText(getApplicationContext(), mHumidityAndWind, Toast.LENGTH_LONG)
                            .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in both ambient and interactive modes.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String clockText = DateUtils.formatDateTime(getApplicationContext(), now,
                    DateUtils.FORMAT_SHOW_TIME
                            | DateUtils.FORMAT_24HOUR);
            canvas.drawText(clockText, mXOffset, mYOffset, mTextClockPaint);

            String dateText = DateUtils.formatDateTime(getApplicationContext(), now,
                    DateUtils.FORMAT_SHOW_DATE
                            | DateUtils.FORMAT_NO_YEAR
                            | DateUtils.FORMAT_SHOW_WEEKDAY
                            | DateUtils.FORMAT_ABBREV_WEEKDAY
                            | DateUtils.FORMAT_ABBREV_MONTH);

            if (isInAmbientMode()) {
                canvas.drawText(dateText.toUpperCase(), mXOffsetDate, mYOffsetDate, mTextDateWhitePaint);
            } else {
                canvas.drawText(dateText.toUpperCase(), mXOffsetDate, mYOffsetDate, mTextDatePaint);
            }

            // add Sunshine logo
            canvas.drawBitmap(mSunshineLogoBitmap, mXOffsetLogo, mYOffsetLogo, null);

            // add weather icon
            if (isInAmbientMode()) {
                canvas.drawBitmap(mWeatherIconBitmap, mXOffsetWeather, mYOffsetWeather, mGrayPaint);
            } else {
                canvas.drawBitmap(mWeatherIconBitmap, mXOffsetWeather, mYOffsetWeather, null);
            }
            // add weather information
            canvas.drawText(mHighTemp, mXOffsetHighTemp, mYOffsetHighTemp, mTextHighTempPaint);
            canvas.drawText(mLowTemp, mXOffsetLowTemp, mYOffsetLowTemp, mTextLowTempPaint);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(Bundle connectionHint) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            /* We request forecast data on connect. This ensures that we always have the
             * freshest data available.
             * Changes in location and units are handled by the mobile device.
             */
            new RequestForecastTask().execute();
        }

        @Override
        public void onConnectionSuspended(int cause) {

            Log.d(TAG, "Connection to Google API client was suspended" + cause);

        }

        @Override
        public void onConnectionFailed(ConnectionResult result) {

            Log.e(TAG, "Failed to connect, with result: " + result);

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {

            for (DataEvent event : dataEvents) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    String path = event.getDataItem().getUri().getPath();
                    if (path.equals(FORECAST_PATH)) {
                        DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
                        DataMap dm = dataMapItem.getDataMap();

                        if(dm != null) {
                            // high temp
                            if (dm.containsKey(MAX_TEMP_KEY))
                                mHighTemp = dm.getString(MAX_TEMP_KEY);

                            // low temp
                            if (dm.containsKey(MIN_TEMP_KEY))
                                mLowTemp = dm.getString(MIN_TEMP_KEY);

                            // weather icon
                            if (dm.containsKey(WEATHER_ID_KEY)) {
                                mWeatherIcon = getIconForWeatherCondition(dm.getInt(WEATHER_ID_KEY));
                                mWeatherIconBitmap = BitmapFactory.decodeResource(getResources(),
                                        mWeatherIcon);
                            }

                            // humidity and wind
                            if (dm.containsKey(HUMIDITY_KEY) && dm.containsKey(WIND_KEY)) {
                                int formatString = R.string.humidity_and_wind_toast;
                                mHumidityAndWind = String.format(getString(formatString),
                                        dm.getString(HUMIDITY_KEY),
                                        dm.getString(WIND_KEY));
                            }

                            invalidate();
                        }
                    }
                }
            }
        }

        private class RequestForecastTask extends AsyncTask<Void, Void, Void> {
            @Override
            protected Void doInBackground(Void... voids) {
                NodeApi.GetConnectedNodesResult nodes =
                        Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).await();
                for (Node node : nodes.getNodes()) {
                    String msg = "date: " + System.currentTimeMillis();
                    MessageApi.SendMessageResult result =
                            Wearable.MessageApi.sendMessage(mGoogleApiClient, node.getId(),
                                    FORECAST_PATH, msg.getBytes()).await();
                    if(!result.getStatus().isSuccess()) {
                        Log.e(TAG, "Forecast request message failed");
                    }
                }

                return null;
            }
        }

        // this was copied from the Sunshine app
        private int getIconForWeatherCondition(int weatherId) {

        /*
         * Based on weather code data for Open Weather Map.
         */
            if (weatherId >= 200 && weatherId <= 232) {
                return R.drawable.ic_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                return R.drawable.ic_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                return R.drawable.ic_rain;
            } else if (weatherId == 511) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                return R.drawable.ic_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                return R.drawable.ic_fog;
            } else if (weatherId == 761 || weatherId == 771 || weatherId == 781) {
                return R.drawable.ic_storm;
            } else if (weatherId == 800) {
                return R.drawable.ic_clear;
            } else if (weatherId == 801) {
                return R.drawable.ic_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                return R.drawable.ic_cloudy;
            } else if (weatherId >= 900 && weatherId <= 906) {
                return R.drawable.ic_storm;
            } else if (weatherId >= 958 && weatherId <= 962) {
                return R.drawable.ic_storm;
            } else if (weatherId >= 951 && weatherId <= 957) {
                return R.drawable.ic_clear;
            }

            Log.e(TAG, "Unknown Weather: " + weatherId);
            return R.drawable.ic_storm;
        }
    }
}
