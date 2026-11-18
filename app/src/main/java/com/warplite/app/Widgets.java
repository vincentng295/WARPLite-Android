package com.warplite.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Custom-drawn views (no image resources). */
final class Widgets {
    private Widgets() {
    }

    /** The big pill switch of the main screen: off = grey, connecting = thumb in the middle, on = orange. */
    static final class BigToggle extends View {
        static final int OFF = 0, BUSY = 1, ON = 2;
        private int state = OFF;
        private boolean dark;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        BigToggle(Context c) {
            super(c);
        }

        void set(int s, boolean dark) {
            state = s;
            this.dark = dark;
            invalidate();
        }

        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension((int) dp(129), (int) dp(65));
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), rad = h / 2f;
            r.set(0, 0, w, h);
            p.setStyle(Paint.Style.FILL);
            if (state == ON) {
                p.setShader(new LinearGradient(0, 0, w, 0, 0xFFFF9A3D, 0xFFEE4B36, Shader.TileMode.CLAMP));
            } else {
                p.setShader(null);
                p.setColor(dark ? 0xFF3A3A44 : 0xFFE3E3EA);
            }
            cv.drawRoundRect(r, rad, rad, p);
            p.setShader(null);
            float pad = dp(5), tr = rad - pad;
            float cx = state == OFF ? rad : state == ON ? w - rad : w / 2f;
            p.setColor(0xFFFFFFFF);
            p.setAlpha(state == BUSY ? 200 : 255);
            cv.drawCircle(cx, rad, tr, p);
            p.setAlpha(255);
        }
    }

    /** Concentric-ring WARP glyph of the Settings screen. */
    static final class WarpGlyph extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int color = 0xFF1B1B1B;

        WarpGlyph(Context c) {
            super(c);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        void color(int c) {
            color = c;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float s = Math.min(getWidth(), getHeight()), cx = getWidth() / 2f, cy = getHeight() / 2f;
            p.setColor(color);
            p.setStrokeWidth(s * 0.075f);
            float[] radii = {0.44f, 0.30f, 0.16f};
            for (float f : radii) {
                float rr = s * f;
                cv.drawArc(new RectF(cx - rr, cy - rr, cx + rr, cy + rr), 150, 240, false, p);
            }
            p.setStyle(Paint.Style.FILL);
            cv.drawCircle(cx, cy + s * 0.05f, s * 0.05f, p);
            p.setStyle(Paint.Style.STROKE);
        }
    }

    /** Orange "WARP" wordmark: heavy text filled with a gradient. */
    static void gradientText(final android.widget.TextView t) {
        t.post(new Runnable() {
            @Override
            public void run() {
                float w = Math.max(1f, t.getPaint().measureText(t.getText().toString()));
                t.getPaint().setShader(new LinearGradient(0, 0, w, t.getHeight(),
                        new int[]{0xFFFF5A36, 0xFFF6821F, 0xFFE0401F, 0xFFB5381A}, null, Shader.TileMode.CLAMP));
                t.invalidate();
            }
        });
    }
}
