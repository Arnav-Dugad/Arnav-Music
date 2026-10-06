package com.arnav.music.domain.recommend

import kotlin.math.abs

/**
 * An undirected weighted graph in compressed-sparse-row form, built for random walks.
 * Node keys are namespaced strings: "t:<trackId>", "a:<artistKey>", "g:<genre>", "p:<playlist>", "e:<decade>".
 */
class WalkGraph private constructor(
    private val keys: Array<String>,
    private val index: HashMap<String, Int>,
    private val rowStart: IntArray,
    private val cols: IntArray,
    /** Row-normalised transition probabilities, aligned with [cols]. */
    private val probs: DoubleArray,
) {
    val size: Int get() = keys.size
    val edgeCount: Int get() = cols.size

    fun indexOf(key: String): Int = index[key] ?: -1
    fun key(i: Int): String = keys[i]
    fun degree(key: String): Int = indexOf(key).let { if (it < 0) 0 else rowStart[it + 1] - rowStart[it] }

    /**
     * Random walk with restart (personalised PageRank): at every step the walker jumps back to the
     * [restart] distribution with probability [restartProbability], otherwise follows an edge in
     * proportion to its weight. Dead ends teleport back to the restart set. The result sums to 1.
     */
    fun personalizedPageRank(
        restart: Map<String, Double>,
        restartProbability: Double = 0.3,
        iterations: Int = 30,
        tolerance: Double = 1e-7,
    ): DoubleArray {
        val n = keys.size
        val s = DoubleArray(n)
        var mass = 0.0
        for ((k, w) in restart) {
            val i = index[k] ?: continue
            if (w <= 0) continue
            s[i] += w; mass += w
        }
        if (mass <= 0 || n == 0) return DoubleArray(n)
        for (i in 0 until n) s[i] /= mass
        var r = s.copyOf()
        var next = DoubleArray(n)
        val a = restartProbability.coerceIn(0.01, 0.99)
        repeat(iterations) {
            java.util.Arrays.fill(next, 0.0)
            var dangling = 0.0
            for (u in 0 until n) {
                val ru = r[u]
                if (ru == 0.0) continue
                val from = rowStart[u]
                val to = rowStart[u + 1]
                if (from == to) { dangling += ru; continue }
                val f = (1 - a) * ru
                for (e in from until to) next[cols[e]] += f * probs[e]
            }
            val jump = a + (1 - a) * dangling
            var diff = 0.0
            for (i in 0 until n) {
                next[i] += jump * s[i]
                diff += abs(next[i] - r[i])
            }
            val t = r; r = next; next = t
            if (diff < tolerance) return r
        }
        return r
    }

    /** Scores of nodes whose key starts with [prefix] (prefix removed), skipping zeros. */
    fun scores(rank: DoubleArray, prefix: String): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (i in rank.indices) if (rank[i] > 0 && keys[i].startsWith(prefix)) out[keys[i].substring(prefix.length)] = rank[i]
        return out
    }

    /** Collects edges in flat arrays (parallel edges simply add up), then packs them into CSR. */
    class Builder {
        private val index = HashMap<String, Int>()
        private val keys = ArrayList<String>()
        private var src = IntArray(1024)
        private var dst = IntArray(1024)
        private var w = DoubleArray(1024)
        private var edges = 0

        fun node(key: String): Int = index.getOrPut(key) { keys += key; keys.size - 1 }

        /** Adds (or strengthens) the undirected edge a — b. */
        fun edge(a: String, b: String, weight: Double) {
            if (weight <= 0 || a == b) return
            val i = node(a); val j = node(b)
            if (edges + 2 > src.size) {
                val n = src.size * 2
                src = src.copyOf(n); dst = dst.copyOf(n); w = w.copyOf(n)
            }
            src[edges] = i; dst[edges] = j; w[edges] = weight; edges++
            src[edges] = j; dst[edges] = i; w[edges] = weight; edges++
        }

        fun build(): WalkGraph {
            val n = keys.size
            val rowStart = IntArray(n + 1)
            for (e in 0 until edges) rowStart[src[e] + 1]++
            for (i in 0 until n) rowStart[i + 1] += rowStart[i]
            val fill = rowStart.copyOf(n)
            val cols = IntArray(edges)
            val probs = DoubleArray(edges)
            val rowSum = DoubleArray(n)
            for (e in 0 until edges) {
                val p = fill[src[e]]++
                cols[p] = dst[e]; probs[p] = w[e]
                rowSum[src[e]] += w[e]
            }
            for (i in 0 until n) for (p in rowStart[i] until rowStart[i + 1]) probs[p] /= rowSum[i]
            return WalkGraph(keys.toTypedArray(), HashMap(index), rowStart, cols, probs)
        }
    }

    companion object {
        fun track(id: String) = "t:$id"
        fun artist(key: String) = "a:$key"
        fun genre(g: String) = "g:$g"
        fun playlist(id: String) = "p:$id"
        fun era(decade: Int) = "e:$decade"
    }
}
