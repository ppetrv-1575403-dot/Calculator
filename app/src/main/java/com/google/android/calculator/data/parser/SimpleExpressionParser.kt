package com.google.android.calculator.data.parser

class SimpleExpressionParser {

    fun evaluate(expression: String): Double {
        val normalized = expression
            .replace("×", "*")
            .replace("÷", "/")
            .replace(" ", "")
        val tokens = tokenize(normalized)
        val (result, pos) = parseExpression(tokens, 0)
        if (pos != tokens.size) throw IllegalArgumentException("Unexpected token")
        return result
    }

    private fun tokenize(expression: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < expression.length) {
            val c = expression[i]
            when {
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < expression.length && (expression[i].isDigit() || expression[i] == '.')) i++
                    tokens.add(expression.substring(start, i))
                }
                c in "+-*/()" -> {
                    tokens.add(c.toString())
                    i++
                }
                else -> i++
            }
        }
        return tokens
    }

    private fun parseExpression(tokens: List<String>, pos: Int): Pair<Double, Int> {
        var (result, currentPos) = parseTerm(tokens, pos)
        while (currentPos < tokens.size && (tokens[currentPos] == "+" || tokens[currentPos] == "-")) {
            val op = tokens[currentPos]
            val (right, nextPos) = parseTerm(tokens, currentPos + 1)
            result = if (op == "+") result + right else result - right
            currentPos = nextPos
        }
        return result to currentPos
    }

    private fun parseTerm(tokens: List<String>, pos: Int): Pair<Double, Int> {
        var (result, currentPos) = parseFactor(tokens, pos)
        while (currentPos < tokens.size && (tokens[currentPos] == "*" || tokens[currentPos] == "/")) {
            val op = tokens[currentPos]
            val (right, nextPos) = parseFactor(tokens, currentPos + 1)
            result = if (op == "*") result * right else result / right
            currentPos = nextPos
        }
        return result to currentPos
    }

    private fun parseFactor(tokens: List<String>, pos: Int): Pair<Double, Int> {
        if (pos >= tokens.size) throw IllegalArgumentException("Unexpected end")
        val token = tokens[pos]
        if (token == "(") {
            val (result, nextPos) = parseExpression(tokens, pos + 1)
            if (nextPos >= tokens.size || tokens[nextPos] != ")") {
                throw IllegalArgumentException("Missing ')'")
            }
            return result to (nextPos + 1)
        }
        return token.toDouble() to (pos + 1)
    }
}