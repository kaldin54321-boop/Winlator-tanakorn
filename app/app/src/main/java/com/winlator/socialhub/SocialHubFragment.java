package com.winlator.socialhub;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

/**
 * Social Hub entry point hosted from the app main menu. Contains three tabs:
 * News Feed, Official Videos and Forums.
 *
 * <p>The tab bar is a Material {@link TabLayout} styled exactly like the
 * container settings ({@code tab_layout_background}), and pages live in a
 * {@link ViewPager2} so tabs switch by tapping the bar or by swiping
 * left/right.
 */
public class SocialHubFragment extends Fragment {
    private static final int TAB_NEWS = 0;
    private static final int TAB_VIDEOS = 1;
    private static final int TAB_FORUMS = 2;
    private static final int TAB_COUNT = 3;

    private Context ctx;
    private int currentTab = TAB_NEWS;
    private ViewPager2 viewPager;
    private TabLayoutMediator mediator;

    private final ViewPager2.OnPageChangeCallback pageCallback = new ViewPager2.OnPageChangeCallback() {
        @Override
        public void onPageSelected(int position) {
            currentTab = position;
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        ctx = requireContext();
        AppCompatActivity activity = (AppCompatActivity) requireActivity();
        ActionBar actionBar = activity.getSupportActionBar();
        if (actionBar != null) actionBar.setTitle(ctx.getString(com.winlator.R.string.social_hub));

        View view = inflater.inflate(com.winlator.R.layout.social_hub_fragment, container, false);
        TabLayout tabLayout = view.findViewById(com.winlator.R.id.SocialHubTabLayout);
        viewPager = view.findViewById(com.winlator.R.id.SocialHubViewPager);
        viewPager.setAdapter(new HubPagerAdapter(this));
        viewPager.registerOnPageChangeCallback(pageCallback);

        if (savedInstanceState != null) currentTab = savedInstanceState.getInt("socialhub_tab", TAB_NEWS);
        mediator = new TabLayoutMediator(tabLayout, viewPager,
                (tab, position) -> tab.setText(titleFor(position)));
        mediator.attach();
        if (currentTab != TAB_NEWS) viewPager.setCurrentItem(currentTab, false);
        return view;
    }

    @Override
    public void onDestroyView() {
        if (viewPager != null) viewPager.unregisterOnPageChangeCallback(pageCallback);
        if (mediator != null) mediator.detach();
        mediator = null;
        viewPager = null;
        super.onDestroyView();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("socialhub_tab", currentTab);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshChildrenOfflineState();
    }

    private String titleFor(int position) {
        if (position == TAB_VIDEOS) return ctx.getString(com.winlator.R.string.socialhub_tab_videos);
        if (position == TAB_FORUMS) return ctx.getString(com.winlator.R.string.socialhub_tab_forums);
        return ctx.getString(com.winlator.R.string.socialhub_tab_news);
    }

    /** Switches to the given tab with a swipe animation. */
    public void selectTab(int tab) {
        if (tab < 0 || tab >= TAB_COUNT) return;
        currentTab = tab;
        if (viewPager != null) viewPager.setCurrentItem(tab, true);
    }

    private void refreshChildrenOfflineState() {
        for (Fragment f : getChildFragmentManager().getFragments()) {
            if (f instanceof NewsFeedFragment) ((NewsFeedFragment) f).refreshOfflineState();
            else if (f instanceof OfficialVideosFragment) ((OfficialVideosFragment) f).refreshOfflineState();
            else if (f instanceof ForumsFragment) ((ForumsFragment) f).refreshOfflineState();
        }
    }

    /** Returns true if a child consumed the back press (article/forum detail). */
    public boolean onBackPressed() {
        // ViewPager2 tags its pages "f" + item id (position here).
        Fragment currentChild = getChildFragmentManager().findFragmentByTag("f" + currentTab);
        if (currentChild instanceof NewsFeedFragment && ((NewsFeedFragment) currentChild).onBackPressed()) return true;
        return currentChild instanceof ForumsFragment && ((ForumsFragment) currentChild).onBackPressed();
    }

    /** Supplies one child fragment per tab; swiping pages through them. */
    private static class HubPagerAdapter extends FragmentStateAdapter {
        HubPagerAdapter(@NonNull Fragment fragment) {
            super(fragment);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            if (position == TAB_VIDEOS) return new OfficialVideosFragment();
            if (position == TAB_FORUMS) return new ForumsFragment();
            return new NewsFeedFragment();
        }

        @Override
        public int getItemCount() {
            return TAB_COUNT;
        }
    }
}
