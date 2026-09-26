package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.GraphicsContext;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadDrawable;
import com.winlator.xserver.errors.BadGraphicsContext;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadSHMSegment;
import com.winlator.xserver.errors.XRequestError;

import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

public class MITSHMExtension extends Extension {
    public static final byte MAJOR_VERSION = 1;
    public static final byte MINOR_VERSION = 1;
    // Traffic census (see putImage): throttled per XServer instance so one
    // run yields a handful of lines, never per-frame spam. Software Mesa
    // clients (llvmpipe GL, lavapipe WSI) may present via SHM PutImage
    // instead of DRI3+Present; the DRI3/Present probes stay silent then,
    // and these lines are the only witness of whether pixels move at all.
    // putImageTotal counts every PutImage; each emitted line carries the
    // running total (n=...) so throttling can never be mistaken for silence.
    // Size-aware: the desktop shell's strips (e.g. 1280x20) would otherwise
    // consume the whole budget before the guest draws, hiding a 640x480
    // guest frame forever. A new source size always logs once.
    private int putImageProbeLogs = 0;
    private long putImageTotal = 0;
    private int lastPutImageW = -1;
    private int lastPutImageH = -1;
    // Heartbeat: same-size frames re-log at most once per 2s (with the
    // running total). Proves the guest is still presenting at rate (vs
    // stalled after a few frames) without per-frame spam: ~1 line/2s max
    // per size while active.
    private long lastPutImageLogMs = 0;

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte ATTACH = 1;
        private static final byte DETACH = 2;
        private static final byte PUT_IMAGE = 3;
        private static final byte CREATE_PIXMAP = 5;
    }

    public MITSHMExtension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
    }

    @Override
    public String getName() {
        return "MIT-SHM";
    }

    @Override
    public byte getEventCount() {
        return 1;
    }

    @Override
    public byte getErrorCount() {
        return 1;
    }

    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeShort(MAJOR_VERSION);
            outputStream.writeShort(MINOR_VERSION);
            outputStream.writeShort((short)0);
            outputStream.writeShort((short)0);
            outputStream.writeByte((byte)0);
        }
    }

    private void attach(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int xid = inputStream.readInt();
        int shmid = inputStream.readInt();
        inputStream.skip(4);
        xServer.getSHMSegmentManager().attach(xid, shmid);
    }

    private void detach(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        xServer.getSHMSegmentManager().detach(inputStream.readInt());
    }

    private void putImage(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int drawableId = inputStream.readInt();
        int gcId = inputStream.readInt();
        short totalWidth = inputStream.readShort();
        short totalHeight = inputStream.readShort();
        short srcX = inputStream.readShort();
        short srcY = inputStream.readShort();
        short srcWidth = inputStream.readShort();
        short srcHeight = inputStream.readShort();
        short dstX = inputStream.readShort();
        short dstY = inputStream.readShort();
        byte depth = inputStream.readByte();
        inputStream.skip(3);
        int shmseg = inputStream.readInt();
        inputStream.skip(4);

        Drawable drawable = xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);

        GraphicsContext graphicsContext = xServer.graphicsContextManager.getGraphicsContext(gcId);
        if (graphicsContext == null) throw new BadGraphicsContext(gcId);

        ByteBuffer data = xServer.getSHMSegmentManager().getData(shmseg);
        if (data == null) throw new BadSHMSegment(shmseg);

        if (graphicsContext.getFunction() != GraphicsContext.Function.COPY) {
            throw new UnsupportedOperationException("GC Function other than COPY is not supported.");
        }

        putImageTotal++;
        long nowMs = android.os.SystemClock.uptimeMillis();
        if (putImageProbeLogs < 3 || srcWidth != lastPutImageW || srcHeight != lastPutImageH
                || nowMs - lastPutImageLogMs >= 2000) {
            putImageProbeLogs++;
            lastPutImageW = srcWidth;
            lastPutImageH = srcHeight;
            lastPutImageLogMs = nowMs;
            try {
                StringBuilder head = new StringBuilder();
                if (data != null) {
                    int n = Math.min(8, data.capacity());
                    for (int i = 0; i < n; i++) head.append(String.format("%02x", data.get(i) & 0xff));
                }
                else head.append("<nodata>");
                Log.e("GraphicsDriver", "ShmPutImage: drawable=" + drawableId
                    + " " + srcWidth + "x" + srcHeight + " -> " + dstX + "," + dstY
                    + " depth=" + depth + " shmseg=" + shmseg + " head=" + head
                    + " n=" + putImageTotal);
            }
            catch (Exception e) {
                Log.e("GraphicsDriver", "ShmPutImage probe failed: " + e);
            }
        }

        if (drawable.isUseSharedData()) {
            synchronized (drawable.renderLock) {
                if (drawable.getData() != data) drawable.setData(data);
                drawable.forceUpdate();
            }
        }
        else {
            // Same VirGL/SHM race as Drawable.drawImage: when VirGL owns this
            // drawable (data == null after a flush), an SHM PutImage for the
            // same window must be skipped, not drawn. Drawing would either
            // crash (old code) or paint a black GDI fill over the 3D frame.
            synchronized (drawable.renderLock) {
                if (drawable.getData() == null) return;
            }
            drawable.drawImage(srcX, srcY, dstX, dstY, srcWidth, srcHeight, depth, data, totalWidth, totalHeight);
        }
    }

    private void createPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(4);
        int drawableId = inputStream.readInt();
        short width = inputStream.readShort();
        inputStream.skip(14);

        Drawable drawable = xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);

        drawable.setUseSharedData(width == drawable.width);
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.ATTACH :
                try (XLock lock = xServer.lock(XServer.Lockable.SHMSEGMENT_MANAGER)) {
                    attach(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.DETACH :
                try (XLock lock = xServer.lock(XServer.Lockable.SHMSEGMENT_MANAGER)) {
                    detach(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.PUT_IMAGE :
                try (XLock lock = xServer.lock(XServer.Lockable.SHMSEGMENT_MANAGER, XServer.Lockable.DRAWABLE_MANAGER, XServer.Lockable.GRAPHIC_CONTEXT_MANAGER)) {
                    putImage(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.CREATE_PIXMAP :
                try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
                    createPixmap(client, inputStream, outputStream);
                }
                break;
            default:
                throw new BadImplementation();
        }
    }
}
