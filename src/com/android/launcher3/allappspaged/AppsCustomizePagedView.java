/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.launcher3.allappspaged;

import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Process;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.widget.ImageView;

import com.android.launcher3.AppInfo;
import com.android.launcher3.BubbleTextView;
import com.android.launcher3.CellLayout;
import com.android.launcher3.DeleteDropTarget;
import com.android.launcher3.DeviceProfile;
import com.android.launcher3.DragController;
import com.android.launcher3.DragSource;
import com.android.launcher3.DropTarget;
import com.android.launcher3.Folder;
import com.android.launcher3.ItemInfo;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherAnimUtils;
import com.android.launcher3.LauncherTransitionable;
import com.android.launcher3.PagedView;
import com.android.launcher3.R;
import com.android.launcher3.ShortcutAndWidgetContainer;
import com.android.launcher3.Workspace;
import com.android.launcher3.model.AppNameComparator;
import com.android.launcher3.settings.SettingsProvider;
import com.android.launcher3.util.GestureHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * A simple callback interface which also provides the results of the task.
 */
interface AsyncTaskCallback {
    void run(AppsCustomizeAsyncTask task, AsyncTaskPageData data);
}

/**
 * The data needed to perform either of the custom AsyncTasks.
 */
class AsyncTaskPageData {
    int page;
    ArrayList<Object> items;
    ArrayList<Bitmap> generatedImages;
    int maxImageWidth;
    int maxImageHeight;
    AsyncTaskCallback doInBackgroundCallback;
    AsyncTaskCallback postExecuteCallback;
    AsyncTaskPageData(int p, ArrayList<Object> l, int cw, int ch, AsyncTaskCallback bgR,
                      AsyncTaskCallback postR) {
        page = p;
        items = l;
        generatedImages = new ArrayList<>();
        maxImageWidth = cw;
        maxImageHeight = ch;
        doInBackgroundCallback = bgR;
        postExecuteCallback = postR;
    }
    enum Type {
        LoadWidgetPreviewData
    }
}

/**
 * A generic template for an async task used in AppsCustomize.
 */
class AppsCustomizeAsyncTask extends AsyncTask<AsyncTaskPageData, Void, AsyncTaskPageData> {
    // The page that this async task is associated with
    AsyncTaskPageData.Type dataType;
    int page;
    int threadPriority;

    AppsCustomizeAsyncTask(int p, AsyncTaskPageData.Type ty) {
        page = p;
        threadPriority = Process.THREAD_PRIORITY_DEFAULT;
        dataType = ty;
    }

    @Override
    protected AsyncTaskPageData doInBackground(AsyncTaskPageData... params) {
        if (params.length != 1) return null;
        // Load each of the widget previews in the background
        params[0].doInBackgroundCallback.run(this, params[0]);
        return params[0];
    }

    @Override
    protected void onPostExecute(AsyncTaskPageData result) {
        // All the widget previews are loaded, so we can just callback to inflate the page
        result.postExecuteCallback.run(this, result);
    }

    void setThreadPriority(int p) {
        threadPriority = p;
    }
}

/**
 * The Apps/Customize page that displays all the applications, widgets, and shortcuts.
 */
