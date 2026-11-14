/*
 * Copyright (C) 2026 The LineageOS Project
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
package com.android.launcher3;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import com.android.launcher3.statemanager.StateManager.StateListener;

import java.util.Locale;

/**
 * The status tile of the wheel's list home in landscape (ShortcutAndWidgetContainer's two
 * columns): battery, its temperature and memory, as the power menu's stats tell them, in the last
 * slot of the column the dock is not in. Which side it takes is a launcher setting, as is hiding
 * it. A page that gives its left column to a tall widget has no slot for it.
 */
public class WheelHomeStats extends View implements Insettable, PagedView.PageSwitchListener,
        StateListener<LauncherState>, ViewTreeObserver.OnGlobalLayoutListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    /** The launcher setting: SIDE_LEFT or SIDE_RIGHT of the dock, or SIDE_OFF. */
    public static final String PREF_SIDE = "pref_r1_landscape_stats";
    public static final String SIDE_LEFT = "left";
    public static final String SIDE_RIGHT = "right";
    public static final String SIDE_OFF = "off";

    /** As the power menu's stats colour them: charging, warm, almost empty or hot. */
    private static final int GOOD = 0xFF81C995;
    private static final int OK = 0xFFFDD663;
    private static final int BAD = 0xFFF28B82;
    /** Memory is read again this often while the tile shows; the battery tells of itself. */
    private static final long REFRESH_MS = 10_000;

    private Launcher mLauncher;
    private Workspace<?> mWorkspace;
    private final Rect mInsets = new Rect();
    private final Rect mSlot = new Rect();
    private final RectF mTile = new RectF();
    private final Paint mBackground = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mLabel = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mValue = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mExtra = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final int mOnSurface;
    private boolean mTallPage;
    private boolean mListening;

    private int mBatteryPercent = -1;
    private boolean mCharging;
    private boolean mPlugged;
    private float mBatteryCelsius = Float.NaN;
    private float mWatts = Float.NaN;
    private int mRamUsedMb = -1;
    private int mRamTotalMb = -1;

    private final BroadcastReceiver mBattery = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            readBattery(intent);
            readMemory();
            describe();
            invalidate();
        }
    };

    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            if (mListening) {
                readMemory();
                describe();
                invalidate();
                postDelayed(this, REFRESH_MS);
            }
        }
    };

    public WheelHomeStats(Context context) {
        this(context, null);
    }

    public WheelHomeStats(Context context, AttributeSet attrs) {
        super(context, attrs);
        final float density = getResources().getDisplayMetrics().density;
        // The list's rows: BubbleTextView's tile
        mBackground.setColor(context.getColor(android.R.color.system_neutral1_800));
        mOnSurface = context.getColor(android.R.color.system_on_surface_dark);
        mLabel.setColor(context.getColor(android.R.color.system_on_surface_variant_dark));
        mLabel.setTextSize(11 * density);
        mValue.setColor(mOnSurface);
        mValue.setTextSize(21 * density);
        mValue.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mExtra.setColor(context.getColor(android.R.color.system_on_surface_variant_dark));
        mExtra.setTextSize(11 * density);
        setFocusable(false);
    }

    /** The setting: which side of the dock the tile takes, or SIDE_OFF. */
    public static String side(Context context) {
        final String side = LauncherPrefs.getPrefs(context).getString(PREF_SIDE, SIDE_LEFT);
        return SIDE_RIGHT.equals(side) || SIDE_OFF.equals(side) ? side : SIDE_LEFT;
    }

    /**
     * Whether the dock takes the right column's last slot: unless the tile is set to its right,
     * and always beside a widget that has the left column to itself.
     */
    public static boolean dockOnRight(Context context, boolean tallWidgetPage) {
        return tallWidgetPage || !SIDE_RIGHT.equals(side(context));
    }

    /** Called by the launcher once its views are set up. */
    public void setup(Launcher launcher, Workspace<?> workspace) {
        mLauncher = launcher;
        mWorkspace = workspace;
        workspace.addPageSwitchListener(this);
        launcher.getStateManager().addStateListener(this);
        getViewTreeObserver().addOnGlobalLayoutListener(this);
        update();
    }

    private boolean tallPage() {
        final View page = mWorkspace.getPageAt(mWorkspace.getNextPage());
        return page instanceof CellLayout cl && cl.getShortcutsAndWidgets().hasTallWidget();
    }

    private boolean shown() {
        return WheelHome.enabled(getContext())
                && ShortcutAndWidgetContainer.isWheelListSplit(mLauncher.getDeviceProfile())
                && mLauncher.isInState(LauncherState.NORMAL)
                && !SIDE_OFF.equals(side(getContext()))
                && !mTallPage;
    }

    /** Shows or hides the tile, and moves the dock to its side of a page with a tall widget. */
    private void update() {
        if (mLauncher == null) {
            return;
        }
        final boolean tall = tallPage();
        if (tall != mTallPage) {
            mTallPage = tall;
            mLauncher.getHotseat().updateSplitPlacement();
        }
        final boolean shown = shown();
        setVisibility(shown ? VISIBLE : GONE);
        listen(shown && isAttachedToWindow());
    }

    @Override
    public void setInsets(Rect insets) {
        mInsets.set(insets);
        if (mLauncher == null
                || !ShortcutAndWidgetContainer.isWheelListSplit(mLauncher.getDeviceProfile())) {
            update();
            return;
        }
        ShortcutAndWidgetContainer.splitBottomSlot(mLauncher.getDeviceProfile(), getResources(),
                SIDE_RIGHT.equals(side(getContext())), mSlot);
        final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.width = mSlot.width();
        lp.height = mSlot.height();
        lp.leftMargin = mSlot.left;
        lp.topMargin = mSlot.top;
        setLayoutParams(lp);
        update();
    }

    @Override
    public void onPageSwitch() {
        update();
    }

    @Override
    public void onStateTransitionStart(LauncherState toState) {
        // Out of the way of all apps and the overview as they come
        if (toState != LauncherState.NORMAL) {
            setVisibility(GONE);
        }
    }

    @Override
    public void onStateTransitionComplete(LauncherState finalState) {
        update();
    }

    @Override
    public void onGlobalLayout() {
        // A widget comes or goes with the page's items, after the views are set up
        if (mLauncher != null && tallPage() != mTallPage) {
            post(this::update);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (PREF_SIDE.equals(key) && mLauncher != null) {
            mLauncher.getHotseat().updateSplitPlacement();
            setInsets(new Rect(mInsets));
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        LauncherPrefs.getPrefs(getContext()).registerOnSharedPreferenceChangeListener(this);
        if (mLauncher != null) {
            update();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        LauncherPrefs.getPrefs(getContext()).unregisterOnSharedPreferenceChangeListener(this);
        listen(false);
        super.onDetachedFromWindow();
    }

    /** Follows the battery and memory while the tile shows, and only then. */
    private void listen(boolean listen) {
        if (listen == mListening) {
            return;
        }
        mListening = listen;
        if (listen) {
            // Sticky: the battery as it is comes back at once
            readBattery(getContext().registerReceiver(mBattery,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED)));
            readMemory();
            describe();
            invalidate();
            postDelayed(mRefresh, REFRESH_MS);
        } else {
            getContext().unregisterReceiver(mBattery);
            removeCallbacks(mRefresh);
        }
    }

    private void readBattery(Intent intent) {
        if (intent == null) {
            return;
        }
        final int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        mBatteryPercent = level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
        final int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        mCharging = status == BatteryManager.BATTERY_STATUS_CHARGING;
        mPlugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        final int tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        mBatteryCelsius = tenths == Integer.MIN_VALUE ? Float.NaN : tenths / 10f;
        // What goes into or out of the battery: its current, in microamps, at its voltage
        final int millivolts = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        final BatteryManager battery = getContext().getSystemService(BatteryManager.class);
        final int microamps = battery == null ? Integer.MIN_VALUE
                : battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        final float watts = Math.abs(microamps / 1e6f) * millivolts / 1e3f;
        mWatts = microamps == Integer.MIN_VALUE || microamps == 0 || millivolts <= 0
                || watts > 30f ? Float.NaN : watts;
    }

    private void readMemory() {
        final ActivityManager am = getContext().getSystemService(ActivityManager.class);
        if (am == null) {
            return;
        }
        final ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        // The memory the r1 is sold with, as Settings shows it: the size up to the next whole
        // gigabyte, so what the hardware keeps for itself counts as used, not as missing
        final long gigabyte = 1L << 30;
        final long known = Math.max(info.advertisedMem, info.totalMem);
        final long total = (known + gigabyte - 1) / gigabyte * gigabyte;
        mRamTotalMb = (int) (total >> 20);
        mRamUsedMb = (int) ((total - info.availMem) >> 20);
    }

    /** "+4.6 W" while charging, "-1.9 W" on battery, "USB power" plugged in and not charging. */
    private String batteryExtra() {
        if (mPlugged && !mCharging) {
            return getContext().getString(R.string.wheel_home_stats_usb_power);
        }
        return Float.isNaN(mWatts) || mWatts < 0.05f ? ""
                : String.format(Locale.US, "%s%.1f W", mCharging ? "+" : "-", mWatts);
    }

    private static String gigabytes(int mb) {
        final String text = String.format(Locale.US, "%.1f", mb / 1024f);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float density = getResources().getDisplayMetrics().density;
        // As a row of the list: its tile, 4dp in from the slot over and under
        mTile.set(0, 4 * density, getWidth(), getHeight() - 4 * density);
        final float radius = Math.min(28 * density, mTile.height() / 2);
        canvas.drawRoundRect(mTile, radius, radius, mBackground);

        final float pad = 20 * density;
        final float cell = (mTile.width() - 2 * pad) / 3;
        final String unknown = "--";
        drawCell(canvas, pad, cell, R.string.wheel_home_stats_battery,
                mBatteryPercent < 0 ? unknown : mBatteryPercent + "%",
                mCharging ? GOOD : mBatteryPercent >= 0 && mBatteryPercent <= 15 ? BAD
                        : mOnSurface,
                batteryExtra());
        drawCell(canvas, pad + cell, cell, R.string.wheel_home_stats_temp,
                Float.isNaN(mBatteryCelsius) ? unknown : Math.round(mBatteryCelsius) + "°C",
                Float.isNaN(mBatteryCelsius) ? mOnSurface
                        : mBatteryCelsius >= 50 ? BAD : mBatteryCelsius >= 42 ? OK : mOnSurface,
                getContext().getString(R.string.wheel_home_stats_temp_of));
        final int ramPercent = mRamTotalMb > 0 ? mRamUsedMb * 100 / mRamTotalMb : 0;
        drawCell(canvas, pad + 2 * cell, cell, R.string.wheel_home_stats_ram,
                mRamUsedMb < 0 ? unknown : gigabytes(mRamUsedMb) + " G",
                ramPercent >= 90 ? OK : mOnSurface,
                mRamTotalMb <= 0 ? "" : getContext().getString(
                        R.string.wheel_home_stats_ram_of, gigabytes(mRamTotalMb)));

    }

    /** The tile read out: as its values change, not as it is drawn. */
    private void describe() {
        final String unknown = "--";
        setContentDescription(getContext().getString(R.string.wheel_home_stats_description,
                mBatteryPercent < 0 ? unknown : mBatteryPercent + "%",
                Float.isNaN(mBatteryCelsius) ? unknown : Math.round(mBatteryCelsius) + "°C",
                mRamUsedMb < 0 ? unknown : gigabytes(mRamUsedMb) + " G"));
    }

    /** A label, its value under it and a line of detail under that, as the power menu's stats. */
    private void drawCell(Canvas canvas, float x, float width, int label, String value,
            int valueColor, String extra) {
        final float density = getResources().getDisplayMetrics().density;
        final float middle = mTile.centerY();
        canvas.drawText(clip(getContext().getString(label), mLabel, width), x,
                middle - 16 * density, mLabel);
        mValue.setColor(valueColor);
        canvas.drawText(clip(value, mValue, width), x, middle + 8 * density, mValue);
        if (!TextUtils.isEmpty(extra)) {
            canvas.drawText(clip(extra, mExtra, width), x, middle + 25 * density, mExtra);
        }
    }

    private static String clip(String text, TextPaint paint, float width) {
        return TextUtils.ellipsize(text, paint, width - 2, TextUtils.TruncateAt.END).toString();
    }
}
