
package com.speedy10.app;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class PredictionService extends Service {

    private static final String CHANNEL_ID = "speedy10_service";

    private final Handler handler =
        new Handler(Looper.getMainLooper());

    private final ExecutorService executor =
        Executors.newSingleThreadExecutor();

    private final AtomicBoolean checking =
        new AtomicBoolean(false);

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

        Notification notification =
            new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("极速复式预测Pro")
                .setContentText("正在自动分析开奖和生成预测")
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(true)
                .build();

        startForeground(1, notification);

        handler.post(task);
    }

    private void checkLatestDraw() {
        if (!checking.compareAndSet(false, true)) {
            return;
        }

        executor.execute(() -> {
            try {
                boolean success =
                    PredictionEngine.run(getApplicationContext());

                if (success) {
                    Log.i("Speedy10", "45秒自动检查完成");
                } else {
                    Log.e("Speedy10", "自动检查未成功");
                }

            } catch (Exception e) {
                Log.e("Speedy10", "后台检查异常", e);

            } finally {
                checking.set(false);
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel =
                new NotificationChannel(
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
    public int onStartCommand(
        Intent intent, int flags, int startId
    ) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(task);
        executor.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
