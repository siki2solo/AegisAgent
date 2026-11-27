package com.aegis.localagent

data class SandboxResult(
    val success: Boolean,
    val stdout: String,
    val traceback: String?
)

class LocalSandboxEngine {

    fun executeScript(script: String): SandboxResult {
        val env = mutableMapOf<String, Double>()
        val out = mutableListOf<String>()
        val lines = script.lines()

        return try {
            lines.forEachIndexed { index, rawLine ->
                val lineNum = index + 1
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

                if (line.startsWith("print(") && line.endsWith(")")) {
                    val inner = line.removePrefix("print(").removeSuffix(")").trim()
                    val value = evalNumeric(inner, env, lineNum)
                    out.add(value.toString())
                } else if (line.contains("=")) {
                    val parts = line.split("=", limit = 2)
                    val key = parts[0].trim()
                    val rhs = parts[1].trim()
                    env[key] = evalNumeric(rhs, env, lineNum)
                } else {
                    evalNumeric(line, env, lineNum)
                }
            }
            SandboxResult(true, out.joinToString("\n").ifEmpty { "(no output)" }, null)
        } catch (err: Exception) {
            SandboxResult(
                false,
                out.joinToString("\n"),
                "Traceback (most recent call last):\n  File \"<aegis_sandbox>\", ${err.message}"
            )
        }
    }

    private fun evalNumeric(expr: String, env: Map<String, Double>, lineNum: Int): Double {
        val clean = expr.trim()
        clean.toDoubleOrNull()?.let { return it }
        env[clean]?.let { return it }

        for (op in listOf('+', '-', '*', '/')) {
            val idx = clean.lastIndexOf(op)
            if (idx > 0) {
                val left = evalNumeric(clean.substring(0, idx), env, lineNum)
                val right = evalNumeric(clean.substring(idx + 1), env, lineNum)
                return when (op) {
                    '+' -> left + right
                    '-' -> left - right
                    '*' -> left * right
                    '/' -> {
                        if (right == 0.0) {
                            throw ArithmeticException("ZeroDivisionError on line $lineNum")
                        }
                        left / right
                    }
                    else -> 0.0
                }
            }
        }
        throw IllegalArgumentException("NameError or SyntaxError on line $lineNum: $clean")
    }
}
