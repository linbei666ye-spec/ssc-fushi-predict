package com.speedy10.app;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.net.URL;
import java.net.HttpURLConnection;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class PredictionService extends Service {

    private static final String CHANNEL_ID = "speedy10_service";
    private static final String API_URL =
        "https://speedy10-proxy.lin-bei666ye.workers.dev/api?type=current";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable task = new Runnable() {
        @Override
        public void run() {
            checkLatestDraw();
            handler.postDelayed(this, 45000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("极速预测后台服务")
            .setContentText("正在检查最新开奖数据")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build();

        startForeground(1, notification);
        handler.post(task);
    }

    private void checkLatestDraw() {
        new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                URL url = new URL(API_URL);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream())
                );

                StringBuilder result = new StringBuilder();
                String line;

                while ((line = reader.readLine()) != null) {
                    result.append(line);
                }

                reader.close();

                JSONObject root = new JSONObject(result.toString());
                JSONObject data = root
                    .getJSONObject("result")
                    .getJSONObject("data");

                String issue = data.optString("preDrawIssue", "");
                String numbers = data.optString("preDrawCode", "");

                SharedPreferences prefs = getSharedPreferences(
                    "speedy10_data", MODE_PRIVATE
                );

                String previous = prefs.getString("last_issue", "");

                if (!issue.isEmpty() && !issue.equals(previous)) {
                    prefs.edit()
                        .putString("last_issue", issue)
                        .putString("last_numbers", numbers)
                        .apply();
                }

            } catch (Exception e) {
                android.util.Log.e(
                    "Speedy10",
                    "读取开奖数据失败",
                    e
                );
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "极速预测后台运行",
                NotificationManager.IMPORTANCE_LOW
            );

            NotificationManager manager =
                getSystemService(NotificationManager.class);

            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(task);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}