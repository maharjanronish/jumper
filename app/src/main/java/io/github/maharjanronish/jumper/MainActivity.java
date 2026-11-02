package io.github.maharjanronish.jumper;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import io.github.maharjanronish.jumper.core.Game;

/**
 * Single-activity host: fullscreen GL game + light native overlays for the
 * game-over card and the offline leaderboard. ZERO XML UI: every view and
 * drawable is built in code (the original shipped one layout + five drawable
 * XMLs + strings/colors/styles), so res/ contains launcher icons only.
 */
public final class MainActivity extends Activity implements GameRenderer.Ui {

    // palette (same values as the original res/values)
    private static final int BG       = 0xFFFAF5EC;
    private static final int INK      = 0xFF4A4238;
    private static final int INK_SOFT = 0xFF9A8F80;
    private static final int CARD     = 0xFFFFFDF8;
    private static final int BADGE    = 0xFFE8912D;
    private static final int PRIMARY  = 0xFF3A342C;
    private static final int PRIMARY_D = 0xFF241F19;
    private static final int GHOST_P  = 0xFFF1EAE0;
    private static final int PILL     = 0xFFF5F0E6;

    private Game game;
    private GameRenderer renderer;
    private GameView gameView;
    private Sfx sfx;
    private Store store;

    private TextView hintView, finalScoreView, bestView, newBestView, soundButton;
    private LinearLayout gameOverPanel, recordsPanel, recordsRows;
    private final Random seedRandom = new Random();

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        store = new Store(this);
        sfx = new Sfx(this);
        sfx.setSoundOn(store.soundOn());

        game = new Game(seedRandom.nextLong());
        renderer = new GameRenderer(this, game, sfx, this);
        gameView = new GameView(this, renderer);

        setContentView(buildUi());

