package com.speedy10.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.net.HttpURLConnection;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

public class PredictionEngine {

    private static final String CURRENT_URL =
        "https://speedy10-proxy.lin-bei666ye.workers.dev/api?type=current";

    private static final String HISTORY_URL =
        "https://speedy10-proxy.lin-bei666ye.workers.dev/api?type=history";

    private static final String PREFS = "speedy10_data";

    private static final int[][] SYSTEMS = {
        {0, 1, 2},
        {1, 2, 3},
        {2, 3, 4}
    };

    private static final String[] SYSTEM_NAMES = {
        "first", "middle", "last"
    };

    private PredictionEngine() {}

    public static boolean run(Context context) {
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
                throw new Exception("当前开奖数据格式不正确");
            }

            if (nextIssue.isEmpty()) {
                try {
                    nextIssue = String.valueOf(
                        Long.parseLong(issue) + 1
                    );
                } catch (Exception ignored) {}
            }

            SharedPreferences prefs = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE
            );

            JSONArray draws = new JSONArray(
                prefs.getString("draws", "[]")
            );

            JSONArray records = new JSONArray(
                prefs.getString("records", "[]")
            );

            try {
                JSONObject history = new JSONObject(request(HISTORY_URL));
                collectHistory(history, draws);
            } catch (Exception e) {
                Log.e("Speedy10", "历史数据读取失败", e);
            }

            addDraw(draws, issue, code);
            draws = sortDraws(draws);

            settleRecords(records, draws);

            if (!nextIssue.isEmpty()) {
                createPrediction(records, draws, nextIssue);
            }

            prefs.edit()
                .putString("draws", draws.toString())
                .putString("records", records.toString())
                .putString("last_issue", issue)
                .putString("last_numbers", codeText)
                .putString("next_issue", nextIssue)
                .putLong("last_update", System.currentTimeMillis())
                .apply();

            Log.i("Speedy10",
                "预测引擎运行成功，当前期：" + issue +
                "，目标期：" + nextIssue);

            return true;

        } catch (Exception e) {
            Log.e("Speedy10", "预测引擎运行失败", e);
            return false;
        }
    }

    private static String request(String address) throws Exception {
        HttpURLConnection connection = null;

        try {
            URL url = new URL(address);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                    connection.getInputStream(), "UTF-8"
                )
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

    private static int[] parseCode(String value) {
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

    private static void collectHistory(
        JSONObject object, JSONArray draws
    ) {
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

    private static void collectObject(
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
                    collectObject(
                        array.get(i), draws,
                        issueKeys, codeKeys, depth + 1
                    );
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
                        JSONArray array = (JSONArray) value;
                        StringBuilder builder = new StringBuilder();

                        for (int i = 0; i < array.length(); i++) {
                            if (i > 0) builder.append(",");
                            builder.append(array.optString(i));
                        }

                        codeText = builder.toString();
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
                Object child = item.opt(keys.next());

                if (child instanceof JSONObject ||
                    child instanceof JSONArray) {
                    collectObject(
                        child, draws,
                        issueKeys, codeKeys, depth + 1
                    );
                }
            }

        } catch (Exception e) {
            Log.e("Speedy10", "解析历史数据失败", e);
        }
    }

    private static void addDraw(
        JSONArray draws, String issue, int[] code
    ) {
        try {
            JSONArray numbers = new JSONArray();

            for (int n : code) numbers.put(n);

            JSONObject item = new JSONObject();
            item.put("issue", issue);
            item.put("code", numbers);

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
            Log.e("Speedy10", "保存开奖失败", e);
        }
    }

    private static JSONArray sortDraws(JSONArray input) {
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

    private static long issueValue(String issue) {
        try {
            return Long.parseLong(
                issue.replaceAll("[^0-9]", "")
            );
        } catch (Exception e) {
            return 0;
        }
    }

    private static class Draw {
        String issue;
        int[] code;

        Draw(String issue, int[] code) {
            this.issue = issue;
            this.code = code;
        }
    }

    private static List<Draw> readDraws(JSONArray array) {
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

    private static void countWindow(
        List<Draw> list,
        int position,
        int size,
        double[] output
    ) {
        int start = Math.max(0, list.size() - size);

        for (int i = start; i < list.size(); i++) {
            output[list.get(i).code[position]]++;
        }
    }

    // 复式 V3.0：按位置独立计算，单码 V2.2 保持不变。
    private static List<Integer> chooseNumbers(
        List<Draw> data, int position
    ) {
        int start = Math.max(0, data.size() - 100);
        List<Draw> list = data.subList(start, data.size());
        int size = list.size();

        double[] c8 = new double[10];
        double[] c10 = new double[10];
        double[] c20 = new double[10];
        double[] c50 = new double[10];
        double[] c100 = new double[10];
        int[] last = new int[10];
        Arrays.fill(last, -1);

        for (int i = 0; i < size; i++) {
            int number = list.get(i).code[position];
            c100[number]++;
            last[number] = i;
            if (i >= Math.max(0, size - 8)) c8[number]++;
            if (i >= Math.max(0, size - 10)) c10[number]++;
            if (i >= Math.max(0, size - 20)) c20[number]++;
            if (i >= Math.max(0, size - 50)) c50[number]++;
        }

        int len8 = Math.max(1, Math.min(8, size));
        int len10 = Math.max(1, Math.min(10, size));
        int len20 = Math.max(1, Math.min(20, size));
        int len50 = Math.max(1, Math.min(50, size));
        int len100 = Math.max(1, size);

        // 趋势：最近10期与此前20期比较。
        int previousStart = Math.max(0, size - 30);
        int previousEnd = Math.max(0, size - 10);
        int previousLength = Math.max(1, previousEnd - previousStart);
        double[] previous = new double[10];

        for (int i = previousStart; i < previousEnd; i++) {
            previous[list.get(i).code[position]]++;
        }

        // 稳定性：比较四个时间块的出现率与整体出现率的偏差。
        double[] stabilityDeviation = new double[10];
        int validBlocks = 0;

        for (int block = 0; block < 4; block++) {
            int from = block * size / 4;
            int to = (block + 1) * size / 4;
            int blockLength = to - from;
            if (blockLength <= 0) continue;
            validBlocks++;

            int[] blockCounts = new int[10];
            for (int i = from; i < to; i++) {
                blockCounts[list.get(i).code[position]]++;
            }

            for (int n = 0; n < 10; n++) {
                double blockRate = blockCounts[n] / (double) blockLength;
                double overallRate = c100[n] / len100;
                stabilityDeviation[n] += Math.abs(blockRate - overallRate);
            }
        }

        double[] scores = new double[10];

        for (int n = 0; n < 10; n++) {
            double rate8 = c8[n] / len8 * 100.0;
            double rate10 = c10[n] / len10 * 100.0;
            double rate20 = c20[n] / len20 * 100.0;
            double rate50 = c50[n] / len50 * 100.0;
            double rate100 = c100[n] / len100 * 100.0;

            double recentRate = c10[n] / len10;
            double previousRate = previous[n] / previousLength;
            double trendScore = Math.max(0.0, Math.min(100.0,
                50.0 + (recentRate - previousRate) * 250.0));

            double averageDeviation = validBlocks == 0 ? 0.0
                : stabilityDeviation[n] / validBlocks;
            double stabilityScore = Math.max(0.0,
                100.0 - averageDeviation * 100.0);

            int omission = last[n] < 0 ? size : size - 1 - last[n];
            double omissionScore = Math.min(omission, 30) / 30.0 * 100.0;

            scores[n] =
                rate100 * 0.32 +
                rate8 * 0.10 +
                rate10 * 0.10 +
                rate20 * 0.20 +
                rate50 * 0.12 +
                trendScore * 0.10 +
                stabilityScore * 0.04 +
                omissionScore * 0.02;
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

    private static double[][] singleFeatures(List<Draw> data) {
        double[][] raw = new double[6][10];

        int start = Math.max(0, data.size() - 100);
        List<Draw> list = data.subList(start, data.size());

        if (list.isEmpty()) {
            for (double[] row : raw) Arrays.fill(row, 50);
            return raw;
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

                int gap = last[n] == -1
                    ? list.size()
                    : list.size() - 1 - last[n];

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

    private static double[] baseWeights() {
        return new double[] {
            0.22, 0.22, 0.16, 0.16, 0.06, 0.18
        };
    }

    private static double[] adaptiveWeights(List<Draw> data) {
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

    private static int chooseSingle(List<Draw> data) {
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

    private static void createPrediction(
        JSONArray records,
        JSONArray drawArray,
        String nextIssue
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
            record.put("algorithm", "复式V3.0/单码V2.2");
            record.put("createdAt", System.currentTimeMillis());

            JSONObject systems = new JSONObject();

            for (int s = 0; s < 3; s++) {
                JSONArray numbers = new JSONArray();

                for (int pos : SYSTEMS[s]) {
                    JSONArray selected = new JSONArray();

                    for (int n : chooseNumbers(data, pos)) {
                        selected.put(n);
                    }

                    numbers.put(selected);
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
            Log.e("Speedy10", "生成预测失败", e);
        }
    }

    private static void settleRecords(
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

                    for (int n : actual.code) {
                        actualNumbers.put(n);
                    }

                    single.put("status", hit ? "中" : "挂");
                    single.put("actual", actualNumbers);
                }
            }

        } catch (Exception e) {
            Log.e("Speedy10", "自动结算失败", e);
        }
    }
}