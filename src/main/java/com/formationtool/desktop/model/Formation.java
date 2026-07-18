package com.formationtool.desktop.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 队形数据模型
 *
 * 时间轴结构（前红后绿）：
 * [前一帧绿色区结束] --[红色区 duration]--> [K @ timestamp] --[绿色区 holdDuration]--> [绿色区结束]
 *
 * - duration: 前面的红色区长度（从前一帧绿色区结束到当前帧timestamp的过渡时间）
 * - holdDuration: 后面的绿色区长度（从当前帧timestamp开始的保持时间）
 * - 红色区范围: [timestamp - duration, timestamp)
 * - 绿色区范围: [timestamp, timestamp + holdDuration)
 */
public class Formation {
    private String id;
    private String name;
    private List<Dancer> dancers;
    private List<Prop> props;  // 道具列表（与Android端兼容）
    private long timestamp;  // 音乐时间戳(毫秒) - 关键帧时间点（红绿分界点）
    private long holdDuration;  // 后面的绿色区长度(毫秒) - 从timestamp开始的保持时间
    private long duration;  // 前面的红色区长度(毫秒) - 从前一帧绿色区结束到timestamp的过渡时间
    private String notes;  // 注释

    public Formation(String id, String name, long timestamp) {
        this.id = id;
        this.name = name;
        this.timestamp = timestamp;
        this.holdDuration = 4000L;  // 默认后面绿色区4秒
        this.duration = 0L;  // 默认前面红色区0秒（第一帧无前红区）
        this.dancers = new ArrayList<>();
        this.props = new ArrayList<>();
        this.notes = "";
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<Dancer> getDancers() { return dancers; }
    public void setDancers(List<Dancer> dancers) { this.dancers = dancers; }

    public List<Prop> getProps() { return props; }
    public void setProps(List<Prop> props) { this.props = props; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public long getHoldDuration() { return holdDuration; }
    public void setHoldDuration(long holdDuration) { this.holdDuration = holdDuration; }

    public long getDuration() { return duration; }
    public void setDuration(long duration) { this.duration = duration; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    // 创建深拷贝
    public Formation copy() {
        Formation copy = new Formation(this.id, this.name, this.timestamp);
        copy.holdDuration = this.holdDuration;
        copy.duration = this.duration;
        copy.notes = this.notes;
        for (Dancer dancer : this.dancers) {
            copy.dancers.add(dancer.copy());
        }
        // 注意：Props暂时不复制，需要时再添加
        return copy;
    }
}
