package com.winlator.xserver;

import android.util.SparseArray;

import com.winlator.core.Callback;
import com.winlator.renderer.Texture;

public class DrawableManager extends XResourceManager implements XResourceManager.OnResourceLifecycleListener {
    private final XServer xServer;
    private final SparseArray<Drawable> drawables = new SparseArray<>();

    public DrawableManager(XServer xServer) {
        this.xServer = xServer;
        xServer.pixmapManager.addOnResourceLifecycleListener(this);
    }

    public Drawable getDrawable(int id) {
        return drawables.get(id);
    }

    /**
     * Size-based fallback for VirGL flushes carrying a drawable id the X
     * server never registered. The pre-fix virgl-25.0.7 asset sent truncated
     * struct xlib_drawable* pointer bits (0xA0...) instead of ws-&gt;drawable,
     * so every flush was dropped -&gt; black window while SHM fallback kept FPS
     * counting. Matching by WxH retargets the frame to the real window.
     * Returns null when no candidate matches.
     */
    public Drawable findDrawableBySize(int width, int height) {
        if (width <= 0 || height <= 0) return null;
        for (int i = 0; i < drawables.size(); i++) {
            Drawable d = drawables.valueAt(i);
            if (d != null && d.width == (short) width && d.height == (short) height) return d;
        }
        return null;
    }

    public Drawable createDrawable(int id, short width, short height, byte depth) {
        return createDrawable(id, width, height, xServer.pixmapManager.getVisualForDepth(depth));
    }

    public Drawable createDrawable(int id, short width, short height, Visual visual) {
        if (id == 0) return new Drawable(id, width, height, visual);
        if (drawables.indexOfKey(id) >= 0) return null;
        Drawable drawable = new Drawable(id, width, height, visual);
        drawables.put(id, drawable);
        return drawable;
    }

    public void removeDrawable(int id) {
        Drawable drawable = drawables.get(id);

        final Texture texture = drawable.getTexture();
        if (texture != null) {
            if (texture.getOwner() == drawable) texture.setOwner(null);
            xServer.getRenderer().xServerView.queueEvent(texture::destroy);
        }

        Callback<Drawable> onDestroyListener = drawable.getOnDestroyListener();
        if (onDestroyListener != null) onDestroyListener.call(drawable);

        drawable.setOnDrawListener(null);
        drawables.remove(id);
    }

    @Override
    public void onFreeResource(XResource resource) {
        if (resource instanceof Pixmap) removeDrawable(((Pixmap)resource).drawable.id);
    }

    public Visual getVisual() {
        return xServer.pixmapManager.visual;
    }
}
