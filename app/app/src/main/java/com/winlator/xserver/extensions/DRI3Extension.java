package com.winlator.xserver.extensions;

import static com.winlator.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.Log;

import com.winlator.core.Callback;
import com.winlator.renderer.GPUImage;
import com.winlator.sysvshm.SysVSharedMemory;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xconnector.XInputStream;
import com.winlator.xconnector.XOutputStream;
import com.winlator.xconnector.XStreamLock;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.Pixmap;
import com.winlator.xserver.Window;
import com.winlator.xserver.XClient;
import com.winlator.xserver.XLock;
import com.winlator.xserver.XServer;
import com.winlator.xserver.errors.BadAlloc;
import com.winlator.xserver.errors.BadDrawable;
import com.winlator.xserver.errors.BadIdChoice;
import com.winlator.xserver.errors.BadImplementation;
import com.winlator.xserver.errors.BadPixmap;
import com.winlator.xserver.errors.BadWindow;
import com.winlator.xserver.errors.XRequestError;

import java.io.IOException;
import java.nio.ByteBuffer;

public class DRI3Extension extends Extension {
    public static final byte MAJOR_VERSION = 1;
    // 1.2 advertises PixmapFromBuffers + GetSupportedModifiers. Mesa 26 WSI
    // clients (notably lavapipe, which exposes
    // VK_EXT_image_drm_format_modifier) query supported modifiers before
    // creating swapchains; without the 1.2 version AND the handler below,
    // that query dies as an X error and the swapchain (hence every DXVK /
    // VKD3D frame) never materializes -- black screen with a correctly
    // enumerating ICD. The handler reports zero modifiers (linear-only SHM
    // presentation, which is all this SHM-backed server implements).
    public static final byte MINOR_VERSION = 2;
    private final Callback<Drawable> onDestroyDrawableListener = (drawable) -> {
        ByteBuffer data = drawable.getData();
        SysVSharedMemory.unmapSHMSegment(data, data.capacity());
    };

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte OPEN = 1;
        private static final byte PIXMAP_FROM_BUFFER = 2;
        private static final byte BUFFER_FROM_PIXMAP = 3;
        private static final byte GET_SUPPORTED_MODIFIERS = 6;
        private static final byte PIXMAP_FROM_BUFFERS = 7;
    }

    public DRI3Extension(XServer xServer, byte majorOpcode) {
        super(xServer, majorOpcode);
    }

    @Override
    public String getName() {
        return "DRI3";
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

    private void open(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int drawableId = inputStream.readInt();
        inputStream.skip(4);

        Drawable drawable = xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) throw new BadDrawable(drawableId);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writePad(24);
        }
    }

    private void pixmapFromBuffer(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int pixmapId = inputStream.readInt();
        int windowId = inputStream.readInt();
        int size = inputStream.readInt();
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        short stride = inputStream.readShort();
        byte depth = inputStream.readByte();
        inputStream.skip(1);

        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        Pixmap pixmap = xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap != null) throw new BadIdChoice(pixmapId);

        int fd = inputStream.getAncillaryFd();
        pixmapFromFd(client, pixmapId, width, height, stride, 0, depth, fd, size);
    }

    private void bufferFromPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();

        Window window = xServer.windowManager.getWindow(windowId);
        Drawable content;
        if (window == null) {
            Pixmap pixmap = xServer.pixmapManager.getPixmap(windowId);
            if (pixmap == null) throw new BadPixmap(windowId);
            content = pixmap.drawable;
        }
        else content = window.getContent();

        GPUImage texture = GPUImage.createOrObtain(xServer, content, false, true);
        short stride = texture.getStride();
        int nativeHandle = texture.getNativeHandle();

        xServer.debugPrint("bufferFromPixmap handle "+nativeHandle+", width "+content.width+", height "+content.height+", stride "+stride);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)1);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(stride * content.height * 4);
            outputStream.writeShort(content.width);
            outputStream.writeShort(content.height);
            outputStream.writeShort(stride);
            outputStream.writeByte((byte)32);
            outputStream.writeByte((byte)32);
            outputStream.writePad(12);
            outputStream.setAncillaryFd(nativeHandle);
        }
    }

    private void pixmapFromBuffers(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int pixmapId = inputStream.readInt();
        int windowId = inputStream.readInt();
        inputStream.skip(4);
        short width = inputStream.readShort();
        short height = inputStream.readShort();
        int stride = inputStream.readInt();
        int offset = inputStream.readInt();
        inputStream.skip(24);
        byte depth = inputStream.readByte();
        inputStream.skip(11);

        Window window = xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        Pixmap pixmap = xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap != null) throw new BadIdChoice(pixmapId);

        int fd = inputStream.getAncillaryFd();
        long size = (long)stride * height;
        pixmapFromFd(client, pixmapId, width, height, stride, offset, depth, fd, size);
    }

    private void pixmapFromFd(XClient client, int pixmapId, short width, short height, int stride, int offset, byte depth, int fd, long size)  throws IOException, XRequestError {
        ByteBuffer buffer = SysVSharedMemory.mapSHMSegment(fd, size, offset, true);
        try {
            if (buffer == null) throw new BadAlloc();
            // Frame-path probe (GraphicsDriver tag, ERROR level so it shows
            // in filtered logcats): one line per imported pixmap. Imports
            // happen at swapchain/window setup, so volume is a handful of
            // lines per run. If frames stay black, this line tells whether
            // the import itself succeeded (fd valid, mapping ok) -- a
            // FAILED line (or its absence) with failing presents points at
            // the import; successful imports with zero head bytes on the
            // Present side point at the driver rendering nothing.
            Log.e("GraphicsDriver", "DRI3 import pixmap=" + pixmapId
                + " " + width + "x" + height + " depth=" + depth
                + " stride=" + stride + " offset=" + offset + " size=" + size
                + " fd=" + fd + " mapped=true");

            short totalWidth = (short)(stride / 4);
            Drawable drawable = xServer.drawableManager.createDrawable(pixmapId, totalWidth, height, depth);
            drawable.setData(buffer);
            drawable.setTexture(null);
            drawable.setOnDestroyListener(onDestroyDrawableListener);
            xServer.pixmapManager.createPixmap(drawable);
        }
        catch (XRequestError e) {
            Log.e("GraphicsDriver", "DRI3 import pixmap=" + pixmapId
                + " FAILED " + e.getClass().getSimpleName()
                + " (" + width + "x" + height + " depth=" + depth
                + " stride=" + stride + " size=" + size + " fd=" + fd + ")");
            if (buffer != null) SysVSharedMemory.unmapSHMSegment(buffer, size);
            throw e;
        }
        finally {
            XConnectorEpoll.closeFd(fd);
        }
    }

    private void getSupportedModifiers(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException {
        int window = inputStream.readInt();
        int depth = inputStream.readByte() & 0xFF;
        int bpp = inputStream.readByte() & 0xFF;
        inputStream.skip(2);

        int numWindowModifiers = 0;
        int numScreenModifiers = 0;
        // Zero modifiers means the additional reply body is empty, so its
        // length (in 4-byte words past the 32-byte reply header) is 0. The
        // 16 pad bytes below complete the header itself. (A nonzero length
        // with a body of zeroes misaligns strict Mesa 26 WSI parsers
        // reading the modifier arrays, which surfaces as swapchain failure
        // -- black frames from a correctly enumerating lavapipe ICD.)
        int length = 0;

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte) 0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(length);
            outputStream.writeInt(numWindowModifiers);
            outputStream.writeInt(numScreenModifiers);
            outputStream.writePad(16);
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.OPEN :
                try (XLock lock = xServer.lock(XServer.Lockable.DRAWABLE_MANAGER)) {
                    open(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.PIXMAP_FROM_BUFFER:
                try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                    pixmapFromBuffer(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.BUFFER_FROM_PIXMAP:
                try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                    bufferFromPixmap(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.GET_SUPPORTED_MODIFIERS:
                getSupportedModifiers(client, inputStream, outputStream);
                break;
            case ClientOpcodes.PIXMAP_FROM_BUFFERS:
                try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER, XServer.Lockable.DRAWABLE_MANAGER)) {
                    pixmapFromBuffers(client, inputStream, outputStream);
                }
                break;
            default:
                throw new BadImplementation();
        }
    }
}
