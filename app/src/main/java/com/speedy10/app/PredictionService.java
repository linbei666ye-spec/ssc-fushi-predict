package com.speedy10.app;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.net.HttpURLConnection;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PredictionService extends Service {

    private static final String CHANNEL_ID = "speedy10_service";
    private static final String CURRENT_URL =
        "https://speedy10-proxy.lin-bei666ye.workers.dev/api?type=current";
    private static final String HISTORY_URL =
        "https://speedy10-proxy.lin-bei666ye.workers.dev/api?type=history";

    private static final String PREFS = "speedy10_data";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor =
        Executors.newSingleThreadExecutor();

    private boolean checking = false;

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
            .setContentTitle("极速复式预测Pro")
            .setContentText("正在自动分析开奖和生成预测")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build();

        startForeground(1, notification);
        handler.post(task);
    }

    private String request(String address) throws Exception {
        HttpURLConnection connection = null;

        try {
            URL url = new URL(address);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), "UTF-8")
            );

            StringBuilder result = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                result.append(line);
            }

            reader.close();
            return result.toString();

        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void checkLatestDraw() {
        if (checking) return;
        checking = true;

        executor.execute(() -> {
            try {
                JSONObject current = new JSONObject(request(CURRENT_URL));
                JSONObject data = current
                    .getJSONObject("result")
                    .getJSONObject("data");

                String issue = data.optString("preDrawIssue", "");
                String codeText = data.optString("preDrawCode", "");
                String nextIssue = data.optString("drawIssue", "");

                int[] code = parseCode(codeText);

                if (issue.isEmpty() || code == null) {
                    throw new Exception("开奖数据格式不正确");
                }

                if (nextIssue.isEmpty()) {
                    try {
                        nextIssue = String.valueOf(
                            Long.parseLong(issue) + 1
                        );
                    } catch (Exception ignored) {}
                }

                SharedPreferences prefs =
                    getSharedPreferences(PREFS, MODE_PRIVATE);

                JSONArray draws = new JSONArray(
                    prefs.getString("draws", "[]")
                );

                JSONArray records = new JSONArray(
                    prefs.getString("records", "[]")
                );

                // 读取历史开奖
                try {
                    JSONObject history =
                        new JSONObject(request(HISTORY_URL));
                    collectHistory(history, draws);
                } catch (Exception e) {
                    android.util.Log.e("Speedy10",
                        "历史数据读取失败", e);
                }

                // 合并当前最新开奖
                addDraw(draws, issue, code);

                // 按期号排序并限制保存数量
                draws = sortDraws(draws);

                // 先结算已开奖记录
                settleRecords(records, draws);

                // 为下一期创建预测
                if (!nextIssue.isEmpty()) {
                    createPrediction(records, draws, nextIssue);
                }

                // 保存后台数据
                prefs.edit()
                    .putString("draws", draws.toString())
                    .putString("records", records.toString())
                    .putString("last_issue", issue)
                    .putString("last_numbers", codeText)
                    .putString("next_issue", nextIssue)
                    .putLong("last_update", System.currentTimeMillis())
                    .apply();

                android.util.Log.i("Speedy10",
                    "后台预测完成，当前期：" + issue +
                    "，目标期：" + nextIssue);

            } catch (Exception e) {
                android.util.Log.e("Speedy10",
                    "后台预测失败", e);
            } finally {
                checking = false;
            }
        });
    }

    private int[] parseCode(String value) {
        if (value == null) return null;

        String[] parts = value.split("[,，\\s-]+");
        int[] result = new int[5];

        if (parts.length == 5) {
            try {
                for (int i = 0; i < 5; i++) {
                    result[i] = Integer.parseInt(parts[i]);
                    if (result[i] < 0 || result[i] > 9) return null;
                }
                return result;
            } catch (Exception ignored) {}
        }

        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() != 5) return null;

        for (int i = 0; i < 5; i++) {
            result[i] = digits.charAt(i) - '0';
        }

        return result;
    }

    private void collectHistory(JSONObject object, JSONArray draws) {
        if (object == null) return;

        String[] issueKeys = {
            "preDrawIssue", "issue", "drawIssue", "expect",
            "period", "qihao", "issueNo", "drawNo"
        };

        String[] codeKeys = {
            "preDrawCode", "drawCode", "code", "numbers",
            "openCode", "resultCode", "lotteryCode"
        };

        collectObject(object, draws, issueKeys, codeKeys, 0);
    }

    private void collectObject(
        Object object,
        JSONArray draws,
        String[] issueKeys,
        String[] codeKeys,
        int depth
    ) {
        if (object == null || depth > 12) return;

        try {
            if (object instanceof JSONArray) {
                JSONArray array = (JSONArray) object;
                for (int i = 0; i < array.length(); i++) {
                    collectObject(array.get(i), draws,
                        issueKeys, codeKeys, depth + 1);
                }
                return;
            }

            if (!(object instanceof JSONObject)) return;

            JSONObject item = (JSONObject) object;

            String issue = "";
            String codeText = "";

            for (String key : issueKeys) {
                if (item.has(key)) {
                    issue = item.optString(key, "");
                    if (!issue.isEmpty()) break;
                }
            }

            for (String key : codeKeys) {
                if (item.has(key)) {
                    Object value = item.opt(key);
                    if (value instanceof JSONArray) {
                        JSONArray a = (JSONArray) value;
                        StringBuilder b = new StringBuilder();
                        for (int i = 0; i < a.length(); i++) {
                            if (i > 0) b.append(",");
                            b.append(a.optString(i));
                        }
                        codeText = b.toString();
                    } else {
                        codeText = String.valueOf(value);
                    }

                    if (parseCode(codeText) != null) break;
                }
            }

            int[] code = parseCode(codeText);

            if (!issue.isEmpty() && code != null) {
                addDraw(draws, issue, code);
            }

            Iterator<String> keys = item.keys();

            while (keys.hasNext()) {
                String key = keys.next();
                Object child = item.opt(key);

                if (child instanceof JSONObject ||
                    child instanceof JSONArray) {
                    collectObject(child, draws,
                        issueKeys, codeKeys, depth + 1);
                }
            }

        } catch (Exception e) {
            android.util.Log.e("Speedy10",
                "解析历史数据失败", e);
        }
    }

    private void addDraw(JSONArray draws, String issue, int[] code) {
        try {
            JSONArray result = new JSONArray();

            for (int n : code) result.put(n);

            JSONObject item = new JSONObject();
            item.put("issue", issue);
            item.put("code", result);

            for (int i = 0; i < draws.length(); i++) {
                JSONObject old = draws.optJSONObject(i);

                if (old != null &&
                    issue.equals(old.optString("issue"))) {
                    draws.put(i, item);
                    return;
                }
            }

            draws.put(item);

        } catch (Exception e) {
            android.util.Log.e("Speedy10", "保存开奖失败", e);
        }
    }

    private JSONArray sortDraws(JSONArray input) {
        List<JSONObject> list = new ArrayList<>();

        for (int i = 0; i < input.length(); i++) {
            JSONObject item = input.optJSONObject(i);
            if (item != null) list.add(item);
        }

        Collections.sort(list, (a, b) -> {
            long x = issueValue(a.optString("issue"));
            long y = issueValue(b.optString("issue"));
            return Long.compare(x, y);
        });

        JSONArray output = new JSONArray();

        int start = Math.max(0, list.size() - 500);

        for (int i = start; i < list.size(); i++) {
            output.put(list.get(i));
        }

        return output;
    }

    private long issueValue(String issue) {
        try {
            return Long.parseLong(issue.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private List<Draw> readDraws(JSONArray array) {
        List<Draw> list = new ArrayList<>();

        for (int i = 0; i < array.length(); i++) {
            try {
                JSONObject item = array.getJSONObject(i);
                JSONArray numbers = item.getJSONArray("code");

                if (numbers.length() != 5) continue;

                int[] code = new int[5];

                for (int p = 0; p < 5; p++) {
                    code[p] = numbers.getInt(p);
                }

                list.add(new Draw(
                    item.optString("issue"), code
                ));

            } catch (Exception ignored) {}
        }

        return list;
    }

    private static class Draw {
        String issue;
        int[] code;

        Draw(String issue, int[] code) {
            this.issue = issue;
            this.code = code;
        }
    }

    private static final int[][] SYSTEMS = {
        {0, 1, 2},
        {1, 2, 3},
        {2, 3, 4}
    };

    private static final String[] SYSTEM_NAMES = {
        "first", "middle", "last"
    };

    private List<Integer> chooseNumbers(
        List<Draw> data, int position
    ) {
        int start = Math.max(0, data.size() - 100);
        List<Draw> list = data.subList(start, data.size());

        double[] freq = new double[10];
        double[] r5 = new double[10];
        double[] r10 = new double[10];
        double[] r20 = new double[10];
        double[] r30 = new double[10];
        double[] previous = new double[10];
        int[] last = new int[10];

        Arrays.fill(last, -1);

        for (int i = 0; i < list.size(); i++) {
            int n = list.get(i).code[position];
            freq[n]++;
            last[n] = i;
        }

        countWindow(list, position, 5, r5);
        countWindow(list, position, 10, r10);
        countWindow(list, position, 20, r20);
        countWindow(list, position, 30, r30);

        int previousStart = Math.max(0, list.size() - 20);
        int previousEnd = Math.max(0, list.size() - 10);

        for (int i = previousStart; i < previousEnd; i++) {
            previous[list.get(i).code[position]]++;
        }

        double volatility = 0;

        for (int n = 0; n < 10; n++) {
            double recent = r10[n] /
                Math.max(1, Math.min(10, list.size()));

            double before = previous[n] /
                Math.max(1, previousEnd - previousStart);

            volatility += Math.abs(recent - before);
        }

        volatility /= 10;

        double factor = Math.min(1, volatility * 5);

        double[] weights = {
            0.25 - 0.05 * factor,
            0.08 + 0.02 * factor,
            0.18 + 0.03 * factor,
            0.18,
            0.14,
            0.05,
            0.12
        };

        double[] scores = new double[10];

        for (int n = 0; n < 10; n++) {
            double longRate = freq[n] /
                Math.max(1, list.size()) * 100;

            double fiveRate = r5[n] /
                Math.max(1, Math.min(5, list.size())) * 100;

            double tenRate = r10[n] /
                Math.max(1, Math.min(10, list.size())) * 100;

            double twentyRate = r20[n] /
                Math.max(1, Math.min(20, list.size())) * 100;

            double thirtyRate = r30[n] /
                Math.max(1, Math.min(30, list.size())) * 100;

            int omission = last[n] == -1 ?
                list.size() : list.size() - 1 - last[n];

            double omissionScore =
                Math.min(omission, 20) / 20.0 * 100;

            double recent = r10[n] /
                Math.max(1, Math.min(10, list.size()));

            double before = previous[n] /
                Math.max(1, previousEnd - previousStart);

            double trend = Math.max(0, Math.min(100,
                50 + (recent - before) * 250));

            scores[n] =
                longRate * weights[0] +
                fiveRate * weights[1] +
                tenRate * weights[2] +
                twentyRate * weights[3] +
                thirtyRate * weights[4] +
                omissionScore * weights[5] +
                trend * weights[6];
        }

        List<Integer> result = new ArrayList<>();

        for (int i = 0; i < 7; i++) {
            int best = -1;

            for (int n = 0; n < 10; n++) {
                if (result.contains(n)) continue;

                if (best == -1 || scores[n] > scores[best]) {
                    best = n;
                }
            }

            if (best >= 0) result.add(best);
        }

        return result;
    }

    private void countWindow(
        List<Draw> list, int position, int size, double[] output
    ) {
        int start = Math.max(0, list.size() - size);

        for (int i = start; i < list.size(); i++) {
            output[list.get(i).code[position]]++;
        }
    }

    private static final String[] FEATURES = {
        "short", "medium", "long",
        "trend", "omission", "consensus"
    };

    private double[] baseWeights() {
        return new double[] {
            0.22, 0.22, 0.16, 0.16, 0.06, 0.18
        };
    }

    private double[][] singleFeatures(List<Draw> data) {
        double[][] raw = new double[6][10];

        int start = Math.max(0, data.size() - 100);
        List<Draw> list = data.subList(start, data.size());

        if (list.isEmpty()) {
            double[][] empty = new double[6][10];
            for (double[] row : empty) Arrays.fill(row, 50);
            return empty;
        }

        for (int pos = 0; pos < 5; pos++) {
            int[] c5 = new int[10];
            int[] c10 = new int[10];
            int[] c20 = new int[10];
            int[] c50 = new int[10];
            int[] c100 = new int[10];
            int[] previous = new int[10];
            int[] last = new int[10];

            Arrays.fill(last, -1);

            for (int i = 0; i < list.size(); i++) {
                int n = list.get(i).code[pos];

                if (i >= list.size() - 5) c5[n]++;
                if (i >= list.size() - 10) c10[n]++;
                if (i >= list.size() - 20) c20[n]++;
                if (i >= list.size() - 50) c50[n]++;

                c100[n]++;
                last[n] = i;

                if (i >= Math.max(0, list.size() - 20) &&
                    i < Math.max(0, list.size() - 10)) {
                    previous[n]++;
                }
            }

            int len5 = Math.min(5, list.size());
            int len10 = Math.min(10, list.size());
            int len20 = Math.min(20, list.size());
            int len50 = Math.min(50, list.size());

            int previousLength = Math.max(
                0, Math.min(10, list.size() - 10)
            );

            for (int n = 0; n < 10; n++) {
                double rate5 = c5[n] / (double) Math.max(1, len5);
                double rate10 = c10[n] / (double) Math.max(1, len10);
                double rate20 = c20[n] / (double) Math.max(1, len20);
                double rate50 = c50[n] / (double) Math.max(1, len50);
                double rate100 = c100[n] / (double) list.size();

                raw[0][n] +=
                    (rate5 * 0.55 + rate10 * 0.45) / 5;

                raw[1][n] +=
                    (rate20 * 0.55 + rate50 * 0.45) / 5;

                raw[2][n] += rate100 / 5;

                double recent = c10[n] /
                    (double) Math.max(1, len10);

                double before = previous[n] /
                    (double) Math.max(1, previousLength);

                raw[3][n] +=
                    (0.5 + (recent - before) * 2.5) / 5;

                int gap = last[n] == -1 ?
                    list.size() : list.size() - 1 - last[n];

                double gapScore = 50 +
                    Math.min(30, Math.max(-30, (gap - 10) * 2));

                raw[4][n] += gapScore / 5;

                boolean appeared = false;

                for (int i = Math.max(0, list.size() - 20);
                     i < list.size(); i++) {
                    if (list.get(i).code[pos] == n) {
                        appeared = true;
                        break;
                    }
                }

                raw[5][n] += (appeared ? 1 : 0) / 5.0;
            }
        }

        for (int f = 0; f < 6; f++) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;

            for (int n = 0; n < 10; n++) {
                min = Math.min(min, raw[f][n]);
                max = Math.max(max, raw[f][n]);
            }

            if (max - min < 0.000001) {
                Arrays.fill(raw[f], 50);
            } else {
                for (int n = 0; n < 10; n++) {
                    raw[f][n] =
                        (raw[f][n] - min) / (max - min) * 100;
                }
            }
        }

        return raw;
    }

    private double[] adaptiveWeights(List<Draw> data) {
        double[] weights = baseWeights();

        if (data.size() < 35) return weights;

        int tests = Math.min(25, data.size() - 30);
        int start = data.size() - tests;

        double[] hits = new double[6];
        double[] tries = new double[6];

        for (int i = start; i < data.size(); i++) {
            List<Draw> past = data.subList(
                Math.max(0, i - 100), i
            );

            if (past.size() < 25) continue;

            double[][] features = singleFeatures(past);
            int[] actual = data.get(i).code;

            for (int f = 0; f < 6; f++) {
                int best = 0;

                for (int n = 1; n < 10; n++) {
                    if (features[f][n] > features[f][best]) {
                        best = n;
                    }
                }

                tries[f]++;

                for (int number : actual) {
                    if (number == best) {
                        hits[f]++;
                        break;
                    }
                }
            }
        }

        double[] rates = new double[6];
        double average = 0;

        for (int f = 0; f < 6; f++) {
            rates[f] = (hits[f] + 2) / (tries[f] + 4);
            average += rates[f];
        }

        average /= 6;

        double total = 0;

        for (int f = 0; f < 6; f++) {
            double ratio = rates[f] / Math.max(0.01, average);
            double factor = Math.max(0.65,
                Math.min(1.35, ratio));

            weights[f] *= factor;
            total += weights[f];
        }

        for (int f = 0; f < 6; f++) {
            weights[f] /= Math.max(0.0001, total);
        }

        return weights;
    }

    private int chooseSingle(List<Draw> data) {
        if (data.isEmpty()) return 0;

        double[][] features = singleFeatures(data);
        double[] weights = adaptiveWeights(data);

        int best = 0;
        double bestScore = -1;

        for (int n = 0; n < 10; n++) {
            double score = 0;

            for (int f = 0; f < 6; f++) {
                score += features[f][n] * weights[f];
            }

            if (score > bestScore) {
                bestScore = score;
                best = n;
            }
        }

        return best;
    }

    private void createPrediction(
        JSONArray records, JSONArray drawArray, String nextIssue
    ) {
        try {
            for (int i = 0; i < records.length(); i++) {
                JSONObject old = records.optJSONObject(i);

                if (old != null &&
                    nextIssue.equals(old.optString("issue"))) {
                    return;
                }
            }

            List<Draw> data = readDraws(drawArray);

            JSONObject record = new JSONObject();
            record.put("issue", nextIssue);
            record.put("algorithm", "V2.2");
            record.put("createdAt", System.currentTimeMillis());

            JSONObject systems = new JSONObject();

            for (int s = 0; s < 3; s++) {
                JSONArray positions = new JSONArray();
                JSONArray numbers = new JSONArray();

                for (int pos : SYSTEMS[s]) {
                    JSONArray selected = new JSONArray();

                    for (int n : chooseNumbers(data, pos)) {
                        selected.put(n);
                    }

                    numbers.put(selected);
                    positions.put(pos);
                }

                JSONObject item = new JSONObject();
                item.put("numbers", numbers);
                item.put("status", "待开奖");
                item.put("actual", JSONObject.NULL);

                systems.put(SYSTEM_NAMES[s], item);
            }

            record.put("systems", systems);

            JSONObject single = new JSONObject();
            single.put("number", chooseSingle(data));
            single.put("status", "待开奖");
            single.put("actual", JSONObject.NULL);
            single.put("algorithm", "V2.2");

            record.put("singleCode", single);

            records.put(0, record);

            while (records.length() > 300) {
                records.remove(records.length() - 1);
            }

        } catch (Exception e) {
            android.util.Log.e("Speedy10",
                "生成预测失败", e);
        }
    }

    private void settleRecords(
        JSONArray records, JSONArray drawArray
    ) {
        try {
            List<Draw> data = readDraws(drawArray);

            for (int r = 0; r < records.length(); r++) {
                JSONObject record = records.optJSONObject(r);
                if (record == null) continue;

                String issue = record.optString("issue");
                Draw actual = null;

                for (Draw d : data) {
                    if (issue.equals(d.issue)) {
                        actual = d;
                        break;
                    }
                }

                if (actual == null) continue;

                JSONObject systems = record.optJSONObject("systems");

                if (systems != null) {
                    for (int s = 0; s < 3; s++) {
                        JSONObject item = systems.optJSONObject(
                            SYSTEM_NAMES[s]
                        );

                        if (item == null ||
                            !"待开奖".equals(item.optString("status"))) {
                            continue;
                        }

                        JSONArray numbers = item.getJSONArray("numbers");
                        JSONArray result = new JSONArray();
                        boolean hit = true;

                        for (int p = 0; p < 3; p++) {
                            int pos = SYSTEMS[s][p];
                            int actualNumber = actual.code[pos];

                            result.put(actualNumber);

                            JSONArray selected = numbers.getJSONArray(p);
                            boolean found = false;

                            for (int n = 0; n < selected.length(); n++) {
                                if (selected.getInt(n) == actualNumber) {
                                    found = true;
                                    break;
                                }
                            }

                            if (!found) hit = false;
                        }

                        item.put("status", hit ? "中" : "挂");
                        item.put("actual", result);
                    }
                }

                JSONObject single = record.optJSONObject("singleCode");

                if (single != null &&
                    "待开奖".equals(single.optString("status"))) {
                    int predicted = single.optInt("number", -1);
                    boolean hit = false;

                    for (int n : actual.code) {
                        if (n == predicted) hit = true;
                    }

                    JSONArray actualNumbers = new JSONArray();

                    for (int n : actual.code) actualNumbers.put(n);

                    single.put("status", hit ? "中" : "挂");
                    single.put("actual", actualNumbers);
                }
            }

        } catch (Exception e) {
            android.util.Log.e("Speedy10",
                "自动结算失败", e);
        }
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
        executor.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}