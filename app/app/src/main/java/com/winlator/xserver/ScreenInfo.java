package com.winlator.xserver;

import android.util.Rational;

import com.winlator.math.Mathf;

public class ScreenInfo {
    public static final short MIN_WIDTH = 320;
    public static final short MIN_HEIGHT = 160;
    public final short width;
    public final short height;
    // Selects the GLX protocol personality: true (default) serves the full
    // Mesa GLX dialect that Mesa client drivers (llvmpipe/zink/virgl/turnip
    // xlib builds) require -- rich FB configs, version-agnostic context
    // creation, MakeCurrent/IsDirect/SwapBuffers/GetString/CreateWindow and
    // friends. False serves the minimal host-GPU (Gladio) dialect backed by
    // native GLES contexts, which only supports GL <= 3.3. Without the Mesa
    // personality every Mesa GL client dies on unimplemented GLX opcodes or
    // the version gate (blank GPUInfo, no wined3d rendering). The llvmpipe /
    // zink / virgl / turnip slots therefore run with the default true; only
    // the Gladio OpenGL slot sets this to false.
    private boolean mesaDriverMode = true;

    public ScreenInfo(String value) {
        String[] parts = value.split("x");
        width = Short.parseShort(parts[0]);
        height = Short.parseShort(parts[1]);
    }

    public ScreenInfo(int width, int height) {
        this.width = (short)width;
        this.height = (short)height;
    }

    public short getWidthInMillimeters() {
        return (short)(width / 10);
    }

    public short getHeightInMillimeters() {
        return (short)(height / 10);
    }

    public Rational aspectRatio() {
        return Mathf.farey((float)width / height, 10);
    }

    @Override
    public String toString() {
        return width+"x"+height;
    }

    public boolean getMesaDriverMode() {
        return mesaDriverMode;
    }

    public void setMesaDriverMode(boolean mesaDriverMode) {
        this.mesaDriverMode = mesaDriverMode;
    }
}
