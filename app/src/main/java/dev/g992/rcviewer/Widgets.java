package dev.g992.rcviewer;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

/** Small programmatic widgets in the rm330_suite visual language (dark, rounded). */
final class Widgets {
    private Widgets() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable rounded(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    // ------------------------------------------------------------------ status icon

    /** Link state shown by {@link StatusIcon}. */
    enum Link { CONNECTING, LIVE, STALE, TAKEN_OVER }

    /**
     * Bare chain-link glyph (no background; the 44dp view is just the touch target);
     * colour encodes the link to dji_link's stream:
     * dim = connecting, accent = live, amber = connected but no stick packets,
     * red = another client (usually DJI Fly) took the stream.
     */
    static final class StatusIcon extends View {
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private Link link = Link.CONNECTING;

        StatusIcon(Context c) {
            super(c);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeWidth(dp(c, 2.4f));
            setClickable(true);
        }

        void setLink(Link l) {
            if (l == link) return;
            link = l;
            invalidate();
        }

        Link link() { return link; }

        static int color(Link l) {
            switch (l) {
                case LIVE: return Palette.ACCENT;
                case STALE: return Palette.WARN;
                case TAKEN_OVER: return Palette.DANGER;
                default: return Palette.TEXT_DIM;
            }
        }

        @Override
        protected void onMeasure(int w, int h) {
            int s = dp(getContext(), 44);
            setMeasuredDimension(s, s);
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = getWidth(), cx = s / 2f, cy = s / 2f;
            stroke.setColor(color(link));
            // Two interlocking rounded links, rotated 45 degrees.
            float u = s * 0.13f;
            c.save();
            c.rotate(-45, cx, cy);
            r.set(cx - 3.6f * u, cy - 1.3f * u, cx + 0.4f * u, cy + 1.3f * u);
            c.drawRoundRect(r, 1.3f * u, 1.3f * u, stroke);
            r.set(cx - 0.4f * u, cy - 1.3f * u, cx + 3.6f * u, cy + 1.3f * u);
            c.drawRoundRect(r, 1.3f * u, 1.3f * u, stroke);
            c.restore();
        }
    }

    // ------------------------------------------------------------------ pill switch

    /**
     * Mirror of the suite's Ui.Toggle: rounded pill track with a sliding round knob,
     * accent track when on, TRACK_OFF when off; knob always TEXT.
     */
    static final class Toggle extends View {
        private static final ArgbEvaluator ARGB = new ArgbEvaluator();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF track = new RectF();
        private boolean checked;
        private float pos;
        private ValueAnimator anim;

        Toggle(Context c) {
            super(c);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        boolean isChecked() { return checked; }

        void setChecked(boolean on, boolean animate) {
            if (anim != null) anim.cancel();
            checked = on;
            float target = on ? 1f : 0f;
            if (!animate || !isLaidOut()) { pos = target; invalidate(); return; }
            anim = ValueAnimator.ofFloat(pos, target);
            anim.setDuration(160);
            anim.addUpdateListener(a -> { pos = (float) a.getAnimatedValue(); invalidate(); });
            anim.start();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(getContext(), 52), dp(getContext(), 30));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), rr = h / 2f;
            track.set(0, 0, w, h);
            paint.setColor((Integer) ARGB.evaluate(pos, Palette.TRACK_OFF, Palette.ACCENT));
            c.drawRoundRect(track, rr, rr, paint);
            float kr = rr - dp(getContext(), 3);
            paint.setColor(Palette.TEXT);
            c.drawCircle(rr + (w - 2 * rr) * pos, rr, kr, paint);
        }
    }

    // ------------------------------------------------------------------ chip

    /** Pill label that lights up in its own colour when active, dim otherwise. */
    static final class Chip extends TextView {
        private final int onColor;
        private Boolean active;

        Chip(Context c, String text, int onColor) {
            super(c);
            this.onColor = onColor;
            setText(text);
            setGravity(Gravity.CENTER);
            setTextSize(15);
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            setSingleLine(true);
            int ph = dp(c, 14), pv = dp(c, 9);
            setPadding(ph, pv, ph, pv);
            setActive(false);
        }

        void setActive(boolean on) {
            if (active != null && active == on) return;
            active = on;
            setBackground(rounded(getContext(), on ? onColor : Palette.CARD,
                    on ? 0 : Palette.CARD_BORDER, 20));
            setTextColor(on ? Palette.TEXT : Palette.TEXT_DIM);
        }
    }
}
