package com.formationtool.desktop.model;

/**
 * 道具数据模型（与Android端兼容）
 */
public class Prop {
    private String id;
    private String name;
    private float x;
    private float y;
    private float z;
    private float width;
    private float height;
    private float rotation;
    private int color;
    private String type;  // PropType枚举值

    public Prop(String id, String name) {
        this.id = id;
        this.name = name;
        this.x = 0f;
        this.y = 0f;
        this.z = 0f;
        this.width = 1f;
        this.height = 1f;
        this.rotation = 0f;
        this.color = 0xFFFF9800;
        this.type = "RECTANGLE";
    }

    // Getters and Setters
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

    public float getWidth() { return width; }
    public void setWidth(float width) { this.width = width; }

    public float getHeight() { return height; }
    public void setHeight(float height) { this.height = height; }

    public float getRotation() { return rotation; }
    public void setRotation(float rotation) { this.rotation = rotation; }

    public int getColor() { return color; }
    public void setColor(int color) { this.color = color; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
}
