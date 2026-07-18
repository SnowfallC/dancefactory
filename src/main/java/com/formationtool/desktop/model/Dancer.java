package com.formationtool.desktop.model;

/**
 * Dancer model used by desktop editor.
 */
public class Dancer {
    private String id;
    private String name;
    private float x;
    private float y;
    private float z;
    private int color;
    private String customStylePath;
    private float rotation;
    private float scale;
    private String groupId;
    private transient boolean selected;

    public Dancer(String id, String name, float x, float y, int color) {
        this.id = id;
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = 0.5f;
        this.color = color;
        this.customStylePath = null;
        this.rotation = 0f;
        this.scale = 1f;
        this.selected = false;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public float getX() { return x; }
    public void setX(float x) { this.x = x; }

    public float getY() { return y; }
    public void setY(float y) { this.y = y; }

    public float getZ() { return z; }
    public void setZ(float z) { this.z = z; }

    public int getColor() { return color; }
    public void setColor(int color) { this.color = color; }

    public String getCustomStylePath() { return customStylePath; }
    public void setCustomStylePath(String customStylePath) { this.customStylePath = customStylePath; }

    public float getRotation() { return rotation; }
    public void setRotation(float rotation) { this.rotation = rotation; }

    public float getScale() { return scale; }
    public void setScale(float scale) { this.scale = scale; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public boolean isSelected() { return selected; }
    public void setSelected(boolean selected) { this.selected = selected; }

    public Dancer copy() {
        Dancer copy = new Dancer(this.id, this.name, this.x, this.y, this.color);
        copy.z = this.z;
        copy.customStylePath = this.customStylePath;
        copy.rotation = this.rotation;
        copy.scale = this.scale;
        copy.groupId = this.groupId;
        copy.selected = this.selected;
        return copy;
    }
}