package dev.g992.rcviewer;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import dev.g992.rcviewer.Widgets.Chip;
import dev.g992.rcviewer.Widgets.Link;
import dev.g992.rcviewer.Widgets.StatusIcon;
import dev.g992.rcviewer.Widgets.Toggle;

/** Live view of the RC sticks/buttons/mode switch, with the BLE gamepad switch. */
public class MainActivity extends Activity implements RcReader.Listener {
    /** Documented full deflection is roughly +-660 around 0 (wrap byte centre = 4). */
    static final float FULL_SCALE = 660f;
    private static final long STALE_MS = 700;

    private StickView leftStick, rightStick;
    private StatusIcon statusIcon;
    private TextView statusHint, bleState;
    private Toggle bleToggle;
    private Chip[] buttonChips;
    private Chip[] modeChips; // C, N, S
    private RcReader reader;
    private BleGamepad ble;
    private boolean bleWanted;

    private volatile Duml.RcState pending;
    private volatile long lastStateAt;
    private volatile boolean connected, takenOver;
    private long lastUi;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable render = this::render;
    private final Runnable linkCheck = new Runnable() {
        @Override public void run() {
            updateLink();
            ui.postDelayed(this, 250);
        }
    };

    static float norm(int raw) {
        return Math.max(-1f, Math.min(1f, raw / FULL_SCALE));
    }

    private int dp(float v) { return Widgets.dp(this, v); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        ble = new BleGamepad(this, (msg, active) -> runOnUiThread(() -> {
            bleWanted = active;
            bleToggle.setChecked(active, true);
            bleState.setText(msg.startsWith("BLE: ") ? msg.substring(5) : msg);
            bleState.setTextColor(active ? Palette.ACCENT : Palette.TEXT_DIM);
        }));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Palette.BG);
        root.setPadding(dp(20), dp(14), dp(20), dp(20));

