package com.example.walkassist

import org.json.JSONObject
import java.io.File
import java.text.Normalizer

class Florence2Tokenizer(
    modelRoot: File,
) {
    private val vocab: Map<String, Int>
    private val idToToken: Map<Int, String>
    private val bpeRanks: Map<Pair<String, String>, Int>
    private val cache = mutableMapOf<String, List<String>>()
    private val byteEncoder = bytesToUnicode()
    private val byteDecoder = byteEncoder.entries.associate { (byteValue, encodedChar) -> encodedChar to byteValue }

    init {
        val vocabJson = JSONObject(File(modelRoot, "vocab.json").readText(Charsets.UTF_8))
        val mutableVocab = LinkedHashMap<String, Int>(vocabJson.length())
        vocabJson.keys().forEach { key ->
            mutableVocab[key] = vocabJson.getInt(key)
        }
        vocab = mutableVocab
        idToToken = mutableVocab.entries.associate { (token, id) -> id to token }

        val mergesFile = File(modelRoot, "merges.txt")
        bpeRanks = mergesFile.readLines(Charsets.UTF_8)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size == 2) parts[0] to parts[1] else null
            }
            .withIndex()
            .associate { indexed -> indexed.value to indexed.index }
    }

    fun encode(text: String): LongArray {
        val ids = mutableListOf<Long>()
        ids.add(BOS_TOKEN_ID.toLong())
        tokenPattern.findAll(text).forEach { match ->
            val token = byteEncode(match.value)
            ids.addAll(
                bpe(token).map { piece ->
                    vocab[piece]?.toLong()
                        ?: throw IllegalStateException("Florence-2 tokenizer missing vocab piece: $piece")
                },
            )
        }
        ids.add(EOS_TOKEN_ID.toLong())
        return ids.toLongArray()
    }

    fun decode(tokenIds: List<Int>): String {
        val encoded = buildString {
            tokenIds.forEach { id ->
                if (id in specialTokenIds) return@forEach
                val token = idToToken[id] ?: return@forEach
                if (token.startsWith("<loc_") || token.startsWith("</")) return@forEach
                append(token)
            }
        }
        val bytes = ByteArray(encoded.length)
        var size = 0
        encoded.forEach { char ->
            val value = byteDecoder[char]
            if (value != null) {
                bytes[size++] = value.toByte()
            }
        }
        return String(bytes.copyOf(size), Charsets.UTF_8)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        if (token.isEmpty()) return emptyList()

        var word = token.map { it.toString() }.toMutableList()
        while (word.size > 1) {
            val best = getPairs(word).minByOrNull { pair ->
                bpeRanks[pair] ?: Int.MAX_VALUE
            } ?: break
            if (!bpeRanks.containsKey(best)) break

            val nextWord = mutableListOf<String>()
            var index = 0
            while (index < word.size) {
                if (index < word.lastIndex && word[index] == best.first && word[index + 1] == best.second) {
                    nextWord.add(best.first + best.second)
                    index += 2
                } else {
                    nextWord.add(word[index])
                    index += 1
                }
            }
            word = nextWord
        }

        val result = word.toList()
        cache[token] = result
        return result
    }

    private fun getPairs(word: List<String>): Set<Pair<String, String>> {
        if (word.size < 2) return emptySet()
        return (0 until word.lastIndex).mapTo(mutableSetOf()) { index ->
            word[index] to word[index + 1]
        }
    }

    private fun byteEncode(text: String): String {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        val bytes = normalized.toByteArray(Charsets.UTF_8)
        return buildString(bytes.size) {
            bytes.forEach { byte ->
                append(byteEncoder[byte.toInt() and 0xFF])
            }
        }
    }

    private fun bytesToUnicode(): Map<Int, Char> {
        val bs = mutableListOf<Int>()
        bs.addAll('!'.code..'~'.code)
        bs.addAll(0xA1..0xAC)
        bs.addAll(0xAE..0xFF)

        val cs = bs.toMutableList()
        var next = 0
        for (byteValue in 0..255) {
            if (byteValue !in bs) {
                bs.add(byteValue)
                cs.add(256 + next)
                next += 1
            }
        }
        return bs.zip(cs).associate { (byteValue, codePoint) -> byteValue to codePoint.toChar() }
    }

    companion object {
        const val BOS_TOKEN_ID = 0
        const val PAD_TOKEN_ID = 1
        const val EOS_TOKEN_ID = 2
        private val specialTokenIds = setOf(BOS_TOKEN_ID, PAD_TOKEN_ID, EOS_TOKEN_ID, 3)
        private val tokenPattern = Regex(
            "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+",
        )
    }
}
