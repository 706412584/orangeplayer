/*
 * 媒体缓存进度模型（复刻 animeko app-data 的 MediaCacheProgressProvider.kt）。
 *
 * 差异：原版用 kotlinx.collections.immutable 的 FloatList，这里改为 FloatArray，
 * 避免为一个只读模型引入额外依赖。UI 只读访问，语义等价。
 */
@file:Suppress("unused")

package me.him188.ani.app.domain.media.player

import androidx.compose.runtime.Immutable

/**
 * 缓存进度：按区块（chunk）描述已缓存范围。
 *
 * @param chunkWeights 每个区块的权重（宽度占比），所有元素之和应约为 1
 * @param chunkStates 每个区块的状态，长度须与 [chunkWeights] 相等
 */
@Immutable
data class MediaCacheProgressInfo(
    val chunkWeights: FloatArray,
    val chunkStates: List<ChunkState>,
) {
    init {
        require(chunkWeights.size == chunkStates.size) {
            "chunkWeights.size (${chunkWeights.size}) != chunkStates.size (${chunkStates.size})"
        }
    }

    val size: Int get() = chunkWeights.size
    val lastIndex: Int get() = chunkWeights.size - 1

    fun isEmpty(): Boolean = chunkWeights.isEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaCacheProgressInfo) return false
        return chunkWeights.contentEquals(other.chunkWeights) && chunkStates == other.chunkStates
    }

    override fun hashCode(): Int = 31 * chunkWeights.contentHashCode() + chunkStates.hashCode()

    companion object {
        val Empty = MediaCacheProgressInfo(
            chunkWeights = FloatArray(0),
            chunkStates = emptyList(),
        )
    }
}

/** 区块状态。 */
enum class ChunkState {
    /** 初始状态 */
    NONE,

    /** 正在下载 */
    DOWNLOADING,

    /** 下载完成 */
    DONE,

    /** 没有任何 peer 拥有该分片 */
    NOT_AVAILABLE,
}
