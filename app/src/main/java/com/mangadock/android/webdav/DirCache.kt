package com.mangadock.android.webdav

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * WebDAV 目录列表的磁盘缓存。
 *
 * iOS 那边反复踩过两次的坑：**先发网络请求、再读本地缓存** —— 冷启动时本地明明
 * 有列表，却要干等一个网络超时才显示。正确顺序是
 * **本地缓存 → 立刻显示 → 后台刷新覆盖**。这个对象只负责存和取，顺序由调用方保证。
 *
 * 缓存按「服务器 + 账号 + 路径」隔离，换服务器不会串目录。
 */
class DirCache(ctx: Context) {

    private val dir = File(ctx.cacheDir, "dircache").apply { if (!exists()) mkdirs() }

    /** 把可能含中文/斜杠的 key 压成安全文件名。 */
    fun key(base: String, user: String, path: String): String {
        val raw = "$base|$user|$path"
        val md = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    fun read(key: String): List<DavEntry>? = runCatching {
        val f = File(dir, "$key.json")
        if (!f.isFile) return null
        val arr = JSONObject(f.readText()).getJSONArray("items")
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            DavEntry(
                name = o.getString("n"),
                path = o.getString("p"),
                isDir = o.getBoolean("d"),
                size = o.optLong("s", 0L),
                mtime = o.optString("m", "")
            )
        }
    }.getOrNull()

    fun write(key: String, entries: List<DavEntry>) {
        runCatching {
            // 「清空缓存」可能把整个 dircache 目录删掉，而本对象是 remember 住的，
            // 构造时的 mkdirs 不会再跑一次。写之前补一下，否则 runCatching 会静默吞掉
            // FileNotFoundException，之后就再也不落缓存了。
            if (!dir.exists()) dir.mkdirs()
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(JSONObject().apply {
                    put("n", e.name)
                    put("p", e.path)
                    put("d", e.isDir)
                    put("s", e.size)
                    put("m", e.mtime)
                })
            }
            val body = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("items", arr)
            }.toString()

            val f = File(dir, "$key.json")
            val tmp = File(dir, "$key.json.part")
            tmp.writeText(body)
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }
    }
}
