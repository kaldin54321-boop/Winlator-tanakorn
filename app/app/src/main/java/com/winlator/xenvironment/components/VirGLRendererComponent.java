package com.winlator.xenvironment.components;

import android.opengl.GLES20;

import androidx.annotation.Keep;

import com.winlator.renderer.Texture;
import com.winlator.xconnector.ConnectedClient;
import com.winlator.xconnector.ConnectionHandler;
import com.winlator.xconnector.RequestHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.XServer;

import java.io.IOException;

public class VirGLRendererComponent extends EnvironmentComponent implements ConnectionHandler, RequestHandler {
    private final XServer xServer;
    private final UnixSocketConfig socketConfig;
    private XConnectorEpoll connector;

    static {
        System.loadLibrary("virglrenderer");
    }

    public VirGLRendererComponent(XServer xServer, UnixSocketConfig socketConfig) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        if (connector != null) return;
        connector = new XConnectorEpoll(socketConfig, this, this);
        connector.setInitialInputBufferCapacity(0);
        connector.setInitialOutputBufferCapacity(0);
        connector.start();
    }

    @Override
    public void stop() {
        if (connector != null) {
            connector.destroy();
            connector = null;
        }
    }

    @Keep
    private void killConnection(int fd) {
        // Kills are rare and always mean a wire error (bad header, failed
        // create, unknown command with short payload). Always log: a kill
        // followed by black output localizes the fault instantly.
        android.util.Log.e("GraphicsDriver", "VirGL killConnection fd=" + fd);
        connector.killConnection(connector.getClientWidthFd(fd));
    }

    @Override
    public void handleConnectionShutdown(ConnectedClient client) {
        long clientPtr = (long)client.getTag();
        destroyClient(clientPtr);
    }

    @Override
    public void handleNewConnection(ConnectedClient client) {
        long clientPtr = handleNewConnection(client.fd);
        client.setTag(clientPtr);
    }

    @Override
    public boolean handleRequest(ConnectedClient client) throws IOException {
        long clientPtr = (long)client.getTag();
        handleRequest(clientPtr);
        return true;
    }

    private int flushCount = 0;
    private int fallbackCount = 0;

    @Keep
    private void flushFrontbuffer(int drawableId, int framebuffer, int width, int height) {
        Drawable drawable = xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) {
            // The virgl-25.0.7 asset sent (uint32_t)(uintptr_t)winsys_drawable_handle
            // where the handle is struct xlib_drawable* (see xm_st.c:
            // flush_frontbuffer(..., &xstfb->buffer->ws, ...)). The truncated
            // pointer (0xA0... = 2684376328/-1610590968) never matches an XID,
            // so every flush was dropped -> black TestD3D while SHM PutImages
            // (head=000... black) on the real window kept FPS counting. Fixed
            // guests now send ws->drawable; this fallback rescues still-broken
            // assets by retargeting via the resource size the host already knows.
            drawable = xServer.drawableManager.findDrawableBySize(width, height);
            if (drawable == null) {
                if (flushCount < 8)
                    android.util.Log.e("GraphicsDriver", "VirGL flush #"
                        + flushCount + " for unknown drawable=" + drawableId
                        + " (" + Integer.toUnsignedString(drawableId) + "u)"
                        + " fbo=" + framebuffer + " " + width + "x" + height + " (dropped, no size match)");
                flushCount++;
                return;
            }
            if (fallbackCount < 3)
                android.util.Log.e("GraphicsDriver", "VirGL flush #"
                    + flushCount + " unknown drawable=" + drawableId
                    + " (" + Integer.toUnsignedString(drawableId) + "u)"
                    + " retargeted to " + drawable.id + " by size " + width + "x" + height);
            fallbackCount++;
        }
        if (flushCount < 3 || (flushCount % 600) == 0)
            android.util.Log.e("GraphicsDriver", "VirGL flush #"
                + flushCount + " drawable=" + drawableId
                + " fbo=" + framebuffer + " " + drawable.width + "x" + drawable.height
                + " res=" + width + "x" + height);
        flushCount++;

        synchronized (drawable.renderLock) {
            drawable.setData(null);
            Texture texture = drawable.getTexture();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer);
            texture.copyFromReadBuffer(drawable.width, drawable.height);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        Runnable onDrawListener = drawable.getOnDrawListener();
        if (onDrawListener != null) onDrawListener.run();
    }

    private native long handleNewConnection(int fd);

    private native void handleRequest(long clientPtr);

    private native long getCurrentEGLContextPtr();

    private native void destroyClient(long clientPtr);

    private native void destroyRenderer(long clientPtr);
}
