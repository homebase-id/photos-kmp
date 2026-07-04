package id.homebase.photos.data

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Test-only UTC decode of epoch-millis, so month-spread assertions don't depend on the runner zone. */
internal fun millisToUtcDateTimeForTest(millis: Long): LocalDateTime =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC)

internal val LocalDateTime.monthNumberForTest: Int get() = this.month.ordinal + 1
