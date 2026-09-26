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
import java.util.List;
import java.util.Locale;

/**
 * Helper for the Wine LC_ALL option.
 *
 * Research notes (glibc / Wine):
 * - LC_ALL is a glibc override that takes precedence over LANG and every LC_* category
 *   (LC_MESSAGES, LC_TIME, LC_NUMERIC, ...). Wine reads the Unix locale at startup and maps
 *   it to a Windows locale/codepage (language, sorting, date/time formats, ANSI/OEM codepage).
 * - Format is language_COUNTRY.ENCODING, e.g. en_US.UTF-8. The special values "C", "C.UTF-8"
 *   and "POSIX" mean "no localization" (ASCII only).
 * - The locale must exist inside the guest rootfs/container (locales generated). If it does
 *   not exist, glibc/Wine silently fall back to "C", so we only offer known-good UTF-8 locales
 *   plus "C". Users can still type any value manually.
 * - It must be exported in the environment before wine starts (see XServerDisplayActivity,
 *   GuestProgramLauncherComponent env vars). Changing it requires restarting the container.
 */
public class WineLocales {
    public static final String[] LOCALE_CODES = {
        "C",
        "C.UTF-8",
        "en_US.UTF-8",
        "en_GB.UTF-8",
        "ar_EG.UTF-8",
        "ar_SA.UTF-8",
        "ar_SY.UTF-8",
        "ar_TN.UTF-8",
        "ar_YE.UTF-8",
        "bg_BG.UTF-8",
        "ca_ES.UTF-8",
        "cs_CZ.UTF-8",
        "da_DK.UTF-8",
        "de_AT.UTF-8",
        "de_CH.UTF-8",
        "de_DE.UTF-8",
        "de_LI.UTF-8",
        "de_LU.UTF-8",
        "el_GR.UTF-8",
        "es_ES.UTF-8",
        "es_MX.UTF-8",
        "es_AR.UTF-8",
        "et_EE.UTF-8",
        "fi_FI.UTF-8",
        "fr_FR.UTF-8",
        "fr_CA.UTF-8",
        "fr_BE.UTF-8",
        "fr_CH.UTF-8",
        "he_IL.UTF-8",
        "hi_IN.UTF-8",
        "hr_HR.UTF-8",
        "hu_HU.UTF-8",
        "id_ID.UTF-8",
        "it_IT.UTF-8",
        "ja_JP.UTF-8",
        "ko_KR.UTF-8",
        "lt_LT.UTF-8",
        "lv_LV.UTF-8",
        "ms_MY.UTF-8",
        "nb_NO.UTF-8",
        "nl_NL.UTF-8",
        "nl_BE.UTF-8",
        "pl_PL.UTF-8",
        "pt_PT.UTF-8",
        "pt_BR.UTF-8",
        "ro_RO.UTF-8",
        "ru_RU.UTF-8",
        "ru_UA.UTF-8",
        "sk_SK.UTF-8",
        "sl_SI.UTF-8",
        "sr_RS.UTF-8",
        "sv_SE.UTF-8",
        "th_TH.UTF-8",
        "tr_TR.UTF-8",
        "uk_UA.UTF-8",
        "vi_VN.UTF-8",
        "zh_CN.UTF-8",
        "zh_TW.UTF-8",
        "zh_HK.UTF-8"
    };

    public static String getDisplayName(String code) {
        if (code == null) return "";
        if (code.equals("C")) return "C (POSIX)";
        if (code.equals("C.UTF-8")) return "C.UTF-8 (POSIX UTF-8)";
        String base = code;
        int dot = base.indexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String[] parts = base.split("_");
        try {
            Locale locale;
            if (parts.length >= 2) locale = new Locale(parts[0], parts[1]);
            else if (parts.length == 1) locale = new Locale(parts[0]);
            else return code;
            String lang = locale.getDisplayLanguage(Locale.ENGLISH);
            String country = locale.getDisplayCountry(Locale.ENGLISH);
            if (lang == null || lang.isEmpty()) return code;
            if (country == null || country.isEmpty()) return lang;
            return lang.substring(0, 1).toUpperCase(Locale.ENGLISH) + lang.substring(1) + " (" + country + ")";
        }
        catch (Exception e) {
            return code;
        }
    }

    /**
     * Detect the device language/region and map it to a Wine LC_ALL value.
     * e.g. device ms_MY -> ms_MY.UTF-8, device en -> en_US.UTF-8.
     * Falls back to en_US.UTF-8 when nothing matches.
     */
    public static String getDeviceDefault() {
        try {
            Locale locale = Locale.getDefault();
            String language = locale != null ? locale.getLanguage() : "";
            String country = locale != null ? locale.getCountry() : "";
            if (language == null) language = "";
            if (country == null) country = "";
            language = language.toLowerCase(Locale.ENGLISH);
            country = country.toUpperCase(Locale.ENGLISH);
            if (language.isEmpty()) return "en_US.UTF-8";

            if (!country.isEmpty()) {
                String candidate = language + "_" + country + ".UTF-8";
                for (String code : LOCALE_CODES) {
                    if (code.equalsIgnoreCase(candidate)) return code;
                }
                // Accept the raw device value even if not in our curated list,
                // as long as it looks like ll_CC.
                if (language.matches("[a-z]{2,3}") && country.matches("[A-Z]{2}")) return candidate;
            }
            // Language only: pick first curated locale with same language.
            for (String code : LOCALE_CODES) {
                if (code.toLowerCase(Locale.ENGLISH).startsWith(language + "_")) return code;
            }
            // Common bare-language fallbacks.
            switch (language) {
                case "en": return "en_US.UTF-8";
                case "zh": return "zh_CN.UTF-8";
                case "pt": return "pt_BR.UTF-8";
                default: break;
            }
        }
        catch (Exception ignored) {}
        return "en_US.UTF-8";
    }

    public static boolean isValid(String value) {
        if (value == null || value.isEmpty()) return false;
        if (value.equals("C") || value.equals("C.UTF-8") || value.equals("POSIX")) return true;
        return value.matches("[a-zA-Z]+_[a-zA-Z]+(\\.[a-zA-Z0-9\\-]+)?(@[a-zA-Z]+)?");
    }

    public static void showPickerDialog(Context context, String currentValue, Callback<String> callback) {
        ContentDialog dialog = new ContentDialog(context);
        dialog.setTitle(R.string.wine_localization);
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

        final List<String> codes = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        for (String code : LOCALE_CODES) {
            codes.add(code);
            labels.add(getDisplayName(code) + "  —  " + code);
        }
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
            // label format: "Display — code"
            String code = label;
            int sep = label.lastIndexOf("—");
            if (sep >= 0) code = label.substring(sep + 1).trim();
            callback.call(code);
            dialog.dismiss();
        });
        searchView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                String q = s.toString().toLowerCase(Locale.ENGLISH).trim();
                adapter.clear();
                for (int i = 0; i < codes.size(); i++) {
                    if (q.isEmpty() || labels.get(i).toLowerCase(Locale.ENGLISH).contains(q)) adapter.add(labels.get(i));
                }
                adapter.notifyDataSetChanged();
            }
        });

        FrameLayout slot = dialog.findViewById(R.id.FrameLayout);
        slot.setVisibility(View.VISIBLE);
        slot.addView(container);
        // Hide the OK/Cancel bottom bar for a pure picker.
        dialog.findViewById(R.id.BTConfirm).setVisibility(View.GONE);
        dialog.findViewById(R.id.BTCancel).setVisibility(View.GONE);
        dialog.show();
    }
}