public class AppsCustomizePagedView extends PagedViewWithDraggableItems implements
        View.OnClickListener, View.OnKeyListener, DragSource, LauncherTransitionable {
    static final String TAG = "AppsCustomizePagedView";
    /*
     * We load an extra page on each side to prevent flashes from scrolling and loading of the
     * widget previews in the background with the AsyncTasks.
     */
    final static int sLookBehindPageCount = 2;
    final static int sLookAheadPageCount = 2;
    private static final int FILTER_APPS_SYSTEM_FLAG = 1;
    private static final int FILTER_APPS_DOWNLOADED_FLAG = 2;
    private final LayoutInflater mLayoutInflater;
    // Previews & outlines
    ArrayList<AppsCustomizeAsyncTask> mRunningTasks;
    boolean mPageBackgroundsVisible = true;
    private ContentType mContentType = ContentType.Applications;
    private SortMode mSortMode = SortMode.Title;
    private int mFilterApps = FILTER_APPS_SYSTEM_FLAG | FILTER_APPS_DOWNLOADED_FLAG;
    // Refs
    private Launcher mLauncher;
    // Save and Restore
    private int mSaveInstanceStateItemIndex = -1;
    // Content
    private ArrayList<AppInfo> mApps;
    private ArrayList<AppInfo> mFilteredApps;
    private ArrayList<ComponentName> mHiddenApps;
    private ArrayList<String> mHiddenPackages;
    private GestureDetector mGestureDetector;
    private AppNameComparator mAppNameComparator;
    // Dimens
    private int mContentWidth, mContentHeight;
    private int mNumAppsPages;
    Runnable mLayoutRunnable = new Runnable() {
        @Override
        public void run() {
            boolean attached = true;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                attached = isAttachedToWindow();
            }
            if (attached) {
                setDataIsReady();
                onDataReady(getMeasuredWidth(), getMeasuredHeight());
            }
        }
    };
    private Rect mAllAppsPadding = new Rect();
    private boolean mInBulkBind;
    private boolean mNeedToUpdatePageCountsAndInvalidateData;

    public AppsCustomizePagedView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLayoutInflater = LayoutInflater.from(context);
        mAppNameComparator = new AppNameComparator(context);
        mApps = new ArrayList<>();
        mFilteredApps = new ArrayList<>();
        mRunningTasks = new ArrayList<>();

        // Save the default widget preview background
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.AppsCustomizePagedView, 0, 0);
        a.recycle();

        // The padding on the non-matched dimension for the default widget preview icons
        // (top + bottom)
        mFadeInAdjacentScreens = false;

        // Unless otherwise specified this view is important for accessibility.
        if (getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        //setSinglePageInViewport();

        mGestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onFling(MotionEvent start, MotionEvent finish,
                                           float xVelocity, float yVelocity) {
                        if (GestureHelper.isSwipeUP(finish.getRawY(), start.getRawY())) {
                            if (GestureHelper.doesSwipeUpContainShowAllApps(mLauncher)) {
                                mLauncher.showWorkspace(true);
                            }
                        } else if (GestureHelper.isSwipeDOWN(finish.getRawY(), start.getRawY())) {
                            if (GestureHelper.doesSwipeDownContainShowAllApps(mLauncher)) {
                                mLauncher.showWorkspace(true);
                            }
                        }
                        return true;
                    }
                }
        );

        updateSortMode(context);
        updateHiddenAppsList(context);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        mGestureDetector.onTouchEvent(ev);
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public void getEdgeVerticalPostion(int[] pos) {
    }

    private void updateHiddenAppsList(Context context) {
        String[] flattened = SettingsProvider.getString(context,
                SettingsProvider.KEY_HIDDEN_APPS, "").split("\\|");
        mHiddenApps = new ArrayList<ComponentName>(flattened.length);
        mHiddenPackages = new ArrayList<String>(flattened.length);
        for (String flat : flattened) {
            ComponentName cmp = ComponentName.unflattenFromString(flat);
            if (cmp != null) {
                mHiddenApps.add(cmp);
                mHiddenPackages.add(cmp.getPackageName());
            }
        }
    }

    @Override
    protected void init() {
        super.init();
        mCenterPagesVertically = false;

        Context context = getContext();
        Resources r = context.getResources();
        setDragSlopeThreshold(r.getInteger(
                R.integer.config_appsCustomizeDragSlopeThreshold) / 100f);
    }

    public void onFinishInflate() {
        super.onFinishInflate();
    }

    void setAllAppsPadding(Rect r) {
        mAllAppsPadding.set(r);
    }

    /**
     * Returns the item index of the center item on this page so that we can restore to this
     * item index when we rotate.
     */
    private int getMiddleComponentIndexOnCurrentPage() {
        int i = -1;
        if (getPageCount() > 0) {
            int currentPage = getCurrentPage();
            if (mContentType == ContentType.Applications) {
                AppsCustomizeCellLayout layout = (AppsCustomizeCellLayout) getPageAt(currentPage);
                ShortcutAndWidgetContainer childrenLayout = layout.getShortcutsAndWidgets();
                int numItemsPerPage = mCellCountX * mCellCountY;
                int childCount = childrenLayout.getChildCount();
                if (childCount > 0) {
                    i = (currentPage * numItemsPerPage) + (childCount / 2);
                }
            } else {
                throw new RuntimeException("Invalid ContentType");
            }
        }
        return i;
    }

    /**
     * Get the index of the item to restore to if we need to restore the current page.
     */
    public int getSaveInstanceStateIndex() {
        if (mSaveInstanceStateItemIndex == -1) {
            mSaveInstanceStateItemIndex = getMiddleComponentIndexOnCurrentPage();
        }
        return mSaveInstanceStateItemIndex;
    }

    /**
     * Returns the page in the current orientation which is expected to contain the specified
     * item index.
     */
    int getPageForComponent(int index) {
        if (index < 0) return 0;
        int numItemsPerPage = mCellCountX * mCellCountY;
        return (index / numItemsPerPage);
    }

    /**
     * Restores the page for an item at the specified index
     */
    public void restorePageForIndex(int index) {
        if (index < 0) return;
        mSaveInstanceStateItemIndex = index;
    }

    private void updatePageCounts() {
        mNumAppsPages = (int) Math.ceil((float) mFilteredApps.size() / (mCellCountX * mCellCountY));
    }

    public void filterContent() {
        filterAppsWithoutInvalidate();
    }

    public void updateGridSize() {
        DeviceProfile grid = mLauncher.getDeviceProfile();
        mCellCountX = grid.pagedAllAppsNumCols;
        mCellCountY = grid.pagedAllAppsNumRows;
        updatePageCounts();
        updateSortMode(mLauncher);
    }

    public void updateSortMode(Context context) {
        /*int sortMode = Integer.parseInt(SettingsProvider.getString(context,
                SettingsProvider.KEY_DRAWER_SORT_MODE, "0"));
        if (sortMode == 0) {
            mSortMode = SortMode.Title;
        } else if (sortMode == 1) {
            mSortMode = SortMode.LaunchCount;
        } else if (sortMode == 2) {
            mSortMode = SortMode.InstallTime;
        }*/
        mSortMode = SortMode.Title;
    }

    protected void onDataReady(int width, int height) {
        // Now that the data is ready, we can calculate the content width, the number of cells to
        // use for each page
        updateGridSize();

        // Force a measure to update recalculate the gaps
        mContentWidth = getMeasuredWidth() - getPaddingLeft() - getPaddingRight();
        mContentHeight = getMeasuredHeight() - getPaddingTop() - getPaddingBottom();

        final boolean hostIsTransitioning = getTabHost().isInTransition();
        int page = getPageForComponent(mSaveInstanceStateItemIndex);
        invalidatePageData(Math.max(0, page), hostIsTransitioning);
    }

    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        if (!isDataReady()) {
            if (!mFilteredApps.isEmpty()) {
                post(mLayoutRunnable);
            }
        }
    }

    public void setBulkBind(boolean bulkBind) {
        if (bulkBind) {
            mInBulkBind = true;
        } else {
            mInBulkBind = false;
            if (mNeedToUpdatePageCountsAndInvalidateData) {
                updatePageCountsAndInvalidateData();
            }
        }
    }

    private void updatePageCountsAndInvalidateData() {
        if (mInBulkBind) {
            mNeedToUpdatePageCountsAndInvalidateData = true;
        } else {
            updatePageCounts();
            invalidateOnDataChange();
            mNeedToUpdatePageCountsAndInvalidateData = false;
        }
    }

    @Override
    public void onClick(View v) {
        // When we have exited all apps or are in transition, disregard clicks
        if (!mLauncher.isAllAppsVisible()
                || mLauncher.getWorkspace().isSwitchingState()) return;

        // Create a little animation to show that the widget can move
        float offsetY = getResources().getDimensionPixelSize(R.dimen.dragViewOffsetY);
        final ImageView p = (ImageView) v.findViewById(R.id.widget_preview);
        AnimatorSet bounce = LauncherAnimUtils.createAnimatorSet();
        ValueAnimator tyuAnim = LauncherAnimUtils.ofFloat(p, "translationY", offsetY);
        tyuAnim.setDuration(125);
        ValueAnimator tydAnim = LauncherAnimUtils.ofFloat(p, "translationY", 0f);
        tydAnim.setDuration(100);
        bounce.play(tyuAnim).before(tydAnim);
        bounce.setInterpolator(new AccelerateInterpolator());
        bounce.start();
    }

    public boolean onKey(View v, int keyCode, KeyEvent event) {
        return false;
    }

    /*
     * PagedViewWithDraggableItems implementation
     */
    @Override
    protected void determineDraggingStart(MotionEvent ev) {
        // Disable dragging by pulling an app down for now.
    }

    private void beginDraggingApplication(View v) {
        mLauncher.getWorkspace().beginExternalDragShared(v, this);
    }

    @Override
    protected boolean beginDragging(final View v) {
        if (!super.beginDragging(v)) return false;

        if (v instanceof BubbleTextView) {
            beginDraggingApplication(v);
        }

        // We delay entering spring-loaded mode slightly to make sure the UI
        // thready is free of any work.
        postDelayed(new Runnable() {
            @Override
            public void run() {
                // We don't enter spring-loaded mode if the drag has been cancelled
                if (mLauncher.getDragController().isDragging()) {
                    // Go into spring loaded mode (must happen before we startDrag())
                    mLauncher.enterSpringLoadedDragMode();
                }
            }
        }, 150);

        return true;
    }

    /**
     * Clean up after dragging.
     *
     * @param target where the item was dragged to (can be null if the item was flung)
     */
    private void endDragging(View target, boolean isFlingToDelete, boolean success) {
        if (isFlingToDelete || !success || (target != mLauncher.getWorkspace() &&
                !(target instanceof DeleteDropTarget) && !(target instanceof Folder))) {
            // Exit spring loaded mode if we have not successfully dropped or have not handled the
            // drop in Workspace
            mLauncher.exitSpringLoadedDragModeDelayed(true,
                    Launcher.EXIT_SPRINGLOADED_MODE_SHORT_TIMEOUT, null);
            mLauncher.unlockScreenOrientation(false);
        } else {
            mLauncher.unlockScreenOrientation(false);
        }
    }

    //@Override
    public View getContent() {
        if (getChildCount() > 0) {
            return getChildAt(0);
        }
        return null;
    }

    @Override
    public void onLauncherTransitionPrepare(Launcher l, boolean animated, boolean toWorkspace) {
        if (toWorkspace) {
            //cancelAllTasks();
        }
    }

    @Override
    public void onLauncherTransitionStart(Launcher l, boolean animated, boolean toWorkspace) {
    }

    @Override
    public void onLauncherTransitionStep(Launcher l, float t) {
    }

    @Override
    public void onLauncherTransitionEnd(Launcher l, boolean animated, boolean toWorkspace) {
    }

    @Override
    public void onDropCompleted(View target, DropTarget.DragObject d, boolean isFlingToDelete,
                                boolean success) {
        // Return early and wait for onFlingToDeleteCompleted if this was the result of a fling
        if (isFlingToDelete) return;

        endDragging(target, false, success);

        // Display an error message if the drag failed due to there not being enough space on the
        // target layout we were dropping on.
        if (!success) {
            boolean showOutOfSpaceMessage = false;
            if (target instanceof Workspace) {
                int currentScreen = mLauncher.getCurrentWorkspaceScreen();
                Workspace workspace = (Workspace) target;
                CellLayout layout = (CellLayout) workspace.getChildAt(currentScreen);
                ItemInfo itemInfo = (ItemInfo) d.dragInfo;
                if (layout != null) {
                    layout.calculateSpans(itemInfo);
                    showOutOfSpaceMessage =
                            !layout.findCellForSpan(null, itemInfo.spanX, itemInfo.spanY);
                }
            }
            if (showOutOfSpaceMessage) {
                mLauncher.showOutOfSpaceMessage(false);
            }

            d.deferDragViewCleanupPostAnimation = false;
        }
    }

    @Override
    public void onFlingToDeleteCompleted() {
        // We just dismiss the drag when we fling, so cleanup here
        endDragging(null, true, true);
    }

    @Override
    public boolean supportsFlingToDelete() {
        return true;
    }

    @Override
    public boolean supportsAppInfoDropTarget() {
        return true;
    }

    @Override
    public boolean supportsDeleteDropTarget() {
        return false;
    }

    @Override
    public float getIntrinsicIconScaleFactor() {
        DeviceProfile grid = mLauncher.getDeviceProfile();
        return (float) grid.allAppsIconSizePx / grid.iconSizePx;
    }

    public ContentType getContentType() {
        return mContentType;
    }

    public void setContentType(ContentType type) {
        // Widgets appear to be cleared every time you leave, always force invalidate for them
        if (mContentType != type) {
            mContentType = type;
            invalidatePageData(0, true);
        }
    }

    protected void snapToPage(int whichPage, int delta, int duration) {
        super.snapToPage(whichPage, delta, duration);

        // Update the thread priorities given the direction lookahead
        Iterator<AppsCustomizeAsyncTask> iter = mRunningTasks.iterator();
        for (AppsCustomizeAsyncTask task : mRunningTasks) {
            task.setThreadPriority(Process.THREAD_PRIORITY_LOWEST);
        }
    }

    /*
     * Apps PagedView implementation
     */
    private void setVisibilityOnChildren(ViewGroup layout, int visibility) {
        int childCount = layout.getChildCount();
        for (int i = 0; i < childCount; ++i) {
            layout.getChildAt(i).setVisibility(visibility);
        }
    }

    private void setupPage(AppsCustomizeCellLayout layout) {
        layout.setGridSize(mCellCountX, mCellCountY);

        // Note: We force a measure here to get around the fact that when we do layout calculations
        // immediately after syncing, we don't have a proper width.  That said, we already know the
        // expected page width, so we can actually optimize by hiding all the TextView-based
        // children that are expensive to measure, and let that happen naturally later.
        setVisibilityOnChildren(layout, View.GONE);
        int widthSpec = MeasureSpec.makeMeasureSpec(mContentWidth, MeasureSpec.AT_MOST);
        int heightSpec = MeasureSpec.makeMeasureSpec(mContentHeight, MeasureSpec.AT_MOST);
        layout.measure(widthSpec, heightSpec);

        Drawable bg = getContext().getResources().getDrawable(R.drawable.quantum_panel);
        //int color = SettingsProvider.getInt(mLauncher, SettingsProvider.KEY_DRAWER_BACKGROUND,
        //      Color.WHITE);
        if (bg != null) {
            bg.setAlpha(mPageBackgroundsVisible ? 255 : 0);
            //bg.setColorFilter(color, PorterDuff.Mode.MULTIPLY);
            layout.setBackground(bg);
        }

        setVisibilityOnChildren(layout, View.VISIBLE);
    }

    public void setPageBackgroundsVisible(boolean visible) {
        mPageBackgroundsVisible = visible;
        int childCount = getChildCount();
        for (int i = 0; i < childCount; ++i) {
            Drawable bg = getChildAt(i).getBackground();
            if (bg != null) {
                bg.setAlpha(visible ? 255 : 0);
            }
        }
    }

    public void syncAppsPageItems(int page, boolean immediate) {
        // ensure that we have the right number of items on the pages
        final boolean isRtl = false;
        int numCells = mCellCountX * mCellCountY;
        int startIndex = page * numCells;
        int endIndex = Math.min(startIndex + numCells, mFilteredApps.size());
        AppsCustomizeCellLayout layout = (AppsCustomizeCellLayout) getPageAt(page);

        layout.removeAllViewsOnPage();
        ArrayList<Object> items = new ArrayList<Object>();
        ArrayList<Bitmap> images = new ArrayList<Bitmap>();
        //boolean hideIconLabels = SettingsProvider.getBoolean(mLauncher,
        //      SettingsProvider.KEY_DRAWER_HIDE_LABELS, false);
        for (int i = startIndex; i < endIndex; ++i) {
            AppInfo info = mFilteredApps.get(i);
            BubbleTextView icon = (BubbleTextView) mLayoutInflater.inflate(
                    R.layout.apps_customize_application, layout, false);
            /*if (!ColorUtils.darkTextColor(SettingsProvider.getInt(mLauncher,
                    SettingsProvider.KEY_DRAWER_BACKGROUND, Color.WHITE))) {
                icon.setTextColor(Color.WHITE);
            } else {
                icon.setTextColor(Color.BLACK);
            }*/
            icon.applyFromApplicationInfo(info);
            //icon.setTextVisibility(!hideIconLabels);
            icon.setOnClickListener(mLauncher);
            icon.setOnLongClickListener(this);
            icon.setOnTouchListener(this);
            icon.setOnKeyListener(this);
            icon.setOnFocusChangeListener(layout.mFocusHandlerView);

            int index = i - startIndex;
            int x = index % mCellCountX;
            int y = index / mCellCountX;
            if (isRtl) {
                x = mCellCountX - x - 1;
            }
            layout.addViewToCellLayout(icon, -1, i, new CellLayout.LayoutParams(x, y, 1, 1), false);

            items.add(info);
            images.add(info.iconBitmap);
        }

        enableHwLayersOnVisiblePages();
    }

    //@Override
    public void syncPages() {
        //disablePagedViewAnimations();

        removeAllViews();

        Context context = getContext();
        if (mContentType == ContentType.Applications) {
            for (int i = 0; i < mNumAppsPages; ++i) {
                AppsCustomizeCellLayout layout = new AppsCustomizeCellLayout(context);
                setupPage(layout);
                addView(layout, new PagedView.LayoutParams(LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT));
            }
        } else {
            throw new RuntimeException("Invalid ContentType");
        }

        //enablePagedViewAnimations();
    }

    //@Override
    public void syncPageItems(int page, boolean immediate) {
        syncAppsPageItems(page, immediate);
    }

    // We want our pages to be z-ordered such that the further a page is to the left, the higher
    // it is in the z-order. This is important to insure touch events are handled correctly.
    public View getPageAt(int index) {
        return getChildAt(indexToPage(index));
    }

    @Override
    protected int indexToPage(int index) {
        return getChildCount() - index - 1;
    }

    // In apps customize, we have a scrolling effect which emulates pulling cards off of a stack.
    @Override
    protected void screenScrolled(int screenCenter) {
        super.screenScrolled(screenCenter);
        enableHwLayersOnVisiblePages();
    }

    private void enableHwLayersOnVisiblePages() {
        final int screenCount = getChildCount();

        getVisiblePages(mTempVisiblePagesRange);
        int leftScreen = mTempVisiblePagesRange[0];
        int rightScreen = mTempVisiblePagesRange[1];
        int forceDrawScreen = -1;
        if (leftScreen == rightScreen) {
            // make sure we're caching at least two pages always
            if (rightScreen < screenCount - 1) {
                rightScreen++;
                forceDrawScreen = rightScreen;
            } else if (leftScreen > 0) {
                leftScreen--;
                forceDrawScreen = leftScreen;
            }
        } else {
            forceDrawScreen = leftScreen + 1;
        }

        for (int i = 0; i < screenCount; i++) {
            final View layout = getPageAt(i);
            if (!(leftScreen <= i && i <= rightScreen &&
                    (i == forceDrawScreen || shouldDrawChild(layout)))) {
                layout.setLayerType(LAYER_TYPE_NONE, null);
            }
        }

        for (int i = 0; i < screenCount; i++) {
            final View layout = getPageAt(i);

            if (leftScreen <= i && i <= rightScreen &&
                    (i == forceDrawScreen || shouldDrawChild(layout))) {
                if (layout.getLayerType() != LAYER_TYPE_HARDWARE) {
                    layout.setLayerType(LAYER_TYPE_HARDWARE, null);
                }
            }
        }
    }

    protected void overScroll(float amount) {
        dampedOverScroll(amount);
    }

    @Override
    protected void onPageEndMoving() {
        super.onPageEndMoving();
        mForceDrawAllChildrenNextFrame = true;
        // We reset the save index when we change pages so that it will be recalculated on next
        // rotation
        mSaveInstanceStateItemIndex = -1;
    }

    public Comparator<AppInfo> getComparatorForSortMode() {
        /*switch (mSortMode) {
            case Title:
                return LauncherModel.getAppNameComparator();
            case LaunchCount:
                return LauncherModel.getLaunchCountComparator(mLauncher.getStats());
            case InstallTime:
                return LauncherModel.getAppInstallTimeComparator();
            default:
                return LauncherModel.getAppNameComparator();
        }*/
        return null;
    }

    /*
     * AllAppsView implementation
     */
    public void setup(Launcher launcher, DragController dragController) {
        mLauncher = launcher;

        DeviceProfile grid = mLauncher.getDeviceProfile();
        setPadding(grid.edgeMarginPx, 2 * grid.edgeMarginPx,
                grid.edgeMarginPx, 2 * grid.edgeMarginPx);
    }

    /**
     * We should call thise method whenever the core data changes (mApps, mWidgets) so that we can
     * appropriately determine when to invalidate the PagedView page data.  In cases where the data
     * has yet to be set, we can requestLayout() and wait for onDataReady() to be called in the
     * next onMeasure() pass, which will trigger an invalidatePageData() itself.
     */
    public void invalidateOnDataChange() {
        if (!isDataReady()) {
            // The next layout pass will trigger data-ready if both widgets and apps are set, so
            // request a layout to trigger the page data when ready.
            requestLayout();
        }
    }

    public void setApps(ArrayList<AppInfo> list) {
        mApps = list;
        filterAppsWithoutInvalidate();
        updatePageCountsAndInvalidateData();
    }

    private void addAppsWithoutInvalidate(ArrayList<AppInfo> list) {
        // We add it in place, in alphabetical order
        int count = list.size();
        for (int i = 0; i < count; ++i) {
            AppInfo info = list.get(i);
            int index = Collections.binarySearch(mApps, info, getComparatorForSortMode());
            if (index < 0) {
                mApps.add(-(index + 1), info);
            }
        }
    }

    public void addApps(ArrayList<AppInfo> list) {
        addAppsWithoutInvalidate(list);
        filterAppsWithoutInvalidate();
        updatePageCountsAndInvalidateData();
    }

    private int findAppByComponent(List<AppInfo> list, AppInfo item) {
        ComponentName removeComponent = item.intent.getComponent();
        int length = list.size();
        for (int i = 0; i < length; ++i) {
            AppInfo info = list.get(i);
            if (info.user.equals(item.user)
                    && info.intent.getComponent().equals(removeComponent)) {
                return i;
            }
        }
        return -1;
    }

    private void removeAppsWithoutInvalidate(ArrayList<AppInfo> list) {
        // loop through all the apps and remove apps that have the same component
        int length = list.size();
        for (int i = 0; i < length; ++i) {
            AppInfo info = list.get(i);
            int removeIndex = findAppByComponent(mApps, info);
            if (removeIndex > -1) {
                mApps.remove(removeIndex);
            }
        }
    }

    public void removeApps(ArrayList<AppInfo> appInfos) {
        removeAppsWithoutInvalidate(appInfos);
        filterAppsWithoutInvalidate();
        updatePageCountsAndInvalidateData();
    }

    public void updateApps(ArrayList<AppInfo> list) {
        // We remove and re-add the updated applications list because it's properties may have
        // changed (ie. the title), and this will ensure that the items will be in their proper
        // place in the list.
        removeAppsWithoutInvalidate(list);
        addAppsWithoutInvalidate(list);
        filterAppsWithoutInvalidate();
        updatePageCountsAndInvalidateData();
    }

    public void filterAppsWithoutInvalidate() {
        updateHiddenAppsList(mLauncher);

        mFilteredApps = new ArrayList<AppInfo>(mApps);
        Iterator<AppInfo> iterator = mFilteredApps.iterator();
        while (iterator.hasNext()) {
            AppInfo appInfo = iterator.next();
            if (mHiddenApps.contains(appInfo.componentName)) {
                iterator.remove();
            }
        }
        Collections.sort(mFilteredApps, mAppNameComparator.getAppInfoComparator());
    }

    public void filterApps() {
        filterAppsWithoutInvalidate();
        updatePageCountsAndInvalidateData();
    }

    public void reset() {
        // If we have reset, then we should not continue to restore the previous state
        mSaveInstanceStateItemIndex = -1;

        if (mContentType != ContentType.Applications) {
            setContentType(ContentType.Applications);
        }

        if (mCurrentPage != 0) {
            invalidatePageData(0);
        }
    }

    private AppsCustomizeTabHost getTabHost() {
        return (AppsCustomizeTabHost) mLauncher.findViewById(R.id.apps_customize_pane);
    }

    public void dumpState() {
        // TODO: Dump information related to current list of Applications, Widgets, etc.
        AppInfo.dumpApplicationInfoList(TAG, "mApps", mApps);
    }

    protected int getAssociatedLowerPageBound(int page) {
        final int count = getChildCount();
        int windowSize = Math.min(count, sLookBehindPageCount + sLookAheadPageCount + 1);
        return Math.max(Math.min(page - sLookBehindPageCount, count - windowSize), 0);
    }

    protected int getAssociatedUpperPageBound(int page) {
        final int count = getChildCount();
        int windowSize = Math.min(count, sLookBehindPageCount + sLookAheadPageCount + 1);
        return Math.min(Math.max(page + sLookAheadPageCount, windowSize - 1),
                count - 1);
    }

    protected String getCurrentPageDescription() {
        int page = (mNextPage != INVALID_PAGE) ? mNextPage : mCurrentPage;
        int stringId = R.string.default_scroll_format;
        int count = 0;

        if (mContentType == ContentType.Applications) {
            //stringId = R.string.apps_customize_apps_scroll_format;
            count = mNumAppsPages;
        } else {
            throw new RuntimeException("Invalid ContentType");
        }

        return String.format(getContext().getString(stringId), page + 1, count);
    }

    /**
     * The different content types that this paged view can show.
     */
    public enum ContentType {
        Applications
    }

    /**
     * The different sort modes that can be used to order items
     */
    public enum SortMode {
        Title,
        LaunchCount,
        InstallTime
    }
}