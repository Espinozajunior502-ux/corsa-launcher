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
    private float blur = 0f;
    private float roadOffset = 0f;
    private long lastFrame = 0;
    private long demoUntil = 0;
    private boolean running = false;
    private boolean night = false;

    // ---- modo 3D (se activa al arrastrar con el dedo) ----
    private final Car3D car3d = new Car3D();
    private boolean mode3d = false;
    private float blend = 0f;          // 0 = foto, 1 = modelo 3D
    private float yaw = 210f, pitch = 14f, yawVel = 0f;
    private boolean touching = false, dragging = false;
    private float downX, downY, lastX, lastY;
    private long lastTouchTime = 0, lastMoveTime = 0;
    private final int slop;
    /** Modo 3D desactivado hasta tener el modelo OBJ exacto del Corsa. */
    private static final boolean ENABLE_3D = false;

    public CarView(Context c) {
        super(c);
        slop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
        init();
    }

    public CarView(Context c, AttributeSet a) {
        super(c, a);
        slop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
        init();
    }

    private void init() {

        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        car = BitmapFactory.decodeResource(getResources(), R.drawable.car_corsa, o);
        pickBodyColor();
        spoke.moveTo(14, 9);
        spoke.lineTo(84, 16);
        spoke.lineTo(84, -16);
        spoke.lineTo(14, -9);
        spoke.close();
        glow.setShader(new RadialGradient(0, 0, 46, 0x77FFF1C4, 0x00FFF1C4, Shader.TileMode.CLAMP));
        setClickable(true);
    }

    /** Toma el color de la carrocería de la foto para teñir el modelo 3D. */
    private void pickBodyColor() {
        // Plateado por defecto; si la foto tiene un color vivo claro, usa ese.
        int silver = 0xC4C8D0;
        try {
            long r = 0, g = 0, b = 0, n = 0;
            long r2 = 0, g2 = 0, b2 = 0, n2 = 0;
            for (int y = 100; y < 240; y += 4) {
                for (int x = 80; x < 540; x += 4) {
                    int p = car.getPixel(x, y);
                    if ((p >>> 24) < 230) continue;
                    int pr = (p >> 16) & 255, pg = (p >> 8) & 255, pb = p & 255;
                    int mx = Math.max(pr, Math.max(pg, pb)), mn = Math.min(pr, Math.min(pg, pb));
                    if (mx > 150 && mn > 90) { // zonas claras (carrocería metálica)
                        r2 += pr; g2 += pg; b2 += pb; n2++;
                    }
                    if (mx - mn > 90 && mx > 110) { // color muy vivo
                        r += pr; g += pg; b += pb; n++;
                    }
                }
            }
            if (n > 400) {
                car3d.setBodyColor(((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n));
            } else if (n2 > 40) {
                int ar = (int) (r2 / n2), ag = (int) (g2 / n2), ab = (int) (b2 / n2);
                car3d.setBodyColor((Math.min(255, ar + 12) << 16) | (Math.min(255, ag + 12) << 8) | Math.min(255, ab + 12));
            } else {
                car3d.setBodyColor(silver);
            }
        } catch (Exception e) {
            car3d.setBodyColor(silver);
        }
    }

    public void setSpeed(float kmh) {
        targetSpeed = Math.max(0f, kmh);
        startLoop();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        long now = SystemClock.uptimeMillis();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touching = true;
                dragging = false;
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                lastTouchTime = lastMoveTime = now;
                yawVel = 0f;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - lastX, dy = e.getY() - lastY;
                if (!dragging && (Math.abs(e.getX() - downX) > slop || Math.abs(e.getY() - downY) > slop)) {
                    dragging = true;
                    if (ENABLE_3D && !mode3d) {
                        mode3d = true;
                        yaw = 210f;
                        pitch = 14f;
                    }
                }
                if (dragging && ENABLE_3D) {
                    float dt = Math.max(0.008f, (now - lastMoveTime) / 1000f);
                    yaw += dx * 0.5f;
                    pitch = Math.max(2f, Math.min(42f, pitch - dy * 0.2f));
                    float v = dx * 0.5f / dt;
                    yawVel = yawVel * 0.6f + v * 0.4f;
                    lastTouchTime = now;
                    startLoop();
                    invalidate();
                }
                lastX = e.getX();
                lastY = e.getY();
                lastMoveTime = now;
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                lastTouchTime = now;
                if (now - lastMoveTime > 120) yawVel = 0f; // soltó sin lanzar
                if (yawVel > 900f) yawVel = 900f;
                if (yawVel < -900f) yawVel = -900f;
                if (e.getActionMasked() == MotionEvent.ACTION_UP && (!dragging || !ENABLE_3D)) {
                    demoUntil = now + 3500;
                    performClick();
                }
                startLoop();
                return true;
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

            // Giro progresivo: más velocidad = más rápido. Se limita el avance por cuadro para
            // que no parezca girar hacia atrás, y a alta velocidad se añade desenfoque.
            float omega = shownSpeed * 12f;                // grados por segundo
            wheelAngle = (wheelAngle + Math.min(omega * dt, 22f)) % 360f;
            blur = Math.max(0f, Math.min(0.85f, (omega - 420f) / 480f));
            roadOffset = (roadOffset + shownSpeed * 2.2f * dt) % 100f;

            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            night = h >= 18 || h < 6;

            // modo 3D: inercia, giro lento al soltar y regreso a la foto
            if (mode3d) {
                blend = Math.min(1f, blend + dt * 4f);
                if (!touching) {
                    yaw += yawVel * dt;
                    yawVel *= (float) Math.pow(0.05, dt);
                    if (Math.abs(yawVel) < 10f && now - lastTouchTime > 1500) yaw += 10f * dt;
                    if (now - lastTouchTime > 10000) mode3d = false;
                }
                yaw %= 360f;
            } else if (blend > 0f) {
                blend = Math.max(0f, blend - dt * 3f);
            }

            invalidate();
            if (mode3d || blend > 0f || shownSpeed > 0.1f || goal > 0f || demo) {
                postOnAnimation(this);
            } else {
                running = false; // parado: no gasta batería
            }
        }
    };

    @Override
    protected void onDraw(Canvas canvas) {
        if (car == null) return;
        if (blend >= 0.999f) {
            car3d.draw(canvas, getWidth(), getHeight(), yaw, pitch, wheelAngle,
                    night || SystemClock.uptimeMillis() < demoUntil);
            return;
        }
        int layer = -1;
        if (blend > 0f) {
            layer = canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), (int) (255 * (1f - blend)));
        }
        drawPhoto(canvas);
        if (layer != -1) {
            canvas.restoreToCount(layer);
            int l2 = canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), (int) (255 * blend));
            car3d.draw(canvas, getWidth(), getHeight(), yaw, pitch, wheelAngle,
                    night || SystemClock.uptimeMillis() < demoUntil);
            canvas.restoreToCount(l2);
        }
    }

    private void drawPhoto(Canvas canvas) {
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
        fill.setAlpha((int) (255 * (1f - blur * 0.85f)));
        for (int k = 0; k < 6; k++) {
            c.save();
            c.rotate(wheelAngle + k * 60f);
            c.drawPath(spoke, fill);
            c.restore();
        }
        if (blur > 0.02f) { // desenfoque de movimiento a alta velocidad
            fill.setColor(0xFF9A9DA5);
            fill.setAlpha((int) (255 * blur * 0.65f));
            c.drawCircle(0, 0, 84, fill);
            fill.setColor(0xFF34363C);
            fill.setAlpha((int) (255 * blur * 0.5f));
            c.drawCircle(0, 0, 40, fill);
        }
        fill.setAlpha(255);
        fill.setColor(0xFF82858C);
        c.drawCircle(0, 0, 16, fill);
        fill.setColor(0xFFBEC0C6);
        c.drawCircle(0, 0, 8, fill);
        c.restore();
    }
}
