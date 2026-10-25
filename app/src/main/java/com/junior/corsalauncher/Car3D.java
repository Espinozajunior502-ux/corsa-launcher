package com.junior.corsalauncher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.ArrayList;
import java.util.List;

/**
 * Modelo 3D sencillo (hecho con polígonos) de un hatchback tipo Corsa.
 * Se dibuja con perspectiva, caras ocultas y sombreado. Sin librerías externas.
 */
public class Car3D {

    private static final int K_BODY = 0, K_GLASS = 1, K_LAMP_F = 2, K_LAMP_R = 3;
    private static final float CAM = 9f;
    private static final float R = 0.30f;
    private static final float[][] WHEELS = {
            {1.15f, 0.30f, 0.82f}, {1.15f, 0.30f, -0.82f},
            {-1.05f, 0.30f, 0.82f}, {-1.05f, 0.30f, -0.82f}
    };

    private static class Face {
        float[][] v;
        int kind;
        float bias;
        float[] n = new float[3];   // normal saliente
        float[] c = new float[3];   // centro de la cara
        float[] px, py;
        float depth;
        float k;
        boolean visible;
    }

    private final List<Face> body = new ArrayList<>();
    private final List<Face> cabin = new ArrayList<>();
    private int bodyColor = 0xFFC4C8D0;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    // cámara por cuadro
    private float sy, cy, sp, cp, scale, f, cx, cyScreen;
    private final float[] tmp = new float[3];
    private final float[] light = norm(-0.35f, 0.8f, 0.5f);

