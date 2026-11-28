package com.fmtlab.toolbox;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 使用说明：从「关于」页进入，收纳各模块的使用细节与限制 */
public class GuideActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guide);
        Ui.centerTabletContent(this);

        ((TextView) findViewById(R.id.tvTitle)).setText("使用说明");
        ((TextView) findViewById(R.id.tvSubtitle)).setText("细则在这，用到再看");
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        LinearLayout host = (LinearLayout) findViewById(R.id.guideHost);
        int pad = Ui.dpi(this, 16);
        int gap = Ui.dpi(this, 12);
        for (String[] s : GuideText.SECTIONS) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_card);
            card.setElevation(Ui.dp(this, 1f));
            card.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = gap;
            card.setLayoutParams(lp);

            TextView title = new TextView(this, null, 0, R.style.CardText);
            title.setText(s[0]);
            card.addView(title);

            TextView body = new TextView(this, null, 0, R.style.BodyText);
            body.setText(s[1]);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bp.topMargin = Ui.dpi(this, 8);
            body.setLayoutParams(bp);
            card.addView(body);

            host.addView(card);
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Ui.refreshTabletLayout(this);
    }
}
