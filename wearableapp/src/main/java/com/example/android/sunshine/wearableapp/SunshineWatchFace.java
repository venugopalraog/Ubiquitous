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

package com.example.android.sunshine.wearableapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.TextUtils;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {

    private static final String TAG = SunshineWatchFace.class.getSimpleName();

    private static final String KEY_TEMPERATURE_HIGHEST = "temperatureHighest";
    private static final String KEY_TEMPERATURE_LOWEST = "temperatureLowest";
    private static final String KEY_WEATHER_ICON = "weatherIcon";
    private static final String SUNSHINE_WEATHER_PATH = "/sunshine_weather";


    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

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

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, DataApi.DataListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTimerPaint;
        Paint mDatePaint;
        Paint mLinePaint;
        Paint mTempHighPaint;
        Paint mTempLowPaint;
        Date date;
        Bitmap weatherBitmap;

        private String highestTemperature = "65";
        private String lowestTemperature = "45";

        boolean mAmbient;
        Calendar mCalendar;
        SimpleDateFormat mSimpleDateFormat;

        GoogleApiClient mGoogleApiClient;


        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mXOffset;
        float mYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

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

            mTimerPaint = new Paint();
            mTimerPaint = createTextPaint(resources.getColor(R.color.digital_text), BOLD_TYPEFACE);

            mDatePaint = new Paint();
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE);

            mLinePaint = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE);
            mTempHighPaint = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE);
            mTempLowPaint = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE);

            mCalendar = Calendar.getInstance();
            mSimpleDateFormat = new SimpleDateFormat("EEE, MMM dd yyyy", Locale.getDefault());

            date = new Date();

            int weatherIconSize = Float.valueOf(getResources().getDimension(R.dimen.weather_icon_size)).intValue();
            weatherBitmap = Bitmap.createScaledBitmap(BitmapFactory.decodeResource(getResources(), R.drawable.ic_light_rain),
                    weatherIconSize, weatherIconSize, false);

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
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
                if (mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
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

            mTimerPaint.setTextSize(textSize);
            mDatePaint.setTextSize(resources.getDimension(R.dimen.digital_date_text_size));
            mTempHighPaint.setTextSize(resources.getDimension(R.dimen.digital_temp_size));
            mTempLowPaint.setTextSize(resources.getDimension(R.dimen.digital_temp_size));

        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
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
                    mTimerPaint.setAntiAlias(!inAmbientMode);
                    mDatePaint.setAntiAlias(!inAmbientMode);
                    mTempHighPaint.setAntiAlias(!inAmbientMode);
                    mTempLowPaint.setAntiAlias(!inAmbientMode);
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

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            date.setTime(now);


            // Draw the background.
            if (isInAmbientMode()) {

                canvas.drawLine(bounds.width() / 2 - 40, bounds.height() / 2, bounds.width() / 2 + 20, bounds.height() / 2, mTimerPaint);

                String timeString = String.format("%d:%02d", mCalendar.get(Calendar.HOUR),
                        mCalendar.get(Calendar.MINUTE));

                float startX = bounds.width() / 2 - mTimerPaint.measureText(timeString) / 2 ;
                float startY = bounds.height() / 2 - 40;

                canvas.drawText(timeString, startX, startY, mTimerPaint);

                float startHighTempX = bounds.width() / 2 - mTempHighPaint.measureText(highestTemperature) / 2  - 25;
                startY = (bounds.height() / 2) + mYOffset;

                canvas.drawText(highestTemperature, startHighTempX, startY, mTempHighPaint);

                startX = startHighTempX + mTempHighPaint.measureText(highestTemperature) + 10;

                canvas.drawText(lowestTemperature, startX, startY, mTempLowPaint);

            } else {

                canvas.drawLine(bounds.width() / 2 - 80, bounds.height() / 2, bounds.width() / 2 + 80, bounds.height() / 2, mTimerPaint);

                String timeString = String.format("%d:%02d:%02d", mCalendar.get(Calendar.HOUR),
                        mCalendar.get(Calendar.MINUTE), mCalendar.get(Calendar.SECOND));


                float startX = bounds.width() / 2 - mTimerPaint.measureText(timeString) / 2;
                float startY = bounds.height() / 2 - mYOffset;

                canvas.drawText(timeString, startX, startY, mTimerPaint);

                String datetext  = mSimpleDateFormat.format(date);

                startX = bounds.width() / 2 - mDatePaint.measureText(datetext) / 2;
                startY = startY + mDatePaint.getTextSize() + 20;

                canvas.drawText(datetext, startX, startY, mDatePaint);

                float startHighTempX = bounds.width() / 2 - mTempHighPaint.measureText(highestTemperature) / 2;
                startY = (bounds.height() / 2) + mYOffset;

                canvas.drawText(highestTemperature, startHighTempX, startY, mTempHighPaint);

                startX = startHighTempX + mTempHighPaint.measureText(highestTemperature) + 10;

                canvas.drawText(lowestTemperature, startX, startY, mTempLowPaint);

                if (weatherBitmap != null) {
                    float startXImage = startHighTempX - weatherBitmap.getScaledWidth(canvas) - 10;
                    canvas.drawBitmap(weatherBitmap, startXImage -10, startY - 30, mTempLowPaint);
                }
            }
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

        private void processDataItem(DataItem dataItem) {
            Log.d(TAG, "processDataItem");

            DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
            final String latestHighestTemperature = dataMap.getString(KEY_TEMPERATURE_HIGHEST);
            final String latestLowestTemperature = dataMap.getString(KEY_TEMPERATURE_LOWEST);

            if (TextUtils.isEmpty(latestHighestTemperature) || TextUtils.isEmpty(latestLowestTemperature))
                return;

            if (!latestHighestTemperature.equalsIgnoreCase(highestTemperature)
                    || !latestLowestTemperature.equalsIgnoreCase(lowestTemperature)) {

                final Asset iconAsset = dataMap.getAsset(KEY_WEATHER_ICON);

                Log.d(TAG, "Received Data :: Low Temperature - " + lowestTemperature + " High Temperature - " + highestTemperature);

                highestTemperature = latestHighestTemperature;
                lowestTemperature = latestLowestTemperature;

                Bitmap weatherIcon = assetToBitmap(iconAsset);
                int weatherIconSize = Float.valueOf(getResources().getDimension(R.dimen.weather_icon_size)).intValue();
                weatherBitmap = Bitmap.createScaledBitmap(weatherIcon, weatherIconSize, weatherIconSize, false);
                invalidate();
            }
        }

        public Bitmap assetToBitmap(Asset asset) {
            if (asset == null)
                return null;

            ConnectionResult result = mGoogleApiClient.blockingConnect(500, TimeUnit.MILLISECONDS);
            if (!result.isSuccess())
                return null;

            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(mGoogleApiClient, asset).await().getInputStream();

            if (assetInputStream == null)
                return null;

            return BitmapFactory.decodeStream(assetInputStream);
        }


        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(TAG, "Connected to Google API Client");

            Wearable.DataApi.addListener(mGoogleApiClient, this);
            Wearable.DataApi.getDataItems(mGoogleApiClient).setResultCallback(new ResultCallback<DataItemBuffer>() {

                @Override
                public void onResult(@NonNull DataItemBuffer dataItems) {
                    Log.d(TAG, "onResult Data Received from App Data Items Count:: " + dataItems.getCount());

                    for (DataItem dataItem : dataItems) {
                        if (!dataItem.getUri().getPath().equalsIgnoreCase(SUNSHINE_WEATHER_PATH)) {
                            Log.d(TAG, "URI Path not matched:: " + dataItem.getUri().getPath());
                            continue;
                        }
                        processDataItem(dataItem);
                    }

                    dataItems.release();
                }
            });
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(TAG, "Connection Suspended to Google API Client");

        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(TAG, "Connection Failed to Google API Client");

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(TAG, "onDataChanged Data Received from App");

            for (DataEvent event : dataEventBuffer) {
                if (event.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }
                DataItem dataItem = event.getDataItem();


                if (!dataItem.getUri().getPath().equalsIgnoreCase(SUNSHINE_WEATHER_PATH)) {
                    continue;
                }

                processDataItem(dataItem);
            }
        }
    }
}
