package com.formationtool.app.model

/** 发布给渲染线程的队形与编辑集合完全分离。 */
fun Formation.renderSnapshot(): Formation = copy(
    dancers = dancers.map { it.copy() }.toMutableList(),
    props = props.map { it.copy() }.toMutableList()
)
