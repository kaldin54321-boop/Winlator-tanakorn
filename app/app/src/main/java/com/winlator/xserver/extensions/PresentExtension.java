package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.Log;
import android.util.SparseArray;

import com.winlator.core.GPUHelper;
import com.winlator.renderer.GPUImage;
import com.winlator.renderer.Texture;
import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.core.Bitmask;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Pixmap;
import com.winlator.xserver.Visual;
import com.winlator.xserver.Window;
import com.winlator.xserver.WindowManager;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XResource;
import com.winlator.xserver.XResourceManager;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadMatch;
import com.winlator.xserver.errors.BadWindow;
import com.winlator.xserver.errors.XRequestError;
import com.winlator.xserver.events.PresentCompleteNotify;
import com.winlator.xserver.events.PresentConfigureNotify;
import com.winlator.xserver.events.PresentIdleNotify;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PresentExtension extends Extension implements WindowManager.OnWindowModificationListener, XResourceManager.OnResourceLifecycleListener {
    public static final byte MAJOR_VERSION = 1;
    // 1.2 to match the reference llvmpipe-capable server (alexvorxx):
    // Mesa's WSI negotiates the Present version before using it, and
    // advertises DRI3 1.2 alongside this. Reporting 1.0 while serving
    // DRI3 1.2 is an inconsistent pair that gives a modifier-aware WSI
    // (lavapipe) a reason to avoid the DRI3+Present path entirely.
    // Only QueryVersion/PresentPixmap/SelectInput are implemented either
    // way, so no new request surface is exposed by the bump.
    public static final byte MINOR_VERSION = 2;
    public enum CompleteKind {PIXMAP, MSC_NOTIFY}
    public enum CompleteMode {COPY, FLIP, SKIP}
    private static final byte FLAG_WINDOW_DESTROYED = (1<<0);
    private final SparseArray<PresentEvent> events = new SparseArray<>();
    private SyncExtension syncExtension;
    private long eglContextPtr;
    private ScheduledExecutorService idleNotifyScheduler;
    // Frame-path probes (Frost diagnostic): throttled per XServer instance
    // so one run yields a handful of lines, never per-frame spam. The
    // totals below still count EVERY present, and each emitted line carries
    // its running total (n=...), so "no traffic" vs "probe throttled" is
    // always distinguishable from a filtered logcat. Size-aware: a new
    // pixmap size always logs once, so an early present on one drawable
    // cannot hide a later guest swapchain of a different size.
    private int nullPresentLogs = 0;
    private int presentProbeLogs = 0;
    private long presentTotal = 0;
    private long nullPresentTotal = 0;
    private int lastPresentW = -1;
    private int lastPresentH = -1;
    // Heartbeat: same-size presents re-log at most once per 2s (with the
    // running total n=...). Distinguishes a live swapchain from a stalled
    // one without per-frame spam.
    private long lastPresentLogMs = 0;

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte PRESENT_PIXMAP = 1;
        private static final byte SELECT_INPUT = 3;
    }

    private static class PresentEvent {
        private Window window;
        private XClient client;
        private int id;
        private Bitmask mask;
    }

    public PresentExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
        xServer.windowManager.addOnWindowModificationListener(this);
        xServer.windowManager.addOnResourceLifecycleListener(this);
    }

    @Override
    public String getName() {
        return "Present";
    }

    @Override
    public byte getEventCount() {
        return 3;
    }

    private void sendConfigureNotify(Window window, int pixmapFlags) {
        if (events.size() == 0) return;
        int mask = (1<<PresentConfigureNotify.PRESENT_CONFIGURE);

        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                PresentEvent event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(mask)) {
                    event.client.sendEvent(new PresentConfigureNotify(this, event.id, window, pixmapFlags));
                }
            }
        }
    }

    private void sendIdleNotify(Window window, Pixmap pixmap, int serial, int idleFence) {
        if (idleFence != 0) syncExtension.setTriggered(idleFence);
        if (events.size() == 0) return;
        int mask = (1<<PresentIdleNotify.PRESENT_IDLE);

        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                PresentEvent event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(mask)) {
                    event.client.sendEvent(new PresentIdleNotify(this, event.id, window, pixmap, serial, idleFence));
                }
            }
        }
    }

    private void sendIdleNotifyScheduled(final Window window, final Pixmap pixmap, final int serial, final int idleFence) {
        final long frameTime = 1000000000L / 60;
        long now = System.nanoTime();

        long lastIdleTime = (long)window.getTag("lastIdleTime", now);
        lastIdleTime = lastIdleTime <= (now - frameTime) ? now + frameTime : lastIdleTime + frameTime;
        long delayNanos = lastIdleTime - now;
        window.setTag("lastIdleTime", lastIdleTime);

        if (idleNotifyScheduler == null) idleNotifyScheduler = Executors.newSingleThreadScheduledExecutor();
        idleNotifyScheduler.schedule(() -> sendIdleNotify(window, pixmap, serial, idleFence), delayNanos, TimeUnit.NANOSECONDS);
    }

    private void sendCompleteNotify(Window window, int serial, CompleteKind kind, CompleteMode mode, long ust, long msc) {
        if (events.size() == 0) return;
        int mask = (1<<PresentCompleteNotify.PRESENT_COMPLETE);

        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                PresentEvent event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(mask)) {
                    event.client.sendEvent(new PresentCompleteNotify(this, event.id, window, serial, kind, mode, ust, msc));
                }
            }
        }
    }

    // X11 depth 24 and 32 are the same 32-bpp in-memory layout (X depth 24
    // TrueColor visuals are stored 4 bytes/pixel; this server's own depth-32
    // visual exists for the same reason). Every Drawable buffer here is
    // width*height*4 with raw word copies, so cross-presenting 24<->32 is
    // lossless. Mesa 26 (lavapipe WSI / llvmpipe DRI3 present) reports the X
    // depth (24) for X8R8G8B8 swapchain images while the window may carry the
    // depth-32 visual (or vice versa when the colormap resolves the other
    // visual); a hard BadMatch drops EVERY 3D frame -- black TestD3D / cube
    // missing with GDI background intact -- with no logcat trace. Other
    // depths (1-bit masks etc.) still mismatch as before, and already-matching
    // presents (all working drivers) never reach this branch.
    private static boolean isDepthCompatible(int contentDepth, int pixmapDepth) {
        if (contentDepth == pixmapDepth) return true;
        return (contentDepth == 24 || contentDepth == 32) && (pixmapDepth == 24 || pixmapDepth == 32);
    }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(MAJOR_VERSION);
            outputStream.writeInt(MINOR_VERSION);
            outputStream.writePad(16);
        }
    }

    private void createCopyEGLContext(XClient client) {
        eglContextPtr = GPUHelper.createOffscreenEGLContext(true);
        client.addOnDestroyListener((unused) -> {
            GPUHelper.destroyOffscreenEGLContext(eglContextPtr);
            eglContextPtr = 0;
        });
    }

    private void presentPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int pixmapId = inputStream.readInt();
        int serial = inputStream.readInt();
        inputStream.skip(8);
        short xOff = inputStream.readShort();
        short yOff = inputStream.readShort();
        inputStream.skip(8);
        int idleFence = inputStream.readInt();
        inputStream.skip(8);
        long targetMSC = inputStream.readLong();
        inputStream.skip(client.getRemainingRequestLength());

        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        Pixmap pixmap = xServer.pixmapManager.getPixmap(pixmapId);

        Drawable content = window.getContent();
        // Null-safe: getVisualForDepth returns null for depths outside
        // {1,24,32}. A null visual used to NPE here and kill the present
        // (black frame, no trace); the copy itself is depth-agnostic raw
        // words, so an unverifiable depth is allowed through instead.
        Visual pixmapVisual = (pixmap != null) ? pixmap.drawable.visual : null;
        if (pixmapVisual != null && content.visual.depth != pixmapVisual.depth && !isDepthCompatible(content.visual.depth, pixmapVisual.depth))
            throw new BadMatch();

        // Throttle presents to limit actual game FPS (Steamlator winlator_bionic logic).
        xServer.pacePresentIfNeeded();

        final int frameTime = 1000000 / 60;
        long ust = System.nanoTime() / 1000L;
        long msc = ust / frameTime;

        synchronized (content.renderLock) {
            sendCompleteNotify(window, serial, CompleteKind.PIXMAP, CompleteMode.COPY, ust, msc);

            if (pixmap != null) {
                presentTotal++;
                Texture srcTexture = pixmap.drawable.getTexture();
                if (srcTexture instanceof GPUImage) {
                    if (eglContextPtr == 0) createCopyEGLContext(client);
                    content.setData(null);
                    content.getTexture().copyFromSource(srcTexture);
                    content.forceUpdate();
                }
                else content.copyArea((short)0, (short)0, xOff, yOff, pixmap.drawable.width, pixmap.drawable.height, pixmap.drawable);

                if (targetMSC > 0) {
                    sendIdleNotifyScheduled(window, pixmap, serial, idleFence);
                }
                else sendIdleNotify(window, pixmap, serial, idleFence);
                // Present probe: proves a found pixmap is actually copied
                // into the window, with dimensions on both sides plus the
                // first bytes of the source pixels. head=all zeros across
                // frames means the driver never wrote pixels (rendering-side
                // failure); nonzero head with a black screen means the
                // copy/upload/display side drops them.
                long nowMs = android.os.SystemClock.uptimeMillis();
                if (presentProbeLogs < 3
                        || pixmap.drawable.width != lastPresentW
                        || pixmap.drawable.height != lastPresentH
                        || nowMs - lastPresentLogMs >= 2000) {
                    presentProbeLogs++;
                    lastPresentW = pixmap.drawable.width;
                    lastPresentH = pixmap.drawable.height;
                    lastPresentLogMs = nowMs;
                    try {
                        java.nio.ByteBuffer src = pixmap.drawable.getData();
                        StringBuilder head = new StringBuilder();
                        if (src != null) {
                            int n = Math.min(8, src.capacity());
                            for (int i = 0; i < n; i++) head.append(String.format("%02x", src.get(i) & 0xff));
                        }
                        else head.append("<nodata>");
                        Log.e("GraphicsDriver", "Present: pixmap=" + pixmapId
                            + " " + pixmap.drawable.width + "x" + pixmap.drawable.height
                            + " -> window=" + windowId + " " + content.width + "x" + content.height
                            + " off=" + xOff + "," + yOff + " head=" + head
                            + " n=" + presentTotal);
                    }
                    catch (Exception e) {
                        Log.e("GraphicsDriver", "Present probe failed: " + e);
                    }
                }
            }
            else {
                nullPresentTotal++;
                content.forceUpdate();
                // A present naming an unknown pixmap keeps stale window
                // contents (black on a fresh window) with no other trace.
                // Throttled: the import having failed is the story, not each
                // dropped frame.
                if (nullPresentLogs < 5) {
                    nullPresentLogs++;
                    Log.e("GraphicsDriver", "Present: pixmap=" + pixmapId
                        + " NOT FOUND for window=" + windowId + " (stale frame kept)"
                        + " n=" + nullPresentTotal);
                }
            }
        }
    }

    private void selectInput(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int eventId = inputStream.readInt();
        int windowId = inputStream.readInt();
        Bitmask mask = new Bitmask(inputStream.readInt());

        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        window.removeTag("lastIdleTime");
        GPUImage.createOrObtain(xServer, window.getContent(), true, true);

        if (eventId > 0) {
            synchronized (events) {
                PresentEvent event = events.get(eventId);
                if (event != null) {
                    if (event.window != window || event.client != client) throw new BadMatch();

                    if (!mask.isEmpty()) {
                        event.mask = mask;
                    }
                    else events.remove(eventId);
                }
                else {
                    event = new PresentEvent();
                    event.id = eventId;
                    event.window = window;
                    event.client = client;
                    event.mask = mask;
                    events.put(eventId, event);
                }
            }
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        if (syncExtension == null) syncExtension = (SyncExtension)xServer.getExtensionByName("SYNC");

        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.PRESENT_PIXMAP:
                try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER)) {
                    presentPixmap(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.SELECT_INPUT:
                try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    selectInput(client, inputStream, outputStream);
                }
                break;
            default:
                throw new BadImplementation();
        }
    }

    @Override
    public void onFreeResource(XResource resource) {
        if (resource instanceof Window) {
            sendConfigureNotify((Window)resource, FLAG_WINDOW_DESTROYED);
            synchronized (events) {
                for (int i = events.size()-1; i >= 0; i--) {
                    PresentEvent event = events.valueAt(i);
                    if (event.window == resource) events.removeAt(i);
                }
            }
        }
    }

    @Override
    public void onUpdateWindowGeometry(Window window, boolean resized) {
        window.removeTag("lastIdleTime");
        sendConfigureNotify(window, 0);
    }
}
