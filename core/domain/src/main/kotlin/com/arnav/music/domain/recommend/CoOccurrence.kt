package com.arnav.music.domain.recommend

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** One neighbour of an item, with the parts of its score kept for explanations. */
data class Neighbour<K>(
    val key: K,
    val score: Double,
    val cosine: Double,
    val ppmi: Double,
    /** P(next = key | current = query) from session order. */
    val markov: Double,
    /** Weighted number of sessions the two shared. */
    val support: Double,
)

/**
 * Item–item co-occurrence learned from listening sessions, for tracks or artists.
 *
 * Within a session two items co-occur with weight 1/distance when at most [window] positions apart
 * (the strongest single weight per pair per session is kept, so looping one album doesn't swamp
 * everything). Consecutive items also count as a directed transition (A → B) for the Markov part.
 *
 * Normalisations:
 * - cosine: c(a,b) / √(sessions(a)·sessions(b)), in 0..1;
 * - PPMI: max(0, ln(p(a,b) / (p(a)·p_α(b)))) with context smoothing α (0.75 = word2vec's choice,
 *   which damps the bias towards rare items);
 * - Markov: transitions(a→b) / transitions(a→·).
 *
 * Sessions are added incrementally with [addSession]; nothing needs recomputing afterwards.
 */
class CoOccurrence<K : Any>(val window: Int = 4, val alpha: Double = 0.75) {
    private val occ = HashMap<K, Double>()
    private val pairs = HashMap<K, HashMap<K, Double>>()
    private val marginal = HashMap<K, Double>()
    private val trans = HashMap<K, HashMap<K, Double>>()
    private val transOut = HashMap<K, Double>()
    private var total = 0.0
    private var smoothedNorm = -1.0

    var sessionCount = 0
        private set

    val items: Set<K> get() = occ.keys

    /** Adds one session: the items the listener kept (early skips already removed), in play order. */
    fun addSession(items: List<K>) {
        if (items.isEmpty()) return
        sessionCount++
        for (k in items.toHashSet()) occ.merge(k, 1.0, Double::plus)
        if (items.size < 2) return
        val best = HashMap<Pair<K, K>, Double>()
        for (i in items.indices) {
            val a = items[i]
            val end = minOf(items.lastIndex, i + window)
            for (j in i + 1..end) {
                val b = items[j]
                if (a == b) continue
                val w = 1.0 / (j - i)
                val ha = a.hashCode()
                val hb = b.hashCode()
                val aFirst = if (ha != hb) ha < hb else a.toString() <= b.toString()
                val key = if (aFirst) a to b else b to a
                val prev = best[key]
                if (prev == null || w > prev) best[key] = w
            }
            if (i < items.lastIndex) {
                val b = items[i + 1]
                if (a != b) {
                    trans.getOrPut(a) { HashMap() }.merge(b, 1.0, Double::plus)
                    transOut.merge(a, 1.0, Double::plus)
                }
            }
        }
        for ((p, w) in best) {
            pairs.getOrPut(p.first) { HashMap() }.merge(p.second, w, Double::plus)
            pairs.getOrPut(p.second) { HashMap() }.merge(p.first, w, Double::plus)
            marginal.merge(p.first, w, Double::plus)
            marginal.merge(p.second, w, Double::plus)
            total += 2 * w
        }
        smoothedNorm = -1.0
    }

    fun sessionsWith(a: K): Double = occ[a] ?: 0.0
    fun pair(a: K, b: K): Double = pairs[a]?.get(b) ?: 0.0
    fun transitions(a: K, b: K): Double = trans[a]?.get(b) ?: 0.0
    fun row(a: K): Map<K, Double> = pairs[a] ?: emptyMap()

    fun cosine(a: K, b: K): Double {
        val c = pair(a, b)
        if (c <= 0) return 0.0
        return (c / sqrt(sessionsWith(a) * sessionsWith(b))).coerceAtMost(1.0)
    }

    /** Pointwise mutual information (can be negative); 0 when the two never co-occurred. */
    fun pmi(a: K, b: K): Double {
        val c = pair(a, b)
        if (c <= 0 || total <= 0) return 0.0
        val ma = marginal[a] ?: return 0.0
        val mb = marginal[b] ?: return 0.0
        val pab = c / total
        val pa = ma / total
        val pb = if (alpha == 1.0) mb / total else mb.pow(alpha) / smoothedNorm()
        return ln(pab / (pa * pb))
    }

    fun ppmi(a: K, b: K): Double = max(0.0, pmi(a, b))

    fun markov(a: K, b: K): Double {
        val out = transOut[a] ?: return 0.0
        return (trans[a]?.get(b) ?: 0.0) / out
    }

    /** The [k] strongest neighbours of [a], strongest first (ties broken by key order for determinism). */
    fun neighbours(a: K, k: Int = 20, exclude: (K) -> Boolean = { false }): List<Neighbour<K>> {
        val keys = LinkedHashSet<K>()
        pairs[a]?.keys?.let(keys::addAll)
        trans[a]?.keys?.let(keys::addAll)
        if (keys.isEmpty()) return emptyList()
        val out = ArrayList<Neighbour<K>>(keys.size)
        for (b in keys) {
            if (b == a || exclude(b)) continue
            val c = pair(a, b)
            val cos = cosine(a, b)
            val p = ppmi(a, b)
            val m = markov(a, b)
            // Squash PPMI into 0..1 and shrink scores that rest on a single shared session.
            val raw = 0.45 * cos + 0.35 * (p / (p + 2.0)) + 0.20 * m
            val confidence = (c + transitions(a, b)) / (c + transitions(a, b) + 1.0)
            out += Neighbour(b, raw * confidence, cos, p, m, c)
        }
        out.sortWith(compareByDescending<Neighbour<K>> { it.score }.thenBy { it.key.toString() })
        return if (out.size > k) out.subList(0, k).toList() else out
    }

    private fun smoothedNorm(): Double {
        if (smoothedNorm < 0) smoothedNorm = marginal.values.sumOf { it.pow(alpha) }
        return smoothedNorm
    }
}
