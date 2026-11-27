
package com.speedy10.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.ViewGroup;

import org.json.JSONObject;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private WebView webView;

    public class AndroidBridge {

        @JavascriptInterface
        public String getBackgroundData() {
            try {
                SharedPreferences prefs = getSharedPreferences(
                    "speedy10_data",
                    MODE_PRIVATE
                );

                JSONObject data = new JSONObject();
                data.put("draws", new org.json.JSONArray(
                    prefs.getString("draws", "[]")
                ));
                data.put("records", new org.json.JSONArray(
                    prefs.getString("records", "[]")
                ));
                data.put("last_issue", prefs.getString("last_issue", ""));
                data.put("last_numbers", prefs.getString("last_numbers", ""));
                data.put("next_issue", prefs.getString("next_issue", ""));
                data.put("last_update", prefs.getString("last_update", ""));

                return data.toString();

            } catch (Exception e) {
                return "{}";
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 启动后台开奖检查服务
        Intent serviceIntent = new Intent(this, PredictionService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        // 创建网页窗口
        webView = new WebView(this);

        webView.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        // 连接 Android 后台数据
        webView.addJavascriptInterface(
            new AndroidBridge(),
            "AndroidBridge"
        );

        webView.setWebViewClient(new WebViewClient());

        setContentView(webView);

        webView.loadUrl(
            "https://linbei666ye-spec.github.io/ssc-fushi-predict/"
        );
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
