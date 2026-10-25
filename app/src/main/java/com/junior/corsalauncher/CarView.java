package com.junior.corsalauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Calendar;

/**
 * Foto del Corsa con ruedas que giran según la velocidad, rebote leve,
 * franjas de carretera y luces. Tócalo para ver la animación de prueba.
 */
public class CarView extends View {

    // Ruedas en coordenadas de la imagen: cx, cy, radio horizontal, radio vertical
    private static final float[][] WHEELS = {
            {329f, 239f, 21f, 36f},
            {509f, 220f, 13.5f, 31f}
    };
    // Faros delanteros
    private static final float[][] LIGHTS = {{80f, 168f}, {247f, 172f}};

    private Bitmap car;
    private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path spoke = new Path();

    private float targetSpeed = 0f;   // km/h reales
    private float shownSpeed = 0f;    // suavizada
    private float wheelAngle = 0f;
    private float roadOffset = 0f;
    private long lastFrame = 0;
    private long demoUntil = 0;
    private boolean running = false;
    private boolean night = false;

    public CarView(Context c) {
        super(c);
        init();
    }

    public CarView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        car = BitmapFactory.decodeResource(getResources(), R.drawable.car_corsa, o);
        spoke.moveTo(14, 9);
        spoke.lineTo(84, 16);
        spoke.lineTo(84, -16);
        spoke.lineTo(14, -9);
        spoke.close();
        glow.setShader(new RadialGradient(0, 0, 46, 0x77FFF1C4, 0x00FFF1C4, Shader.TileMode.CLAMP));
        setClickable(true);
    }

    public void setSpeed(float kmh) {
        targetSpeed = Math.max(0f, kmh);
        startLoop();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP) {
            demoUntil = SystemClock.uptimeMillis() + 3500;
            startLoop();
            performClick();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startLoop();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    private void startLoop() {
        if (running || !isAttachedToWindow()) return;
        running = true;
        lastFrame = 0;
        postOnAnimation(frame);
    }

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;

            boolean demo = now < demoUntil;
            float goal = demo ? Math.max(targetSpeed, 70f) : targetSpeed;
            shownSpeed += (goal - shownSpeed) * Math.min(1f, dt * 3f);
            if (Math.abs(goal - shownSpeed) < 0.3f) shownSpeed = goal;

            float degPerSec = Math.min(shownSpeed * 14f, 900f);
            wheelAngle = (wheelAngle + degPerSec * dt) % 360f;
            roadOffset = (roadOffset + shownSpeed * 2.2f * dt) % 100f;

            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            night = h >= 18 || h < 6;

            invalidate();
            if (shownSpeed > 0.1f || goal > 0f || demo) {
                postOnAnimation(this);
            } else {
                running = false; // parado: no gasta batería
            }
        }
    };

    @Override
    protected void onDraw(Canvas canvas) {
        if (car == null) return;
        float bw = car.getWidth(), bh = car.getHeight();
        float s = Math.min(getWidth() / bw, getHeight() / bh);
        float dx = (getWidth() - bw * s) / 2f;
        float dy = (getHeight() - bh * s) / 2f;

        canvas.save();
        canvas.translate(dx, dy);
        canvas.scale(s, s);

        boolean moving = shownSpeed > 1f;

        // Franjas de carretera bajo el auto
        if (moving) {
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(0x33FFFFFF);
            for (int i = -1; i < 7; i++) {
                float x = i * 100f + roadOffset;
                canvas.drawRect(x, 296f, x + 46f, 299f, fill);
            }
        }

        // Rebote leve
        float bob = moving ? (float) Math.sin(SystemClock.uptimeMillis() / 55.0) * 1.1f * Math.min(shownSpeed / 30f, 1f) : 0f;
        canvas.translate(0, bob);

        canvas.drawBitmap(car, 0, 0, bmpPaint);

        // Luces
        if (night || SystemClock.uptimeMillis() < demoUntil) {
            for (float[] l : LIGHTS) {
                canvas.save();
                canvas.translate(l[0], l[1]);
                canvas.drawCircle(0, 0, 46, glow);
                canvas.restore();
            }
        }

        // Ruedas
        for (float[] w : WHEELS) drawWheel(canvas, w[0], w[1], w[2], w[3]);

        canvas.restore();
    }

    private void drawWheel(Canvas c, float cx, float cy, float rx, float ry) {
        c.save();
        c.translate(cx, cy);
        c.scale(rx / 100f, ry / 100f);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFF26282C);
        c.drawCircle(0, 0, 100, fill);
        fill.setColor(0xFFA5A8AF);
        c.drawCircle(0, 0, 97, fill);
        fill.setColor(0xFF34363C);
        c.drawCircle(0, 0, 86, fill);
        fill.setColor(0xFFB0B3BA);
        for (int k = 0; k < 6; k++) {
            c.save();
            c.rotate(wheelAngle + k * 60f);
            c.drawPath(spoke, fill);
            c.restore();
        }
        fill.setColor(0xFF82858C);
        c.drawCircle(0, 0, 16, fill);
        fill.setColor(0xFFBEC0C6);
        c.drawCircle(0, 0, 8, fill);
        c.restore();
    }
}
