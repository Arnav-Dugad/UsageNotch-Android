package io.github.arnavdugad.usagenotch

import org.junit.Assert.*
import org.junit.Test

/** Windows 2.7: plans, where the week went, Edu monthly credits, information rows, day coverage and plain-language stats. */
class AccountsAndStatsTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/$name")!!.use { String(it.readBytes()) }

    private val edu = """
        {"schema":1,"protocol":2,"generatedAt":1793400000000,"providers":[
          {"id":"codex","name":"Codex","status":"Ok","plan":"Edu","windows":[
            {"id":"codex-primary","label":"5-hour limit","used":0.12,"at":1793400000000,"reset":1793443246000,"points":[]},
            {"id":"codex-secondary","label":"Weekly limit","used":0.3,"at":1793400000000,"reset":1794030046000,"points":[]},
            {"id":"codex-monthly","label":"Monthly limit","used":0.2,"at":1793400000000,"reset":1795000000000,"points":[],"detail":"Credits shared across Codex and other ChatGPT agents","usedAmount":30,"limitAmount":150,"unit":"credits"}],
           "session":"codex-primary","weekly":"codex-secondary",
           "extras":[{"id":"codex-credits","label":"Credits","detail":"Available to this account"},
                     {"id":"codex-reset-credits","label":"Free limit reset","detail":"Full reset (Weekly + 5 hr) · use by Nov 7 · redeem it in Codex","reset":1794001071000}]},
          {"id":"claude","name":"Claude","status":"Ok","plan":"Max 5×","breakdown":[{"name":"Claude Code","percent":82.5},{"name":"Claude","percent":17.5},{"name":"bad","percent":0}],
           "windows":[{"id":"five_hour","label":"Current session","used":0.28,"at":1793400000000,"points":[]},
                      {"id":"iguana_necktie","label":"Usage credit · ${'$'}250","used":0.9999,"at":1793400000000,"points":[],"usedAmount":249.99,"limitAmount":250,"unit":"USD"}]}],
         "tokens":{"updatedAt":1793400000000,"sources":[{"id":"codex","label":"Codex","allDevices":true,"lifetime":1890000000,"peakDaily":218000000,"longestStreak":7,
           "today":{"total":5000,"cost":null},"week":{"total":90000,"cost":null},"days":[],"projects":[],"models":[]}]}}
    """.trimIndent()

    @Test fun plansBreakdownAndEduMonthlyCredits() {
        val s = Snapshot.parse(edu)
        val codex = s.providers.first { it.id == "codex" }
        assertEquals("Edu", codex.plan)
        // Rings stay on the session and weekly windows; the monthly limit is one more limit with an amount.
        assertEquals("codex-primary", codex.sessionWindow()!!.id); assertEquals("codex-secondary", codex.weeklyWindow()!!.id)
        val monthly = codex.windows.first { it.id == "codex-monthly" }
        assertEquals("30 of 150 credits", amountText(monthly.usedAmount, monthly.limitAmount, monthly.unit, null))
        val claude = s.providers.first { it.id == "claude" }
        assertEquals("Max 5×", claude.plan)
        assertEquals(listOf("Claude Code", "Claude"), claude.breakdown.map { it.name })
        val credit = claude.windows.first { it.id == "iguana_necktie" }
        assertEquals("$249.99 of $250", amountText(credit.usedAmount, credit.limitAmount, credit.unit, null))
    }

    @Test fun rowsWithoutAPercentageAreInformation() {
        val codex = Snapshot.parse(edu).providers.first { it.id == "codex" }
        assertTrue("no bar for them", codex.windows.none { it.id.endsWith("credits") })
        assertEquals("Available to this account" to null, extraText(codex.extras.first { it.id == "codex-credits" }))
        assertEquals("Full reset (Weekly + 5 hr)" to "Use by Nov 7 · redeem it in Codex", extraText(codex.extras.first { it.id == "codex-reset-credits" }))
        assertEquals("$3.50 used" to null, extraText(Extra("api", "This month's API spend", null, null, 3.5, null, "USD")))
    }

    @Test fun accountWideTokens() {
        val source = Snapshot.parse(edu).tokens!!.sources.single()
        assertTrue(source.allDevices); assertNull(source.today!!.cost)
        assertEquals("Lifetime 1.89B tokens · busiest day 218M · longest streak 7 days", tokenFacts(source))
    }

    @Test fun realWindowsDaysCarryCoverage() {
        val h = Snapshot.parse(resource("phone-snapshot-v2-windows.json")).providers.first { it.id == "claude" }.history.first()
        val recorded = h.days.filter { it.used != null }
        assertTrue(recorded.isNotEmpty() && recorded.all { it.coverage != null && it.coverage!! in 0.0..1.0 })
        assertTrue("days without readings have no coverage", h.days.filter { it.used == null }.all { it.coverage == null })
    }

    @Test fun limitsReadInPlainLanguage() {
        assertEquals("48%", LimitText.of(.48)); assertEquals("5.6×", LimitText.of(5.61)); assertEquals("2×", LimitText.of(2.0)); assertEquals("—", LimitText.of(null))
        // Up to 105% stays a percentage, as on Windows 2.8: a full limit is "100%", never "1×".
        assertEquals("100%", LimitText.of(1.0)); assertEquals("104%", LimitText.of(1.04)); assertEquals("1.1×", LimitText.of(1.05))
        assertEquals("100% of the limit", LimitText.ofLimit(1.0)); assertEquals("1.6× the limit", LimitText.ofLimit(1.6))
        assertEquals("5.6 full current session limits' worth", LimitText.worth(5.61, "Current session"))
        assertEquals("of one current session limit", LimitText.worth(.4, "Current session"))
        assertEquals("1.6× more", LimitText.compared(1.28, .8)); assertEquals("30% less", LimitText.compared(.56, .8))
        assertEquals("92% of the day observed", LimitText.coverage(.92))
    }
}
