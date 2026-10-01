package com.winlator.socialhub;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/**
 * A {@link SwipeRefreshLayout} that plays well with inner scrollable views.
 *
 * <p>The Social Hub tabs wrap their content (status row + list/detail
 * {@link ScrollView}s) in a plain {@code LinearLayout}, so the stock
 * implementation always believes the view is scrolled to the top and steals
 * vertical gestures — scrolling back up after scrolling down wrongly fires a
 * refresh instead of scrolling the list. This subclass delegates
 * {@link #canChildScrollUp()} to the currently visible scrolling descendant,
 * so pull-to-refresh only fires when that list is genuinely at the top.
 */
public class ChildScrollSwipeRefreshLayout extends SwipeRefreshLayout {

    public ChildScrollSwipeRefreshLayout(Context context) {
        super(context);
    }

    public ChildScrollSwipeRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean canChildScrollUp() {
        View scrollable = findVisibleScrollableChild(this);
        if (scrollable != null) return scrollable.canScrollVertically(-1);
        return super.canChildScrollUp();
    }

    /**
     * Finds the first visible scrolling descendant (the active list/detail
     * {@link ScrollView} of the tab). Hidden views (e.g. the detail scroll
     * while the list is shown) are skipped.
     */
    private static View findVisibleScrollableChild(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE) continue;
            if (child instanceof ScrollView
                    || child instanceof androidx.core.widget.NestedScrollView
                    || child instanceof android.widget.AbsListView) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findVisibleScrollableChild((ViewGroup) child);
                if (found != null) return found;
            }
        }
        return null;
    }
}
