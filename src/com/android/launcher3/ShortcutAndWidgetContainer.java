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

        int widthSpecSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSpecSize = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(widthSpecSize, heightSpecSize);

        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != GONE) {
                measureChild(child);
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
        splitCellRect(lp.getCellY(), lp.cellVSpan, mTmpSplitRect);
        if (child instanceof NavigableAppWidgetHostView && findTallWidget() == lp) {
            // As large as in portrait, so it lays out as there, in the middle of the column:
            // layoutChild scales it down into the column
            final DeviceProfile dp = mActivity.getDeviceProfile();
            final int side = mTmpSplitRect.right + mTmpSplitRect.right
                    + getResources().getDimensionPixelSize(R.dimen.r1_list_column_gap);
            lp.width = Math.min(dp.getDeviceProperties().getWidthPx(),
                    dp.getDeviceProperties().getHeightPx())
                    - (dp.getDeviceProperties().getWidthPx() - side);
            lp.height = lp.cellVSpan * splitRowHeight(getResources());
            lp.x = mTmpSplitRect.centerX() - lp.width / 2;
            lp.y = mTmpSplitRect.centerY() - lp.height / 2;
            return;
        }
        lp.x = mTmpSplitRect.left;
        lp.width = mTmpSplitRect.width();
        lp.y = mTmpSplitRect.top;
        lp.height = mTmpSplitRect.height();
    }

    private final android.graphics.Rect mTmpSplitRect = new android.graphics.Rect();

    /** Whether this page shows its rows in the two landscape columns of the list home. */
    boolean isSplit() {
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
        final CellLayoutLayoutParams tall = findTallWidget();
        if (tall != null) {
            // A widget of more rows than a column holds is alone on the left, down to the
            // bottom; the page's other rows stack down the right in their order, over the dock
            final int top = tall.getCellY();
            if (cellY >= top && cellY < top + tall.cellVSpan) {
                out.set(0, 0, columnWidth, (half + 1) * pitch - mBorderSpace.y);
                return;
            }
            final int index = cellY < top ? cellY : cellY - tall.cellVSpan;
            final int y = index * pitch;
            out.set(columnWidth + gap, y, 2 * columnWidth + gap, y + pitch - mBorderSpace.y);
            return;
        }
        final int column = cellY >= half ? 1 : 0;
        final int row = cellY - column * half;
        span = Math.max(1, Math.min(span, half - row));
        final int x = column * (columnWidth + gap);
        final int y = row * pitch;
        out.set(x, y, x + columnWidth, y + span * pitch - mBorderSpace.y);
    }

    /** The widget of this page of more rows than a landscape column holds, if there is one. */
    private CellLayoutLayoutParams findTallWidget() {
        final int half = (mCountY + 1) / 2;
        for (int i = 0; i < getChildCount(); i++) {
            final View child = getChildAt(i);
            if (child instanceof NavigableAppWidgetHostView
                    && child.getLayoutParams() instanceof CellLayoutLayoutParams lp
                    && lp.cellVSpan > half) {
                return lp;
            }
        }
        return null;
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
        final CellLayoutLayoutParams tall = findTallWidget();
        if (tall != null) {
            final int top = tall.getCellY();
            final int others = mCountY - tall.cellVSpan;
            if (column == 0 || others <= 0) return top;
            final int index = Math.max(0, Math.min(others - 1, y / splitRowPitch()));
            return index < top ? index : index + tall.cellVSpan;
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
            if (isSplit() && findTallWidget() == lp) {
                // At its portrait size around the middle of the left column (splitForLandscape):
                // scaled about that middle to fit the column, its proportions kept
                splitCellRect(lp.getCellY(), lp.cellVSpan, mTmpSplitRect);
                nahv.setScaleToFit(Math.min(1f, Math.min(
                        mTmpSplitRect.width() / (float) lp.width,
                        mTmpSplitRect.height() / (float) lp.height)));
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
