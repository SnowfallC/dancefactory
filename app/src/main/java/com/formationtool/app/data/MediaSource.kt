package com.formationtool.app.data

import java.io.File

/** 同一媒体的内部路径无需重复复制；选择新来源必须重新持久化。 */
internal fun shouldCopyMedia(uri: String?, path: String?, sourceUri: String?): Boolean {
    if (uri.isNullOrBlank()) return false
    if (path.isNullOrBlank() || !File(path).isFile) return true
    if (uri == File(path).toURI().toString() || uri == "file://$path") return false
    return sourceUri != uri
}
