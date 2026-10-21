package com.formationtool.desktop.utils;

import java.math.BigDecimal;

/** 精确时间输入，支持秒数及分:秒.毫秒。 */
public final class TimeInput {
    private TimeInput() {}

    public static long parse(String value) {
        String[] parts = value.trim().split(":", -1);
        if (parts.length < 1 || parts.length > 2) throw new IllegalArgumentException("时间格式无效");
        for (String part : parts) {
            if (!part.matches("[0-9]+(?:\\.[0-9]{1,3})?")) throw new IllegalArgumentException("时间格式无效");
        }
        BigDecimal seconds = new BigDecimal(parts[parts.length - 1]);
        if (parts.length == 2) {
            if (!parts[0].matches("[0-9]+") || seconds.compareTo(BigDecimal.valueOf(60)) >= 0)
                throw new IllegalArgumentException("秒数必须小于 60");
            seconds = new BigDecimal(parts[0]).multiply(BigDecimal.valueOf(60)).add(seconds);
        }
        return seconds.multiply(BigDecimal.valueOf(1000)).longValueExact();
    }
}
