package com.winlator.xserver;

import com.winlator.XServerDisplayActivity;
import com.winlator.contentdialog.DebugDialog;
import com.winlator.core.CursorLocker;
import com.winlator.renderer.GLRenderer;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.extensions.BigReqExtension;
import com.winlator.xserver.extensions.DRI3Extension;
import com.winlator.xserver.extensions.Extension;
import com.winlator.xserver.extensions.GLXExtension;
import com.winlator.xserver.extensions.GenericEventExtension;
import com.winlator.xserver.extensions.MITSHMExtension;
import com.winlator.xserver.extensions.PresentExtension;
import com.winlator.xserver.extensions.SyncExtension;
import com.winlator.xserver.extensions.XComposite;
import com.winlator.xserver.extensions.XInputExtension;

import java.nio.charset.Charset;
import java.util.EnumMap;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

public class XServer {
    public enum Lockable {WINDOW_MANAGER, PIXMAP_MANAGER, DRAWABLE_MANAGER, GRAPHIC_CONTEXT_MANAGER, INPUT_DEVICE, CURSOR_MANAGER, SHMSEGMENT_MANAGER}
    private static final boolean ENABLE_CURSOR_LOCKER = false;
    public static final short VERSION = 11;
    public static final String VENDOR_NAME = "Elbrus Technologies, LLC";
    public static final Charset LATIN1_CHARSET = Charset.forName("latin1");
    public final XServerDisplayActivity activity;
    private final Extension[] extensions;
    public final ScreenInfo screenInfo;
    public final PixmapManager pixmapManager;
    public final ResourceIDs resourceIDs = new ResourceIDs(128);
    public final GraphicsContextManager graphicsContextManager = new GraphicsContextManager();
    public final SelectionManager selectionManager;
    public final DrawableManager drawableManager;
    public final WindowManager windowManager;
    public final CursorManager cursorManager;
    public final Keyboard keyboard = Keyboard.createKeyboard(this);
    public final Pointer pointer = new Pointer(this);
    public final InputDeviceManager inputDeviceManager;
    public final GrabManager grabManager;
    public final CursorLocker cursorLocker;
    private SHMSegmentManager shmSegmentManager;
    private GLRenderer renderer;
    private WinHandler winHandler;
    private final EnumMap<Lockable, ReentrantLock> locks = new EnumMap<>(Lockable.class);
    private boolean relativeMouseMovement = false;

    // FPS limiter applied at the X11 Present stage (Steamlator winlator_bionic logic).
    // Throttles clients that present frames (e.g. DXVK) rather than the Android UI refresh.
    private volatile int presentFpsLimit = 0; // 0 = unlimited
    private long nextPresentDeadlineNs = 0L;

    // Frame Pacing (works together with the FPS limiter above):
    // - The limiter alone only caps the maximum presents/sec.
    // - Pacing additionally spaces those presents evenly on a stable,
    //   drift-corrected timeline (deadline += interval) using a high-resolution
    //   wait (parkNanos for the bulk, yield-spin for the last ~0.5ms), which
    //   removes burst/jitter micro-stutter.
    // - With pacing OFF the limiter falls back to a coarse throttle (sleep the
    //   remainder, deadline reset every frame, drift allowed) — lower latency
    //   but uneven frame spacing. With no FPS limit (0 = unlimited) there is no
    //   target interval, so pacing is a no-op either way.
    private volatile boolean framePacingEnabled = true;

    public XServer(XServerDisplayActivity activity, ScreenInfo screenInfo) {
        this.activity = activity;
        this.screenInfo = screenInfo;
        cursorLocker = ENABLE_CURSOR_LOCKER ? new CursorLocker(this) : null;
        for (Lockable lockable : Lockable.values()) locks.put(lockable, new ReentrantLock());

        pixmapManager = new PixmapManager();
        drawableManager = new DrawableManager(this);
        cursorManager = new CursorManager(drawableManager);
        windowManager = new WindowManager(screenInfo, drawableManager);
        selectionManager = new SelectionManager(windowManager);
        inputDeviceManager = new InputDeviceManager(this);
        grabManager = new GrabManager(this);

        DesktopHelper.attachTo(this);
        extensions = setupExtensions();
    }

    public boolean isRelativeMouseMovement() {
        return relativeMouseMovement;
    }

    public int getPresentFpsLimit() {
        return presentFpsLimit;
    }

    public void setPresentFpsLimit(int fps) {
        this.presentFpsLimit = Math.max(0, fps);
        this.nextPresentDeadlineNs = 0L;
    }

    public boolean isFramePacingEnabled() {
        return framePacingEnabled;
    }

    public void setFramePacingEnabled(boolean enabled) {
        this.framePacingEnabled = enabled;
        this.nextPresentDeadlineNs = 0L;
    }

