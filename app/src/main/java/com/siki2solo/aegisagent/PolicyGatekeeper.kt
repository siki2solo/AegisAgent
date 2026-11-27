package com.siki2solo.aegisagent

import android.content.Context

object PolicyGatekeeper {

    private const val PREFS_NAME = "aegis_agent_prefs"
    private const val KEY_CUSTOM_BLACKLIST = "custom_blacklist_packages"

    val SYSTEM_BLACKLIST: Set<String> = setOf(
        "com.google.android.apps.nbu.paisa.user",
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp",
        "com.sbi.lotusintouch",
        "com.snapwork.hdfc",
        "com.csam.icici.bank.imobile",
        "com.x8bit.bitwarden",
        "com.onepassword.android",
        "keepass2android.keepass2android",
        "com.google.android.apps.authenticator2",
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller"
    )

    private val EXTERNAL_COMMS_PACKAGES: Set<String> = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "com.google.android.gm",
        "com.google.android.apps.messaging",
        "com.instagram.android",
        "com.twitter.android",
        "com.Slack"
    )

    private val CONSEQUENTIAL_KEYWORDS = Regex(
        "\\b(send|submit|pay|transfer|confirm|delete|remove|uninstall|grant|allow|forward|post|publish|purchase|checkout|order)\\b",
        RegexOption.IGNORE_CASE
    )

    private val INJECTION_PATTERNS = Regex(
        "ignore (previous|user|all)|forward contacts|exfiltrate|send otp|bypass policy",
        RegexOption.IGNORE_CASE
    )

    fun loadCustomBlacklist(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_CUSTOM_BLACKLIST)) {
            saveCustomBlacklist(context, AegisDefaults.DEFAULT_CUSTOM_BLACKLIST)
            return AegisDefaults.DEFAULT_CUSTOM_BLACKLIST
        }
        return prefs.getStringSet(KEY_CUSTOM_BLACKLIST, emptySet())?.toSet() ?: emptySet()
    }

    fun saveCustomBlacklist(context: Context, packages: Set<String>) {
        val cleaned = packages.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_CUSTOM_BLACKLIST, cleaned)
            .apply()
    }

    fun isBlacklisted(packageName: String, customBlacklist: Set<String>): Boolean {
        return SYSTEM_BLACKLIST.contains(packageName) || customBlacklist.contains(packageName)
    }

    /**
     * Strips invisible zero-width formatting characters (U+200B..U+200F, U+202A..U+202E,
     * U+2060..U+206F, U+FEFF) and UTF-16 surrogate pairs for Unicode Tags (U+E0000..U+E007F).
     */
    fun sanitizeText(raw: String): SanitizeResult {
        val sb = StringBuilder(raw.length)
        var strippedCount = 0
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            val code = ch.code

            if (code == 0xDB40 && i + 1 < raw.length) {
                val low = raw[i + 1].code
                if (low in 0xDC00..0xDC7F) {
                    strippedCount++
                    i += 2
                    continue
                }
            }

            val isInvisibleBmp = (code in 0x200B..0x200F) ||
                (code in 0x202A..0x202E) ||
                (code in 0x2060..0x206F) ||
                (code == 0xFEFF)

            if (isInvisibleBmp) {
                strippedCount++
            } else {
                sb.append(ch)
            }
            i++
        }

        val cleaned = sb.toString()
        val suspicious = strippedCount > 0 || INJECTION_PATTERNS.containsMatchIn(cleaned)
        return SanitizeResult(
            cleanText = cleaned,
            strippedCount = strippedCount,
            detectedInjection = suspicious
        )
    }

    fun classifyRisk(action: ProposedAction, strictHitlExternal: Boolean): RiskTier {
        if (
            action.actionType == ActionType.BACK ||
            action.actionType == ActionType.HOME ||
            action.actionType == ActionType.RECENTS ||
            action.actionType == ActionType.DONE
        ) {
            return RiskTier.READ_ONLY
        }

        val combined = "${action.targetNodeId} ${action.targetLabel} ${action.rationale}"
        if (CONSEQUENTIAL_KEYWORDS.containsMatchIn(combined)) {
            return RiskTier.CONSEQUENTIAL
        }

        if (strictHitlExternal && EXTERNAL_COMMS_PACKAGES.contains(action.targetPackage)) {
            if (action.actionType == ActionType.TAP || action.actionType == ActionType.LAUNCH_APP) {
                return RiskTier.CONSEQUENTIAL
            }
        }

        return RiskTier.LOCAL_REVERSIBLE
    }

    fun evaluate(
        action: ProposedAction,
        scope: TaskScope,
        sawUntrustedContent: Boolean,
        customBlacklist: Set<String>,
        strictHitlExternal: Boolean
    ): PolicyDecision {
        // 1. Immutable System + User Custom Blacklist
        if (isBlacklisted(action.targetPackage, customBlacklist)) {
            return PolicyDecision(
                verdict = PolicyVerdict.BLOCKED_BLACKLIST,
                riskTier = RiskTier.CONSEQUENTIAL,
                reason = "Package ${action.targetPackage} is protected by Aegis Blacklist.",
                matchedRule = "SYSTEM_BLACKLIST"
            )
        }

        // 2. Scope Lock (disallow cross-package jumps outside active TaskScope)
        if (!scope.allowedPackages.contains(action.targetPackage)) {
            val rule = if (sawUntrustedContent) "TASK_SCOPE_AND_RULE_OF_TWO" else "TASK_SCOPE_LOCK"
            return PolicyDecision(
                verdict = PolicyVerdict.BLOCKED_SCOPE,
                riskTier = RiskTier.CONSEQUENTIAL,
                reason = "Target package ${action.targetPackage} is outside allowed scope ${scope.allowedPackages.joinToString()}",
                matchedRule = rule
            )
        }

        // 3. Rule-of-Two: untrusted content cannot drive a consequential action
        val riskTier = classifyRisk(action, strictHitlExternal)
        if (sawUntrustedContent && riskTier == RiskTier.CONSEQUENTIAL) {
            return PolicyDecision(
                verdict = PolicyVerdict.BLOCKED_RULE_OF_TWO,
                riskTier = RiskTier.CONSEQUENTIAL,
                reason = "Rule-of-Two blocked consequential action after reading untrusted content.",
                matchedRule = "RULE_OF_TWO"
            )
        }

        // 4. Risk Tier Gate (HITL freeze for consequential actions)
        if (riskTier == RiskTier.CONSEQUENTIAL) {
            return PolicyDecision(
                verdict = PolicyVerdict.REQUIRE_HITL,
                riskTier = riskTier,
                reason = "Consequential action requires explicit human confirmation.",
                matchedRule = "HITL_CONFIRMATION_GATE"
            )
        }

        return PolicyDecision(
            verdict = PolicyVerdict.ALLOW,
            riskTier = riskTier,
            reason = "Action permitted under ${riskTier.name} policy.",
            matchedRule = if (riskTier == RiskTier.READ_ONLY) "READ_ONLY_FAST_PATH" else "LOCAL_REVERSIBLE_POLICY"
        )
    }
}
