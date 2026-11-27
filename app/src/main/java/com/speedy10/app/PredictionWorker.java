
package com.speedy10.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class PredictionWorker extends Worker {

    private static final String CURRENT_URL =
        "https://speedy10-proxy.lin-bei666ye.workers.dev/api?type=current";

    public PredictionWorker(
        @NonNull Context context,
        @NonNull WorkerParameters params
    ) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        HttpURLConnection connection = null;

        try {
            URL url = new URL(CURRENT_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    connection.getInputStream(), "UTF-8"
                )
            );

            StringBuilder response = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            JSONObject current = new JSONObject(response.toString());
            JSONObject data = current
                .getJSONObject("result")
                .getJSONObject("data");

            String issue = data.optString("preDrawIssue", "");
            String codeText = data.optString("preDrawCode", "");

            String[] parts = codeText.split("[,，\\s-]+");

            if (issue.isEmpty() || parts.length != 5) {
                return Result.retry();
            }

            JSONArray numbers = new JSONArray();

            for (String part : parts) {
                int number = Integer.parseInt(part);

                if (number < 0 || number > 9) {
                    return Result.retry();
                }

                numbers.put(number);
            }

            SharedPreferences prefs =
                getApplicationContext().getSharedPreferences(
                    "speedy10_data",
                    Context.MODE_PRIVATE
                );

            JSONArray draws = new JSONArray(
                prefs.getString("draws", "[]")
            );

            JSONObject draw = new JSONObject();
            draw.put("issue", issue);
            draw.put("code", numbers);

            boolean found = false;

            for (int i = 0; i < draws.length(); i++) {
                JSONObject old = draws.optJSONObject(i);

                if (old != null &&
                    issue.equals(old.optString("issue"))) {
                    draws.put(i, draw);
                    found = true;
                    break;
                }
            }

            if (!found) {
                draws.put(draw);
            }

            prefs.edit()
                .putString("draws", draws.toString())
                .putString("last_issue", issue)
                .putString("last_numbers", codeText)
                .putLong("last_update", System.currentTimeMillis())
                .apply();

            Log.i("Speedy10", "后台补漏成功：" + issue);

            return Result.success();

        } catch (Exception e) {
            Log.e("Speedy10", "后台补漏失败", e);
            return Result.retry();

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