        root.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, -2));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        bodyLp.topMargin = dp(12);
        root.addView(body, bodyLp);

        leftStick = new StickView(this, "ЛЕВЫЙ СТИК");
        rightStick = new StickView(this, "ПРАВЫЙ СТИК");
        body.addView(centered(leftStick), new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(dp(250), -1);
        midLp.leftMargin = dp(16);
        midLp.rightMargin = dp(16);
        body.addView(buildCenterColumn(), midLp);
        body.addView(centered(rightStick), new LinearLayout.LayoutParams(0, -1, 1f));

        setContentView(root);
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(getString(R.string.app_name));
        title.setTextColor(Palette.TEXT);
        title.setTextSize(20);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        statusHint = new TextView(this);
        statusHint.setTextSize(13);
        statusHint.setTextColor(Palette.DANGER);
        statusHint.setVisibility(View.GONE);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(-2, -2);
        hLp.rightMargin = dp(10);
        bar.addView(statusHint, hLp);

        statusIcon = new StatusIcon(this);
        statusIcon.setOnClickListener(v -> onStatusTap());
        statusHint.setOnClickListener(v -> onStatusTap());
        bar.addView(statusIcon);
        return bar;
    }

    private View buildCenterColumn() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);

        // BLE card: title + state line, pill switch; the whole card is the touch target.
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(16), dp(16), dp(16));
        card.setBackground(Widgets.rounded(this, Palette.CARD, Palette.CARD_BORDER, 16));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText("BLE-джойстик");
        t.setTextColor(Palette.TEXT);
        t.setTextSize(17);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        bleState = new TextView(this);
        bleState.setText("выключен");
        bleState.setTextColor(Palette.TEXT_DIM);
        bleState.setTextSize(13);
        bleState.setMaxLines(2);
        bleState.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(t);
        texts.addView(bleState);
        card.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        bleToggle = new Toggle(this);
        LinearLayout.LayoutParams tgLp = new LinearLayout.LayoutParams(-2, -2);
        tgLp.leftMargin = dp(12);
        card.addView(bleToggle, tgLp);
        card.setClickable(true);
        card.setOnClickListener(v -> onBleTap());
        col.addView(card, new LinearLayout.LayoutParams(-1, -2));

        col.addView(sectionLabel("КНОПКИ"), labelLp());
        String[] names = {"RTH", "Фокус", "Фото", "Запись"}; // order = Duml.ALL_BUTTONS
        buttonChips = new Chip[names.length];
        LinearLayout rowA = chipRow(), rowB = chipRow();
        for (int i = 0; i < names.length; i++) {
            buttonChips[i] = new Chip(this, names[i], Palette.ACCENT);
            (i < 2 ? rowA : rowB).addView(buttonChips[i], chipLp(i % 2 == 1));
        }
        col.addView(rowA, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams rowBLp = new LinearLayout.LayoutParams(-1, -2);
        rowBLp.topMargin = dp(8);
        col.addView(rowB, rowBLp);

        col.addView(sectionLabel("РЕЖИМ"), labelLp());
        LinearLayout modes = chipRow();
        modeChips = new Chip[]{
                new Chip(this, "C", Palette.GOOD),
                new Chip(this, "N", Palette.ACCENT),
                new Chip(this, "S", Palette.WARN)};
        for (int i = 0; i < 3; i++) modes.addView(modeChips[i], chipLp(i > 0));
        col.addView(modes, new LinearLayout.LayoutParams(-1, -2));
        return col;
    }

    private FrameLayout centered(View v) {
        FrameLayout f = new FrameLayout(this);
        f.addView(v, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        return f;
    }

    private TextView sectionLabel(String s) {
        TextView l = new TextView(this);
        l.setText(s);
        l.setTextColor(Palette.TEXT_DIM);
        l.setTextSize(12);
        l.setLetterSpacing(0.1f);
        return l;
    }

    private LinearLayout.LayoutParams labelLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(22);
        lp.bottomMargin = dp(8);
        lp.leftMargin = dp(4);
        return lp;
    }

    private LinearLayout chipRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private LinearLayout.LayoutParams chipLp(boolean gapBefore) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        if (gapBefore) lp.leftMargin = dp(8);
        return lp;
    }

    // ------------------------------------------------------------ actions

    private void onBleTap() {
        bleWanted = !bleWanted;
        bleToggle.setChecked(bleWanted, true);
        bleState.setText(bleWanted ? "включение..." : "выключение...");
        if (bleWanted) ble.start(); else ble.stop();
    }

    private void onStatusTap() {
        Link l = statusIcon.link();
        if (l == Link.TAKEN_OVER || l == Link.CONNECTING) {
            Toast.makeText(this, "Переподключение к пульту (другой клиент, например DJI Fly, будет отключён)",
                    Toast.LENGTH_SHORT).show();
            startReader();
        } else if (l == Link.STALE) {
            Toast.makeText(this, "Связь есть, но пакеты стиков не приходят", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Связь с пультом активна", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        startReader();
        ui.post(linkCheck);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(linkCheck);
        if (reader != null) reader.shutdown();
        reader = null;
    }

    @Override
    protected void onStop() {
        super.onStop();
        // BLE is stopped here, not in onPause: Android's pairing confirmation is a dialog
        // activity that only PAUSES us; stopping the GATT server then would kill the
        // pairing being confirmed. Real backgrounding still reaches onStop.
        if (bleWanted) { bleWanted = false; ble.stop(); }
    }

    private void startReader() {
        if (reader != null) reader.shutdown();
        takenOver = false;
        connected = false;
        lastStateAt = 0;
        reader = new RcReader(this);
        reader.start();
        updateLink();
    }

    private void updateLink() {
        boolean fresh = lastStateAt != 0 && SystemClock.uptimeMillis() - lastStateAt <= STALE_MS;
        Link l = takenOver ? Link.TAKEN_OVER
                : fresh ? Link.LIVE
                : connected ? Link.STALE : Link.CONNECTING;
        statusIcon.setLink(l);
        statusHint.setVisibility(l == Link.TAKEN_OVER ? View.VISIBLE : View.GONE);
        float a = l == Link.LIVE ? 1f : 0.35f;
        leftStick.setAlpha(a);
        rightStick.setAlpha(a);
    }

    // ------------------------------------------------------------ RcReader.Listener (bg thread)

    @Override
    public void onStatus(String s) {
        if (s.startsWith("connected")) connected = true;
        else if (s.startsWith("connect")) connected = false; // "connecting..." / "connect ... failed"
    }

    @Override
    public void onPacket(Duml.Packet p) {
        // Only decoded 0x06/0xAE state is used.
    }

    @Override
    public void onTakenOver(String host, String reason) {
        takenOver = true;
        connected = false;
        runOnUiThread(() -> {
            statusHint.setText("поток занят — нажмите");
            updateLink();
            Toast.makeText(this, "Поток пульта забрал другой клиент (обычно DJI Fly). "
                    + "Нажмите значок связи, чтобы переподключиться.", Toast.LENGTH_LONG).show();
        });
    }

    @Override
    public void onState(Duml.RcState st, long count, double rate) {
        pending = st;
        lastStateAt = SystemClock.uptimeMillis();
        ble.update(st);
        long now = SystemClock.uptimeMillis();
        if (now - lastUi >= 33) { // ~30 fps
            lastUi = now;
            runOnUiThread(render);
        }
    }

    // ------------------------------------------------------------ render

    private void render() {
        Duml.RcState st = pending;
        if (st == null) return;
        int[] a = st.axes;
        leftStick.set(norm(a[Duml.AX_LH]), norm(a[Duml.AX_LV]));
        rightStick.set(norm(a[Duml.AX_RH]), norm(a[Duml.AX_RV]));
        for (int i = 0; i < buttonChips.length; i++) buttonChips[i].setActive(st.buttons[i]);
        int mode = st.ch1 & 0x03; // 0 Sport, 1 Normal, 2 Cinema
        modeChips[0].setActive(mode == 2);
        modeChips[1].setActive(mode == 1);
        modeChips[2].setActive(mode == 0);
    }
}
