package cloud.cholewa.energy.database;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

//A row of grid_outage (V4, HAS-334): one outage of the grid, endedAt null while it lasts. The state of charge in %
//at the start, at the end and the lowest in between; lastRemindedAt the last notification of it, lowSocAlertedAt the
//alert of a low battery (at most one an outage). inferred (V19, HAS-505) - seen after the fact from the uptime of the
//grid meter, not live by the SOLAX: the times are bounds, no state of charge. The live watch makes rows with the
//constructor without it - never inferred; the rows are read with the canonical one (Spring Data takes it for a record)
@Table("grid_outage")
public record GridOutageEntity(
    @Id Long id,
    @Column("started_at") LocalDateTime startedAt,
    @Column("ended_at") LocalDateTime endedAt,
    @Column("soc_at_start") Double socAtStart,
    @Column("soc_at_end") Double socAtEnd,
    @Column("lowest_soc") Double lowestSoc,
    @Column("last_reminded_at") LocalDateTime lastRemindedAt,
    @Column("low_soc_alerted_at") LocalDateTime lowSocAlertedAt,
    @Column("inferred") boolean inferred
) {

    public GridOutageEntity(final Long id, final LocalDateTime startedAt, final LocalDateTime endedAt,
                            final Double socAtStart, final Double socAtEnd, final Double lowestSoc,
                            final LocalDateTime lastRemindedAt, final LocalDateTime lowSocAlertedAt) {
        this(id, startedAt, endedAt, socAtStart, socAtEnd, lowestSoc, lastRemindedAt, lowSocAlertedAt, false);
    }

    public static GridOutageEntity opened(final LocalDateTime startedAt, final Double soc, final LocalDateTime now) {
        return new GridOutageEntity(null, startedAt, null, soc, null, soc, now, null);
    }

    //an outage seen after the fact: between the bounds, already over, told once at now
    public static GridOutageEntity inferred(final LocalDateTime from, final LocalDateTime to, final LocalDateTime now) {
        return new GridOutageEntity(null, from, to, null, null, null, now, null, true);
    }

    public GridOutageEntity withId(final Long rowId) {
        return new GridOutageEntity(rowId, startedAt, endedAt, socAtStart, socAtEnd, lowestSoc, lastRemindedAt,
            lowSocAlertedAt, inferred);
    }

    public GridOutageEntity closed(final LocalDateTime at, final Double soc) {
        return new GridOutageEntity(id, startedAt, at, socAtStart, soc, lowerOf(lowestSoc, soc), lastRemindedAt,
            lowSocAlertedAt, inferred);
    }

    public GridOutageEntity withSoc(final Double soc) {
        return new GridOutageEntity(id, startedAt, endedAt, socAtStart, socAtEnd, lowerOf(lowestSoc, soc),
            lastRemindedAt, lowSocAlertedAt, inferred);
    }

    public GridOutageEntity reminded(final LocalDateTime at) {
        return new GridOutageEntity(id, startedAt, endedAt, socAtStart, socAtEnd, lowestSoc, at, lowSocAlertedAt,
            inferred);
    }

    public GridOutageEntity lowSocAlerted(final LocalDateTime at) {
        return new GridOutageEntity(id, startedAt, endedAt, socAtStart, socAtEnd, lowestSoc, lastRemindedAt, at,
            inferred);
    }

    private static Double lowerOf(final Double lowest, final Double soc) {
        if (lowest == null) {
            return soc;
        }
        return soc == null ? lowest : Math.min(lowest, soc);
    }
}
