// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.debugger.core

internal class MetadataParser(input: CharArray) : Parser(input) {
    companion object {
        private const val EQUALS = '='
        private const val COMMA = ','
        private const val LBRACE = '{'
        private const val RBRACE = '}'
        private const val QMARK = '"'

        private val escapeChars = arrayOf('\t', '\b', '\n', '\r', /* \f */ '\u000c', '\'', '\"', '\\')
        private val escapeCharsToByte = escapeChars.associateBy({ it }, { it.code.toByte() })
    }

    class MetadataBuilder(
        var kind: Int? = null,
        var metadataVersion: IntArray? = null,
        var bytecodeVersion: IntArray? = null,
        var data1: Array<String>? = null,
        var data2: Array<String>? = null,
        var extraString: String? = null,
        var packageName: String? = null,
        var extraInt: Int? = null,
    ) {
        fun toMetadata(): Metadata? {
            return Metadata(
                kind ?: return null,
                metadataVersion ?: return null,
                bytecodeVersion ?: return null,
                data1 ?: return null,
                data2 ?: return null,
                extraString ?: return null,
                packageName ?: return null,
                extraInt ?: return null
            )
        }
    }

    fun parseMetadata(): Metadata? {
        val builder = MetadataBuilder()
        while (currentChar != null) {
            val token = parseStringUntil(EQUALS)
            advanceIf(EQUALS) ?: return null

            when (token) {
                "bv" -> {
                    builder.bytecodeVersion = parseIntArray() ?: return null
                }
                "d1" -> {
                    advanceIf(LBRACE) ?: return null
                    advanceIf(QMARK) ?: return null
                    val string = parseStringUntil(RBRACE)
                    advanceIf(RBRACE) ?: return null
                    builder.data1 = arrayOf(string.dropLast(1)) //BitEncoding.encodeBytes(BitEncoding.decodeBytes(arrayOf(string)))
                }
                "d2" -> {
                    builder.data2 = parseStringArray() ?: return null
                }
                "k" -> {
                    builder.kind = parseIntUntil(COMMA) ?: return null
                }
                "mv" -> {
                    builder.metadataVersion = parseIntArray() ?: return null
                }
                "pn" -> {
                    builder.packageName = parseStringUntil(COMMA)
                }
                "xi" -> {
                    builder.extraInt = parseIntUntil(COMMA)
                }
                "xs" -> {
                    builder.extraString = parseStringUntil(COMMA)
                }
                else -> return null
            }

            advanceIf(COMMA) ?: run {
                if (currentChar != null) {
                    return null
                }
            }
        }

        return builder.toMetadata()
    }

    private fun parseStringArray(): Array<String>? {
        advanceIf(LBRACE) ?: return null
        val result = mutableListOf<String>()
        while (currentChar != null) {
            while (currentChar!!.isWhitespace()) {
                advance()
            }

            advanceIf(QMARK) ?: break
            val str = parseStringUntil(QMARK)
            advanceIf(QMARK) ?: return null
            result.add(str)
            if (currentChar == RBRACE) {
                break
            }
            advance()
        }
        advanceIf(RBRACE) ?: return null
        return result.toTypedArray()
    }

    private fun parseIntArray(): IntArray? {
        advanceIf(LBRACE) ?: return null
        val result = mutableListOf<Int>()
        while (currentChar != null) {
            val str = parseStringUntil { it == COMMA || it == RBRACE }
            val item = str.toIntOrNull() ?: return null
            result.add(item)
            if (currentChar == RBRACE) {
                break
            }
            advance()
        }
        advanceIf(RBRACE) ?: return null
        return result.toIntArray()
    }

    private fun parseIntUntil(char: Char): Int? {
        return parseStringUntil(char).toIntOrNull()
    }

    private fun parseStringUntil(char: Char): String {
        return parseStringUntil { it == char }
    }

    private fun parseSingleUTF8StringArray(): String? {
        advanceIf(LBRACE) ?: return null
        advanceIf(QMARK) ?: return null
        val bytes = mutableListOf<Byte>()
        while (currentChar != null && currentChar != RBRACE) {
            when (val char = currentChar!!) {
                '\\' -> {
                    advance()
                    if (currentChar == 'u') {
                        for (i in 0 until 4) {
                            advance()
                            val byteChar = currentChar ?: return null
                            if (!byteChar.isHexDigit()) {
                                return null
                            }
                        }

                    } else {
                        val byte = escapeCharsToByte[currentChar] ?: return null
                        bytes.add(byte)
                    }
                }
                '"' -> {
                    advance()
                    break
                }
                else -> bytes.add(char.code.toByte())
            }
        }
        advanceIf(RBRACE) ?: return null
        return null
    }

    private fun parseStringUntil(predicate: (Char) -> Boolean): String {
        val token = StringBuilder()
        while (currentChar != null) {
            val char = currentChar!!
            when {
                predicate(char) -> break
                else -> {
                    if (!char.isWhitespace()) {
                        token.append(char)
                    }
                    advance()
                }
            }
        }
        return token.toString()
    }
}

internal abstract class Parser(private val input: CharArray) {
    protected val currentChar: Char?
        get() = input.getOrNull(index)

    private var index = 0

    protected fun advance(): Char? =
        currentChar.also { index += 1 }

    protected fun advanceIf(char: Char): Char? {
        if (currentChar == char) {
            return advance()
        }
        return null
    }
}

private fun Char.isHexDigit(): Boolean {
    return this.isDigit() || this in ('a'..'f')
}
