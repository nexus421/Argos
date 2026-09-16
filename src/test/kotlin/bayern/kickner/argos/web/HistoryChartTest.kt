package bayern.kickner.argos.web

import bayern.kickner.argos.db.DaySummary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.LocalDate

private val day = LocalDate.parse("2026-09-16")
private fun noData(at: LocalDate) = DaySummary(at, 0, 0, null)

class HistoryChartTest : FunSpec({

    test("one bar per day: grey stub without data, green when nothing failed, red as soon as one check failed") {
        val svg = renderHistorySvg(listOf(noData(day.minusDays(1)), DaySummary(day, 10, 0, 100.0), DaySummary(day.plusDays(1), 10, 2, 50.0)))

        Regex("""class="bar-(\w+)"""").findAll(svg).map { it.groupValues[1] }.toList() shouldBe listOf("nodata", "ok", "failed")
    }

    test("bar height is the average latency relative to the slowest day; a day without any successful check is full height") {
        val svg = renderHistorySvg(listOf(DaySummary(day, 10, 0, 100.0), DaySummary(day, 10, 2, 50.0), DaySummary(day, 10, 10, null), noData(day)))

        Regex("""height="(\d+)"""").findAll(svg).map { it.groupValues[1].toInt() }.toList() shouldBe listOf(38, 19, 38, 2)
    }

    test("days whose successful checks all took 0.0 ms are drawn at minimum height instead of failing on NaN") {
        val svg = renderHistorySvg(listOf(DaySummary(day, 10, 0, 0.0), DaySummary(day.plusDays(1), 10, 1, 0.0)))

        Regex("""height="(\d+)"""").findAll(svg).map { it.groupValues[1].toInt() }.toList() shouldBe listOf(2, 2)
    }

    test("every bar carries a tooltip with date, uptime, failures and average latency") {
        val svg = renderHistorySvg(listOf(noData(day.minusDays(1)), DaySummary(day, 1440, 3, 240.0), DaySummary(day.plusDays(1), 5, 5, null)))

        svg shouldContain "<title>15.09.2026: no data</title>"
        svg shouldContain "<title>16.09.2026: 99.8 % up, 3/1440 failed, avg 240 ms</title>"
        svg shouldContain "<title>17.09.2026: 0.0 % up, 5/5 failed, avg –</title>"
    }

    test("the chart scales to its container and stays inline (no script, no external reference)") {
        val svg = renderHistorySvg(List(30) { noData(day.plusDays(it.toLong())) })

        svg shouldContain """viewBox="0 0 300 40""""
        svg shouldContain """preserveAspectRatio="none""""
        svg shouldNotContain "<script"
        svg shouldNotContain "href"
    }
})
