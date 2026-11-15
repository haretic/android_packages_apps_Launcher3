/*
 * Copyright (C) 2008 The Android Open Source Project
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

import static android.view.MotionEvent.ACTION_DOWN;

import static com.android.launcher3.CellLayout.FOLDER;
import static com.android.launcher3.CellLayout.HOTSEAT;
import static com.android.launcher3.CellLayout.WORKSPACE;
import static com.android.launcher3.util.MultiTranslateDelegate.INDEX_BUBBLE_ADJUSTMENT_ANIM;
import static com.android.launcher3.util.MultiTranslateDelegate.INDEX_WIDGET_CENTERING;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Trace;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.android.launcher3.CellLayout.ContainerType;
import com.android.launcher3.celllayout.CellLayoutLayoutParams;
import com.android.launcher3.folder.FolderIcon;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.views.ActivityContext;
import com.android.launcher3.widget.LauncherAppWidgetHostView;
import com.android.launcher3.widget.NavigableAppWidgetHostView;
import com.android.launcher3.widget.util.WidgetSizeHandler;

import java.util.ArrayList;
import java.util.Comparator;

public class ShortcutAndWidgetContainer extends ViewGroup implements FolderIcon.FolderIconParent {
    static final String TAG = "ShortcutAndWidgetContainer";

    // These are temporary variables to prevent having to allocate a new object just to
    // return an (x, y) value from helper functions. Do NOT use them to maintain other state.
    private final int[] mTmpCellXY = new int[2];

    @ContainerType
    private final int mContainerType;
    private final WallpaperManager mWallpaperManager;

    private int mCellWidth;
    private int mCellHeight;
    private Point mBorderSpace;

    private int mCountX;
    private int mCountY;

    private final ActivityContext mActivity;
    private boolean mInvertIfRtl = false;
    public boolean mHasOnLayoutBeenCalled = false;

    private boolean mLastSplitWidgetSizes;

    @Nullable
    private TranslationProvider mTranslationProvider = null;

    public ShortcutAndWidgetContainer(Context context, @ContainerType int containerType) {
        super(context);
        mActivity = ActivityContext.lookupContext(context);
        mWallpaperManager = WallpaperManager.getInstance(context);
        mContainerType = containerType;
        setClipChildren(false);
    }

    public void setCellDimensions(int cellWidth, int cellHeight, int countX, int countY,
            Point borderSpace) {
        mCellWidth = cellWidth;
        mCellHeight = cellHeight;
        mCountX = countX;
        mCountY = countY;
        mBorderSpace = borderSpace;
    }

    public View getChildAt(int cellX, int cellY) {
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            CellLayoutLayoutParams lp = (CellLayoutLayoutParams) child.getLayoutParams();

            if ((lp.getCellX() <= cellX) && (cellX < lp.getCellX() + lp.cellHSpan)
                    && (lp.getCellY() <= cellY) && (cellY < lp.getCellY() + lp.cellVSpan)) {
                return child;
            }
        }
        return null;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int count = getChildCount();
        final boolean split = isSplit();
        final boolean updateWidgetSizes = split != mLastSplitWidgetSizes;
        mLastSplitWidgetSizes = split;

        int widthSpecSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSpecSize = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(widthSpecSize, heightSpecSize);

        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                measureChild(child);
                if (updateWidgetSizes && child instanceof LauncherAppWidgetHostView widget) {
                    final CellLayoutLayoutParams lp =
                            (CellLayoutLayoutParams) child.getLayoutParams();
                    // Restore the original provider options when leaving the split layout.
                    WidgetSizeHandler.updateSizeRanges(widget, lp.cellHSpan, lp.cellVSpan);
                }
            }
        }
    }

    /**
     * Adds view to Layout a new position and it does not trigger a layout request.
     * For more information check documentation for
     * {@code ViewGroup#addViewInLayout(View, int, LayoutParams, boolean)}
     *
     * @param child view to be added
     * @return true if the child was added, false otherwise
     */
    public boolean addViewInLayout(View child, LayoutParams layoutParams) {
        return super.addViewInLayout(child, -1, layoutParams, true);
    }

    public void setupLp(View child) {
        CellLayoutLayoutParams lp = (CellLayoutLayoutParams) child.getLayoutParams();
        if (child instanceof NavigableAppWidgetHostView) {
            DeviceProfile profile = mActivity.getDeviceProfile();
            final PointF appWidgetScale = profile.getAppWidgetScale((ItemInfo) child.getTag());
            lp.setup(mCellWidth, mCellHeight, invertLayoutHorizontally(), mCountX, mCountY,
                    appWidgetScale.x, appWidgetScale.y, mBorderSpace,
                    profile.getWorkspaceProfile().getWidgetPadding());
        } else {
            lp.setup(mCellWidth, mCellHeight, invertLayoutHorizontally(), mCountX, mCountY,
                    mBorderSpace);
        }
        splitForLandscape(child, lp);
    }

    // Set whether or not to invert the layout horizontally if the layout is in RTL mode.
    public void setInvertIfRtl(boolean invert) {
        mInvertIfRtl = invert;
    }

    public int getCellContentHeight() {
        return Math.min(getMeasuredHeight(),
                mActivity.getDeviceProfile().getCellContentHeight(mContainerType));
    }

    /**
     * Whether the wheel's list home shows in two columns: in landscape, its one column of rows
     * split in two, the first half of the rows on the left and the rest on the right.
     */
    public static boolean isWheelListSplit(DeviceProfile dp) {
        return dp.getDeviceProperties().isLandscape() && dp.inv.numColumns == 1
                && dp.inv.dbFile != null && dp.inv.dbFile.startsWith("launcher_r1_list_");
    }

    /**
     * Moves an item of the list home's workspace to its place in the two landscape columns. The
     * cells stay as they are, so the wheel goes through the rows in the same order either way.
     */
    private void splitForLandscape(View child, CellLayoutLayoutParams lp) {
        if (!isSplit()) {
            return;
        }
        if (!lp.isLockedToGrid) {
            return;
        }
        final int cellY = splitCellY(lp);
        splitCellRect(cellY, lp.cellVSpan, mTmpSplitRect);
        if (child instanceof NavigableAppWidgetHostView) {
            mTmpSplitRect.inset(
                    mActivity.getDeviceProfile().getWorkspaceProfile().getWidgetPadding());
        }

        lp.x = mTmpSplitRect.left;
        lp.width = Math.max(1, mTmpSplitRect.width());
        lp.y = mTmpSplitRect.top;
        lp.height = Math.max(1, mTmpSplitRect.height());
    }

    private final android.graphics.Rect mTmpSplitRect = new android.graphics.Rect();

    /** Whether this page shows its rows in the two landscape columns of the list home. */
    public boolean isSplit() {
        return mContainerType == WORKSPACE && mCountX == 1 && mCountY >= 2
                && WheelHome.enabled(getContext())
                && isWheelListSplit(mActivity.getDeviceProfile());
    }

    private int splitColumnWidth() {
        final int gap = getResources().getDimensionPixelSize(R.dimen.r1_list_column_gap);
        return (mCellWidth * mCountX - gap) / 2;
    }

    private int splitRowPitch() {
        return splitRowPitch(mActivity.getDeviceProfile(), getResources());
    }

    /**
     * The distance between the rows of the list home's two landscape columns: the right column's
     * rows and the dock under them share the height under the tabs.
     */
    public static int splitRowPitch(DeviceProfile dp, android.content.res.Resources res) {
        final int half = (dp.inv.numRows + 1) / 2;
        final int available = dp.getDeviceProperties().getHeightPx() - splitCellsTop(dp)
                - res.getDimensionPixelSize(R.dimen.r1_list_bottom_space_land);
        // Up to half again a portrait row, so a short page does not get giant tiles
        return Math.max(splitRowHeight(res) / 2,
                Math.min(available / (half + 1), splitRowHeight(res) * 3 / 2));
    }

    /** Where the rows of the list home's landscape columns start, from the top of the screen. */
    public static int splitCellsTop(DeviceProfile dp) {
        return dp.getDeviceProperties().getInsets().top
                + dp.getWorkspaceProfile().getWorkspacePadding().top
                + dp.getWorkspaceProfile().getCellLayoutPaddingPx().top;
    }

    /**
     * Where rows cellY to cellY + span - 1 show in the two landscape columns, relative to this
     * container: the first half of the rows on the left, the rest on the right.
     */
    void splitCellRect(int cellY, int span, android.graphics.Rect out) {
        final int half = (mCountY + 1) / 2;
        final int gap = getResources().getDimensionPixelSize(R.dimen.r1_list_column_gap);
        final int columnWidth = splitColumnWidth();
        final int pitch = splitRowPitch();
        // A prospective drop must use the incoming span even before its view is on this page.
        if (span > mCountY / 2 || cellY < half && cellY + span > half) {
            final int rows = span > mCountY / 2 ? half + 1 : span;
            out.set(0, 0, columnWidth, rows * pitch - mBorderSpace.y);
            return;
        }
        final CellLayoutLayoutParams leftWidget = findLeftColumnWidget();
        if (leftWidget != null) {
            final int top = splitCellY(leftWidget);
            if (cellY >= top && cellY < top + leftWidget.cellVSpan) {
                final int height = splitWidgetHeight(leftWidget);
                out.set(
                        0,
                        (cellY - top) * height / leftWidget.cellVSpan,
                        columnWidth,
                        (cellY - top + span) * height / leftWidget.cellVSpan);
                return;
            }
            // The rows above the widget from the top, the rows below it up from the bottom:
            // a row that is the dock's neighbour under the widget in portrait stays its
            // neighbour here, not at the top with a hole between them
            final int index = cellY < top ? cellY : half - (mCountY - cellY);
            final int y = Math.max(0, index) * pitch;
            out.set(columnWidth + gap, y, 2 * columnWidth + gap, y + span * pitch - mBorderSpace.y);
            return;
        }
        final int column = cellY >= half ? 1 : 0;
        final int row = cellY - column * half;
        span = Math.max(1, Math.min(span, half - row));
        final int x = column * (columnWidth + gap);
        final int y = row * pitch;
        out.set(x, y, x + columnWidth, y + span * pitch - mBorderSpace.y);
    }

    private static int splitCellY(CellLayoutLayoutParams lp) {
        return lp.useTmpCoords ? lp.getTmpCellY() : lp.getCellY();
    }

    private int splitWidgetHeight(CellLayoutLayoutParams lp) {
        final int rows = lp.cellVSpan > mCountY / 2 ? (mCountY + 1) / 2 + 1 : lp.cellVSpan;
        return rows * splitRowPitch() - mBorderSpace.y;
    }

    // Where a drag would drop on this page, while the page shows it: its row and rows
    private int mSplitDropCellY = -1;
    private int mSplitDropSpan;
    private final CellLayoutLayoutParams mSplitDropLp = new CellLayoutLayoutParams(0, 0, 1, 1);

    /**
     * Says where a drag would drop on this page, -1 for nowhere. A widget that would take the
     * left landscape column decides where every other row shows. Its own view still holds the
     * cell it was picked up from, so without this the rows around a dragged widget were
     * previewed for the place it had left and jumped to the other column on the drop.
     */
    void setSplitDrop(int cellY, int span) {
        if (cellY == mSplitDropCellY && span == mSplitDropSpan) {
            return;
        }
        mSplitDropCellY = cellY;
        mSplitDropSpan = span;
        if (isSplit()) {
            requestLayout();
        }
    }

    /** Also keep a shorter widget crossing the column break intact while resizing. */
    private CellLayoutLayoutParams findLeftColumnWidget() {
        final int half = (mCountY + 1) / 2;
        if (mSplitDropCellY >= 0 && (mSplitDropSpan > mCountY / 2
                || mSplitDropCellY < half && mSplitDropCellY + mSplitDropSpan > half)) {
            mSplitDropLp.setCellY(mSplitDropCellY);
            mSplitDropLp.cellVSpan = mSplitDropSpan;
            return mSplitDropLp;
        }
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (mSplitDropCellY >= 0 && child.getVisibility() != VISIBLE) {
                // The one being dragged, hidden where it was picked up: the drop says where
                // it goes
                continue;
            }
            if (child instanceof NavigableAppWidgetHostView
                    && child.getLayoutParams() instanceof CellLayoutLayoutParams lp
                    && (lp.cellVSpan > mCountY / 2
                            || splitCellY(lp) < half && splitCellY(lp) + lp.cellVSpan > half)) {
                return lp;
            }
        }
        return null;
    }

    private CellLayoutLayoutParams findTallWidget() {
        final CellLayoutLayoutParams lp = findLeftColumnWidget();
        return lp != null && lp.cellVSpan > mCountY / 2 ? lp : null;
    }

    /** The native split-widget size, also reported to providers for this device profile. */
    public static android.util.Size splitWidgetSize(
            DeviceProfile dp, android.content.res.Resources res, int spanY) {
        final Rect slot = new Rect();
        splitBottomSlot(dp, res, false, slot);
        final Rect padding = dp.getWorkspaceProfile().getWidgetPadding();
        final int rows = spanY > dp.inv.numRows / 2 ? (dp.inv.numRows + 1) / 2 + 1 : spanY;
        return new android.util.Size(
                Math.max(1, slot.width() - padding.left - padding.right),
                Math.max(
                        1,
                        rows * splitRowPitch(dp, res)
                                - dp.getWorkspaceProfile().getCellLayoutBorderSpacePx().y
                                - padding.top
                                - padding.bottom));
    }

    /** Whether a widget has the left landscape column of this page of the list home to itself. */
    public boolean hasTallWidget() {
        return isSplit() && findTallWidget() != null;
    }

    /**
     * The last slot of a landscape column of the list home, under its rows, in the launcher's
     * window: the dock's, and the status tile's beside it.
     */
    public static void splitBottomSlot(DeviceProfile dp, android.content.res.Resources res,
            boolean right, android.graphics.Rect out) {
        final com.android.launcher3.deviceprofile.WorkspaceProfile wp = dp.getWorkspaceProfile();
        final android.graphics.Rect insets = dp.getDeviceProperties().getInsets();
        final int left = insets.left + wp.getWorkspacePadding().left
                + wp.getCellLayoutPaddingPx().left;
        final int end = dp.getDeviceProperties().getWidthPx() - insets.right
                - wp.getWorkspacePadding().right - wp.getCellLayoutPaddingPx().right;
        final int gap = res.getDimensionPixelSize(R.dimen.r1_list_column_gap);
        final int columnWidth = (end - left - gap) / 2;
        final int pitch = splitRowPitch(dp, res);
        final int x = left + (right ? columnWidth + gap : 0);
        final int y = splitCellsTop(dp) + ((dp.inv.numRows + 1) / 2) * pitch;
        out.set(x, y, x + columnWidth, y + pitch - wp.getCellLayoutBorderSpacePx().y);
    }

    /** The row under a point of this container in the two landscape columns. */
    int splitPointToCellY(int x, int y) {
        final int half = (mCountY + 1) / 2;
        final int gap = getResources().getDimensionPixelSize(R.dimen.r1_list_column_gap);
        final int column = x >= splitColumnWidth() + gap / 2 ? 1 : 0;
        final CellLayoutLayoutParams tall = findLeftColumnWidget();
        if (tall != null) {
            final int top = splitCellY(tall);
            final int others = mCountY - tall.cellVSpan;
            if (column == 0 || others <= 0) {
                return top
                        + Math.max(
                                0,
                                Math.min(
                                        tall.cellVSpan - 1,
                                        y * tall.cellVSpan / Math.max(1, splitWidgetHeight(tall))));
            }
            // The inverse of splitCellRect: slots from the top are the rows above the widget,
            // slots up from the bottom the rows below it, a slot between them the nearer row
            final int below = mCountY - top - tall.cellVSpan;
            final int index = Math.max(0, Math.min(half - 1, y / splitRowPitch()));
            if (index < top) {
                return index;
            }
            if (below > 0) {
                return mCountY - Math.max(1, Math.min(below, half - index));
            }
            return top - 1;
        }

        final int row = Math.max(0, Math.min(half - 1, y / splitRowPitch()));
        return Math.min(mCountY - 1, column * half + row);
    }

    /** The height of a row of the list home's two landscape columns: a portrait tile's. */
    public static int splitRowHeight(android.content.res.Resources res) {
        return res.getDimensionPixelSize(R.dimen.r1_list_row_height);
    }

    public void measureChild(View child) {
        CellLayoutLayoutParams lp = (CellLayoutLayoutParams) child.getLayoutParams();
        final DeviceProfile dp = mActivity.getDeviceProfile();

        if (child instanceof NavigableAppWidgetHostView) {
            final PointF appWidgetScale = dp.getAppWidgetScale((ItemInfo) child.getTag());
            lp.setup(mCellWidth, mCellHeight, invertLayoutHorizontally(), mCountX, mCountY,
                    appWidgetScale.x, appWidgetScale.y, mBorderSpace,
                    dp.getWorkspaceProfile().getWidgetPadding());
        } else if (isChildQsb(child)) {
            lp.setup(mCellWidth, mCellHeight, invertLayoutHorizontally(), mCountX, mCountY,
                    mBorderSpace);
            // No need to add padding for Qsb, which is either Smartspace (actual or preview), or
            // QsbContainerView.
        } else {
            lp.setup(mCellWidth, mCellHeight, invertLayoutHorizontally(), mCountX, mCountY,
                    mBorderSpace);
            // Center the icon/folder
            int cHeight = getCellContentHeight();
            int cellPaddingY =
                    dp.getWorkspaceProfile().getCellYPaddingPx() >= 0 && mContainerType == WORKSPACE
                            ? dp.getWorkspaceProfile().getCellYPaddingPx()
                            : (int) Math.max(0, ((lp.height - cHeight) / 2f));

            // No need to add padding when cell layout border spacing is present.
            boolean noPaddingX =
                    (dp.getWorkspaceProfile().getCellLayoutBorderSpacePx().x > 0
                            && mContainerType == WORKSPACE)
                            || (dp.getFolderProfile().getCellLayoutBorderSpacePx().x > 0
                                && mContainerType == FOLDER)
                            || (dp.getHotseatProfile().getBorderSpace() > 0
                            && mContainerType == HOTSEAT);
            int cellPaddingX = noPaddingX
                    ? 0
                    : mContainerType == WORKSPACE
                            ? dp.getWorkspaceProfile().getWorkspaceCellPaddingXPx()
                            : (int) (dp.getWorkspaceProfile().getEdgeMarginPx() / 2f);
            if (WheelHome.enabled(getContext())
                    && dp.inv.numColumns == 1
                    && child instanceof BubbleTextView) {
                int inset = getResources().getDimensionPixelSize(R.dimen.r1_list_row_padding);
                child.setPadding(inset, 0, inset, 0);
            } else if (WheelHome.enabled(getContext())
                    && dp.inv.numColumns == 1
                    && child instanceof com.android.launcher3.folder.FolderIcon folder) {
                folder.setListRowInset(
                        getResources().getDimensionPixelSize(R.dimen.r1_list_row_padding));
            } else {
                child.setPadding(cellPaddingX, cellPaddingY, cellPaddingX, 0);
            }
        }
        splitForLandscape(child, lp);
        int childWidthMeasureSpec = MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY);
        int childheightMeasureSpec = MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY);
        child.measure(childWidthMeasureSpec, childheightMeasureSpec);
    }

    private boolean isChildQsb(View child) {
        return child.getId() == R.id.search_container_workspace;
    }

    public boolean invertLayoutHorizontally() {
        return mInvertIfRtl && Utilities.isRtl(getResources());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        Trace.beginSection("ShortcutAndWidgetConteiner#onLayout");
        mHasOnLayoutBeenCalled = true; // b/349929393 - is the required call to onLayout not done?
        int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                layoutChild(child);
            }
        }
        Trace.endSection();
    }

    /** The tall widget first, then right-column items in their logical order. */
    View splitFocusEnd(boolean last) {
        final ArrayList<View> items = splitFocusItems();
        return items.isEmpty() ? null : items.get(last ? items.size() - 1 : 0);
    }

    private ArrayList<View> splitFocusItems() {
        final ArrayList<View> items = new ArrayList<>();
        final CellLayoutLayoutParams tall = findTallWidget();
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() == VISIBLE && child.isFocusable()) {
                items.add(child);
            }
        }
        items.sort(
                Comparator.comparingInt(
                        child ->
                                child.getLayoutParams() == tall
                                        ? -1
                                        : splitCellY(
                                                (CellLayoutLayoutParams) child.getLayoutParams())));
        return items;
    }

    @Override
    public View focusSearch(View focused, int direction) {
        if (hasTallWidget()
                && WheelHome.wheelFocus(getContext())
                && focused != null
                && focused.getParent() == this
                && (direction == FOCUS_DOWN
                        || direction == FOCUS_UP
                        || direction == FOCUS_LEFT
                        || direction == FOCUS_RIGHT)) {
            final ArrayList<View> items = splitFocusItems();
            final int index = items.indexOf(focused);
            final boolean forward = direction == FOCUS_DOWN || direction == FOCUS_RIGHT;
            final int next = index + (forward ? 1 : -1);
            if (index >= 0 && next >= 0 && next < items.size()) {
                return items.get(next);
            }
            if (direction == FOCUS_UP && index == 0 && mActivity instanceof Launcher launcher) {
                final View tabs = launcher.findViewById(R.id.wheel_home_tabs);
                if (tabs != null && tabs.isShown() && tabs.isFocusable()) return tabs;
            }
            if (direction == FOCUS_DOWN && mActivity instanceof Launcher launcher) {
                final View dock = launcher.getHotseat().getShortcutsAndWidgets().getChildAt(0);
                if (dock != null && dock.isShown() && dock.isFocusable()) return dock;
            }
            if (direction == FOCUS_LEFT || direction == FOCUS_RIGHT) return null;
        }
        return super.focusSearch(focused, direction);
    }

    /**
     * Core logic to layout a child for this ViewGroup.
     */
    public void layoutChild(View child) {
        CellLayoutLayoutParams lp = (CellLayoutLayoutParams) child.getLayoutParams();
        if (child instanceof NavigableAppWidgetHostView) {
            NavigableAppWidgetHostView nahv = (NavigableAppWidgetHostView) child;

            // Scale and center the widget to fit within its cells.
            DeviceProfile profile = mActivity.getDeviceProfile();
            final PointF appWidgetScale = profile.getAppWidgetScale((ItemInfo) child.getTag());
            float scaleX = appWidgetScale.x;
            float scaleY = appWidgetScale.y;
            Log.d(TAG, "appWidgetScale - [x,y]: [" + scaleX + "," + scaleY + "]");

            nahv.setScaleToFit(Math.min(scaleX, scaleY));
            nahv.getTranslateDelegate().setTranslation(INDEX_WIDGET_CENTERING,
                    -(lp.width - (lp.width * scaleX)) / 2.0f,
                    -(lp.height - (lp.height * scaleY)) / 2.0f);
            if (isSplit()) {
                // Split widgets lay out at their visible size; no portrait-shaped scaled surface.
                nahv.setScaleToFit(1f);
                nahv.getTranslateDelegate().setTranslation(INDEX_WIDGET_CENTERING, 0f, 0f);
            }
        }

        int childLeft = lp.x;
        int childTop = lp.y;

        // We want to get the layout position of the widget, but layout() is a final function in
        // ViewGroup which makes it impossible to be overridden. Overriding onLayout() will have no
        // effect since it will not be called when the transition is enabled. The only possible
        // solution here seems to be sending the positions when CellLayout is laying out the views
        if (child instanceof LauncherAppWidgetHostView widgetView
                && widgetView.getCellChildViewPreLayoutListener() != null) {
            widgetView.getCellChildViewPreLayoutListener().notifyBoundChangeOnPreLayout(child,
                    childLeft, childTop, childLeft + lp.width, childTop + lp.height);
        }
        child.layout(childLeft, childTop, childLeft + lp.width, childTop + lp.height);
        if (mTranslationProvider != null) {
            final float tx = mTranslationProvider.getTranslationX(lp.getCellX());
            if (child instanceof Reorderable) {
                ((Reorderable) child).getTranslateDelegate()
                        .getTranslationX(INDEX_BUBBLE_ADJUSTMENT_ANIM)
                        .setValue(tx);
            } else {
                child.setTranslationX(tx);
            }
        }

        if (lp.dropped) {
            lp.dropped = false;

            final int[] cellXY = mTmpCellXY;
            getLocationOnScreen(cellXY);
            mWallpaperManager.sendWallpaperCommand(getWindowToken(),
                    WallpaperManager.COMMAND_DROP,
                    cellXY[0] + childLeft + lp.width / 2,
                    cellXY[1] + childTop + lp.height / 2, 0, null);
        }
    }


    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getAction() == ACTION_DOWN && getAlpha() == 0) {
            // Dont let children handle touch, if we are not visible.
            return true;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return false;
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        if (child != null) {
            Rect r = new Rect();
            child.getDrawingRect(r);
            requestRectangleOnScreen(r);
        }
    }

    @Override
    public void cancelLongPress() {
        super.cancelLongPress();

        // Cancel long press for all children
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            child.cancelLongPress();
        }
    }

    @Override
    public void drawFolderLeaveBehindForIcon(FolderIcon child) {
        CellLayoutLayoutParams lp = (CellLayoutLayoutParams) child.getLayoutParams();
        // While the folder is open, the position of the icon cannot change.
        lp.canReorder = false;
        if (mContainerType == HOTSEAT) {
            CellLayout cl = (CellLayout) getParent();
            cl.setFolderLeaveBehindCell(lp.getCellX(), lp.getCellY());
        }
    }

    @Override
    public void clearFolderLeaveBehind(FolderIcon child) {
        ((CellLayoutLayoutParams) child.getLayoutParams()).canReorder = true;
        if (mContainerType == HOTSEAT) {
            CellLayout cl = (CellLayout) getParent();
            cl.clearFolderLeaveBehind();
        }
    }

    void setTranslationProvider(@Nullable TranslationProvider provider) {
        mTranslationProvider = provider;
    }

    /** Returns the current {@link TranslationProvider translation provider}. */
    public @Nullable TranslationProvider getTranslationProvider() {
        return mTranslationProvider;
    }

    /** Provides translation values to apply when laying out child views. */
    public interface TranslationProvider {
        float getTranslationX(int cellX);
    }
}
