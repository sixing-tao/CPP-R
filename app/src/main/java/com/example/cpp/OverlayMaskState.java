package com.example.cppr;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Thread-safe shared state for overlay rectangles that should be masked
 * out from screenshots before uploading.
 */
public final class OverlayMaskState {

    private static final Object LOCK = new Object();
    private static List<Rect> currentRects = new ArrayList<>();

    private OverlayMaskState() {}

    public static void setRects(List<Rect> rects) {
        synchronized (LOCK) {
            currentRects = new ArrayList<>(rects != null ? rects : Collections.emptyList());
        }
    }

    public static void setSingleRect(Rect rect) {
        synchronized (LOCK) {
            currentRects = new ArrayList<>();
            if (rect != null) {
                currentRects.add(new Rect(rect));
            }
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            currentRects = new ArrayList<>();
        }
    }

    public static List<Rect> getRectsCopy() {
        synchronized (LOCK) {
            List<Rect> copy = new ArrayList<>(currentRects.size());
            for (Rect r : currentRects) {
                copy.add(new Rect(r));
            }
            return copy;
        }
    }
}


