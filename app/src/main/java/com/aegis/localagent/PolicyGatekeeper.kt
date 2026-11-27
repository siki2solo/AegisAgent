package com.aegis.localagent

enum class PolicyVerdict {
    ALLOWED,
    NEEDS_HITL,
    BLOCKED_BLACKLIST,
    BLOCKED_SCOPE
}

enum class RiskTier {
    READ_ONLY,
    LOCAL_REVERSIBLE,
    CONSEQUENTIAL
}

data class TaskScope(
    val allowedPackages: Set<String>,
    val isUntrustedScreen: Boolean
)

data class PolicyDecision(
    val verdict: PolicyVerdict,
    val riskTier: RiskTier,
    val reason: String
)

object HardcodedBlacklist {
    val immutablePackages: Set<String> = setOf(
        "com.google.android.apps.nbu.paisa.user",
        "com.phonepe.app",
        "net.one97.paytm",
        "com.x8bit.bitwarden",
        "com.onepassword.android",
        "com.google.android.apps.authenticator2"
    )
}

object InputSanitizer {
    fun stripInvisibleUnicode(raw: String): Pair<String, Int> {
        if (raw.isEmpty()) return "" to 0
        var stripped = 0
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val charCount = Character.charCount(cp)
            val isZeroWidth = cp in 0x200B..0x200F || cp == 0xFEFF || cp == 0x2060
            val isBidi = cp in 0x202A..0x202E || cp in 0x2066..0x2069
            val isTag = cp in 0xE0000..0xE007F
            if (isZeroWidth || isBidi || isTag) {
                stripped += 1
            } else {
                out.appendCodePoint(cp)
            }
            i += charCount
        }
        return out.toString() to stripped
    }
}

class PolicyGatekeeper(
    private val customBlacklist: MutableSet<String> = mutableSetOf(),
    var enforceRuleOfTwo: Boolean = true,
    var stripUnicodeEnabled: Boolean = true
) {
    fun isBlacklisted(packageName: String): Boolean {
        return HardcodedBlacklist.immutablePackages.contains(packageName) ||
            customBlacklist.contains(packageName)
    }

    fun evaluate(
        targetPackage: String,
        actionKind: String,
        scope: TaskScope
    ): PolicyDecision {
        if (isBlacklisted(targetPackage)) {
            return PolicyDecision(
                verdict = PolicyVerdict.BLOCKED_BLACKLIST,
                riskTier = RiskTier.CONSEQUENTIAL,
                reason = "Blocked by immutable safety blacklist: $targetPackage"
            )
        }
        if (scope.allowedPackages.isNotEmpty() && !scope.allowedPackages.contains(targetPackage)) {
            return PolicyDecision(
                verdict = PolicyVerdict.BLOCKED_SCOPE,
                riskTier = RiskTier.CONSEQUENTIAL,
                reason = "Outside locked TaskScope: $targetPackage"
            )
        }
        val upper = actionKind.uppercase()
        val consequential = upper in setOf("SEND", "PAY", "DELETE", "POST", "SHARE")
        if (scope.isUntrustedScreen && enforceRuleOfTwo && consequential) {
            return PolicyDecision(
                verdict = PolicyVerdict.NEEDS_HITL,
                riskTier = RiskTier.CONSEQUENTIAL,
                reason = "Rule-of-Two paused $upper on untrusted screen pending approval."
            )
        }
        if (consequential) {
            return PolicyDecision(
                verdict = PolicyVerdict.NEEDS_HITL,
                riskTier = RiskTier.CONSEQUENTIAL,
                reason = "Consequential action requires Human-in-the-Loop approval."
            )
        }
        return PolicyDecision(
            verdict = PolicyVerdict.ALLOWED,
            riskTier = if (upper == "READ") RiskTier.READ_ONLY else RiskTier.LOCAL_REVERSIBLE,
            reason = "Permitted inside locked TaskScope."
        )
    }
}
