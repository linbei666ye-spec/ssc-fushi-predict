
package com.speedy10.app;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class PredictionWorker extends Worker {

    public PredictionWorker(
        @NonNull Context context,
        @NonNull WorkerParameters params
    ) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            boolean success = PredictionEngine.run(
                getApplicationContext()
            );

            if (success) {
                Log.i("Speedy10", "WorkManager后台预测和结算成功");
                return Result.success();
            }

            Log.e("Speedy10", "WorkManager后台预测失败");
            return Result.retry();

        } catch (Exception e) {
            Log.e("Speedy10", "WorkManager执行异常", e);
            return Result.retry();
        }
    }
}
