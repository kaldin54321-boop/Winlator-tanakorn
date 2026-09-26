package com.winlator;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.HardwarePropertiesManager;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class DeviceFragment extends Fragment {

    public static class GlInfo {
        public String renderer = "—";
        public String vendor = "—";
        public String glVersion = "—";
        public boolean gles20;
        public boolean gles30;
        public boolean gles31;
        public boolean gles32;
    }

    private static final HashMap<String, String> CHIP_NAMES = new HashMap<>();
    private static final HashMap<String, String> CHIP_NODE_SIZES = new HashMap<>();
    private static final HashMap<String, String> CHIP_RELEASE_DATES = new HashMap<>();

    static {
        // ---------- Snapdragon 8 series ----------
        CHIP_NAMES.put("SM8850", "Snapdragon 8 Elite Gen 2");
        CHIP_NAMES.put("SM8750", "Snapdragon 8 Elite");
        CHIP_NAMES.put("SM8735", "Snapdragon 8s Gen 4");
        CHIP_NAMES.put("SM8650", "Snapdragon 8 Gen 3");
        CHIP_NAMES.put("SM8635", "Snapdragon 8s Gen 3");
        CHIP_NAMES.put("SM8550", "Snapdragon 8 Gen 2");
        CHIP_NAMES.put("SM8475", "Snapdragon 8+ Gen 1");
        CHIP_NAMES.put("SM8450", "Snapdragon 8 Gen 1");
        CHIP_NAMES.put("SM8350", "Snapdragon 888 5G");
        CHIP_NAMES.put("SM8250", "Snapdragon 865");
        CHIP_NAMES.put("SM8150", "Snapdragon 855");
        CHIP_NAMES.put("SDM845", "Snapdragon 845");
        CHIP_NAMES.put("MSM8998", "Snapdragon 835");
        CHIP_NAMES.put("MSM8996", "Snapdragon 820/821");
        CHIP_NAMES.put("MSM8994", "Snapdragon 810");
        CHIP_NAMES.put("MSM8992", "Snapdragon 808");
        CHIP_NAMES.put("APQ8084", "Snapdragon 805");
        CHIP_NAMES.put("MSM8974", "Snapdragon 800/801");
        // Snapdragon X (laptop) / 8cx
        CHIP_NAMES.put("SC8480XP", "Snapdragon X2 Elite");
        CHIP_NAMES.put("SC8380XP", "Snapdragon X Elite");
        CHIP_NAMES.put("SC8280XP", "Snapdragon 8cx Gen 3");
        CHIP_NAMES.put("SC8180XP", "Snapdragon 8cx Gen 2");
        CHIP_NAMES.put("QCM6490", "Snapdragon QCM6490");
        CHIP_NAMES.put("QCS6490", "Snapdragon QCS6490");

        // ---------- Snapdragon 7 series ----------
        CHIP_NAMES.put("SM7750", "Snapdragon 7 Gen 4");
        CHIP_NAMES.put("SM7735", "Snapdragon 7s Gen 4");
        CHIP_NAMES.put("SM7675", "Snapdragon 7+ Gen 3");
        CHIP_NAMES.put("SM7635", "Snapdragon 7s Gen 3");
        CHIP_NAMES.put("SM7550", "Snapdragon 7 Gen 3");
        CHIP_NAMES.put("SM7475", "Snapdragon 7+ Gen 2");
        CHIP_NAMES.put("SM7450", "Snapdragon 7 Gen 1");
        CHIP_NAMES.put("SM7435", "Snapdragon 7s Gen 2");
        CHIP_NAMES.put("SM7350", "Snapdragon 780G 5G");
        CHIP_NAMES.put("SM7325", "Snapdragon 778G 5G");
        CHIP_NAMES.put("SM7315", "Snapdragon 782G");
        CHIP_NAMES.put("SM7250", "Snapdragon 765G/768G");
        CHIP_NAMES.put("SM7225", "Snapdragon 750G");
        CHIP_NAMES.put("SM7150", "Snapdragon 730G");
        CHIP_NAMES.put("SM7125", "Snapdragon 720G");
        CHIP_NAMES.put("SDM710", "Snapdragon 710");

        // ---------- Snapdragon 6 series ----------
        CHIP_NAMES.put("SM6650", "Snapdragon 6 Gen 4");
        CHIP_NAMES.put("SM6550", "Snapdragon 6 Gen 1");
        CHIP_NAMES.put("SM6475", "Snapdragon 6 Gen 3");
        CHIP_NAMES.put("SM6450", "Snapdragon 6s Gen 3");
        CHIP_NAMES.put("SM6375", "Snapdragon 695 5G");
        CHIP_NAMES.put("SM6350", "Snapdragon 690 5G");
        CHIP_NAMES.put("SM6250", "Snapdragon 750");
        CHIP_NAMES.put("SM6225", "Snapdragon 680/685");
        CHIP_NAMES.put("SM6150", "Snapdragon 675");
        CHIP_NAMES.put("SM6125", "Snapdragon 665");
        CHIP_NAMES.put("SM6115", "Snapdragon 662");
        CHIP_NAMES.put("SDM670", "Snapdragon 670");
        CHIP_NAMES.put("SDM660", "Snapdragon 660");
        CHIP_NAMES.put("SDM636", "Snapdragon 636");
        CHIP_NAMES.put("SDM630", "Snapdragon 630");

        // ---------- Snapdragon 4 series ----------
        CHIP_NAMES.put("SM4550", "Snapdragon 4s Gen 2");
        CHIP_NAMES.put("SM4450", "Snapdragon 4 Gen 2");
        CHIP_NAMES.put("SM4375", "Snapdragon 480+ 5G");
        CHIP_NAMES.put("SM4350", "Snapdragon 480 5G");
        CHIP_NAMES.put("SM4250", "Snapdragon 460");
        CHIP_NAMES.put("SM4150", "Snapdragon 439");
        CHIP_NAMES.put("SDM450", "Snapdragon 450");
        CHIP_NAMES.put("SDM429", "Snapdragon 429");
        CHIP_NAMES.put("SDM439", "Snapdragon 439");

        // ---------- Dimensity 9000 series ----------
        CHIP_NAMES.put("MT6993", "Dimensity 9500");
        CHIP_NAMES.put("MT6991", "Dimensity 9400");
        CHIP_NAMES.put("MT6989", "Dimensity 9300");
        CHIP_NAMES.put("MT6985", "Dimensity 9200+");
        CHIP_NAMES.put("MT6983", "Dimensity 9000/9200");
        CHIP_NAMES.put("MT6982", "Dimensity 9000+");

        // ---------- Dimensity 8000 series ----------
        CHIP_NAMES.put("MT6899", "Dimensity 8400");
        CHIP_NAMES.put("MT6897", "Dimensity 8300");
        CHIP_NAMES.put("MT6896", "Dimensity 8200");
        CHIP_NAMES.put("MT6895", "Dimensity 8100");
        CHIP_NAMES.put("MT6878", "Dimensity 7300");
        CHIP_NAMES.put("MT6886", "Dimensity 7200");
        CHIP_NAMES.put("MT6877V", "Dimensity 1080/7050");
        CHIP_NAMES.put("MT6879", "Dimensity 1050");
        CHIP_NAMES.put("MT6877T", "Dimensity 920");
        CHIP_NAMES.put("MT6877", "Dimensity 900");
        CHIP_NAMES.put("MT6885", "Dimensity 1000");

        // ---------- Dimensity 6000/7000 series ----------
        CHIP_NAMES.put("MT6835", "Dimensity 6300");
        CHIP_NAMES.put("MT6833", "Dimensity 700/810/6000 series");
        CHIP_NAMES.put("MT6893", "Dimensity 1200/8050");
        CHIP_NAMES.put("MT6891", "Dimensity 1100");
        CHIP_NAMES.put("MT6873", "Dimensity 800");
        CHIP_NAMES.put("MT6875", "Dimensity 820");
        CHIP_NAMES.put("MT6853", "Dimensity 720/800U");
        CHIP_NAMES.put("MT6855", "Dimensity 930/7020");

        // ---------- Helio / Kompanio ----------
        CHIP_NAMES.put("MT6789", "Helio G99");
        CHIP_NAMES.put("MT6785", "Helio G90/G95");
        CHIP_NAMES.put("MT6781", "Helio G96");
        CHIP_NAMES.put("MT6779", "Helio G80/G85/P90");
        CHIP_NAMES.put("MT6769", "Helio G70/G80/G85");
        CHIP_NAMES.put("MT6769V", "Helio G100");
        CHIP_NAMES.put("MT6768", "Helio G85");
        CHIP_NAMES.put("MT6765", "Helio G35");
        CHIP_NAMES.put("MT6762", "Helio G25");
        CHIP_NAMES.put("MT6761", "Helio A20");
        CHIP_NAMES.put("MT6771", "Helio P60/P70");
        CHIP_NAMES.put("MT8195", "Kompanio 1380");
        CHIP_NAMES.put("MT8192", "Kompanio 820");
        CHIP_NAMES.put("MT8188", "Kompanio 828");
        CHIP_NAMES.put("MT8183", "Kompanio 500");

        // ---------- Exynos ----------
        CHIP_NAMES.put("S5E9955", "Exynos 2500");
        CHIP_NAMES.put("S5E9945", "Exynos 2400e");
        CHIP_NAMES.put("exynos2400", "Exynos 2400");
        CHIP_NAMES.put("exynos2200", "Exynos 2200");
        CHIP_NAMES.put("exynos2100", "Exynos 2100");
        CHIP_NAMES.put("S5E990", "Exynos 990");
        CHIP_NAMES.put("S5E9830", "Exynos 990");
        CHIP_NAMES.put("S5E9825", "Exynos 9825");
        CHIP_NAMES.put("S5E9820", "Exynos 9820");
        CHIP_NAMES.put("S5E9810", "Exynos 9810");
        CHIP_NAMES.put("S5E8895", "Exynos 8895");
        CHIP_NAMES.put("S5E8890", "Exynos 8890");
        CHIP_NAMES.put("S5E8845", "Exynos 1480");
        CHIP_NAMES.put("S5E8855", "Exynos 1580");
        CHIP_NAMES.put("S5E8835", "Exynos 1380");
        CHIP_NAMES.put("S5E8825", "Exynos 1280");
        CHIP_NAMES.put("S5E8535", "Exynos 1330");
        CHIP_NAMES.put("S5E9611", "Exynos 9611");
        CHIP_NAMES.put("S5E9610", "Exynos 9610");
        CHIP_NAMES.put("S5E9609", "Exynos 9609");
        CHIP_NAMES.put("S5E7890", "Exynos 7904");
        CHIP_NAMES.put("S5E7884", "Exynos 7884");
        CHIP_NAMES.put("S5E3830", "Exynos 850");
        CHIP_NAMES.put("S5E5515", "Exynos W930");

        // ---------- Google Tensor ----------
        CHIP_NAMES.put("GS501", "Google Tensor G5");
        CHIP_NAMES.put("GS401", "Google Tensor G4");
        CHIP_NAMES.put("GS301", "Google Tensor G3");
        CHIP_NAMES.put("GS201", "Google Tensor G2");
        CHIP_NAMES.put("GS101", "Google Tensor G1");

        // ---------- Unisoc ----------
        CHIP_NAMES.put("UMS9632", "Unisoc T830/T8000 Series");
        CHIP_NAMES.put("UMS9620", "Unisoc T820/T9000 Series");
        CHIP_NAMES.put("UMS9621", "Unisoc T820");
        CHIP_NAMES.put("UMS512T", "Unisoc T770");
        CHIP_NAMES.put("UMS512", "Unisoc T760/T750/T618/T616/T612/T610");
        CHIP_NAMES.put("UMS9230", "Unisoc T606");
        CHIP_NAMES.put("UMS312", "Unisoc Tiger T312");
        CHIP_NAMES.put("UMS310", "Unisoc Tiger T310");

        // ---------- Kirin ----------
        CHIP_NAMES.put("kirin9020", "Kirin 9020");
        CHIP_NAMES.put("kirin9010", "Kirin 9010");
        CHIP_NAMES.put("kirin9000S1", "Kirin 9000S1");
        CHIP_NAMES.put("kirin9000S", "Kirin 9000S");
        CHIP_NAMES.put("kirin9000", "Kirin 9000/9000E");
        CHIP_NAMES.put("kirin990", "Kirin 990 5G");
        CHIP_NAMES.put("kirin985", "Kirin 985");
        CHIP_NAMES.put("kirin980", "Kirin 980");
        CHIP_NAMES.put("kirin970", "Kirin 970");
        CHIP_NAMES.put("kirin820", "Kirin 820");
        CHIP_NAMES.put("kirin810", "Kirin 810");
        CHIP_NAMES.put("kirin8000", "Kirin 8000");
        CHIP_NAMES.put("kirin710", "Kirin 710/710F");
        CHIP_NAMES.put("kirin710A", "Kirin 710A");

        // ---------- Node sizes ----------
        CHIP_NODE_SIZES.put("SM8850", "3nm (TSMC N3P)");
        CHIP_NODE_SIZES.put("SM8750", "3nm (TSMC N3E)");
        CHIP_NODE_SIZES.put("SM8735", "4nm (TSMC N4P)");
        CHIP_NODE_SIZES.put("SM8650", "4nm (TSMC N4P)");
        CHIP_NODE_SIZES.put("SM8635", "4nm (TSMC N4P)");
        CHIP_NODE_SIZES.put("SM8550", "4nm (TSMC N4)");
        CHIP_NODE_SIZES.put("SM8475", "4nm (TSMC N4)");
        CHIP_NODE_SIZES.put("SM8450", "4nm (Samsung)");
        CHIP_NODE_SIZES.put("SM8350", "5nm (Samsung)");
        CHIP_NODE_SIZES.put("SM8250", "7nm (TSMC N7P)");
        CHIP_NODE_SIZES.put("SM8150", "7nm (TSMC)");
        CHIP_NODE_SIZES.put("SDM845", "10nm (Samsung)");
        CHIP_NODE_SIZES.put("MSM8998", "10nm (Samsung)");
        CHIP_NODE_SIZES.put("MSM8996", "14nm (Samsung)");
        CHIP_NODE_SIZES.put("SC8480XP", "3nm (TSMC N3E)");
        CHIP_NODE_SIZES.put("SC8380XP", "4nm (TSMC N4)");
        CHIP_NODE_SIZES.put("SC8280XP", "5nm (TSMC)");
        CHIP_NODE_SIZES.put("SM7750", "4nm (TSMC N4P)");
        CHIP_NODE_SIZES.put("SM7735", "4nm (TSMC)");
        CHIP_NODE_SIZES.put("SM7675", "4nm (TSMC N4P)");
        CHIP_NODE_SIZES.put("SM7550", "4nm (TSMC N4P)");
        CHIP_NODE_SIZES.put("SM7475", "4nm (TSMC N4)");
        CHIP_NODE_SIZES.put("SM7325", "6nm (TSMC)");
        CHIP_NODE_SIZES.put("SM6650", "4nm (TSMC)");
        CHIP_NODE_SIZES.put("SM6450", "6nm (Samsung)");
        CHIP_NODE_SIZES.put("SM6375", "6nm (TSMC)");
        CHIP_NODE_SIZES.put("SM6225", "6nm (TSMC)");
        CHIP_NODE_SIZES.put("SM4550", "4nm (Samsung)");
        CHIP_NODE_SIZES.put("MT6993", "3nm (TSMC N3P)");
        CHIP_NODE_SIZES.put("MT6991", "3nm (TSMC N3E)");
        CHIP_NODE_SIZES.put("MT6989", "4nm (TSMC N4P)");
        CHIP_NODE_SIZES.put("MT6985", "4nm (TSMC N4P)");
        CHIP_NODE_SIZES.put("MT6899", "4nm (TSMC N4P)");
        CHIP_NODE_SIZES.put("MT6896", "4nm (TSMC N4)");
        CHIP_NODE_SIZES.put("MT6886", "4nm (TSMC N4)");
        CHIP_NODE_SIZES.put("MT6769V", "6nm (TSMC)");
        CHIP_NODE_SIZES.put("S5E9955", "3nm (Samsung SF3)");
        CHIP_NODE_SIZES.put("S5E9945", "4nm (Samsung SF4P)");
        CHIP_NODE_SIZES.put("exynos2400", "4nm (Samsung SF4P)");
        CHIP_NODE_SIZES.put("exynos2200", "4nm (Samsung)");
        CHIP_NODE_SIZES.put("exynos2100", "5nm (Samsung)");
        CHIP_NODE_SIZES.put("S5E8845", "4nm (Samsung)");
        CHIP_NODE_SIZES.put("S5E8855", "4nm (Samsung)");
        CHIP_NODE_SIZES.put("S5E8835", "5nm (Samsung)");
        CHIP_NODE_SIZES.put("GS501", "3nm (TSMC N3E)");
        CHIP_NODE_SIZES.put("GS401", "4nm (Samsung)");
        CHIP_NODE_SIZES.put("GS201", "4nm (Samsung)");
        CHIP_NODE_SIZES.put("GS301", "4nm (Samsung)");
        CHIP_NODE_SIZES.put("GS101", "5nm (Samsung)");
        CHIP_NODE_SIZES.put("kirin9020", "7nm (SMIC)");
        CHIP_NODE_SIZES.put("kirin9010", "7nm (SMIC)");
        CHIP_NODE_SIZES.put("kirin9000S", "7nm (SMIC)");
        CHIP_NODE_SIZES.put("kirin9000S1", "7nm (SMIC)");
        CHIP_NODE_SIZES.put("kirin9000", "5nm (TSMC)");
        CHIP_NODE_SIZES.put("kirin8000", "7nm (SMIC)");
        CHIP_NODE_SIZES.put("kirin710A", "14nm (SMIC)");

        // ---------- Release dates ----------
        CHIP_RELEASE_DATES.put("SM8850", "September 2025");
        CHIP_RELEASE_DATES.put("SM8750", "October 2024");
        CHIP_RELEASE_DATES.put("SM8735", "April 2025");
        CHIP_RELEASE_DATES.put("SM8650", "October 2023");
        CHIP_RELEASE_DATES.put("SM8635", "March 2024");
        CHIP_RELEASE_DATES.put("SM8550", "November 2022");
        CHIP_RELEASE_DATES.put("SM8475", "May 2022");
        CHIP_RELEASE_DATES.put("SM8450", "December 2021");
        CHIP_RELEASE_DATES.put("SM8350", "December 2020");
        CHIP_RELEASE_DATES.put("SM8250", "December 2019");
        CHIP_RELEASE_DATES.put("SM8150", "December 2018");
        CHIP_RELEASE_DATES.put("SC8480XP", "September 2025");
        CHIP_RELEASE_DATES.put("SC8380XP", "June 2024");
        CHIP_RELEASE_DATES.put("SC8280XP", "December 2021");
        CHIP_RELEASE_DATES.put("SM7750", "May 2025");
        CHIP_RELEASE_DATES.put("SM7735", "August 2025");
        CHIP_RELEASE_DATES.put("SM6650", "February 2025");
        CHIP_RELEASE_DATES.put("SM4550", "June 2024");
        CHIP_RELEASE_DATES.put("MT6993", "September 2025");
        CHIP_RELEASE_DATES.put("MT6991", "October 2024");
        CHIP_RELEASE_DATES.put("MT6989", "November 2023");
        CHIP_RELEASE_DATES.put("MT6985", "November 2023");
        CHIP_RELEASE_DATES.put("MT6899", "December 2024");
        CHIP_RELEASE_DATES.put("exynos2400", "October 2023");
        CHIP_RELEASE_DATES.put("S5E9955", "July 2025");
        CHIP_RELEASE_DATES.put("S5E8855", "October 2024");
        CHIP_RELEASE_DATES.put("S5E8835", "January 2021");
        CHIP_RELEASE_DATES.put("GS501", "August 2025");
        CHIP_RELEASE_DATES.put("GS401", "August 2024");
        CHIP_RELEASE_DATES.put("GS201", "October 2023");
        CHIP_RELEASE_DATES.put("GS301", "October 2022");
        CHIP_RELEASE_DATES.put("GS101", "October 2021");
        CHIP_RELEASE_DATES.put("kirin9020", "November 2024");
        CHIP_RELEASE_DATES.put("MT6769V", "July 2024");
    }

    private boolean isDarkMode = true;
    private String rawChipModel = "";

    private void addInfoRow(LinearLayout parent, String label, Boolean supported) {
        Context ctx = requireContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 4, 0, 4);
        row.setLayoutParams(params);

        TextView tvLabel = new TextView(ctx);
        tvLabel.setText(label);
        tvLabel.setTypeface(null, Typeface.BOLD);
        tvLabel.setTextColor(isDarkMode ? 0xFFFFFFFF : 0xFF000000);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f);
        tvLabel.setLayoutParams(lp1);

        TextView tvVal = new TextView(ctx);
        if (supported == null) {
            tvVal.setText("—");
        } else if (supported) {
            tvVal.setText("✓ " + ctx.getString(R.string.device_supported));
            tvVal.setTextColor(0xFF4CAF50);
        } else {
            tvVal.setText("✗ " + ctx.getString(R.string.device_not_supported));
            tvVal.setTextColor(0xFFF44336);
        }
        if (supported == null) {
            tvVal.setTextColor(isDarkMode ? 0xFFFFFFFF : 0xFF000000);
        }
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        tvVal.setLayoutParams(lp2);
        tvVal.setGravity(Gravity.END);

        row.addView(tvLabel);
        row.addView(tvVal);
        parent.addView(row);
    }

    private void addInfoRow(LinearLayout parent, String label, String value, boolean bold) {
        Context ctx = requireContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 4, 0, 4);
        row.setLayoutParams(params);

        TextView tvLabel = new TextView(ctx);
        tvLabel.setText(label);
        if (bold) tvLabel.setTypeface(null, Typeface.BOLD);
        tvLabel.setTextColor(isDarkMode ? 0xFFFFFFFF : 0xFF000000);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        tvLabel.setLayoutParams(lp1);

        TextView tvVal = new TextView(ctx);
        tvVal.setText(value != null ? value : "—");
        tvVal.setTextColor(isDarkMode ? 0xFFFFFFFF : 0xFF000000);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f);
        tvVal.setLayoutParams(lp2);
        tvVal.setGravity(Gravity.END);

        row.addView(tvLabel);
        row.addView(tvVal);
        parent.addView(row);
    }

    private void applyTheme(View view) {
        view.setBackgroundColor(isDarkMode ? 0xFF121212 : 0xFFFFFFFF);
        applyThemeRecursively(view);
    }

    private void applyThemeRecursively(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyThemeRecursively(group.getChildAt(i));
            }
        } else if (view instanceof TextView) {
            ((TextView) view).setTextColor(isDarkMode ? 0xFFFFFFFF : 0xFF000000);
        }
    }

    private boolean checkGlesVersionFeature(android.content.pm.PackageManager pm, int hexVersion) {
        return pm.hasSystemFeature("android.hardware.opengles.version", hexVersion);
    }

    private String describeBatteryHealth(Intent batteryStatus) {
        if (batteryStatus == null) return null;
        int health = batteryStatus.getIntExtra("health", -1);
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return getString(R.string.device_battery_health_good);
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return getString(R.string.device_battery_health_overheat);
            case BatteryManager.BATTERY_HEALTH_DEAD: return getString(R.string.device_battery_health_dead);
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return getString(R.string.device_battery_health_over_voltage);
            case BatteryManager.BATTERY_HEALTH_COLD: return getString(R.string.device_battery_health_cold);
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return getString(R.string.device_battery_health_failure);
            default: return getString(R.string.device_battery_unknown);
        }
    }

    private String describeBatteryStatus(Intent batteryStatus) {
        if (batteryStatus == null) return null;
        int status = batteryStatus.getIntExtra("status", -1);
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return getString(R.string.device_battery_status_charging);
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return getString(R.string.device_battery_status_discharging);
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return getString(R.string.device_battery_status_not_charging);
            case BatteryManager.BATTERY_STATUS_FULL: return getString(R.string.device_battery_status_full);
            default: return getString(R.string.device_battery_unknown);
        }
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "—";
        double gb = bytes / 1073741824.0;
        if (gb >= 1.0) return String.format(Locale.US, "%.1f GB", gb);
        double mb = bytes / 1048576.0;
        return String.format(Locale.US, "%.0f MB", mb);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(false);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.device_fragment, container, false);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        isDarkMode = prefs.getBoolean("dark_mode", true);
        applyTheme(view);
        populateOsInfo(view);
        populateCpuInfo(view);
        populateSocDetails(view);
        populatePerCoreInfo(view);
        populateRamInfo(view);
        populateBatteryInfo(view);
        populateVulkanInfo(view);
        final View root = view;
        new Thread(() -> {
            final GlInfo glInfo = queryGlInfo();
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                populateGpuInfo(root, glInfo);
                populateOpenGLInfo(root, glInfo);
            });
        }).start();
        return view;
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getActivity() instanceof AppCompatActivity) {
            ActionBar actionBar = ((AppCompatActivity) getActivity()).getSupportActionBar();
            if (actionBar != null) actionBar.setTitle(R.string.device);
        }
    }

    private void populateOsInfo(View view) {
        LinearLayout ll = view.findViewById(R.id.LLOsInfo);
        addInfoRow(ll, getString(R.string.device_os_version), "Android " + Build.VERSION.RELEASE, false);
        addInfoRow(ll, getString(R.string.device_api_level), String.valueOf(Build.VERSION.SDK_INT), false);
        addInfoRow(ll, getString(R.string.device_security_patch), Build.VERSION.SECURITY_PATCH, false);
        addInfoRow(ll, getString(R.string.device_build), Build.DISPLAY, false);
        addInfoRow(ll, getString(R.string.device_kernel), readKernelVersion(), false);
        addInfoRow(ll, getString(R.string.device_manufacturer), Build.MANUFACTURER, false);
        addInfoRow(ll, getString(R.string.device_model), Build.MODEL, false);
        addInfoRow(ll, getString(R.string.device_board), Build.BOARD, false);
        addInfoRow(ll, getString(R.string.device_bootloader), Build.BOOTLOADER, false);
    }

    private void populateCpuInfo(View view) {
        TextView tvCpuName = view.findViewById(R.id.TVCpuName);
        TextView tvCpuCores = view.findViewById(R.id.TVCpuCores);
        String rawModel = "";
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                rawModel = Build.SOC_MODEL;
            } catch (Exception e) {
                rawModel = "";
            }
        }
        if (rawModel == null || rawModel.isEmpty() || rawModel.equals("unknown")) {
            rawModel = readCpuInfoField("Hardware");
        }
        if (rawModel == null || rawModel.isEmpty()) rawModel = Build.HARDWARE;
        rawChipModel = rawModel != null ? rawModel.trim() : "";
        String friendlyName = resolveChipName(rawChipModel);
        String displayName;
        if (friendlyName != null && !friendlyName.equals(rawModel)) {
            displayName = friendlyName + " (" + rawModel + ")";
        } else if (friendlyName != null) {
            displayName = friendlyName;
        } else {
            displayName = "—";
        }
        int cores = Runtime.getRuntime().availableProcessors();
        tvCpuName.setText(displayName);
        tvCpuCores.setText(cores + " " + getString(R.string.device_cpu_cores));
    }

    private void populateSocDetails(View view) {
        LinearLayout ll = view.findViewById(R.id.LLSocDetails);
        String socManufacturer = null;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                socManufacturer = Build.SOC_MANUFACTURER;
                if ("unknown".equals(socManufacturer)) socManufacturer = null;
            } catch (Exception e) {
                socManufacturer = null;
            }
        }
        addInfoRow(ll, getString(R.string.device_soc_manufacturer), socManufacturer, false);
        String primaryAbi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : null;
        addInfoRow(ll, getString(R.string.device_primary_abi), primaryAbi, false);
        addInfoRow(ll, getString(R.string.device_supported_abis), String.join(", ", Build.SUPPORTED_ABIS), false);
        long maxFreqKHz = readMaxCpuFreqKHz();
        String maxFreqStr = maxFreqKHz > 0
            ? String.format(Locale.US, "%.2f GHz", maxFreqKHz / 1000000.0) : null;
        addInfoRow(ll, getString(R.string.device_max_freq), maxFreqStr, false);
        addInfoRow(ll, getString(R.string.device_process_node),
            resolveChipAttribute(CHIP_NODE_SIZES, rawChipModel), false);
        addInfoRow(ll, getString(R.string.device_release_date),
            resolveChipAttribute(CHIP_RELEASE_DATES, rawChipModel), false);
        Float tempC = readCpuTemperatureCelsius();
        addInfoRow(ll, getString(R.string.device_cpu_temp),
            tempC != null ? String.format(Locale.US, "%.1f°C", tempC) : null, false);
    }

    private void populatePerCoreInfo(View view) {
        LinearLayout ll = view.findViewById(R.id.LLPerCore);
        int cores = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < cores; i++) {
            String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            long curKHz = readLongFromFile(base + "scaling_cur_freq");
            long minKHz = readLongFromFile(base + "scaling_min_freq");
            long maxKHz = readLongFromFile(base + "cpuinfo_max_freq");
            String onlineRaw = readFirstLine(new File("/sys/devices/system/cpu/cpu" + i + "/online"));
            boolean online = onlineRaw == null || onlineRaw.trim().equals("1");
            String value;
            if (!online) {
                value = getString(R.string.device_cpu_offline);
            } else if (curKHz > 0 && minKHz > 0 && maxKHz > 0) {
                value = String.format(Locale.US, "%.2f GHz (%.2f–%.2f GHz)",
                    curKHz / 1000000.0, minKHz / 1000000.0, maxKHz / 1000000.0);
            } else if (maxKHz > 0) {
                value = String.format(Locale.US, "%.2f GHz max", maxKHz / 1000000.0);
            } else {
                value = null;
            }
            addInfoRow(ll, "CPU" + i, value, false);
        }
    }

    private void populateRamInfo(View view) {
        Context ctx = requireContext();
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        ((TextView) view.findViewById(R.id.TVRamTotal)).setText(
            getString(R.string.device_ram_total) + ": " + formatBytes(mi.totalMem));
        ((TextView) view.findViewById(R.id.TVRamAvail)).setText(
            getString(R.string.device_ram_available) + ": " + formatBytes(mi.availMem));
    }

    private void populateBatteryInfo(View view) {
        LinearLayout ll = view.findViewById(R.id.LLBattery);
        Context context = requireContext();
        Intent batteryStatus = context.registerReceiver(null,
            new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        BatteryManager batteryManager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        int level = -1;
        if (batteryStatus != null) {
            int rawLevel = batteryStatus.getIntExtra("level", -1);
            int scale = batteryStatus.getIntExtra("scale", -1);
            if (rawLevel >= 0 && scale > 0) level = Math.round(rawLevel * 100.0f / scale);
        }
        addInfoRow(ll, getString(R.string.device_battery_level),
            level >= 0 ? level + "%" : null, false);
        addInfoRow(ll, getString(R.string.device_battery_status),
            describeBatteryStatus(batteryStatus), false);
        addInfoRow(ll, getString(R.string.device_battery_health),
            describeBatteryHealth(batteryStatus), false);
        Integer voltageMv = batteryStatus != null
            ? batteryStatus.getIntExtra("voltage", -1) : null;
        addInfoRow(ll, getString(R.string.device_battery_voltage),
            (voltageMv != null && voltageMv > 0)
                ? String.format(Locale.US, "%.2f V", voltageMv / 1000.0) : null, false);
        Integer tempTenths = batteryStatus != null
            ? batteryStatus.getIntExtra("temperature", -1) : null;
        addInfoRow(ll, getString(R.string.device_battery_temperature),
            (tempTenths != null && tempTenths != -1)
                ? String.format(Locale.US, "%.1f°C", tempTenths / 10.0) : null, false);
        addInfoRow(ll, getString(R.string.device_battery_technology),
            batteryStatus != null ? batteryStatus.getStringExtra("technology") : null, false);
        int designCapacityMah = readBatteryDesignCapacityMah(context);
        addInfoRow(ll, getString(R.string.device_battery_design_capacity),
            designCapacityMah > 0 ? designCapacityMah + " mAh" : null, false);
        int chargeCounterUah = batteryManager != null
            ? batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            : Integer.MIN_VALUE;
        addInfoRow(ll, getString(R.string.device_battery_charge_counter),
            chargeCounterUah > 0 ? (chargeCounterUah / 1000) + " mAh" : null, false);
        if (Build.VERSION.SDK_INT >= 34 && batteryStatus != null) {
            int cycleCount = batteryStatus.getIntExtra("android.os.extra.CYCLE_COUNT", -1);
            addInfoRow(ll, getString(R.string.device_battery_cycle_count),
                cycleCount >= 0 ? String.valueOf(cycleCount) : null, false);
        } else {
            addInfoRow(ll, getString(R.string.device_battery_cycle_count),
                getString(R.string.device_battery_not_available), false);
        }
    }

    private void populateGpuInfo(View view, GlInfo gl) {
        ((TextView) view.findViewById(R.id.TVGpuRenderer)).setText(
            getString(R.string.device_gpu_renderer) + ": " + gl.renderer);
        ((TextView) view.findViewById(R.id.TVGpuVendor)).setText(
            getString(R.string.device_gpu_vendor) + ": " + gl.vendor);
    }

    private void populateOpenGLInfo(View view, GlInfo gl) {
        LinearLayout ll = view.findViewById(R.id.LLOpenGL);
        ll.removeAllViews();
        addInfoRow(ll, "GL_VERSION", gl.glVersion != null ? gl.glVersion : "—", false);
        addInfoRow(ll, "OpenGL ES 2.0", gl.gles20);
        addInfoRow(ll, "OpenGL ES 3.0", gl.gles30);
        addInfoRow(ll, "OpenGL ES 3.1", gl.gles31);
        addInfoRow(ll, "OpenGL ES 3.2", gl.gles32);
    }

    private void populateVulkanInfo(View view) {
        LinearLayout ll = view.findViewById(R.id.LLVulkan);
        android.content.pm.PackageManager pm = requireContext().getPackageManager();
        Map<String, Boolean> versions = new LinkedHashMap<>();
        versions.put("Vulkan 1.0", pm.hasSystemFeature("android.hardware.vulkan.level", 0));
        versions.put("Vulkan 1.1", pm.hasSystemFeature("android.hardware.vulkan.version", 0x401000));
        versions.put("Vulkan 1.2", pm.hasSystemFeature("android.hardware.vulkan.version", 0x402000));
        versions.put("Vulkan 1.3", pm.hasSystemFeature("android.hardware.vulkan.version", 0x403000));
        boolean anyVulkan = false;
        for (Map.Entry<String, Boolean> entry : versions.entrySet()) {
            addInfoRow(ll, entry.getKey(), entry.getValue());
            if (entry.getValue()) anyVulkan = true;
        }
        if (!anyVulkan) addInfoRow(ll, getString(R.string.device_not_supported), (Boolean) null);
    }

    private GlInfo queryGlInfo() {
        GlInfo info = new GlInfo();
        EGLDisplay display = EGL14.eglGetDisplay(0);
        if (display == EGL14.EGL_NO_DISPLAY) return info;
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return info;
        int[] attribs = {EGL14.EGL_RENDERABLE_TYPE, 4, EGL14.EGL_RED_SIZE, 1,
            EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, numConfigs, 0)
            || numConfigs[0] == 0) {
            EGL14.eglTerminate(display);
            return info;
        }
        int[] pbufAttribs = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
        EGLSurface surface = EGL14.eglCreatePbufferSurface(display, configs[0], pbufAttribs, 0);
        if (surface == EGL14.EGL_NO_SURFACE) {
            EGL14.eglTerminate(display);
            return info;
        }
        int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        EGLContext ctx = EGL14.eglCreateContext(display, configs[0],
            EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (ctx == EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroySurface(display, surface);
            EGL14.eglTerminate(display);
            return info;
        }
        EGL14.eglMakeCurrent(display, surface, surface, ctx);
        info.renderer = GLES20.glGetString(GLES20.GL_RENDERER);
        info.vendor = GLES20.glGetString(GLES20.GL_VENDOR);
        info.glVersion = GLES20.glGetString(GLES20.GL_VERSION);
        info.gles20 = info.renderer != null;
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroyContext(display, ctx);
        EGL14.eglDestroySurface(display, surface);
        info.gles30 = tryCreateGlesContext(display, configs[0], 3);
        android.content.pm.PackageManager pm = requireContext().getPackageManager();
        boolean aep = pm.hasSystemFeature("android.hardware.opengles.aep");
        info.gles31 = aep || checkGlesVersionFeature(pm, 0x30001);
        info.gles32 = checkGlesVersionFeature(pm, 0x30002);
        EGL14.eglTerminate(display);
        if (info.renderer == null) info.renderer = "—";
        if (info.vendor == null) info.vendor = "—";
        if (info.glVersion == null) info.glVersion = "—";
        return info;
    }

    private boolean tryCreateGlesContext(EGLDisplay display, EGLConfig config, int ver) {
        int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, ver, EGL14.EGL_NONE};
        EGLContext ctx = EGL14.eglCreateContext(display, config,
            EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (ctx != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(display, ctx);
            return true;
        }
        EGL14.eglGetError();
        return false;
    }

    private int readBatteryDesignCapacityMah(Context context) {
        try {
            Class<?> clazz = Class.forName("com.android.internal.os.PowerProfile");
            Object powerProfile = clazz.getConstructor(Context.class).newInstance(context);
            Object result = clazz.getMethod("getBatteryCapacity").invoke(powerProfile);
            return (int) ((Double) result).doubleValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private String readCpuInfoField(String field) {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith(field)) {
                    int colon = line.indexOf(':');
                    if (colon >= 0) return line.substring(colon + 1).trim();
                }
            }
        } catch (IOException e) {
        }
        return null;
    }

    private Float readCpuTemperatureCelsius() {
        try {
            HardwarePropertiesManager hpm = (HardwarePropertiesManager)
                requireContext().getSystemService(Context.HARDWARE_PROPERTIES_SERVICE);
            if (hpm != null) {
                float[] temps = hpm.getDeviceTemperatures(
                    HardwarePropertiesManager.DEVICE_TEMPERATURE_CPU, 0);
                for (float t : temps) {
                    if (t > -50.0f && t < 150.0f) return t;
                }
            }
        } catch (Exception e) {
        }
        File thermalDir = new File("/sys/class/thermal");
        File[] zones = thermalDir.listFiles((FilenameFilter) (dir, name) ->
            name.startsWith("thermal_zone"));
        if (zones == null) return null;
        for (File zone : zones) {
            String type = readFirstLine(new File(zone, "type"));
            if (type == null) continue;
            String typeLower = type.toLowerCase(Locale.US);
            if (!typeLower.contains("cpu") && !typeLower.contains("soc")
                && !typeLower.contains("apps")) continue;
            String tempRaw = readFirstLine(new File(zone, "temp"));
            if (tempRaw == null) continue;
            try {
                long milliC = Long.parseLong(tempRaw.trim());
                float celsius = milliC > 1000 ? milliC / 1000.0f : milliC;
                if (celsius > -50.0f && celsius < 150.0f) return celsius;
            } catch (NumberFormatException e) {
            }
        }
        return null;
    }

    private String readFirstLine(File file) {
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            return br.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    private String readKernelVersion() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/version"))) {
            String line = br.readLine();
            if (line != null && line.startsWith("Linux version ")) {
                String rest = line.substring("Linux version ".length());
                int space = rest.indexOf(' ');
                return space > 0 ? rest.substring(0, space) : rest;
            }
            return line;
        } catch (IOException e) {
            return System.getProperty("os.version");
        }
    }

    private long readLongFromFile(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line = br.readLine();
            if (line != null) return Long.parseLong(line.trim());
        } catch (Exception e) {
        }
        return -1;
    }

    private long readMaxCpuFreqKHz() {
        long maxFreq = -1;
        int cores = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < cores; i++) {
            try (BufferedReader br = new BufferedReader(new FileReader(
                "/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq"))) {
                String line = br.readLine();
                if (line != null) {
                    long freq = Long.parseLong(line.trim());
                    if (freq > maxFreq) maxFreq = freq;
                }
            } catch (Exception e) {
            }
        }
        return maxFreq;
    }

    private String resolveChipAttribute(HashMap<String, String> table, String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String value = table.get(raw);
        if (value != null) return value;
        String rawUpper = raw.toUpperCase(Locale.US);
        for (Map.Entry<String, String> e : table.entrySet()) {
            if (e.getKey().toUpperCase(Locale.US).equals(rawUpper)) return e.getValue();
        }
        for (Map.Entry<String, String> e : table.entrySet()) {
            if (rawUpper.startsWith(e.getKey().toUpperCase(Locale.US))) return e.getValue();
        }
        return null;
    }

    private String resolveChipName(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String name = CHIP_NAMES.get(raw);
        if (name != null) return name;
        String rawUpper = raw.toUpperCase(Locale.US);
        for (Map.Entry<String, String> e : CHIP_NAMES.entrySet()) {
            if (e.getKey().toUpperCase(Locale.US).equals(rawUpper)) return e.getValue();
        }
        for (Map.Entry<String, String> e : CHIP_NAMES.entrySet()) {
            if (rawUpper.startsWith(e.getKey().toUpperCase(Locale.US))) return e.getValue();
        }
        return raw;
    }
}