    public void pacePresentIfNeeded() {
        final int limit = presentFpsLimit;
        if (limit <= 0) {
            nextPresentDeadlineNs = 0L;
            return;
        }

        final long frameNs = 1_000_000_000L / limit;
        final long nowNs = System.nanoTime();

        if (!framePacingEnabled) {
            // Coarse throttle without pacing: sleep the remainder against a
            // per-frame deadline and let the timeline drift. Caps the rate but
            // keeps uneven spacing (characteristic un-paced limiter behavior).
            if (nextPresentDeadlineNs == 0L) {
                nextPresentDeadlineNs = nowNs + frameNs;
                return;
            }
            long remainingNs = nextPresentDeadlineNs - nowNs;
            if (remainingNs > 0L) {
                try {
                    Thread.sleep(remainingNs / 1_000_000L, (int) (remainingNs % 1_000_000L));
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            nextPresentDeadlineNs = System.nanoTime() + frameNs;
            return;
        }

        // Paced path: stable timeline, deadline advances by exactly one
        // interval per present (avoids jitter/drift vs using "now" each time).
        if (nextPresentDeadlineNs == 0L) {
            nextPresentDeadlineNs = nowNs + frameNs;
            return;
        }

        long remainingNs = nextPresentDeadlineNs - nowNs;
        if (remainingNs <= 0L) {
            // We're late; move deadline forward but don't "fast-forward" too much.
            nextPresentDeadlineNs = nowNs + frameNs;
            return;
        }

        // Sleep most of the remaining time with parkNanos (finer than Thread.sleep),
        // then spin/yield for the last ~0.5ms for smoother pacing.
        final long spinThresholdNs = 500_000L;
        if (remainingNs > spinThresholdNs) {
            LockSupport.parkNanos(remainingNs - spinThresholdNs);
        }
        while ((remainingNs = nextPresentDeadlineNs - System.nanoTime()) > 0L) {
            Thread.yield();
        }

        nextPresentDeadlineNs += frameNs;
    }
    public void setRelativeMouseMovement(boolean relativeMouseMovement) {
        if (cursorLocker != null) cursorLocker.setEnabled(!relativeMouseMovement);
        this.relativeMouseMovement = relativeMouseMovement;
    }

    public GLRenderer getRenderer() {
        return renderer;
    }

    public void setRenderer(GLRenderer renderer) {
        this.renderer = renderer;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public void setWinHandler(WinHandler winHandler) {
        this.winHandler = winHandler;
    }

    public SHMSegmentManager getSHMSegmentManager() {
        return shmSegmentManager;
    }

    public void setSHMSegmentManager(SHMSegmentManager shmSegmentManager) {
        this.shmSegmentManager = shmSegmentManager;
    }

    private class SingleXLock implements XLock {
        private final ReentrantLock lock;

        private SingleXLock(Lockable lockable) {
            this.lock = locks.get(lockable);
            lock.lock();
        }

        @Override
        public void close() {
            lock.unlock();
        }
    }

    private class MultiXLock implements XLock {
        private final Lockable[] lockables;

        private MultiXLock(Lockable[] lockables) {
            this.lockables = lockables;
            for (Lockable lockable : lockables) locks.get(lockable).lock();
        }

        @Override
        public void close() {
            for (int i = lockables.length - 1; i >= 0; i--) {
                locks.get(lockables[i]).unlock();
            }
        }
    }

    public XLock lock(Lockable lockable) {
        return new SingleXLock(lockable);
    }

    public XLock lock(Lockable... lockables) {
        return new MultiXLock(lockables);
    }

    public XLock lockAll() {
        return new MultiXLock(Lockable.values());
    }

    public Extension getExtensionByName(String name) {
        for (Extension extension : extensions) if (extension.getName().equals(name)) return extension;
        return null;
    }

    public void injectPointerMove(int x, int y) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setPosition(x, y);
        }
    }

    public void injectPointerMoveDelta(int dx, int dy) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setPosition(pointer.getX() + dx, pointer.getY() + dy);
            if (cursorLocker == null) pointer.clampPosition();

            XInputExtension xInputExtension = getExtension(XInputExtension.class);
            if (xInputExtension != null) xInputExtension.sendRawMotion(dx, dy);
        }
    }

    public void injectPointerButtonPress(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, true);

            XInputExtension xInputExtension = getExtension(XInputExtension.class);
            if (xInputExtension != null) xInputExtension.sendRawButtonState(buttonCode.code(), true);
        }
    }

    public void injectPointerButtonRelease(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, false);

            XInputExtension xInputExtension = getExtension(XInputExtension.class);
            if (xInputExtension != null) xInputExtension.sendRawButtonState(buttonCode.code(), false);
        }
    }

    public void injectKeyPress(XKeycode xKeycode) {
        injectKeyPress(xKeycode, 0);
    }

    public void injectKeyPress(XKeycode xKeycode, int keysym) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyPress(xKeycode.id, keysym);
        }
    }

    public void injectKeyRelease(XKeycode xKeycode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyRelease(xKeycode.id);
        }
    }

    private Extension[] setupExtensions() {
        byte opcode = Extension.START_MAJOR_OPCODE;
        Extension[] extensions = new Extension[]{
            new BigReqExtension(this, opcode--),
            new MITSHMExtension(this, opcode--),
            new DRI3Extension(this, opcode--),
            new PresentExtension(this, opcode--),
            new SyncExtension(this, opcode--),
            new XComposite(this, opcode--),
            new GLXExtension(this, opcode--),
            new GenericEventExtension(this, opcode--),
            new XInputExtension(this, opcode--)
        };

        short nextEventId = 64;
        short nextErrorId = 128;
        for (Extension extension : extensions) {
            byte eventCount = extension.getEventCount();
            byte errorCount = extension.getErrorCount();
            if (eventCount > 0) {
                extension.setFirstEventId((byte)nextEventId);
                nextEventId += eventCount;
            }
            if (errorCount > 0) {
                extension.setFirstErrorId((byte)nextErrorId);
                nextErrorId += errorCount;
            }
        }
        return extensions;
    }

    public <T extends Extension> T getExtension(byte opcode) {
        int index = Extension.START_MAJOR_OPCODE - opcode;
        return (T)extensions[index];
    }

    public <T extends Extension> T getExtension(Class<T> extensionClass) {
        for (Extension extension : extensions) {
            if (extension.getClass() == extensionClass) return (T)extension;
        }
        return null;
    }

    public void debugPrint(String line) {
        DebugDialog debugDialog = activity.getDebugDialog();
        if (debugDialog != null) debugDialog.call("xserver:"+line);
    }
}
