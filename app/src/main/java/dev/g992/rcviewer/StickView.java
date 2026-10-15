package dev.g992.rcviewer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * One stick as a square rounded card: faint centre cross and full-deflection frame, a
 * vector line from centre to the stick position, and a dot with a soft halo.
 * Position is x, y in -1..+1 (y up = +1).
 */
final class StickView extends View {
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vec = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final String title;
    private final float d;
    private float x, y;

    StickView(Context c, String title) {
        super(c);
        this.title = title;
        d = c.getResources().getDisplayMetrics().density;
        card.setColor(Palette.CARD);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(1.5f * d);
        frame.setColor(Palette.CARD_BORDER);
        grid.setStrokeWidth(1f * d);
        grid.setColor(Palette.TRACK_OFF);
        vec.setStrokeWidth(2f * d);
        vec.setStrokeCap(Paint.Cap.ROUND);
        vec.setColor(Palette.ACCENT);
        vec.setAlpha(110);
        halo.setColor(Palette.ACCENT);
        halo.setAlpha(60);
        dot.setColor(Palette.ACCENT);
        label.setColor(Palette.TEXT_DIM);
        label.setTextSize(14 * d);
        label.setLetterSpacing(0.08f);
    }

    void set(float nx, float ny) {
        float cx = Math.max(-1f, Math.min(1f, nx));
        float cy = Math.max(-1f, Math.min(1f, ny));
        if (cx == x && cy == y) return;
        x = cx;
        y = cy;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int size = Math.min(MeasureSpec.getSize(w), MeasureSpec.getSize(h));
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        float r = 18 * d;
        rect.set(0, 0, s, s);
        c.drawRoundRect(rect, r, r, card);

        float top = 40 * d, side = 22 * d;
        float area = Math.min(s - 2 * side, s - top - side);
        float l = (s - area) / 2f, t = top + (s - top - side - area) / 2f;
        float cx = l + area / 2f, cy = t + area / 2f, half = area / 2f;
        rect.set(l, t, l + area, t + area);
        c.drawRoundRect(rect, 10 * d, 10 * d, frame);
        c.drawLine(cx, t + 6 * d, cx, t + area - 6 * d, grid);
        c.drawLine(l + 6 * d, cy, l + area - 6 * d, cy, grid);
        c.drawText(title, side, 26 * d, label);

        float rad = Math.max(7 * d, half * 0.075f);
        float travel = half - rad - 4 * d;
        float px = cx + x * travel, py = cy - y * travel;
        c.drawLine(cx, cy, px, py, vec);
        c.drawCircle(px, py, rad * 2.1f, halo);
        c.drawCircle(px, py, rad, dot);
    }
}
