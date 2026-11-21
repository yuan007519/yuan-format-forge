package com.fmtlab.toolbox;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;

/** 编码工具箱：Base64 / URL / Hex / Unicode 转义 */
public class CodecActivity extends Activity {

    private static final String[] TYPES = {"Base64", "URL", "十六进制", "Unicode"};
    private static final String[] DIRS = {"编码", "解码"};

    private EditText etCodeInput;
    private TextView tvCodeResult;
    private Ui.Chips typeChips, dirChips;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_codec);

        ((TextView) findViewById(R.id.tvTitle)).setText("编码工具箱");
        ((TextView) findViewById(R.id.tvSubtitle)).setText("Base64 · URL · Hex · Unicode 互转");
        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        etCodeInput = (EditText) findViewById(R.id.etCodeInput);
        tvCodeResult = (TextView) findViewById(R.id.tvCodeResult);
        typeChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.typeChips), TYPES, 0);
        dirChips = new Ui.Chips(this, (LinearLayout) findViewById(R.id.dirChips), DIRS, 0);

        findViewById(R.id.btnCodeRun).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run();
            }
        });
        findViewById(R.id.btnCodeCopy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.copy(CodecActivity.this, tvCodeResult.getText().toString());
            }
        });
        findViewById(R.id.btnCodeClear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                etCodeInput.setText("");
                tvCodeResult.setText("转换结果会显示在这里");
            }
        });
    }

    private void run() {
        String input = etCodeInput.getText().toString();
        if (TextUtils.isEmpty(input)) {
            Ui.toast(this, "请输入内容");
            return;
        }
        try {
            String out;
            int type = typeChips.selected();
            boolean encode = dirChips.selected() == 0;
            if (type == 0) {
                if (encode) {
                    out = Base64.encodeToString(input.getBytes("UTF-8"), Base64.NO_WRAP);
                } else {
                    String clean = input.replaceAll("[\\s]", "");
                    byte[] bytes = Base64.decode(clean, Base64.DEFAULT);
                    out = new String(bytes, "UTF-8");
                }
            } else if (type == 1) {
                out = encode ? URLEncoder.encode(input, "UTF-8") : URLDecoder.decode(input, "UTF-8");
            } else if (type == 2) {
                if (encode) {
                    out = toHex(input.getBytes("UTF-8"));
                } else {
                    out = new String(fromHex(input.replaceAll("[^0-9a-fA-F]", "")), "UTF-8");
                }
            } else {
                out = encode ? toUnicode(input) : fromUnicode(input);
            }
            tvCodeResult.setText(out);
        } catch (Exception e) {
            tvCodeResult.setText("转换失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format(java.util.Locale.US, "%02X", b & 0xFF));
            sb.append(' ');
        }
        return sb.toString().trim();
    }

    private byte[] fromHex(String hex) {
        if (hex.length() % 2 != 0) hex = hex.substring(0, hex.length() - 1);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        for (int i = 0; i + 1 < hex.length(); i += 2) {
            bo.write(Integer.parseInt(hex.substring(i, i + 2), 16));
        }
        return bo.toByteArray();
    }

    private String toUnicode(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0x7E) {
                sb.append(String.format(java.util.Locale.US, "\\u%04X", (int) c));
            } else if (c == '\n') {
                sb.append("\\n");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private String fromUnicode(String s) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if ((n == 'u' || n == 'U') && i + 6 <= s.length()) {
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                        i += 6;
                        continue;
                    } catch (Exception ignored) {}
                } else if (n == 'n') {
                    sb.append('\n');
                    i += 2;
                    continue;
                } else if (n == 't') {
                    sb.append('\t');
                    i += 2;
                    continue;
                } else if (n == 'r') {
                    sb.append('\r');
                    i += 2;
                    continue;
                } else if (n == '\\') {
                    sb.append('\\');
                    i += 2;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
