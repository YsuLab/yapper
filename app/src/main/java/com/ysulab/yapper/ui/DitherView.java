package com.ysulab.yapper.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;
import com.ysulab.yapper.R;

/**
 * Draws a {@link SmokeScene} as square dither cells (3dp each), scaled up without smoothing.
 * Cells are computed on a background thread; the UI thread only copies pixels and draws.
 * With animations turned off (Settings, Remove animations) it shows still frames.
 */
public final class DitherView extends View implements Choreographer.FrameCallback {

    private static final float CELL_DP = 3f;
    private static HandlerThread worker;

    private final SmokeScene scene = new SmokeScene((SystemClock.elapsedRealtime() % 100_000) / 1000f);
    private final int[] palette = new int[5];
    private final Paint paint = new Paint();
    private final Rect dst = new Rect();
    private final Handler bg;
    private final Object lock = new Object();

    private Bitmap bitmap;
    private int cellsW;
    private int cellsH;
    private byte[] inks = new byte[0];
    private int[] pixels = new int[0];
    private volatile float targetLevel;
    private boolean frameScheduled;
    private boolean rendering;
    private long lastFrameNs;

    public DitherView(Context context) {
        this(context, null);
    }

    public DitherView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setFilterBitmap(false);
        paint.setAntiAlias(false);
        palette[SmokeScene.INK_GROUND] = context.getColor(R.color.yapper_background);
        palette[SmokeScene.INK_SMOKE] = context.getColor(R.color.yapper_smoke);
        palette[SmokeScene.INK_ASH] = context.getColor(R.color.yapper_ash);
        palette[SmokeScene.INK_MOON] = context.getColor(R.color.yapper_primary);
        palette[SmokeScene.INK_MOON_LIT] = context.getColor(R.color.yapper_primary_line);
        bg = new Handler(worker().getLooper());
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private static synchronized HandlerThread worker() {
        if (worker == null) {
            worker = new HandlerThread("yap-dither");
            worker.start();
        }
        return worker;
    }

    /** The scene, to set layout (moon position, text bands) before showing it. */
    public SmokeScene scene() {
        return scene;
    }

    /** Side key pressed: smoke starts to rise. */
    public void press() {
        synchronized (lock) {
            scene.setHeld(true);
            if (!animationsOn()) {
                scene.snap(1f, 0f);
            }
        }
        kick();
    }

    /** Capture ended: smoke settles and the rings fade. */
    public void release() {
        synchronized (lock) {
            scene.setHeld(false);
            targetLevel = 0f;
        }
        kick();
    }

    /** Voice level 0..1, from the recorder's amplitude. */
    public void setLevel(float level) {
        targetLevel = level;
        if (!animationsOn()) {
            synchronized (lock) {
                scene.snap(scene.isHeld() ? 1f : 0f, level);
            }
            requestRender();
        }
    }

    /** Park the smoke at {@code emerge} (0..1) with no voice: for the main screen's header. */
    public void showStill(float emerge) {
        synchronized (lock) {
            scene.rest = emerge;
            scene.snap(emerge, 0f);
        }
        requestRender();
    }

    private boolean animationsOn() {
        float scale = android.provider.Settings.Global.getFloat(getContext().getContentResolver(),
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        return scale > 0f;
    }

    private void kick() {
        if (animationsOn()) {
            if (!frameScheduled && isAttachedToWindow()) {
                frameScheduled = true;
                lastFrameNs = 0;
                Choreographer.getInstance().postFrameCallback(this);
            }
        } else {
            requestRender();
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        frameScheduled = false;
        if (!isAttachedToWindow()) {
            return;
        }
        float dt = lastFrameNs == 0 ? 0.016f : (frameTimeNanos - lastFrameNs) / 1e9f;
        lastFrameNs = frameTimeNanos;
        boolean more;
        synchronized (lock) {
            scene.step(dt, targetLevel);
            more = scene.isAnimating();
        }
        requestRender();
        if (more) {
            frameScheduled = true;
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    /** Computes the cells off the UI thread; skips a frame if the last one is still rendering. */
    private void requestRender() {
        final int w = cellsW;
        final int h = cellsH;
        if (w == 0 || h == 0 || rendering) {
            return;
        }
        rendering = true;
        bg.post(() -> {
            synchronized (lock) {
                if (inks.length != w * h) {
                    inks = new byte[w * h];
                    pixels = new int[w * h];
                }
                scene.render(w, h, inks);
                for (int i = 0; i < inks.length; i++) {
                    pixels[i] = palette[inks[i]];
                }
            }
            post(() -> {
                rendering = false;
                synchronized (lock) {
                    if (bitmap != null && bitmap.getWidth() == w && bitmap.getHeight() == h && pixels.length == w * h) {
                        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
                    }
                }
                invalidate();
            });
        });
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cell = CELL_DP * getResources().getDisplayMetrics().density;
        cellsW = Math.max(1, Math.round(w / cell));
        cellsH = Math.max(1, Math.round(h / cell));
        synchronized (lock) {
            bitmap = Bitmap.createBitmap(cellsW, cellsH, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(palette[SmokeScene.INK_GROUND]);
        }
        dst.set(0, 0, w, h);
        requestRender();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        synchronized (lock) {
            if (scene.isAnimating()) {
                kick();
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this);
        frameScheduled = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Bitmap b = bitmap;
        if (b != null) {
            canvas.drawBitmap(b, null, dst, paint);
        } else {
            canvas.drawColor(palette[SmokeScene.INK_GROUND]);
        }
    }
}
