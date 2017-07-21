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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.content.LocalBroadcastManager;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService {

	private static final String TAG = MyWatchFace.class.getSimpleName();

	private static final Typeface NORMAL_TYPEFACE =
			Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

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
		private final WeakReference<MyWatchFace.Engine> mWeakReference;

		public EngineHandler(MyWatchFace.Engine reference) {
			mWeakReference = new WeakReference<>(reference);
		}

		@Override
		public void handleMessage(Message msg) {
			MyWatchFace.Engine engine = mWeakReference.get();
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
		Paint mTextPaint;
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

		/**
		 * Whether the display supports fewer bits for each color in ambient mode. When true, we
		 * disable anti-aliasing in ambient mode.
		 */
		boolean mLowBitAmbient;

		boolean mIsRound;

		Paint mTimePaint;
		Paint mDatePaint;
		Paint mWeatherPaint;

		int mWeatherTextHeight;
		boolean mRegisteredWeatherUpdateReceiver = false;
		long mHigh;
		long mLow;
		Bitmap mWeatherIcon;
		final BroadcastReceiver mWeatherUpdateReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				if (intent != null) {
					mWeatherIcon = WearableUtils.getWeatherIconData(context);
					mHigh = WearableUtils.getHighTemperatureData(context);
					mLow = WearableUtils.getLowTemperatureData(context);
					invalidate();
				}
			}
		};


		@Override
		public void onCreate(SurfaceHolder holder) {
			super.onCreate(holder);

			setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
					.setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
					.setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
					.setShowSystemUiTime(false)
					.setAcceptsTapEvents(true)
					.build());
			Resources resources = MyWatchFace.this.getResources();
			mYOffset = resources.getDimension(R.dimen.digital_y_offset);

			mBackgroundPaint = new Paint();
			mBackgroundPaint.setColor(resources.getColor(R.color.background));

			mTextPaint = new Paint();
			mTextPaint = createTextPaint(resources.getColor(R.color.digital_text));


			mTimePaint = new Paint();
			mTimePaint = createTextPaint(resources.getColor(R.color.digital_text));

			mDatePaint = new Paint();
			mDatePaint = createTextPaint(resources.getColor(R.color.digital_text));

			mWeatherPaint = new Paint();
			mWeatherPaint = createTextPaint(resources.getColor(R.color.digital_text));

			mCalendar = Calendar.getInstance();


			mHigh = WearableUtils.getHighTemperatureData(MyWatchFace.this);
			mLow = WearableUtils.getLowTemperatureData(MyWatchFace.this);

			mWeatherIcon = WearableUtils.getWeatherIconData(MyWatchFace.this);
		}

		@Override
		public void onDestroy() {
			mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
			super.onDestroy();
		}

		private Paint createTextPaint(int textColor) {
			Paint paint = new Paint();
			paint.setColor(textColor);
			paint.setTypeface(NORMAL_TYPEFACE);
			paint.setAntiAlias(true);
			return paint;
		}

		@Override
		public void onVisibilityChanged(boolean visible) {
			super.onVisibilityChanged(visible);

			if (visible) {
				registerReceivers();

				// Update time zone in case it changed while we weren't visible.
				mCalendar.setTimeZone(TimeZone.getDefault());
				invalidate();
			} else {
				unregisterReceivers();
			}

			// Whether the timer should be running depends on whether we're visible (as well as
			// whether we're in ambient mode), so we may need to start or stop the timer.
			updateTimer();
		}

		private void registerReceivers() {
			if (!mRegisteredTimeZoneReceiver) {
				mRegisteredTimeZoneReceiver = true;
				IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
				MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
			}

			if (!mRegisteredWeatherUpdateReceiver) {
				mRegisteredWeatherUpdateReceiver = true;
				IntentFilter filter = new IntentFilter(WearableUtils.ACTION_WEATHER_UPDATED);
				LocalBroadcastManager.getInstance(MyWatchFace.this)
						.registerReceiver(mWeatherUpdateReceiver, filter);
			}
		}

		private void unregisterReceivers() {
			if (mRegisteredTimeZoneReceiver) {
				mRegisteredTimeZoneReceiver = false;
				MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
			}

			if (mRegisteredWeatherUpdateReceiver) {
				mRegisteredWeatherUpdateReceiver = false;
				LocalBroadcastManager.getInstance(MyWatchFace.this)
						.unregisterReceiver(mWeatherUpdateReceiver);
			}
		}

		@Override
		public void onApplyWindowInsets(WindowInsets insets) {
			super.onApplyWindowInsets(insets);

			Log.d(TAG, "onApplyWindowInsets");
			// Load resources that have alternate values for round watches.
			Resources resources = MyWatchFace.this.getResources();
			mIsRound = insets.isRound();

			float timeTextSize = resources.getDimension(mIsRound
					? R.dimen.time_text_size_round : R.dimen.time_text_size);
			mTimePaint.setTextSize(timeTextSize);

			float dateTextSize = resources.getDimension(mIsRound
					? R.dimen.date_text_size_round : R.dimen.date_text_size);
			mDatePaint.setTextSize(dateTextSize);

			float weatherTextSize = resources.getDimension(mIsRound
					? R.dimen.weather_text_size_round : R.dimen.weather_text_size);
			mWeatherPaint.setTextSize(weatherTextSize);
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
				Resources resources = MyWatchFace.this.getResources();

				if (mAmbient) {
					mTimePaint.setColor(resources.getColor(R.color.digital_text_ambient));
					mDatePaint.setColor(resources.getColor(R.color.digital_text_ambient));
					mWeatherPaint.setColor(resources.getColor(R.color.digital_text_ambient));
				} else {
					mTimePaint.setColor(resources.getColor(R.color.digital_text));
					mDatePaint.setColor(resources.getColor(R.color.digital_text_ambient));
					mWeatherPaint.setColor(resources.getColor(R.color.digital_text_ambient));
				}

				if (mLowBitAmbient) {
					mTimePaint.setAntiAlias(!inAmbientMode);
					mDatePaint.setAntiAlias(!inAmbientMode);
					mWeatherPaint.setAntiAlias(!inAmbientMode);
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
			Resources resources = MyWatchFace.this.getResources();

			if (isInAmbientMode()) {
				canvas.drawColor(resources.getColor(R.color.background_ambient));
			} else {
				canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
			}

			long now = System.currentTimeMillis();
			mCalendar.setTimeInMillis(now);

			String timeText;
			if (mAmbient) {
				DateFormat f = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault());
				timeText = f.format(mCalendar.getTime());
			} else {
				DateFormat f = DateFormat.getTimeInstance(DateFormat.DEFAULT, Locale.getDefault());
				timeText = f.format(mCalendar.getTime());
			}

			float timeXOffset = getXOffset(timeText, mTimePaint, bounds);
			float timeYOffset = getTimeYOffset(timeText, mTimePaint, bounds);
			canvas.drawText(timeText, timeXOffset, timeYOffset, mTimePaint);

			DateFormat f = DateFormat.getDateInstance(DateFormat.SHORT, Locale.getDefault());
			String dateText = f.format(mCalendar.getTime());

			float dateXOffset = getXOffset(dateText, mDatePaint, bounds);
			float dateYOffset = getDateYOffset(dateText, mDatePaint);
			canvas.drawText(dateText, dateXOffset, timeYOffset + dateYOffset, mDatePaint);


			if (mWeatherIcon != null) {
				// High and Low temperatures text
				String highLow = resources.getString(R.string.temperature_text, mHigh, mLow);
				float weatherXOffset = getXOffset(highLow, mWeatherPaint, bounds);
				float weatherYOffset = getWeatherYOffset(highLow, mWeatherPaint);
				float weatherTextSize = resources.getDimension(
						mIsRound ? R.dimen.weather_text_size_round : R.dimen.weather_text_size);
				mWeatherPaint.setTextSize(weatherTextSize);
				canvas.drawText(highLow, weatherXOffset, timeYOffset + dateYOffset +
						weatherYOffset, mWeatherPaint);

				// Weather icon
				float weatherIconXOffset = getXWeatherIconOffset(mWeatherIcon, bounds);
				canvas.drawBitmap(mWeatherIcon, weatherIconXOffset,
						timeYOffset + dateYOffset + weatherYOffset, null);
			} else {
				// Need to sync text
				String needToSync = resources.getString(R.string.please_sync_sunshine);
				float weatherXOffset = getXOffset(needToSync, mWeatherPaint, bounds);
				float weatherYOffset = getWeatherYOffset(needToSync, mWeatherPaint);
				mWeatherPaint.setTextSize(resources.getDimension(R.dimen.need_to_sync_text_size));
				canvas.drawText(needToSync, weatherXOffset, timeYOffset + dateYOffset +
						weatherYOffset, mWeatherPaint);
			}
		}

		private float getXOffset(String text, Paint paint, Rect watchBounds) {
			float centerX = watchBounds.exactCenterX();
			float timeLength = paint.measureText(text);
			return centerX - timeLength / 2;
		}

		private float getXWeatherIconOffset(Bitmap bitmap, Rect watchBounds) {
			float centerX = watchBounds.exactCenterX();
			float iconLength = bitmap.getWidth();
			return centerX - iconLength / 2;
		}

		private float getTimeYOffset(String timeText, Paint timePaint, Rect watchBounds) {
			float centerY = watchBounds.exactCenterY();
			Rect textBounds = new Rect();
			timePaint.getTextBounds(timeText, 0, timeText.length(), textBounds);

			Resources resources = MyWatchFace.this.getResources();
			float offset = resources.getDimension(
					mIsRound ? R.dimen.time_y_offset_round : R.dimen.time_y_offset);

			return centerY / 3 + textBounds.height() + offset;
		}

		private float getDateYOffset(String dateText, Paint datePaint) {
			Rect textBounds = new Rect();
			datePaint.getTextBounds(dateText, 0, dateText.length(), textBounds);

			Resources resources = MyWatchFace.this.getResources();
			float offset = resources.getDimension(
					mIsRound ? R.dimen.date_y_offset_round : R.dimen.date_y_offset);

			return textBounds.height() + offset;
		}

		private float getWeatherYOffset(String weatherText, Paint weatherPaint) {
			Rect textBounds = new Rect();
			weatherPaint.getTextBounds(weatherText, 0, weatherText.length(), textBounds);
			mWeatherTextHeight = textBounds.height();

			Resources resources = MyWatchFace.this.getResources();
			float offset = resources.getDimension(mIsRound
					? R.dimen.weather_y_offset_round : R.dimen.weather_y_offset);
			return textBounds.height() + offset;
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
