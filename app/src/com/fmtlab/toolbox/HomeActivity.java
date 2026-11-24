package com.fmtlab.toolbox;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/** 首页：六个功能入口 */
public class HomeActivity extends Activity {

    private static final class Feature {
        final String title;
        final String desc;
        final int icon;
        final int tint;
        final int tintBg;
        final Class<?> target;

        Feature(String title, String desc, int icon, int tint, int tintBg, Class<?> target) {
            this.title = title;
            this.desc = desc;
            this.icon = icon;
            this.tint = tint;
            this.tintBg = tintBg;
            this.target = target;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        Feature[] features = new Feature[]{
                new Feature(getString(R.string.feat_text_title), getString(R.string.feat_text_desc),
                        R.drawable.ic_text, getColor(R.color.tile_text), getColor(R.color.tile_text_bg), TextActivity.class),
                new Feature(getString(R.string.feat_image_title), getString(R.string.feat_image_desc),
                        R.drawable.ic_image, getColor(R.color.tile_image), getColor(R.color.tile_image_bg), ImageActivity.class),
                new Feature(getString(R.string.feat_pdf_title), getString(R.string.feat_pdf_desc),
                        R.drawable.ic_pdf, getColor(R.color.tile_pdf), getColor(R.color.tile_pdf_bg), PdfActivity.class),
                new Feature(getString(R.string.feat_doc_title), getString(R.string.feat_doc_desc),
                        R.drawable.ic_doc, getColor(R.color.tile_doc), getColor(R.color.tile_doc_bg), DocActivity.class),
                new Feature(getString(R.string.feat_sub_title), getString(R.string.feat_sub_desc),
                        R.drawable.ic_sub, getColor(R.color.tile_doc), getColor(R.color.tile_doc_bg), SubtitleActivity.class),
                new Feature(getString(R.string.feat_audio_title), getString(R.string.feat_audio_desc),
                        R.drawable.ic_audio, getColor(R.color.tile_audio), getColor(R.color.tile_audio_bg), AudioActivity.class),
                new Feature(getString(R.string.feat_video_title), getString(R.string.feat_video_desc),
                        R.drawable.ic_video, getColor(R.color.tile_video), getColor(R.color.tile_video_bg), VideoActivity.class),
                new Feature(getString(R.string.feat_convert_title), getString(R.string.feat_convert_desc),
                        R.drawable.ic_convert, getColor(R.color.tile_convert), getColor(R.color.tile_convert_bg), ConvertActivity.class),
                new Feature(getString(R.string.feat_codec_title), getString(R.string.feat_codec_desc),
                        R.drawable.ic_code, getColor(R.color.tile_code), getColor(R.color.tile_code_bg), CodecActivity.class),
                new Feature(getString(R.string.feat_about_title), getString(R.string.feat_about_desc),
                        R.drawable.ic_info, getColor(R.color.tile_code), getColor(R.color.tile_code_bg), AboutActivity.class),
        };

        LinearLayout host = (LinearLayout) findViewById(R.id.cardHost);
        LinearLayout row = null;
        for (int i = 0; i < features.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                host.addView(row);
            }
            row.addView(makeCard(features[i], i, features.length));
        }
    }

    private View makeCard(Feature f, int index, int total) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dpi(this, 15), Ui.dpi(this, 15), Ui.dpi(this, 15), Ui.dpi(this, 16));
        card.setBackgroundResource(R.drawable.bg_card_ripple);
        card.setElevation(Ui.dp(this, 1.2f));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout.LayoutParams lp;
        boolean alone = (total % 2 == 1) && (index == total - 1);
        if (alone) {
            lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        } else {
            lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        }
        lp.setMargins(Ui.dpi(this, 5), Ui.dpi(this, 5), Ui.dpi(this, 5), Ui.dpi(this, 5));
        card.setLayoutParams(lp);

        FrameLayout tile = new FrameLayout(this);
        GradientDrawable bg = Ui.roundRect(this, f.tintBg, 13f, 0, 0);
        tile.setBackground(bg);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(Ui.dpi(this, 42), Ui.dpi(this, 42));
        tile.setLayoutParams(tlp);
        ImageView icon = new ImageView(this);
        icon.setImageResource(f.icon);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(f.tint));
        icon.setPadding(Ui.dpi(this, 9), Ui.dpi(this, 9), Ui.dpi(this, 9), Ui.dpi(this, 9));
        tile.addView(icon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(tile);

        TextView title = new TextView(this);
        title.setText(f.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp2.topMargin = Ui.dpi(this, 12);
        title.setLayoutParams(tlp2);
        card.addView(title);

        TextView desc = new TextView(this);
        desc.setText(f.desc);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        desc.setTextColor(getColor(R.color.text_secondary));
        desc.setMaxLines(3);
        desc.setLineSpacing(Ui.dp(this, 2f), 1f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = Ui.dpi(this, 6);
        desc.setLayoutParams(dlp);
        card.addView(desc);

        final Class<?> target = f.target;
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, target));
                overridePendingTransition(0, 0);
            }
        });

        card.setAlpha(0f);
        card.animate().alpha(1f).setStartDelay(index * 45L).setDuration(260).start();
        return card;
    }
}
