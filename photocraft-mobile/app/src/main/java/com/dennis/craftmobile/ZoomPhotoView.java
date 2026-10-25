package com.dennis.craftmobile;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * Inspection-only photo zoom; does not alter the underlying Bitmap or exported file.
 * At fit scale, a horizontal fling changes the current album photo.
 * At inspection scale, a finger pans and album navigation is suspended.
 * Guided Level receives unmodified view coordinates while the picture is fitted.
 */
final class ZoomPhotoView extends ImageView {
    interface GuidedTap {
        void onGuidedTap(float x, float y);
    }
    interface GuideMode {
        boolean isGuiding();
    }
    interface PhotoNavigation {
        void navigate(int direction);
    }

    private static final float MAX_ZOOM = 5f;
    private static final float DOUBLE_TAP_ZOOM = 2.5f;
    private static final float MIN_ZOOM = 1f;

    private final Matrix imageTransform = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private final PhotoNavigation navigation;
    private final GuidedTap guidedTap;
    private final GuideMode guideMode;
    private final Runnable layoutChanged;
    private float zoom = 1f;
    private float left, top;
    private float lastTouchX, lastTouchY, guideDownX, guideDownY;
    private boolean multiTouchSeen;

    ZoomPhotoView(Context context, PhotoNavigation navigation, GuidedTap guidedTap,
                  GuideMode guideMode, Runnable layoutChanged) {
        super(context);
        this.navigation = navigation;
        this.guidedTap = guidedTap;
        this.guideMode = guideMode;
        this.layoutChanged = layoutChanged;
        setScaleType(ScaleType.MATRIX);
        setBackgroundColor(android.graphics.Color.rgb(8, 12, 19));

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                        return getDrawable() != null && !ZoomPhotoView.this.guideMode.isGuiding();
                    }

                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        float oldZoom = zoom;
                        zoom = Math.max(MIN_ZOOM,
                                Math.min(MAX_ZOOM, zoom * detector.getScaleFactor()));
                        float factor = zoom / oldZoom;
                        left = detector.getFocusX() - (detector.getFocusX() - left) * factor;
                        top = detector.getFocusY() - (detector.getFocusY() - top) * factor;
                        applyTransform();
                        return true;
                    }
                });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) {
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent event) {
                if (getDrawable() == null || ZoomPhotoView.this.guideMode.isGuiding())
                    return false;
                if (zoom > 1.05f) {
                    resetZoom();
                } else {
                    zoomAround(DOUBLE_TAP_ZOOM, event.getX(), event.getY());
                }
                return true;
            }
            @Override public boolean onFling(MotionEvent start, MotionEvent end,
                                            float velocityX, float velocityY) {
                if (start == null || end == null || isZoomed() || multiTouchSeen
                        || ZoomPhotoView.this.guideMode.isGuiding()) return false;
                float dx = end.getX() - start.getX();
                float dy = end.getY() - start.getY();
                float density = getResources().getDisplayMetrics().density;
                if (Math.abs(dx) < 80f * density
                        || Math.abs(dx) < 1.4f * Math.abs(dy)
                        || Math.abs(velocityX) < 190f) return false;
                navigation.navigate(dx < 0 ? 1 : -1);
                return true;
            }
        });
        gestureDetector.setOnDoubleTapListener(new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent event) {
                if (getDrawable() == null || ZoomPhotoView.this.guideMode.isGuiding())
                    return false;
                if (isZoomed()) resetZoom();
                else zoomAround(DOUBLE_TAP_ZOOM, event.getX(), event.getY());
                return true;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                return false;
            }
        });
    }

    boolean isZoomed() {
        return zoom > 1.005f;
    }

    void resetZoom() {
        zoom = 1f;
        applyTransform();
    }

    private void zoomAround(float desired, float focusX, float focusY) {
        float next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, desired));
        float factor = next / zoom;
        zoom = next;
        left = focusX - (focusX - left) * factor;
        top = focusY - (focusY - top) * factor;
        applyTransform();
    }

    private void applyTransform() {
        Drawable current = getDrawable();
        int viewW = getWidth(), viewH = getHeight();
        if (current == null || viewW <= 0 || viewH <= 0) return;
        int imgW = current.getIntrinsicWidth(), imgH = current.getIntrinsicHeight();
        if (imgW <= 0 || imgH <= 0) return;

        float fit = Math.min(viewW / (float) imgW, viewH / (float) imgH);
        float scale = fit * zoom;
        float displayedW = imgW * scale, displayedH = imgH * scale;
        if (displayedW <= viewW) left = (viewW - displayedW) / 2f;
        else left = Math.max(viewW - displayedW, Math.min(0f, left));
        if (displayedH <= viewH) top = (viewH - displayedH) / 2f;
        else top = Math.max(viewH - displayedH, Math.min(0f, top));

        imageTransform.reset();
        imageTransform.setScale(scale, scale);
        imageTransform.postTranslate(left, top);
        setImageMatrix(imageTransform);
        if (layoutChanged != null) layoutChanged.run();
    }

    @Override public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        if (imageTransform != null) applyTransform();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyTransform();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (getDrawable() == null) return false;
        int action = event.getActionMasked();
        if (guideMode.isGuiding()) {
            if (action == MotionEvent.ACTION_DOWN) {
                guideDownX = event.getX();
                guideDownY = event.getY();
            } else if (action == MotionEvent.ACTION_UP &&
                    Math.hypot(event.getX() - guideDownX,
                            event.getY() - guideDownY) < 22f * getResources().getDisplayMetrics().density) {
                guidedTap.onGuidedTap(event.getX(), event.getY());
            }
            return true;
        }

        if (action == MotionEvent.ACTION_DOWN) {
            multiTouchSeen = false;
            lastTouchX = event.getX();
            lastTouchY = event.getY();
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            multiTouchSeen = true;
        }

        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1 &&
                isZoomed() && !scaleDetector.isInProgress()) {
            float x = event.getX(), y = event.getY();
            left += x - lastTouchX;
            top += y - lastTouchY;
            applyTransform();
            lastTouchX = x;
            lastTouchY = y;
        } else if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1) {
            lastTouchX = event.getX();
            lastTouchY = event.getY();
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            int survivor = event.getActionIndex() == 0 ? 1 : 0;
            if (survivor < event.getPointerCount()) {
                lastTouchX = event.getX(survivor);
                lastTouchY = event.getY(survivor);
            }
        }
        return true;
    }
}
