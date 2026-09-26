package com.winlator.container;

import org.json.JSONException;
import org.json.JSONObject;

public class HUDConfig {
    public static final int ELEMENT_FPS = 1 << 0;
    public static final int ELEMENT_CPU = 1 << 1;
    public static final int ELEMENT_GPU = 1 << 2;
    public static final int ELEMENT_RAM = 1 << 3;
    public static final int ELEMENT_BATTERY = 1 << 4;
    public static final int ELEMENT_POWER = 1 << 5;
    public static final int ELEMENT_TEMP = 1 << 6;
    public static final int ELEMENT_RENDERER = 1 << 7;

    public static final int DEFAULT_ELEMENTS = ELEMENT_FPS | ELEMENT_CPU | ELEMENT_GPU | ELEMENT_RAM |
                                               ELEMENT_BATTERY | ELEMENT_POWER | ELEMENT_TEMP | ELEMENT_RENDERER;

    private int scale = 100;
    private int transparency = 0;
    private int elements = DEFAULT_ELEMENTS;
    private boolean horizontal = false;

    public HUDConfig() {}

    public HUDConfig(HUDConfig other) {
        if (other != null) {
            this.scale = other.scale;
            this.transparency = other.transparency;
            this.elements = other.elements;
            this.horizontal = other.horizontal;
        }
    }

    public int getScale() {
        return scale;
    }

    public void setScale(int scale) {
        this.scale = scale;
    }

    public int getTransparency() {
        return transparency;
    }

    public void setTransparency(int transparency) {
        this.transparency = transparency;
    }

    public int getElements() {
        return elements;
    }

    public void setElements(int elements) {
        this.elements = elements;
    }

    public boolean isElementEnabled(int elementBit) {
        return (elements & elementBit) != 0;
    }

    public void setElementEnabled(int elementBit, boolean enabled) {
        if (enabled) elements |= elementBit;
        else elements &= ~elementBit;
    }

    public boolean isHorizontal() {
        return horizontal;
    }

    public void setHorizontal(boolean horizontal) {
        this.horizontal = horizontal;
    }

    public JSONObject toJSONObject() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("scale", scale);
            obj.put("transparency", transparency);
            obj.put("elements", elements);
            obj.put("horizontal", horizontal);
        } catch (JSONException e) {}
        return obj;
    }

    public static HUDConfig fromJSONObject(JSONObject obj) {
        HUDConfig config = new HUDConfig();
        if (obj != null) {
            config.scale = obj.optInt("scale", 100);
            config.transparency = obj.optInt("transparency", 0);
            config.elements = obj.optInt("elements", DEFAULT_ELEMENTS);
            config.horizontal = obj.optBoolean("horizontal", false);
        }
        return config;
    }

    public String toJSONString() {
        return toJSONObject().toString();
    }

    public static HUDConfig fromJSONString(String json) {
        if (json == null || json.isEmpty()) return new HUDConfig();
        try {
            return fromJSONObject(new JSONObject(json));
        } catch (JSONException e) {
            return new HUDConfig();
        }
    }
}