    public Car3D() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.2f);
        line.setStrokeJoin(Paint.Join.ROUND);
        build();
    }

    public void setBodyColor(int c) {
        bodyColor = 0xFF000000 | (c & 0xFFFFFF);
    }

    // ---------------- Construcción del modelo ----------------

    private static float[] norm(float x, float y, float z) {
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        if (l == 0) l = 1;
        return new float[]{x / l, y / l, z / l};
    }

    private void add(List<Face> lst, float[][] v, int kind, float[] center, float bias) {
        Face fc = new Face();
        fc.v = v;
        fc.kind = kind;
        fc.bias = bias;
        for (float[] p : v) {
            fc.c[0] += p[0] / v.length;
            fc.c[1] += p[1] / v.length;
            fc.c[2] += p[2] / v.length;
        }
        float ax = v[1][0] - v[0][0], ay = v[1][1] - v[0][1], az = v[1][2] - v[0][2];
        float bx = v[2][0] - v[1][0], by = v[2][1] - v[1][1], bz = v[2][2] - v[1][2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float[] n = norm(nx, ny, nz);
        float d = n[0] * (fc.c[0] - center[0]) + n[1] * (fc.c[1] - center[1]) + n[2] * (fc.c[2] - center[2]);
        if (d < 0) {
            n[0] = -n[0];
            n[1] = -n[1];
            n[2] = -n[2];
        }
        fc.n = n;
        fc.px = new float[v.length];
        fc.py = new float[v.length];
        lst.add(fc);
    }

    private void build() {
        float[][] prof = {{-1.85f, 0.28f}, {1.85f, 0.28f}, {1.90f, 0.52f}, {1.75f, 0.72f},
                {1.20f, 0.85f}, {-1.70f, 0.90f}, {-1.88f, 0.72f}, {-1.90f, 0.50f}};
        float zh = 0.82f;
        float[] bc = {0, 0.55f, 0};
        int n = prof.length;
        float[][] left = new float[n][], right = new float[n][];
        for (int i = 0; i < n; i++) {
            left[i] = new float[]{prof[i][0], prof[i][1], zh};
            right[n - 1 - i] = new float[]{prof[i][0], prof[i][1], -zh};
        }
        add(body, left, K_BODY, bc, 0);
        add(body, right, K_BODY, bc, 0);
        for (int i = 0; i < n; i++) {
            float[] a = prof[i], b = prof[(i + 1) % n];
            if (a[1] == 0.28f && b[1] == 0.28f) continue; // fondo
            add(body, new float[][]{{a[0], a[1], zh}, {b[0], b[1], zh}, {b[0], b[1], -zh}, {a[0], a[1], -zh}}, K_BODY, bc, 0);
        }
        // faros delanteros (sobre el borde inclinado del frente)
        float[] a = prof[2], b = prof[3];
        float x0 = a[0] + (b[0] - a[0]) * 0.15f + 0.006f, y0 = a[1] + (b[1] - a[1]) * 0.15f;
        float x1 = a[0] + (b[0] - a[0]) * 0.85f + 0.006f, y1 = a[1] + (b[1] - a[1]) * 0.85f;
        add(body, new float[][]{{x0, y0, 0.46f}, {x1, y1, 0.46f}, {x1, y1, 0.78f}, {x0, y0, 0.78f}}, K_LAMP_F, bc, -0.03f);
        add(body, new float[][]{{x0, y0, -0.78f}, {x1, y1, -0.78f}, {x1, y1, -0.46f}, {x0, y0, -0.46f}}, K_LAMP_F, bc, -0.03f);
        // luces traseras
        add(body, new float[][]{{-1.892f, 0.68f, 0.48f}, {-1.888f, 0.56f, 0.48f}, {-1.888f, 0.56f, 0.78f}, {-1.892f, 0.68f, 0.78f}}, K_LAMP_R, bc, -0.03f);
        add(body, new float[][]{{-1.892f, 0.68f, -0.78f}, {-1.888f, 0.56f, -0.78f}, {-1.888f, 0.56f, -0.48f}, {-1.892f, 0.68f, -0.48f}}, K_LAMP_R, bc, -0.03f);

        // cabina
        float[] FL = {1.05f, 0.86f, -0.74f}, FR = {1.05f, 0.86f, 0.74f};
        float[] RR = {-1.55f, 0.90f, 0.74f}, RL = {-1.55f, 0.90f, -0.74f};
        float[] tFL = {0.30f, 1.42f, -0.60f}, tFR = {0.30f, 1.42f, 0.60f};
        float[] tRR = {-1.05f, 1.42f, 0.60f}, tRL = {-1.05f, 1.42f, -0.60f};
        float[] cc = {-0.2f, 1.1f, 0};
        cabinFace(new float[][]{FR, FL, tFL, tFR}, 0.80f, cc);
        cabinFace(new float[][]{RL, RR, tRR, tRL}, 0.78f, cc);
        cabinFace(new float[][]{FR, RR, tRR, tFR}, 0.84f, cc);
        cabinFace(new float[][]{RL, FL, tFL, tRL}, 0.84f, cc);
        cabinFace(new float[][]{tFL, tFR, tRR, tRL}, 0f, cc);
    }

    private void cabinFace(float[][] vs, float k, float[] cc) {
        add(cabin, vs, K_BODY, cc, 0);
        if (k > 0) {
            float cx0 = 0, cy0 = 0, cz0 = 0;
            for (float[] p : vs) {
                cx0 += p[0] / vs.length;
                cy0 += p[1] / vs.length;
                cz0 += p[2] / vs.length;
            }
            float[][] in = new float[vs.length][3];
            for (int i = 0; i < vs.length; i++) {
                in[i][0] = cx0 + (vs[i][0] - cx0) * k;
                in[i][1] = cy0 + (vs[i][1] - cy0) * k;
                in[i][2] = cz0 + (vs[i][2] - cz0) * k;
            }
            add(cabin, in, K_GLASS, cc, -0.02f);
        }
    }

    // ---------------- Dibujo ----------------

    private void rot(float x, float y, float z) {
        float xr = x * cy + z * sy;
        float zr = -x * sy + z * cy;
        tmp[0] = xr;
        tmp[1] = y * cp - zr * sp - 0.55f * cp;
        tmp[2] = y * sp + zr * cp;
    }

    /** Devuelve la profundidad y deja x,y de pantalla en out[0], out[1]. */
    private float proj(float x, float y, float z, float[] out) {
        rot(x, y, z);
        float dd = CAM - tmp[2];
        out[0] = cx + f * tmp[0] / dd;
        out[1] = cyScreen - f * tmp[1] / dd;
        return dd;
    }

    private final float[] pt = new float[2];

    private static int shade(int col, float k) {
        int r = Math.min(255, Math.max(0, (int) (((col >> 16) & 255) * k)));
        int g = Math.min(255, Math.max(0, (int) (((col >> 8) & 255) * k)));
        int b = Math.min(255, Math.max(0, (int) ((col & 255) * k)));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public void draw(Canvas canvas, int w, int h, float yawDeg, float pitchDeg,
                     float wheelDeg, boolean night) {
        double ya = Math.toRadians(yawDeg), pa = Math.toRadians(pitchDeg);
        sy = (float) Math.sin(ya);
        cy = (float) Math.cos(ya);
        sp = (float) Math.sin(pa);
        cp = (float) Math.cos(pa);
        scale = Math.min(w / 4.6f, h / 2.6f);
        f = scale * CAM;
        cx = w / 2f;
        cyScreen = h * 0.56f;

        // sombra en el piso
        path.reset();
        for (int i = 0; i < 24; i++) {
            double t = i * Math.PI / 12;
            proj((float) (1.95 * Math.cos(t)), 0, (float) Math.sin(t), pt);
            if (i == 0) path.moveTo(pt[0], pt[1]);
            else path.lineTo(pt[0], pt[1]);
        }
        path.close();
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0x6E000000);
        canvas.drawPath(path, fill);

        // ruedas del lado lejano (solo se ve la banda bajo la carrocería)
        for (float[] wh : WHEELS) if (!nearWheel(wh)) tread(canvas, wh);

        drawFaces(canvas, body, night);
        drawFaces(canvas, cabin, night);

        for (float[] wh : WHEELS) if (nearWheel(wh)) {
            tread(canvas, wh);
            float sg = wh[2] > 0 ? 1 : -1;
            disc(canvas, wh, 0.003f * sg, R * 1.16f, 0xFF080809);
            disc(canvas, wh, 0.006f * sg, R, 0xFF26282C);
            disc(canvas, wh, 0.008f * sg, R * 0.72f, 0xFF96999F);
            float[][] sp5 = {{0.06f, -0.20f}, {R * 0.70f, -0.13f}, {R * 0.70f, 0.13f}, {0.06f, 0.20f}};
            fill.setColor(0xFF34363C);
            for (int k = 0; k < 5; k++) {
                double a = -Math.toRadians(wheelDeg) + k * 2 * Math.PI / 5;
                path.reset();
                for (int j = 0; j < 4; j++) {
                    float px = wh[0] + (float) (sp5[j][0] * Math.cos(a + sp5[j][1]));
                    float py = wh[1] + (float) (sp5[j][0] * Math.sin(a + sp5[j][1]));
                    proj(px, py, wh[2] + 0.010f * sg, pt);
                    if (j == 0) path.moveTo(pt[0], pt[1]);
                    else path.lineTo(pt[0], pt[1]);
                }
                path.close();
                canvas.drawPath(path, fill);
            }
            disc(canvas, wh, 0.012f * sg, 0.06f, 0xFFCDCFD4);
        }
    }

    private boolean nearWheel(float[] wh) {
        float nz = wh[2] > 0 ? 1 : -1;
        float xr = nz * sy, zr = nz * cy;
        float ny = -zr * sp, nzr = zr * cp;
        rot(wh[0], wh[1], wh[2]);
        return xr * (0 - tmp[0]) + ny * (0 - tmp[1]) + nzr * (CAM - tmp[2]) > 0;
    }

    private void tread(Canvas canvas, float[] wh) {
        float sg = wh[2] > 0 ? 1 : -1;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFF1E1F22);
        line.setColor(0xFF0E0E10);
        for (int i = 0; i < 24; i++) {
            double a0 = i * 2 * Math.PI / 24, a1 = (i + 1) * 2 * Math.PI / 24;
            path.reset();
            proj(wh[0] + (float) (R * Math.cos(a0)), wh[1] + (float) (R * Math.sin(a0)), wh[2], pt);
            path.moveTo(pt[0], pt[1]);
            proj(wh[0] + (float) (R * Math.cos(a1)), wh[1] + (float) (R * Math.sin(a1)), wh[2], pt);
            path.lineTo(pt[0], pt[1]);
            proj(wh[0] + (float) (R * Math.cos(a1)), wh[1] + (float) (R * Math.sin(a1)), wh[2] - sg * 0.22f, pt);
            path.lineTo(pt[0], pt[1]);
            proj(wh[0] + (float) (R * Math.cos(a0)), wh[1] + (float) (R * Math.sin(a0)), wh[2] - sg * 0.22f, pt);
            path.lineTo(pt[0], pt[1]);
            path.close();
            canvas.drawPath(path, fill);
            canvas.drawPath(path, line);
        }
    }

    private void disc(Canvas canvas, float[] wh, float zoff, float rad, int color) {
        path.reset();
        for (int i = 0; i < 24; i++) {
            double a = i * 2 * Math.PI / 24;
            proj(wh[0] + (float) (rad * Math.cos(a)), wh[1] + (float) (rad * Math.sin(a)), wh[2] + zoff, pt);
            if (i == 0) path.moveTo(pt[0], pt[1]);
            else path.lineTo(pt[0], pt[1]);
        }
        path.close();
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        canvas.drawPath(path, fill);
    }

    private final List<Face> order = new ArrayList<>();

    private void drawFaces(Canvas canvas, List<Face> lst, boolean night) {
        order.clear();
        for (Face fc : lst) {
            // normal rotada
            float xr = fc.n[0] * cy + fc.n[2] * sy;
            float zr = -fc.n[0] * sy + fc.n[2] * cy;
            float nx = xr, ny = fc.n[1] * cp - zr * sp, nz = fc.n[1] * sp + zr * cp;
            rot(fc.c[0], fc.c[1], fc.c[2]);
            float vx = -tmp[0], vy = -tmp[1], vz = CAM - tmp[2];
            fc.visible = nx * vx + ny * vy + nz * vz > 0;
            if (!fc.visible) continue;
            fc.k = Math.max(0f, nx * light[0] + ny * light[1] + nz * light[2]);
            float d = 0;
            for (int i = 0; i < fc.v.length; i++) {
                d += proj(fc.v[i][0], fc.v[i][1], fc.v[i][2], pt);
                fc.px[i] = pt[0];
                fc.py[i] = pt[1];
            }
            fc.depth = d / fc.v.length + fc.bias;
            order.add(fc);
        }
        // de lejos a cerca
        for (int i = 1; i < order.size(); i++) {
            Face x = order.get(i);
            int j = i - 1;
            while (j >= 0 && order.get(j).depth < x.depth) {
                order.set(j + 1, order.get(j));
                j--;
            }
            order.set(j + 1, x);
        }
        fill.setStyle(Paint.Style.FILL);
        for (Face fc : order) {
            int base, col;
            switch (fc.kind) {
                case K_GLASS:
                    base = 0xFF16202C;
                    col = shade(base, 0.7f + 0.6f * fc.k);
                    break;
                case K_LAMP_F:
                    base = night ? 0xFFFFFAD7 : 0xFFD7D2B9;
                    col = base;
                    break;
                case K_LAMP_R:
                    base = 0xFFD21E1E;
                    col = base;
                    break;
                default:
                    base = bodyColor;
                    col = shade(base, 0.62f + 0.48f * fc.k);
            }
            path.reset();
            path.moveTo(fc.px[0], fc.py[0]);
            for (int i = 1; i < fc.px.length; i++) path.lineTo(fc.px[i], fc.py[i]);
            path.close();
            fill.setColor(col);
            canvas.drawPath(path, fill);
            line.setColor(shade(base, 0.55f));
            canvas.drawPath(path, line);
        }
    }
}
