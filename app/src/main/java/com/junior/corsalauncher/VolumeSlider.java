package com.junior.corsalauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Barra de volumen vertical: se desliza con el dedo para subir o bajar. */
public class VolumeSlider extends View {

    public interface OnChange {
        void onChange(float fraction, boolean finished);
    }

    private float level = 0.5f;
    private boolean dragging = false;
    private OnChange listener;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path icon = new Path();
    private final float dp;

    public VolumeSlider(Context c) {
        this(c, null);
    }

    public VolumeSlider(Context c, AttributeSet a) {
        super(c, a);
        dp = c.getResources().getDisplayMetrics().density;
        setClickable(true);
    }

    public void setOnChange(OnChange l) {
        listener = l;
    }

    public boolean isDragging() {
        return dragging;
    }

    public void setLevel(float f) {
        level = Math.max(0f, Math.min(1f, f));
        invalidate();
    }

    private float trackTop() {
        return 46 * dp;
    }

    private float trackBottom() {
        return getHeight() - 14 * dp;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                // sigue al caso MOVE
            case MotionEvent.ACTION_MOVE: {
                float t = trackTop(), b = trackBottom();
                level = Math.max(0f, Math.min(1f, 1f - (e.getY() - t) / Math.max(1f, b - t)));
                invalidate();
                if (listener != null) listener.onChange(level, false);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (listener != null) listener.onChange(level, true);
                performClick();
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float cx = w / 2f;
        float tw = 26 * dp; // ancho de la barra
        float t = trackTop(), b = trackBottom();

        // pista
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFF23262D);
        r.set(cx - tw / 2, t, cx + tw / 2, b);
        c.drawRoundRect(r, tw / 2, tw / 2, p);

        // relleno
        float y = b - (b - t) * level;
        p.setColor(0xFF2F80FF);
        r.set(cx - tw / 2, Math.min(y, b - tw), cx + tw / 2, b);
        c.drawRoundRect(r, tw / 2, tw / 2, p);

        // perilla
        p.setColor(0xFFFFFFFF);
        float kr = 17 * dp;
        float ky = Math.max(t + kr * 0.2f, Math.min(b - kr * 0.2f, y));
        c.drawCircle(cx, ky, kr, p);
        p.setColor(0x33000000);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * dp);
        c.drawCircle(cx, ky, kr, p);

        // icono de altavoz arriba
        p.setStyle(Paint.Style.FILL);
        p.setColor(level <= 0.001f ? 0xFF8E929B : 0xFFFFFFFF);
        float s = dp, ix = cx - 11 * s, iy = 22 * s;
        icon.reset();
        icon.moveTo(ix, iy - 4 * s);
        icon.lineTo(ix + 5 * s, iy - 4 * s);
        icon.lineTo(ix + 11 * s, iy - 10 * s);
        icon.lineTo(ix + 11 * s, iy + 10 * s);
        icon.lineTo(ix + 5 * s, iy + 4 * s);
        icon.lineTo(ix, iy + 4 * s);
        icon.close();
        c.drawPath(icon, p);
        if (level > 0.001f) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f * s);
            p.setStrokeCap(Paint.Cap.ROUND);
            r.set(ix + 8 * s, iy - 6 * s, ix + 18 * s, iy + 6 * s);
            c.drawArc(r, -50, 100, false, p);
            if (level > 0.5f) {
                r.set(ix + 8 * s, iy - 11 * s, ix + 24 * s, iy + 11 * s);
                c.drawArc(r, -50, 100, false, p);
            }
        }
    }
}
