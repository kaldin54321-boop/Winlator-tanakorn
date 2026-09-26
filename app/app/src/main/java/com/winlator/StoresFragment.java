package com.winlator;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.winlator.store.AmazonCredentialStore;
import com.winlator.store.EpicCredentialStore;

/**
 * Single-hub entry point for the game store integrations (Steam / GOG / Epic / Amazon).
 *
 * Each card shows the sign-in state (read from the same credential storage the
 * store uses) and opens that store's main activity, which hosts login, library,
 * download and shortcut creation — downloads land in
 * {@code <filesDir>/imagefs/<store>_games/...} and shortcuts are written into
 * the chosen Wine container via
 * {@link com.winlator.store.StarLaunchBridge}.
 */
public class StoresFragment extends Fragment {
    private LinearLayout cardsContainer;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        AppCompatActivity activity = (AppCompatActivity) requireActivity();
        ActionBar actionBar = activity.getSupportActionBar();
        if (actionBar != null) actionBar.setTitle(R.string.game_stores);

        ScrollView scrollView = new ScrollView(ctx);
        scrollView.setFillViewport(true);
        cardsContainer = new LinearLayout(ctx);
        cardsContainer.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 16);
        cardsContainer.setPadding(pad, pad, pad, pad);
        scrollView.addView(cardsContainer,
            new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refreshCards();
        return scrollView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // One-time (idempotent) data repair, off the UI thread: older builds
        // installed store games under filesDir/imagefs, which Wine cannot see
        // (Z: maps to the RootFS root), so those shortcuts failed with Wine's
        // "Path not found". Move the data into the Wine-visible rootfs tree,
        // rewrite stored paths, and fix stale shortcut targets.
        new Thread(() -> {
            try {
                android.content.Context ctx = getContext();
                if (ctx == null) return;
                int moved = com.winlator.store.StorePaths.migrateLegacyStores(ctx);
                int fixed = com.winlator.store.StorePaths.repairStoreShortcuts(ctx);
                if ((moved > 0 || fixed > 0) && getActivity() != null) {
                    getActivity().runOnUiThread(this::refreshCards);
                }
            }
            catch (Exception ignored) {}
        }, "store-path-migration").start();
        refreshCards();
    }

    private void refreshCards() {
        if (cardsContainer == null) return;
        Context ctx = requireContext();
        cardsContainer.removeAllViews();
        cardsContainer.addView(makeHeader(ctx));
        cardsContainer.addView(makeCard(ctx, "Steam",
            getString(R.string.stores_desc_steam),
            isSteamLoggedIn(ctx), new Intent(ctx, com.winlator.store.SteamMainActivity.class)));
        cardsContainer.addView(makeCard(ctx, "GOG.com",
            getString(R.string.stores_desc_gog),
            isGogLoggedIn(ctx), new Intent(ctx, com.winlator.store.GogMainActivity.class)));
        cardsContainer.addView(makeCard(ctx, "Epic Games",
            getString(R.string.stores_desc_epic),
            EpicCredentialStore.isLoggedIn(ctx), new Intent(ctx, com.winlator.store.EpicMainActivity.class)));
        cardsContainer.addView(makeCard(ctx, "Amazon Games",
            getString(R.string.stores_desc_amazon),
            AmazonCredentialStore.isLoggedIn(ctx), new Intent(ctx, com.winlator.store.AmazonMainActivity.class)));
    }

    private View makeHeader(Context ctx) {
        TextView tv = new TextView(ctx);
        tv.setText(getString(R.string.stores_header));
        tv.setTextSize(13f);
        tv.setTextColor(0xFFAAAAAA);
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 12);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View makeCard(Context ctx, String name, String desc, boolean loggedIn, Intent intent) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xFF1E1E1E);
        int pad = dp(ctx, 16);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(ctx, 12);
        card.setLayoutParams(cardLp);

        TextView title = new TextView(ctx);
        title.setText(name);
        title.setTextSize(20f);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFFFFFFFF);
        card.addView(title);

        TextView sub = new TextView(ctx);
        sub.setText(desc);
        sub.setTextSize(13f);
        sub.setTextColor(0xFFAAAAAA);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(ctx, 4);
        card.addView(sub, subLp);

        TextView status = new TextView(ctx);
        status.setText(loggedIn ? getString(R.string.stores_status_in) : getString(R.string.stores_status_out));
        status.setTextSize(13f);
        status.setTextColor(loggedIn ? 0xFF7BC47F : 0xFFFFB74D);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(ctx, 8);
        card.addView(status, stLp);

        Button open = new Button(ctx);
        open.setText(loggedIn ? getString(R.string.stores_open_library) : getString(R.string.store_sign_in));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(ctx, 8);
        open.setOnClickListener(v -> startActivity(intent));
        card.addView(open, btnLp);
        return card;
    }

    private static boolean isGogLoggedIn(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("bh_gog_prefs", Context.MODE_PRIVATE);
            String token = sp.getString("access_token", null);
            return token != null && !token.isEmpty();
        }
        catch (Exception e) {
            return false;
        }
    }

    private static boolean isSteamLoggedIn(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("steam_prefs", Context.MODE_PRIVATE);
            String token = sp.getString("refresh_token", null);
            String user = sp.getString("username", null);
            return token != null && !token.isEmpty() && user != null && !user.isEmpty();
        }
        catch (Exception e) {
            return false;
        }
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
