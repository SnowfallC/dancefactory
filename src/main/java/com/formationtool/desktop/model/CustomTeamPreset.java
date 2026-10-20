package com.formationtool.desktop.model;

import java.util.List;

/** 本机人员模板，仅用于一次性创建舞者，不写入工程引用。 */
public record CustomTeamPreset(String name, List<Member> members) {
    public record Member(String name, String color) {}
}
