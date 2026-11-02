package io.github.maharjanronish.jumper;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;

/**
 * The GL surface. RGB888 + 16-bit depth works on every GLES 2.0 device
 * (manual RGB565 configs render blank on a few drivers); the EGL context is
 * preserved across pauses for instant resumes.
 */
public final class GameView extends GLSurfaceView {

    private final GameRenderer renderer;

    public GameView(Context ctx, GameRenderer renderer) {
        super(ctx);
        this.renderer = renderer;
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 0, 16, 0);   // RGB888, 16-bit depth, no stencil
        setPreserveEGLContextOnPause(true);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    /** Stops the render loop when the game-over panel is up (saves battery). */
    public void setPausedRendering(boolean paused) {
        setRenderMode(paused ? RENDERMODE_WHEN_DIRTY : RENDERMODE_CONTINUOUSLY);
        if (!paused) requestRender();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerId(e.getActionIndex()) == 0) {
                    queueEvent(renderer::onPress);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (e.getPointerId(e.getActionIndex()) == 0) {
                    queueEvent(renderer::onRelease);
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                queueEvent(renderer::onRelease);
                break;
            default:
                break;
        }
        return true;
    }
}
