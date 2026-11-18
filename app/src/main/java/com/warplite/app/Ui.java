package com.warplite.app;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The look of the screens: colours for the light / dark theme and small view builders (no XML layouts). */
final class Ui {

    final Context ctx;
    final boolean dark;

    final int bg, surface, text, sub, divider, track;
    static final int ORANGE = 0xFFF6821F;
    static final int RED = 0xFFE8452C;
    static final int BLUE = 0xFF0A5BDC;
    static final int AMBER = 0xFFF9A23A;

    Ui(Context c, boolean dark) {
        this.ctx = c;
        this.dark = dark;
        bg = dark ? 0xFF121212 : 0xFFF7F7F7;
        surface = dark ? 0xFF1E1E1E : 0xFFFFFFFF;
        text = dark ? 0xFFEEEEEE : 0xFF222222;
        sub = dark ? 0xFFA3A3A3 : 0xFF777777;
        divider = dark ? 0xFF2D2D2D : 0xFFE7E7E7;
        track = dark ? 0xFF2A2A2E : 0xFFE9E9ED;
    }

    int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }

    TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    // ------------------------------------------------------------------ app bar and rows

    /** Top bar of an inner screen: back arrow + title, white surface with a soft shadow. */
    View appBar(String title, Runnable back) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(surface);
        bar.setElevation(dp(3));
        TextView arrow = tv("←", 24, text, false);
        arrow.setGravity(Gravity.CENTER);
        arrow.setOnClickListener(v -> back.run());
        bar.addView(arrow, new LinearLayout.LayoutParams(dp(56), dp(56)));
        bar.addView(tv(title, 20, text, false));
        bar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        return bar;
    }

    /** A list row: title (+ optional small subtitle), optional value on the right, divider underneath. */
    View row(String title, String subtitle, String value, Runnable click) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackgroundColor(surface);
        r.setPadding(dp(16), dp(10), dp(16), dp(10));
        r.setMinimumHeight(dp(54));
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(tv(title, 16, text, false));
        if (subtitle != null) {
            TextView s = tv(subtitle, 12, sub, false);
            s.setPadding(0, dp(2), 0, 0);
            col.addView(s);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (value != null) {
            TextView v = tv(value, 14, sub, false);
            v.setPadding(dp(12), 0, 0, 0);
            v.setSingleLine(true);
            v.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            v.setMaxWidth(dp(190));
            r.addView(v);
        }
        if (click != null) r.setOnClickListener(v -> click.run());
        return withDivider(r);
    }

    /** A row with an on / off switch on the right. */
    View switchRow(String title, boolean on, java.util.function.Consumer<Boolean> change) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackgroundColor(surface);
        r.setPadding(dp(16), dp(8), dp(16), dp(8));
        r.setMinimumHeight(dp(54));
        r.addView(tv(title, 16, text, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Toggle t = new Toggle(ctx, this, 0.55f);
        t.setChecked(on);
        t.setOnClickListener(v -> {
            t.setChecked(!t.isChecked());
            change.accept(t.isChecked());
        });
        r.addView(t, new LinearLayout.LayoutParams(t.w(), t.h()));
        r.setOnClickListener(v -> t.performClick());
        return withDivider(r);
    }

    /** A selectable row: title + description, red check mark on the chosen one (the settings screen of the original). */
    View choiceRow(String title, String description, boolean selected, Runnable click) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackgroundColor(surface);
        r.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(tv(title, 16, text, false));
        if (description != null) {
            TextView d = tv(description, 12, sub, false);
            d.setPadding(0, dp(3), 0, 0);
            col.addView(d);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (selected) {
            TextView ck = tv("✓", 22, RED, true);
            ck.setPadding(dp(12), 0, 0, 0);
            r.addView(ck);
        }
        r.setOnClickListener(v -> click.run());
        return withDivider(r);
    }

    View gap(int heightDp) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
        return v;
    }

    private View withDivider(View row) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View d = new View(ctx);
        d.setBackgroundColor(divider);
        box.addView(d, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return box;
    }

    GradientDrawable rounded(int color, float topDp, float bottomDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        float t = dp(topDp), b = dp(bottomDp);
        g.setCornerRadii(new float[]{t, t, t, t, b, b, b, b});
        return g;
    }

    // ------------------------------------------------------------------ switch

    /** The pill switch of the original: grey with a white knob when off, orange when on. */
    static final class Toggle extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Ui ui;
        private final float scale;
        private boolean checked;
        private boolean busy;

        Toggle(Context c, Ui ui, float scale) {
            super(c);
            this.ui = ui;
            this.scale = scale;
            setLayerType(LAYER_TYPE_SOFTWARE, null);   // for the knob's shadow
        }

        int w() {
            return ui.dp(140 * scale);
        }

        int h() {
            return ui.dp(72 * scale);
        }

        boolean isChecked() {
            return checked;
        }

        void setChecked(boolean v) {
            checked = v;
            invalidate();
        }

        /** Half-way look while connecting. */
        void setBusy(boolean v) {
            busy = v;
            invalidate();
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(w(), h());
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            boolean on = checked || busy;
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? (busy && !checked ? 0xFFF9C08A : ORANGE) : (ui.dark ? 0xFF3A3A40 : 0xFFE3E3E8));
            c.drawRoundRect(new RectF(0, 0, w, h), h / 2, h / 2, p);
            float r = h / 2 - h * 0.07f;
            float cx = on && !(busy && !checked) ? w - h / 2 : h / 2;
            if (busy && !checked) cx = w / 2;
            p.setColor(Color.WHITE);
            p.setShadowLayer(h * 0.05f, 0, h * 0.02f, 0x44000000);
            c.drawCircle(cx, h / 2, r, p);
            p.clearShadowLayer();
        }
    }
    // ------------------------------------------------------------------ power button (home)

    /**
     * The big button of the home screen. Off: a white disc with a grey power symbol. Connecting: an orange arc turns
     * around the disc. On: an orange gradient disc with a soft glow and a white symbol.
     */
    static final class PowerButton extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final Ui ui;
        private final int size;
        private boolean on, busy;
        private float spin;
        private ValueAnimator anim;

        PowerButton(Context c, Ui ui, int sizeDp) {
            super(c);
            this.ui = ui;
            this.size = ui.dp(sizeDp);
            setLayerType(LAYER_TYPE_SOFTWARE, null);   // the disc's shadow
        }

        int side() {
            return size;
        }

        void setState(boolean on, boolean busy) {
            this.on = on;
            this.busy = busy && !on;
            if (this.busy) {
                if (anim == null) {
                    anim = ValueAnimator.ofFloat(0f, 360f);
                    anim.setDuration(1100);
                    anim.setInterpolator(new LinearInterpolator());
                    anim.setRepeatCount(ValueAnimator.INFINITE);
                    anim.addUpdateListener(a -> {
                        spin = (Float) a.getAnimatedValue();
                        invalidate();
                    });
                    anim.start();
                }
            } else if (anim != null) {
                anim.cancel();
                anim = null;
            }
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (anim != null) {
                anim.cancel();
                anim = null;
            }
            super.onDetachedFromWindow();
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(size, size);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float cx = w / 2f, cy = w / 2f;
            float r = w * 0.34f;                                   // the disc; the rest is room for glow / ring

            p.setShader(null);
            p.clearShadowLayer();
            p.setStyle(Paint.Style.FILL);
            if (on) {                                              // glow
                p.setShader(new RadialGradient(cx, cy, w / 2f, new int[]{0x66F6821F, 0x00F6821F}, new float[]{0.55f, 1f}, Shader.TileMode.CLAMP));
                c.drawCircle(cx, cy, w / 2f, p);
                p.setShader(null);
                p.setShader(new LinearGradient(cx - r, cy - r, cx + r, cy + r, AMBER, RED, Shader.TileMode.CLAMP));
            } else {
                p.setColor(ui.surface);
                p.setShadowLayer(w * 0.035f, 0, w * 0.015f, ui.dark ? 0x99000000 : 0x33000000);
            }
            c.drawCircle(cx, cy, r, p);
            p.setShader(null);
            p.clearShadowLayer();

            // ring
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(w * 0.03f);
            p.setStrokeCap(Paint.Cap.ROUND);
            float rr = r + w * 0.075f;
            box.set(cx - rr, cy - rr, cx + rr, cy + rr);
            if (busy) {
                p.setColor(ui.track);
                c.drawCircle(cx, cy, rr, p);
                p.setColor(ORANGE);
                c.drawArc(box, spin, 110, false, p);
            } else if (!on) {
                p.setColor(ui.track);
                c.drawCircle(cx, cy, rr, p);
            }

            // power symbol: an arc open at the top and a short bar
            float ir = r * 0.42f;
            p.setStrokeWidth(r * 0.15f);
            p.setColor(on ? Color.WHITE : busy ? ORANGE : ui.sub);
            box.set(cx - ir, cy - ir, cx + ir, cy + ir);
            c.drawArc(box, -50, 280, false, p);
            c.drawLine(cx, cy - ir * 1.22f, cx, cy - ir * 0.12f, p);
        }
    }
}
