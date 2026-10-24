/* SPDX-License-Identifier: Apache-2.0 */
package com.android.launcher3;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.android.launcher3.statemanager.StateManager.StateListener;

/**
 * The wheel launcher's tabs: one for each home screen page, over the list of apps (list profiles,
 * WheelHome). A tap goes to the tab; with the wheel, the tabs are a stop above the list, where the
 * side button goes to the next tab and holding it opens the tab's options: rename, add a tab,
 * remove it while empty. A tab is a home screen page, so its apps are dragged onto it and it keeps
 * its place among the others; the names are kept by page, in device protected storage.
 */
public class WheelHomeTabs extends View implements Insettable, PagedView.PageSwitchListener,
        StateListener<LauncherState>, ViewTreeObserver.OnGlobalLayoutListener {

    private static final String PREFS = "wheel_home";
    private static final String NAME = "tab_name_";

    private Launcher mLauncher;
    private Workspace<?> mWorkspace;
    private SharedPreferences mPrefs;
    private int mPages = -1;
    private int mCurrent = -1;
    /** The tabs' names, drawn by the strip itself: a pill under the current one. */
    private String[] mNames = new String[0];
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mPillRect = new RectF();
    /** The tab a long press is on, or -1 for the current one. */
    private int mTouchedTab = -1;
    /** The hold that opened the options, so its release doesn't switch the tab too. */
    private boolean mHeld;

    public WheelHomeTabs(Context context) {
        this(context, null);
    }

    public WheelHomeTabs(Context context, AttributeSet attrs) {
        super(context, attrs);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16,
                getResources().getDisplayMetrics()));
        mPill.setColor(context.getColor(android.R.color.system_primary_dark));
        final int pad = dp(4);
        setPadding(pad, pad, pad, pad);
        final GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(24));
        background.setColor(context.getColor(android.R.color.system_surface_container_high_dark));
        setBackground(background);
        setFocusable(true);
        setClickable(true);
        setOnClickListener(v -> {
            if (mTouchedTab >= 0) {
                mWorkspace.snapToPage(mTouchedTab);
            }
        });
        setOnLongClickListener(v -> {
            if (mTouchedTab >= 0) {
                mWorkspace.snapToPage(mTouchedTab);
            }
            showOptions();
            return true;
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mTouchedTab = tabAt(event.getX());
        }
        return super.onTouchEvent(event);
    }

    /** The tab under x, -1 for none. */
    private int tabAt(float x) {
        final int count = mNames.length;
        if (count == 0) {
            return -1;
        }
        final float width = (getWidth() - getPaddingLeft() - getPaddingRight()) / (float) count;
        return Math.max(0, Math.min(count - 1, (int) ((x - getPaddingLeft()) / width)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int count = mNames.length;
        if (count == 0) {
            return;
        }
        final float left = getPaddingLeft();
        final float top = getPaddingTop();
        final float bottom = getHeight() - getPaddingBottom();
        final float width = (getWidth() - left - getPaddingRight()) / count;
        final float baseline = (top + bottom) / 2 - (mText.ascent() + mText.descent()) / 2;
        final int onPrimary = getContext().getColor(android.R.color.system_on_primary_dark);
        final int onSurface = getContext().getColor(android.R.color.system_on_surface_dark);
        for (int i = 0; i < count; i++) {
            final float x = left + i * width;
            if (i == mCurrent) {
                mPillRect.set(x, top, x + width, bottom);
                canvas.drawRoundRect(mPillRect, dp(20), dp(20), mPill);
            }
            mText.setColor(i == mCurrent ? onPrimary : onSurface);
            final String name = TextUtils.ellipsize(mNames[i], new TextPaint(mText),
                    width - dp(12), TextUtils.TruncateAt.END).toString();
            canvas.drawText(name, x + width / 2, baseline, mText);
        }
    }

    /** Called by the launcher once its views are set up. */
    public void setup(Launcher launcher, Workspace<?> workspace) {
        mLauncher = launcher;
        mWorkspace = workspace;
        mPrefs = getContext().createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // With nothing focused the wheel's first step goes to the apps, not the tabs over them
        workspace.setFocusedByDefault(true);
        workspace.addPageSwitchListener(this);
        launcher.getStateManager().addStateListener(this);
        getViewTreeObserver().addOnGlobalLayoutListener(this);
        getViewTreeObserver().addOnTouchModeChangeListener(inTouchMode -> {
            if (!inTouchMode) {
                focusScreen(mLauncher.getStateManager().getState());
            }
        });
        update();
    }

    /** Whether the tabs show: the wheel's list home on its home screen. */
    private boolean shown() {
        final InvariantDeviceProfile inv = mLauncher.getDeviceProfile().inv;
        return WheelHome.enabled(getContext()) && inv.dbFile != null
                && inv.dbFile.startsWith("launcher_r1_list_")
                && mLauncher.isInState(LauncherState.NORMAL);
    }

    @Override
    public void setInsets(Rect insets) {
        final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        lp.gravity = Gravity.TOP;
        lp.height = dp(48);
        lp.topMargin = insets.top + dp(8);
        lp.leftMargin = insets.left + dp(20);
        lp.rightMargin = insets.right + dp(20);
        setLayoutParams(lp);
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
        if (!isInTouchMode()) {
            focusScreen(finalState);
        }
    }

    /**
     * Under the wheel the focus goes with the screen: into all apps as it opens, its first app,
     * and back home after; else it stays on a row the screen above hides. As the screen
     * settles, and as the wheel turns after touch, which left nothing focused or the handle.
     */
    private void focusScreen(LauncherState state) {
        if (mLauncher == null) {
            return;
        }
        final View focused = mLauncher.getWindow().getDecorView().findFocus();
        if (state == LauncherState.ALL_APPS) {
            final View apps = mLauncher.getAppsView().getActiveRecyclerView();
            // On an app or in the search field; the sheet's handle and the like don't count
            if (apps != null && !isInside(focused, apps)
                    && !(focused instanceof android.widget.EditText)) {
                apps.requestFocus(View.FOCUS_DOWN);
            }
        } else if (state == LauncherState.NORMAL && focused != null
                && !isInside(focused, mWorkspace) && focused != this) {
            mWorkspace.restoreDefaultFocus();
        }
    }

    private static boolean isInside(View view, View group) {
        for (View v = view; v != null;
                v = v.getParent() instanceof View parent ? parent : null) {
            if (v == group) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onGlobalLayout() {
        // Pages come and go as apps are moved; the tabs follow on the next frame, as views
        // added while the layout is still being handed out are never measured
        if (mWorkspace != null && mWorkspace.getPageCount() != mPages) {
            post(this::update);
        }
    }

    private void update() {
        if (mLauncher == null) {
            return;
        }
        final boolean shown = shown();
        setVisibility(shown ? VISIBLE : GONE);
        // The tabs say which page is up: no dots under the list as well
        if (mWorkspace.getPageIndicator() instanceof View dots) {
            dots.setVisibility(shown ? INVISIBLE : VISIBLE);
        }
        if (!shown) {
            return;
        }
        final int pages = mWorkspace.getPageCount();
        final int current = Math.max(0, Math.min(pages - 1, mWorkspace.getNextPage()));
        mPages = pages;
        final String[] names = new String[pages];
        for (int i = 0; i < pages; i++) {
            names[i] = name(i);
        }
        mNames = names;
        mCurrent = current;
        setContentDescription(names.length > 0 ? names[current] : null);
        invalidate();
    }

    /** The tab's name: the one given it, else the first one Apps and the others numbered. */
    private String name(int page) {
        final String saved = mPrefs.getString(NAME + mWorkspace.getScreenIdForPageIndex(page), null);
        if (saved != null && !saved.isEmpty()) {
            return saved;
        }
        return page == 0
                ? getContext().getString(R.string.wheel_home_tab_first)
                : getContext().getString(R.string.wheel_home_tab_numbered, page + 1);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isConfirm(keyCode)) {
            // Held, the options; let go before, the next tab
            event.startTracking();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (isConfirm(keyCode)) {
            mHeld = true;
            showOptions();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isConfirm(keyCode)) {
            if (!mHeld && !event.isCanceled() && mPages > 0) {
                mWorkspace.snapToPage((mWorkspace.getNextPage() + 1) % mPages);
            }
            mHeld = false;
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private static boolean isConfirm(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /** The current tab's options, as a list the wheel walks. */
    void showOptions() {
        final int page = mWorkspace.getNextPage();
        final boolean empty = isEmpty(page);
        final String[] options = {
                getContext().getString(R.string.wheel_home_tab_rename),
                getContext().getString(R.string.wheel_home_tab_add),
                getContext().getString(empty
                        ? R.string.wheel_home_tab_remove
                        : R.string.wheel_home_tab_remove_not_empty),
        };
        new AlertDialog.Builder(getContext())
                .setTitle(name(page))
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0 -> rename(page);
                        case 1 -> addTab();
                        case 2 -> remove(page);
                    }
                })
                .show();
    }

    private boolean isEmpty(int page) {
        final CellLayout layout = (CellLayout) mWorkspace.getPageAt(page);
        return layout == null || layout.getShortcutsAndWidgets().getChildCount() == 0;
    }

    private void rename(int page) {
        final int screenId = mWorkspace.getScreenIdForPageIndex(page);
        final EditText field = new EditText(getContext());
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setText(name(page));
        field.selectAll();
        final FrameLayout box = new FrameLayout(getContext());
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(field);
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.wheel_home_tab_rename)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    final String name = field.getText().toString().trim();
                    mPrefs.edit().putString(NAME + screenId, name).apply();
                    update();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** A new tab at the end; it stays once an app is dragged onto it. */
    private void addTab() {
        final int screenId = LauncherAppState.getInstance(getContext()).getModel()
                .getModelDbController().getNewScreenId();
        mWorkspace.insertNewWorkspaceScreen(screenId);
        update();
        mWorkspace.snapToPage(mWorkspace.getPageCount() - 1);
        Toast.makeText(getContext(), R.string.wheel_home_tab_added, Toast.LENGTH_LONG).show();
    }

    private void remove(int page) {
        if (!isEmpty(page) || mWorkspace.getPageCount() <= 1) {
            Toast.makeText(getContext(), R.string.wheel_home_tab_remove_not_empty,
                    Toast.LENGTH_LONG).show();
            return;
        }
        final int screenId = mWorkspace.getScreenIdForPageIndex(page);
        mPrefs.edit().remove(NAME + screenId).apply();
        // The model has no row for a page without apps, so only the view goes
        mWorkspace.stripEmptyScreens();
        update();
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