        if (!store.firstRunDone()) {
            hintView.setVisibility(View.VISIBLE);
        }
        updateSoundLabel();
        hideSystemBars();
        Window w = getWindow();
        // keep the warm bg behind system bars before they hide
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
    }

    // ---------------------------------------------------------------- programmatic UI

    private FrameLayout buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        // GL surface host
        FrameLayout glContainer = new FrameLayout(this);
        root.addView(glContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        glContainer.addView(gameView, 0);

        // hint pill
        hintView = pill("Hold to charge · Release to jump");
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hp.bottomMargin = dp(64);
        hintView.setLayoutParams(hp);
        hintView.setVisibility(View.GONE);
        root.addView(hintView);

        root.addView(buildGameOverPanel());
        root.addView(buildRecordsPanel());
        return root;
    }

    private LinearLayout buildGameOverPanel() {
        gameOverPanel = card(dp(32), dp(34), dp(32), dp(24));

        TextView caption = new TextView(this);
        caption.setText("SCORE");
        style(caption, INK_SOFT, 13, "sans-serif-medium");
        caption.setLetterSpacing(0.30f);
        caption.setAllCaps(true);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        gameOverPanel.addView(caption);

        finalScoreView = new TextView(this);
        finalScoreView.setText("0");
        style(finalScoreView, INK, 80, "sans-serif-light");
        finalScoreView.setLetterSpacing(0.02f);
        finalScoreView.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(2);
        finalScoreView.setLayoutParams(sp);
        gameOverPanel.addView(finalScoreView);

        bestView = new TextView(this);
        bestView.setText(bestLabel(0));
        style(bestView, INK_SOFT, 14, "sans-serif-medium");
        bestView.setLetterSpacing(0.12f);
        bestView.setAllCaps(true);
        bestView.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(6);
        bestView.setLayoutParams(bp);
        gameOverPanel.addView(bestView);

        newBestView = new TextView(this);
        newBestView.setText("New best!");
        style(newBestView, 0xFFFFFFFF, 12, null);
        newBestView.setTypeface(newBestView.getTypeface(), Typeface.BOLD);
        newBestView.setLetterSpacing(0.10f);
        newBestView.setAllCaps(true);
        newBestView.setGravity(Gravity.CENTER_HORIZONTAL);
        newBestView.setPadding(dp(14), dp(4), dp(14), dp(4));
        newBestView.setBackground(roundRect(BADGE, 13));
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        np.topMargin = dp(12);
        newBestView.setLayoutParams(np);
        newBestView.setVisibility(View.GONE);
        gameOverPanel.addView(newBestView);

        Button playAgain = new Button(this);
        playAgain.setText("Play Again");
        style(playAgain, 0xFFFFFFFF, 16, null);
        playAgain.setTypeface(playAgain.getTypeface(), Typeface.BOLD);
        playAgain.setBackground(button(PRIMARY, PRIMARY_D, 27));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        pp.topMargin = dp(26);
        playAgain.setLayoutParams(pp);
        playAgain.setOnClickListener(v -> restart());
        gameOverPanel.addView(playAgain);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(8);
        row.setLayoutParams(rp);

        Button records = ghostButton("Records");
        records.setOnClickListener(v -> showRecords());
        row.addView(records, new LinearLayout.LayoutParams(0,
                dp(48), 1f));

        soundButton = ghostButton("Sound: On");
        soundButton.setOnClickListener(v -> toggleSound());
        row.addView(soundButton, new LinearLayout.LayoutParams(0,
                dp(48), 1f));

        gameOverPanel.addView(row);
        gameOverPanel.setVisibility(View.GONE);
        return gameOverPanel;
    }

    private LinearLayout buildRecordsPanel() {
        recordsPanel = card(dp(28), dp(26), dp(28), dp(20));

        TextView title = new TextView(this);
        title.setText("Local Records");
        style(title, INK, 20, "sans-serif-light");
        title.setLetterSpacing(0.08f);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        recordsPanel.addView(title);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams scp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(320));
        scp.topMargin = dp(10);
        scroll.setLayoutParams(scp);

        recordsRows = new LinearLayout(this);
        recordsRows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(recordsRows, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        recordsPanel.addView(scroll);

        Button back = ghostButton("Back");
        back.setOnClickListener(v -> showGameOverPanel());
        LinearLayout.LayoutParams bk = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        bk.topMargin = dp(12);
        back.setLayoutParams(bk);
        recordsPanel.addView(back);

        recordsPanel.setVisibility(View.GONE);
        return recordsPanel;
    }

    private TextView pill(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        style(v, INK, 14, null);
        v.setPadding(dp(20), dp(10), dp(20), dp(10));
        v.setBackground(roundRect(PILL, 18));
        v.setAlpha(0.92f);
        return v;
    }

    private LinearLayout card(int l, int t, int r, int b) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER_HORIZONTAL);
        v.setPadding(l, t, r, b);
        v.setMinimumWidth(dp(300));
        v.setBackground(roundRect(CARD, 26));
        v.setElevation(dp(10));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        v.setLayoutParams(lp);
        return v;
    }

    private Button ghostButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        style(btn, INK_SOFT, 14, null);
        btn.setBackground(button(0x00000000, GHOST_P, 23));
        return btn;
    }

    private static void style(TextView v, int color, float sizeSp, String family) {
        v.setTextColor(color);
        v.setTextSize(sizeSp);
        if (family != null) v.setTypeface(Typeface.create(family, Typeface.NORMAL));
    }

    private GradientDrawable roundRect(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp * density());
        return d;
    }

    /** Normal/pressed rounded backgrounds (replaces btn_primary / btn_ghost XML). */
    private StateListDrawable button(int normal, int pressed, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, roundRect(pressed, radiusDp));
        s.addState(new int[0], roundRect(normal, radiusDp));
        return s;
    }

    // ---------------------------------------------------------------- fullscreen

    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController c = decor.getWindowInsetsController();
            if (c != null) {
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.systemBars());
            }
        } else {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    // ---------------------------------------------------------------- game flow

    /** From the GL thread. */
    @Override public void onGameOver(final int score) {
        runOnUiThread(() -> showGameOver(score));
    }

    /** From the GL thread. */
    @Override public void onFirstJump() {
        runOnUiThread(() -> {
            hintView.setVisibility(View.GONE);
            store.markFirstRunDone();
        });
    }

    private void showGameOver(int score) {
        int prevBest = store.best();
        store.addScore(score);
        int best = store.best();

        finalScoreView.setText(String.valueOf(score));
        bestView.setText(bestLabel(best));
        boolean newBest = score > prevBest && score > 0;
        newBestView.setVisibility(newBest ? View.VISIBLE : View.GONE);

        gameOverPanel.setVisibility(View.VISIBLE);
        recordsPanel.setVisibility(View.GONE);
        gameView.setPausedRendering(true);
    }

    private void restart() {
        gameOverPanel.setVisibility(View.GONE);
        recordsPanel.setVisibility(View.GONE);
        gameView.setPausedRendering(false);
        final long seed = seedRandom.nextLong();
        gameView.queueEvent(() -> renderer.resetGame(seed));
    }

    private void showRecords() {
        gameOverPanel.setVisibility(View.GONE);
        recordsPanel.setVisibility(View.VISIBLE);
        recordsRows.removeAllViews();

        List<Store.Record> list = store.records();
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No games yet — start jumping!");
            style(empty, 0xFF8A8595, 15, null);
            empty.setPadding(0, dp(16), 0, dp(16));
            recordsRows.addView(empty);
            return;
        }
        SimpleDateFormat fmt = new SimpleDateFormat("MMM d, yyyy", Locale.getDefault());
        int rank = 1;
        for (Store.Record r : list) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(4), dp(10), dp(4), dp(10));

            TextView rankV = new TextView(this);
            rankV.setText(String.format(Locale.getDefault(), "%2d.", rank++));
            style(rankV, 0xFF56504A, 16, null);
            rankV.setTypeface(rankV.getTypeface(), Typeface.BOLD);
            row.addView(rankV, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 0.14f));

            TextView scoreV = new TextView(this);
            scoreV.setText(String.valueOf(r.score));
            style(scoreV, 0xFF323136, 18, null);
            scoreV.setTypeface(scoreV.getTypeface(), Typeface.BOLD);
            row.addView(scoreV, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 0.34f));

            TextView dateV = new TextView(this);
            dateV.setText(fmt.format(new Date(r.timestamp)));
            style(dateV, 0xFF8A8595, 13, null);
            dateV.setGravity(Gravity.END);
            row.addView(dateV, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 0.52f));

            recordsRows.addView(row,
                    new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private void showGameOverPanel() {
        recordsPanel.setVisibility(View.GONE);
        gameOverPanel.setVisibility(View.VISIBLE);
    }

    private void toggleSound() {
        boolean on = !store.soundOn();
        store.setSoundOn(on);
        sfx.setSoundOn(on);
        updateSoundLabel();
    }

    private void updateSoundLabel() {
        soundButton.setText("Sound: " + (store.soundOn() ? "On" : "Off"));
    }

    private String bestLabel(int best) {
        return "Best " + best;
    }

    // ---------------------------------------------------------------- helpers

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    private int dp(int v) {
        return Math.round(v * density());
    }

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onResume() {
        super.onResume();
        hideSystemBars();
        gameView.onResume();
    }

    @Override protected void onPause() {
        gameView.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        sfx.release();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (recordsPanel.getVisibility() == View.VISIBLE) {
            showGameOverPanel();
            return;
        }
        if (gameOverPanel.getVisibility() == View.VISIBLE) {
            restart();   // back on the game-over card = quick retry
            return;
        }
        super.onBackPressed();
    }
}
