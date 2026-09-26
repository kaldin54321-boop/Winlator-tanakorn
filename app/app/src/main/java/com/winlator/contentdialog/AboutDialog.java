package com.winlator.contentdialog;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.TextView;

import com.winlator.R;

public class AboutDialog extends ContentDialog {
    public AboutDialog(Context context) {
        super(context, R.layout.about_dialog);
        View cancelButton = findViewById(R.id.BTCancel);
        if (cancelButton != null) cancelButton.setVisibility(View.GONE);

        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);

            TextView tvWebpage = findViewById(R.id.TVWebpage);
            String webpageHTML = "<a href=\"https://tanakorn-website.onrender.com\">" + context.getString(R.string.about_main_website) + "</a>";
            tvWebpage.setText(Html.fromHtml(webpageHTML, Html.FROM_HTML_MODE_LEGACY));
            tvWebpage.setMovementMethod(LinkMovementMethod.getInstance());

            TextView tvAppVersion = findViewById(R.id.TVAppVersion);
            tvAppVersion.setText(context.getString(R.string.version) + " " + pInfo.versionName);

            String creatorInfoHTML = String.join("<br /><br />",
                context.getString(R.string.about_creator_line1),
                context.getString(R.string.about_creator_line2)
            );

            TextView tvCreatorInfo = findViewById(R.id.TVCreatorInfo);
            tvCreatorInfo.setText(Html.fromHtml(creatorInfoHTML, Html.FROM_HTML_MODE_LEGACY));
            tvCreatorInfo.setMovementMethod(LinkMovementMethod.getInstance());

            String socialLinksHTML = String.join("<br />",
                context.getString(R.string.about_youtube) + " (<a href=\"https://youtube.com/@TechTanakornOfficialTH\">" + context.getString(R.string.about_visit) + "</a>)",
                context.getString(R.string.about_discord) + " (<a href=\"https://discord.gg/Q74CNHJnq2\">" + context.getString(R.string.about_visit) + "</a>)"
            );

            TextView tvSocialLinks = findViewById(R.id.TVSocialLinks);
            tvSocialLinks.setText(Html.fromHtml(socialLinksHTML, Html.FROM_HTML_MODE_LEGACY));
            tvSocialLinks.setMovementMethod(LinkMovementMethod.getInstance());

            String donationLinksHTML = String.join("<br />",
                context.getString(R.string.about_support_description),
                "",
                context.getString(R.string.about_paypal) + " (<a href=\"https://paypal.me/MUHAMMADINISMAIL\">" + context.getString(R.string.about_donate_link) + "</a>)",
                context.getString(R.string.about_kofi) + " (<a href=\"https://ko-fi.com/haikalmanheem\">" + context.getString(R.string.about_donate_link) + "</a>)",
                context.getString(R.string.about_buymeacoffee) + " (<a href=\"https://buymeacoffee.com/kaltanakorn\">" + context.getString(R.string.about_donate_link) + "</a>)"
            );

            TextView tvDonationLinks = findViewById(R.id.TVDonationLinks);
            tvDonationLinks.setText(Html.fromHtml(donationLinksHTML, Html.FROM_HTML_MODE_LEGACY));
            tvDonationLinks.setMovementMethod(LinkMovementMethod.getInstance());

            String creditsAndThirdPartyAppsHTML = String.join("<br /><br />",
                context.getString(R.string.about_credits_special_thanks),
                context.getString(R.string.about_credits_glibc) + " (<a href=\"https://github.com/termux-pacman/glibc-packages\">Termux Pacman</a>)",
                context.getString(R.string.about_credits_dgvoodoo) + " (<a href=\"https://dege.freeweb.hu/dgVoodoo2\">dege.freeweb.hu/dgVoodoo2</a>)",
                context.getString(R.string.about_credits_vegas) + " (<a href=\"https://github.com/isygold/vegas-releases\">isygold</a>)",
                context.getString(R.string.about_credits_wine) + " (<a href=\"https://www.winehq.org\">winehq.org</a>)",
                context.getString(R.string.about_credits_box86_box64) + " <a href=\"https://github.com/ptitSeb\">ptitseb</a>",
                context.getString(R.string.about_credits_mesa) + " (<a href=\"https://www.mesa3d.org\">mesa3d.org</a>)",
                context.getString(R.string.about_credits_dxvk) + " (<a href=\"https://github.com/doitsujin/dxvk\">github.com/doitsujin/dxvk</a>)",
                context.getString(R.string.about_credits_vkd3d) + " (<a href=\"https://gitlab.winehq.org/wine/vkd3d\">gitlab.winehq.org/wine/vkd3d</a>)",
                context.getString(R.string.about_credits_cnc_ddraw) + " (<a href=\"https://github.com/FunkyFr3sh/cnc-ddraw\">github.com/FunkyFr3sh/cnc-ddraw</a>)"
            );

            TextView tvCreditsAndThirdPartyApps = findViewById(R.id.TVCreditsAndThirdPartyApps);
            if (tvCreditsAndThirdPartyApps != null) {
                tvCreditsAndThirdPartyApps.setText(Html.fromHtml(creditsAndThirdPartyAppsHTML, Html.FROM_HTML_MODE_LEGACY));
                tvCreditsAndThirdPartyApps.setMovementMethod(LinkMovementMethod.getInstance());
            }
        } catch (PackageManager.NameNotFoundException e) {}
    }
}
