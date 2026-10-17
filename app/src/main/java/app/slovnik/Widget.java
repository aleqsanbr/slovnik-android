package app.slovnik;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Home-screen card: streak + how many words are due, and one due word.
 * Tap the card to see the translation, then "Forgot" / "Remember" grade it and the next word appears.
 * Built from the local copy of the dictionary, so it works offline.
 */
public class Widget extends AppWidgetProvider {
    static final String ACTION_REVEAL = "app.slovnik.widget.REVEAL";
    static final String ACTION_GRADE = "app.slovnik.widget.GRADE";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("widget", Context.MODE_PRIVATE); }

    /** Redraws every Словник widget; called whenever the dictionary changes. */
    static void updateAll(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, Widget.class));
        for (int id : ids) render(c, m, id);
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) render(c, m, id);
    }

    @Override
    public void onDeleted(Context c, int[] ids) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (int id : ids) e.remove("word_" + id).remove("open_" + id);
        e.apply();
    }

    @Override
    public void onReceive(Context c, Intent i) {
        super.onReceive(c, i);
        int wid = i.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (wid == AppWidgetManager.INVALID_APPWIDGET_ID) return;
        String a = i.getAction();
        if (ACTION_REVEAL.equals(a)) {
            boolean open = prefs(c).getBoolean("open_" + wid, false);
            prefs(c).edit().putBoolean("open_" + wid, !open).apply();
            render(c, AppWidgetManager.getInstance(c), wid);
        } else if (ACTION_GRADE.equals(a)) {
            String word = i.getStringExtra("id"), g = i.getStringExtra("grade");
            if (word != null) Notifications.applyGrade(c, word, g);
            MainActivity.log("оценка из виджета: " + word + " → " + g);
            prefs(c).edit().remove("word_" + wid).putBoolean("open_" + wid, false).apply();
            updateAll(c);
        }
    }

    /** Lowest level first, words you missed before ahead of the rest; a little randomness among the top few. */
    static JSONObject pick(JSONObject snap, String keepId) {
        long now = System.currentTimeMillis();
        List<JSONObject> due = new ArrayList<>();
        for (JSONObject w : Notifications.words(snap)) {
            if (!Notifications.isDue(w, now)) continue;
            if (keepId != null && keepId.equals(w.optString("id"))) return w;
            due.add(w);
        }
        if (due.isEmpty()) return null;
        due.sort((a, b) -> Integer.compare(score(a), score(b)));
        return due.get((int) (Math.random() * Math.min(4, due.size())));
    }

    static int score(JSONObject w) {
        boolean missed = w.optInt("bad", 0) > 0 && Notifications.lv(w) <= 1;
        return (missed ? 0 : 100) + Notifications.lv(w) * 10;
    }

    static PendingIntent self(Context c, String action, int wid, String word, String grade, int k) {
        Intent i = new Intent(c, Widget.class).setAction(action).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, wid);
        if (word != null) i.putExtra("id", word);
        if (grade != null) i.putExtra("grade", grade);
        return PendingIntent.getBroadcast(c, wid * 10 + k, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void render(Context c, AppWidgetManager m, int wid) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        JSONObject snap;
        synchronized (Store.LOCK) { snap = Store.readSnapshot(c); }
        long now = System.currentTimeMillis();

        // header: streak and due count; opens the review tab
        java.util.Set<String> days = Notifications.practiceDays(c, snap);
        int streak = Notifications.streak(days, now);
        boolean today = days.contains(Notifications.day(now));
        v.setTextViewText(R.id.w_streak, String.valueOf(streak));
        v.setInt(R.id.w_flame, "setColorFilter", c.getColor(today ? R.color.widget_accent : R.color.widget_muted));
        int dueN = 0;
        for (JSONObject w : Notifications.words(snap)) if (Notifications.isDue(w, now)) dueN++;
        v.setTextViewText(R.id.w_due, snap == null ? "Словник"
            : dueN == 0 ? "всё повторено" : Notifications.plural(dueN, "слово", "слова", "слов") + " к повтору");
        v.setOnClickPendingIntent(R.id.w_header, Notifications.openTab(c, "review", 500 + wid));

        SharedPreferences p = prefs(c);
        JSONObject w = snap == null ? null : pick(snap, p.getString("word_" + wid, null));
        boolean open = w != null && p.getBoolean("open_" + wid, false);
        if (w != null) p.edit().putString("word_" + wid, w.optString("id")).apply();

        // Revealed: the translation takes the English word's place (the grade buttons need the height).
        int pad = Math.round(14 * c.getResources().getDisplayMetrics().density);
        int padV = open ? Math.round(10 * c.getResources().getDisplayMetrics().density) : pad;
        v.setViewPadding(R.id.w_card, pad, padV, pad, padV);
        v.setTextViewTextSize(R.id.w_word, TypedValue.COMPLEX_UNIT_SP, open ? 19 : 22);
        v.setInt(R.id.w_word, "setMaxLines", 2);
        v.setViewVisibility(R.id.w_answer, View.GONE);
        v.setViewVisibility(R.id.w_buttons, open ? View.VISIBLE : View.GONE);
        v.setViewVisibility(R.id.w_hint, open ? View.GONE : View.VISIBLE);
        if (snap == null) {
            v.setTextViewText(R.id.w_word, "Открой «Словник»");
            v.setTextViewText(R.id.w_hint, "Один раз с интернетом — и виджет увидит твои слова");
            v.setOnClickPendingIntent(R.id.w_card, Notifications.openTab(c, "review", 600 + wid));
        } else if (w == null) {
            v.setTextViewText(R.id.w_word, "Всё повторено");
            v.setTextViewText(R.id.w_hint, "Новые карточки появятся, когда подойдёт их срок");
            v.setOnClickPendingIntent(R.id.w_card, Notifications.openTab(c, "words", 600 + wid));
        } else {
            String id = w.optString("id");
            v.setTextViewText(R.id.w_word, open ? w.optString("ru") : w.optString("en"));
            v.setTextViewText(R.id.w_hint, "Нажми, чтобы увидеть перевод");
            v.setOnClickPendingIntent(R.id.w_card, self(c, ACTION_REVEAL, wid, null, null, 1));
            v.setOnClickPendingIntent(R.id.w_bad, self(c, ACTION_GRADE, wid, id, "bad", 2));
            v.setOnClickPendingIntent(R.id.w_good, self(c, ACTION_GRADE, wid, id, "good", 3));
        }
        m.updateAppWidget(wid, v);
    }
}
