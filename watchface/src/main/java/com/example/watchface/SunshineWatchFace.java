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

package com.example.watchface;

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
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateUtils;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface MONOSPACE_TYPEFACE = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL);


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
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTextClockPaint;
        Paint mTextHighTempPaint;
        Paint mTextLowTempPaint;
        Paint mTextDatePaint;
        Paint mTextDateWhitePaint;
        Paint mGrayPaint;
        Paint mSeparatorPaint;
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

        String mHighTemp = "23\u00B0";
        String mLowTemp = " 7\u00B0";
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

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);

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

            mSeparatorPaint = new Paint();
            mSeparatorPaint.setColor(resources.getColor(R.color.sunshine_primary_light));

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
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
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
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
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
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                    Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
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

            if(isInAmbientMode()) {
                canvas.drawText(dateText.toUpperCase(), mXOffsetDate, mYOffsetDate, mTextDateWhitePaint);
            } else {
                canvas.drawText(dateText.toUpperCase(), mXOffsetDate, mYOffsetDate, mTextDatePaint);
            }


            // add Sunshine logo
            canvas.drawBitmap(mSunshineLogoBitmap, mXOffsetLogo, mYOffsetLogo, null);

            // add separator
            //canvas.drawLine(mXOffset, mYOffset + 5, bounds.width() - mXOffset, mYOffset + 5, mSeparatorPaint);

            // add weather icon
            if(isInAmbientMode()){
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
    }
}
