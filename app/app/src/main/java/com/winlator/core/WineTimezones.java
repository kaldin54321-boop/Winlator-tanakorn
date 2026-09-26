package com.winlator.core;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;

import com.winlator.R;
import com.winlator.contentdialog.ContentDialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Helper for the Wine TZ option.
 *
 * Research notes (glibc / Wine):
 * - TZ is the standard POSIX/glibc timezone variable. When set to an IANA zone key such as
 *   "Asia/Kuala_Lumpur", glibc loads /usr/share/zoneinfo/Asia/Kuala_Lumpur inside the guest
 *   rootfs to compute local time, UTC offset and DST rules. Wine reads the same value at
 *   startup and maps it to the Windows TIME_ZONE_INFORMATION (used by GetLocalTime,
 *   GetTimeZoneInformation, file timestamps, etc.).
 * - Accepted forms: IANA key ("Asia/Kuala_Lumpur", "America/New_York", "Europe/Paris") or a
 *   POSIX TZ string ("EST5EDT,M3.2.0/2,M11.1.0/2"). We use IANA keys because they are
 *   unambiguous, human readable and exactly what Android's TimeZone.getDefault().getID()
 *   returns, so device detection is a straight pass-through.
 * - If TZ is empty/unset the container runs on the rootfs default (usually UTC), which makes
 *   game clocks, save timestamps and event schedules wrong. Hence every container stores a
 *   non-empty timezone defaulting to the device timezone.
 * - Like LC_ALL it must be exported before wine starts (XServerDisplayActivity puts
 *   TZ into the guest environment). Changing it requires restarting the container.
 */
public class WineTimezones {
    public static String getDeviceDefault() {
        try {
            TimeZone tz = TimeZone.getDefault();
            String id = tz != null ? tz.getID() : "";
            if (id != null && !id.isEmpty()) {
                // Android returns IANA ids (e.g. Asia/Kuala_Lumpur). Keep them as-is
                // when they are known; otherwise still return them so the guest
                // tries the same zone before falling back to UTC.
                return id;
            }
        }
        catch (Exception ignored) {}
        return "UTC";
    }

    public static List<String> getAllIds() {
        List<String> ids = new ArrayList<>(Arrays.asList(TimeZone.getAvailableIDs()));
        Collections.sort(ids);
        // Keep UTC/GMT at the top for easy access.
        ids.remove("UTC");
        ids.remove("GMT");
        ids.add(0, "GMT");
        ids.add(0, "UTC");
        return ids;
    }

    public static String formatOffset(TimeZone tz) {
        long now = new Date().getTime();
        int offsetMillis = tz.getOffset(now);
        int totalMinutes = offsetMillis / 60000;
        char sign = totalMinutes >= 0 ? '+' : '-';
        int abs = Math.abs(totalMinutes);
        int hours = abs / 60;
        int minutes = abs % 60;
        if (minutes == 0) return "UTC" + sign + hours;
        return String.format(Locale.ENGLISH, "UTC%c%d:%02d", sign, hours, minutes);
    }

    public static String getDisplayLabel(String id) {
        try {
            TimeZone tz = TimeZone.getTimeZone(id);
            String city = id.contains("/") ? id.substring(id.lastIndexOf('/') + 1).replace('_', ' ') : id;
            return city + " (" + formatOffset(tz) + ")  —  " + id;
        }
        catch (Exception e) {
            return id;
        }
    }

    public static boolean isValid(String value) {
        if (value == null || value.isEmpty()) return false;
        if (value.equals("UTC") || value.equals("GMT")) return true;
        // IANA key or POSIX TZ string.
        return value.matches("[A-Za-z0-9_\\+\\-./:]+");
    }

    public static void showPickerDialog(Context context, String currentValue, Callback<String> callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setTitle(R.string.timezone);
        dialog.getContentView().findViewById(R.id.BTConfirm).setVisibility(View.GONE);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        int width = AppUtils.getPreferredDialogWidth(context);
        container.setLayoutParams(new FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT));

        final int textColor = AppUtils.getThemeColor(context, R.attr.colorPrimaryText);
        final int hintColor = AppUtils.getThemeColor(context, R.attr.colorSecondaryText);

        EditText searchView = new EditText(context);
        searchView.setHint(android.R.string.search_go);
        searchView.setSingleLine(true);
        searchView.setTextColor(textColor);
        searchView.setHintTextColor(hintColor);
        container.addView(searchView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ListView listView = new ListView(context);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, (int)(320 * context.getResources().getDisplayMetrics().density));
        listParams.topMargin = 8;
        container.addView(listView, listParams);

        final List<String> ids = getAllIds();
        final List<String> labels = new ArrayList<>();
        for (String id : ids) labels.add(getDisplayLabel(id));

        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(context,
            android.R.layout.simple_list_item_single_choice, new ArrayList<>(labels)) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                android.widget.CheckedTextView text = v.findViewById(android.R.id.text1);
                if (text != null) text.setTextColor(textColor);
                else if (v instanceof android.widget.TextView) ((android.widget.TextView) v).setTextColor(textColor);
                return v;
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String label = adapter.getItem(position);
            String zoneId = label;
            int sep = label.lastIndexOf("—");
            if (sep >= 0) zoneId = label.substring(sep + 1).trim();
            callback.call(zoneId);
            dialog.dismiss();
        });
        searchView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                String q = s.toString().toLowerCase(Locale.ENGLISH).trim();
                adapter.clear();
                for (int i = 0; i < ids.size(); i++) {
                    if (q.isEmpty() || labels.get(i).toLowerCase(Locale.ENGLISH).contains(q)) adapter.add(labels.get(i));
                }
                adapter.notifyDataSetChanged();
            }
        });

        FrameLayout slot = dialog.findViewById(R.id.FrameLayout);
        slot.setVisibility(View.VISIBLE);
        slot.addView(container);
        dialog.findViewById(R.id.BTConfirm).setVisibility(View.GONE);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }
}
