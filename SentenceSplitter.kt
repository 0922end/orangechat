package me.rerere.rikkahub.voice

class SentenceSplitter(
    private val minLengthAtComma: Int = 15,
    private val maxLength: Int = 80,
    private val onSentence: (String) -> Unit
) {
    private val buffer = StringBuilder()
    private val sentenceEnders = setOf('\u3002', '\uFF01', '\uFF1F', '\uFF1B', '\u2026')
    private val englishEnders = setOf('.', '!', '?', ';')
    private val commaLike = setOf('\uFF0C', '\u3001', ',', '\u2014', '\u2013')

    fun feed(text: String) {
        for (ch in text) {
            buffer.append(ch)
            if (ch == '\n') { emitBuffer(); continue }
            if (ch in sentenceEnders || ch in englishEnders) { emitBuffer(); continue }
            if (ch in commaLike && buffer.length >= minLengthAtComma) { emitBuffer(); continue }
            if (buffer.length >= maxLength) { emitBuffer(); continue }
        }
    }
    fun flush() { emitBuffer() }
    fun reset() { buffer.clear() }
    private fun emitBuffer() { val s = buffer.toString().trim(); buffer.clear(); if (s.isNotEmpty()) onSentence(s) }
}