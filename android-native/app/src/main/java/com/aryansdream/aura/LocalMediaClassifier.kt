package com.aryansdream.aura

import kotlin.math.exp
import kotlin.math.ln

/** Small metadata-only Naive Bayes classifier. Runs on-device; no model download or network call. */
object LocalMediaClassifier {
    data class Result(val category: String, val confidence: Double)
    private data class Model(
        val vocabulary: Set<String>,
        val counts: Map<String, Map<String, Int>>,
        val totals: Map<String, Int>,
        val documents: Map<String, Int>,
        val documentCount: Int
    )

    private val examples = mapOf(
        "study" to listOf(
            "calculus lecture algebra geometry physics chemistry biology statistics",
            "khan academy course lesson tutorial educational learning homework",
            "python programming coding software engineering github documentation",
            "study focus pomodoro concentration instrumental ambient study music",
            "notion task planning project notes productivity calendar",
            "google docs spreadsheet presentation research paper academic",
            "lecture university classroom exam revision flashcards language learning",
            "coursera udemy textbook audiobook skill training how to guide",
            "code editor terminal developer tools database design",
            "writing essay reading book science technology history",
            "lofi instrumental classical ambient focus music for study concentration",
            "productivity playlist background music lecture tutorial coding work"
        ),
        "entertainment" to listOf(
            "celebrity gossip drama reality show influencer vlog",
            "football soccer basketball highlights sports match live game",
            "gaming gameplay stream twitch esports walkthrough",
            "comedy funny prank reaction meme entertainment",
            "movie trailer series episode horror thriller romance",
            "pop music album playlist concert lyrics dance",
            "shorts reels viral trending social media",
            "news debate talk show celebrity interview",
            "cartoon anime fan edit compilation",
            "travel food review unboxing lifestyle haul",
            "music video song pop rap hip hop chart hit album concert lyrics dance",
            "comedy stand up sketch funny jokes parody sitcom humorous clips",
            "entertainment content music comedy celebrity vlog reaction video"
        )
    )

    private val model: Model by lazy { train() }

    fun classify(text: String): Result {
        val tokens = text.lowercase().split(Regex("[^a-z0-9]+"))
            .filter(String::isNotBlank)
        if (tokens.isEmpty()) return Result("unknown", 0.0)
        val scores = model.documents.keys.associateWith { category ->
            var score = ln(((model.documents[category] ?: 0) + 1.0) / (model.documentCount + 2.0))
            val counts = model.counts[category].orEmpty()
            val denominator = (model.totals[category] ?: 0) + model.vocabulary.size
            for (token in tokens) score += ln(((counts[token] ?: 0) + 1.0) / denominator)
            score
        }
        val max = scores.values.maxOrNull() ?: return Result("unknown", 0.0)
        val probabilities = scores.mapValues { exp(it.value - max) }
        val normalizer = probabilities.values.sum().coerceAtLeast(1e-12)
        val best = scores.maxByOrNull { it.value }?.key ?: return Result("unknown", 0.0)
        val confidence = (probabilities[best] ?: 0.0) / normalizer
        return Result(if (confidence < 0.58) "uncertain" else best, confidence)
    }

    private fun train(): Model {
        val vocabulary = mutableSetOf<String>()
        val counts = mutableMapOf<String, MutableMap<String, Int>>()
        val totals = mutableMapOf<String, Int>()
        val documents = mutableMapOf<String, Int>()
        var documentCount = 0
        examples.forEach { (category, documentsForCategory) ->
            documentsForCategory.forEach { sample ->
                documentCount++
                documents[category] = (documents[category] ?: 0) + 1
                sample.lowercase().split(Regex("[^a-z0-9]+"))
                    .filter(String::isNotBlank)
                    .forEach { token ->
                        vocabulary += token
                        val categoryCounts = counts.getOrPut(category) { mutableMapOf() }
                        categoryCounts[token] = (categoryCounts[token] ?: 0) + 1
                        totals[category] = (totals[category] ?: 0) + 1
                    }
            }
        }
        return Model(vocabulary, counts, totals, documents, documentCount)
    }
}
