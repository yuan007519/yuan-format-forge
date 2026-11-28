package com.fmtlab.toolbox;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

/** 关于 / 开源许可：GPLv3 §5(d) 要求的法律声明、第三方组件与源码获取方式 */
public class AboutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        Ui.centerTabletContent(this);

        ((TextView) findViewById(R.id.tvTitle)).setText("关于");
        ((TextView) findViewById(R.id.tvSubtitle)).setText("版本 " + LicenseText.VERSION_NAME + " · 作者 袁神 · GPLv3");
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        ((TextView) findViewById(R.id.tvAboutInfo)).setText(LicenseText.about());
        ((TextView) findViewById(R.id.tvLicense)).setText(LicenseText.license());
        ((TextView) findViewById(R.id.tvPrivacy)).setText(LicenseText.privacy());
        ((TextView) findViewById(R.id.tvAuthor)).setText(LicenseText.author());

        findViewById(R.id.btnCopyRepo).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.copy(AboutActivity.this, LicenseText.REPO_URL);
            }
        });

        findViewById(R.id.btnOpenGuide).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new android.content.Intent(AboutActivity.this, GuideActivity.class));
            }
        });
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 1.6.1：旋转不再重建页面，这里只把平板居中列的宽度按新屏幕重算一次
        Ui.refreshTabletLayout(this);
    }
}
