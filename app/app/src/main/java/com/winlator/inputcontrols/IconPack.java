package com.winlator.inputcontrols;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class IconPack implements Comparable<IconPack> {
    public final String id;
    private String name;
    private final ArrayList<String> iconNames = new ArrayList<>();
    private final List<String> immutableIconNames = Collections.unmodifiableList(iconNames);

    public IconPack(String id, String name) {
        this.id = id != null ? id : "";
        this.name = name != null ? name : id;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name != null ? name : "";
    }

    public List<String> getIconNames() {
        return immutableIconNames;
    }

    public int getIconCount() {
        return iconNames.size();
    }

    void setIconNames(List<String> names) {
        iconNames.clear();
        if (names != null) iconNames.addAll(names);
        Collections.sort(iconNames);
    }

    void addIconName(String name) {
        if (name == null || name.isEmpty() || iconNames.contains(name)) return;
        iconNames.add(name);
        Collections.sort(iconNames);
    }

    void removeIconName(String name) {
        iconNames.remove(name);
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public int compareTo(IconPack o) {
        return String.valueOf(name).compareToIgnoreCase(String.valueOf(o.name));
    }
}
