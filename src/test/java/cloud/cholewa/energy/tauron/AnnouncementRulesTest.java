package cloud.cholewa.energy.tauron;

import cloud.cholewa.energy.database.PlannedOutageEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static cloud.cholewa.energy.tauron.TauronSamples.NOW;
import static cloud.cholewa.energy.tauron.TauronSamples.WORK;
import static org.assertj.core.api.Assertions.assertThat;

//what a read changes in planned_outage (HAS-337): an outage is its id and its start
class AnnouncementRulesTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 12, 8, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 11, 12, 13, 0);
    private static final String TWIN = "00000000-0000-0000-0000-000000000009";

    //the read before missed every held outage too - what is gone now is closed
    private static List<PlannedOutageEntity> reconcile(final List<PlannedOutageEntity> held,
                                                       final List<Announcement> announced, final LocalDateTime now) {
        return AnnouncementRules.reconcile(held, announced,
            held.stream().map(AnnouncementRules::key).collect(Collectors.toSet()), now).rows();
    }

    //one answer without it may be a hiccup of Tauron: left open, named as missed for the next read
    @Test
    void should_leave_open_an_outage_ahead_missed_by_one_read() {
        final PlannedOutageEntity told = TauronSamples.told(7, START, END);

        final AnnouncementRules.Result first = AnnouncementRules.reconcile(List.of(told), List.of(), Set.of(), NOW);

        assertThat(first.rows()).isEmpty();
        assertThat(first.missed()).containsExactly(AnnouncementRules.key(told));

        final AnnouncementRules.Result back = AnnouncementRules.reconcile(List.of(told),
            List.of(TauronSamples.planned(WORK, START, END)), first.missed(), NOW.plusHours(1));

        assertThat(back.missed()).isEmpty();
        assertThat(back.rows()).singleElement().satisfies(row -> {
            assertThat(row.closedAt()).isNull();
            assertThat(row.notice()).isNull();
        });
    }

    @Test
    void should_close_at_once_an_outage_over_that_is_no_longer_announced() {
        final AnnouncementRules.Result result = AnnouncementRules.reconcile(
            List.of(TauronSamples.told(7, NOW.minusHours(3), NOW.minusHours(1))), List.of(), Set.of(), NOW);

        assertThat(result.rows()).singleElement().extracting(PlannedOutageEntity::closedAs).isEqualTo("ended");
    }

    @Test
    void should_hold_a_new_announcement_as_new() {
        final List<PlannedOutageEntity> rows = reconcile(List.of(),
            List.of(TauronSamples.planned(WORK, START, END)), NOW);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.id()).isNull();
            assertThat(row.notice()).isEqualTo("new");
            assertThat(row.firstSeenAt()).isEqualTo(NOW);
            assertThat(row.lastSeenAt()).isEqualTo(NOW);
            assertThat(row.closedAt()).isNull();
            assertThat(row.skipAutomation()).isFalse();
        });
    }

    //the days of one work share the id: each day its own outage
    @Test
    void should_hold_each_day_of_one_work_apart() {
        final List<PlannedOutageEntity> rows = reconcile(List.of(), List.of(
            TauronSamples.planned(WORK, START, END),
            TauronSamples.planned(WORK, START.plusDays(1), END.plusDays(1)),
            TauronSamples.planned(WORK, START, END)), NOW);

        assertThat(rows).extracting(PlannedOutageEntity::startsAt).containsExactly(START, START.plusDays(1));
    }

    @Test
    void should_see_again_an_outage_held_and_tell_nothing_when_it_did_not_change() {
        final List<PlannedOutageEntity> rows = reconcile(List.of(TauronSamples.told(7, START, END)),
            List.of(TauronSamples.planned(WORK, START, END)), NOW);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(7);
            assertThat(row.lastSeenAt()).isEqualTo(NOW);
            assertThat(row.notice()).isNull();
        });
    }

    @Test
    void should_tell_a_new_end_of_an_outage_told_before_as_changed() {
        final List<PlannedOutageEntity> rows = reconcile(List.of(TauronSamples.told(7, START, END)),
            List.of(TauronSamples.planned(WORK, START, END.plusHours(1))), NOW);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.endsAt()).isEqualTo(END.plusHours(1));
            assertThat(row.notice()).isEqualTo("changed");
        });
    }

    //not told yet: its first notice tells the new hours
    @Test
    void should_keep_the_notice_not_told_yet() {
        final PlannedOutageEntity untold = TauronSamples.row(7, TauronSamples.planned(WORK, START, END)).withNotice("new");

        final List<PlannedOutageEntity> rows = reconcile(List.of(untold),
            List.of(TauronSamples.planned(WORK, START, END.plusHours(1))), NOW);

        assertThat(rows).singleElement().extracting(PlannedOutageEntity::notice).isEqualTo("new");
    }

    @Test
    void should_tell_an_outage_of_the_town_that_came_to_name_the_street_as_new() {
        final PlannedOutageEntity town = TauronSamples.told(7, START, END).withScope("town");

        final List<PlannedOutageEntity> rows = reconcile(List.of(town),
            List.of(TauronSamples.planned(WORK, START, END)), NOW);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.scope()).isEqualTo("street");
            assertThat(row.notice()).isEqualTo("new");
        });
    }

    @Test
    void should_tell_an_outage_told_before_and_announced_at_another_start_as_moved() {
        final List<PlannedOutageEntity> rows = reconcile(List.of(TauronSamples.told(7, START, END)),
            List.of(TauronSamples.planned(WORK, START.plusDays(2), END.plusDays(2))), NOW);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).satisfies(row -> {
            assertThat(row.id()).isEqualTo(7);
            assertThat(row.closedAs()).isEqualTo("moved");
            assertThat(row.closedAt()).isEqualTo(NOW);
            assertThat(row.notice()).isNull();
        });
        assertThat(rows.get(1)).satisfies(row -> {
            assertThat(row.id()).isNull();
            assertThat(row.notice()).isEqualTo("moved");
            assertThat(row.movedFrom()).isEqualTo(START);
            assertThat(row.startsAt()).isEqualTo(START.plusDays(2));
        });
    }

    //owner, 2026-11-03: a cancellation is an info without an SMS - only of the street, only once told
    @Test
    void should_close_a_planned_outage_ahead_no_longer_announced_as_cancelled() {
        final List<PlannedOutageEntity> rows = reconcile(List.of(TauronSamples.told(7, START, END)),
            List.of(), NOW);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.closedAs()).isEqualTo("cancelled");
            assertThat(row.notice()).isEqualTo("cancelled");
        });
    }

    @Test
    void should_cancel_silently_what_was_never_told_or_was_the_town_s() {
        final PlannedOutageEntity untold = TauronSamples.row(7, TauronSamples.planned(WORK, START, END)).withNotice("new");
        final PlannedOutageEntity town = TauronSamples.told(8, START.plusDays(1), END.plusDays(1)).withScope("town");

        final List<PlannedOutageEntity> rows = reconcile(List.of(untold, town), List.of(), NOW);

        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row.closedAs()).isEqualTo("cancelled");
            assertThat(row.notice()).isNull();
        });
    }

    @Test
    void should_close_an_outage_going_on_or_a_failure_no_longer_announced_as_ended() {
        final PlannedOutageEntity goingOn = TauronSamples.told(7, NOW.minusHours(1), NOW.plusHours(2));
        final PlannedOutageEntity failure = TauronSamples.told(8, NOW.plusHours(1), NOW.plusHours(2))
            .withKind("unplanned").withOutageId("f");

        final List<PlannedOutageEntity> rows = reconcile(List.of(goingOn, failure), List.of(), NOW);

        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row.closedAs()).isEqualTo("ended");
            assertThat(row.notice()).isNull();
        });
    }

    //HAS-477, seen 2026-11-22: Tauron announces one outage twice, under two ids - the same start, end and kind, the
    //words differing only in whitespace and case
    private static Announcement twin(final LocalDateTime start, final LocalDateTime end) {
        return new Announcement(TWIN, Announcement.PLANNED, "street", start, end, "  Testowo   UL. Przykładowa ");
    }

    @Test
    void should_hold_a_doubled_announcement_of_one_answer_as_one_outage() {
        final List<PlannedOutageEntity> rows = reconcile(List.of(),
            List.of(TauronSamples.planned(WORK, START, END), twin(START, END)), NOW);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.outageId()).isEqualTo(WORK);
            assertThat(row.notice()).isEqualTo("new");
        });
    }

    //the case of 10.11 / 16.11: the second announcement comes days after the first was stored and told
    @Test
    void should_not_store_a_twin_that_arrives_with_a_later_read() {
        final PlannedOutageEntity told = TauronSamples.told(7, START, END);

        final AnnouncementRules.Result result = AnnouncementRules.reconcile(List.of(told),
            List.of(twin(START, END), TauronSamples.planned(WORK, START, END)), Set.of(), NOW);

        assertThat(result.missed()).isEmpty();
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(7);
            assertThat(row.outageId()).isEqualTo(WORK);
            assertThat(row.closedAt()).isNull();
            assertThat(row.notice()).isNull();
        });
    }

    //two rows stored before the rule: the older stays, the newer closed without a word, its run and skip carried
    @Test
    void should_close_the_newer_of_two_stored_rows_of_one_outage_silently_and_carry_its_run_and_skip() {
        final PlannedOutageEntity older = TauronSamples.told(7, START, END);
        final PlannedOutageEntity newer = TauronSamples.row(8, twin(START, END))
            .withFirstSeenAt(NOW.minusHours(6))
            .withNotifiedAt(NOW.minusHours(6))
            .withSkipAutomation(true)
            .withChargeRunId(41L);

        final List<PlannedOutageEntity> rows = reconcile(List.of(newer, older),
            List.of(TauronSamples.planned(WORK, START, END), twin(START, END)), NOW);

        //the row kept first: a pass cut after it leaves two open rows for the next read, never a lost run
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).satisfies(row -> {
            assertThat(row.id()).isEqualTo(7);
            assertThat(row.closedAt()).isNull();
            assertThat(row.notice()).isNull();
            assertThat(row.skipAutomation()).isTrue();
            assertThat(row.chargeRunId()).isEqualTo(41L);
        });
        assertThat(rows.get(1)).satisfies(row -> {
            assertThat(row.id()).isEqualTo(8);
            assertThat(row.closedAs()).isEqualTo("ended");
            assertThat(row.closedAt()).isEqualTo(NOW);
            assertThat(row.notice()).isNull();
        });
    }

    //the run of the row kept is never replaced by the twin's
    @Test
    void should_keep_the_run_of_the_older_row() {
        final List<PlannedOutageEntity> rows = reconcile(
            List.of(TauronSamples.told(7, START, END).withChargeRunId(40L),
                TauronSamples.row(8, twin(START, END)).withFirstSeenAt(NOW.minusHours(6)).withChargeRunId(41L)),
            List.of(TauronSamples.planned(WORK, START, END), twin(START, END)), NOW);

        assertThat(rows.get(0).chargeRunId()).isEqualTo(40L);
        assertThat(rows.get(0).skipAutomation()).isFalse();
    }

    //Tauron removes the announcement the row is keyed by while its twin stays: the row takes the id of the twin and
    //goes on - at once, not after two reads, with no notice; from then on it is found by that id
    @Test
    void should_continue_a_row_under_its_twin_once_its_own_announcement_is_gone() {
        final PlannedOutageEntity told = TauronSamples.told(7, START, END);

        final AnnouncementRules.Result result = AnnouncementRules.reconcile(List.of(told), List.of(twin(START, END)),
            Set.of(AnnouncementRules.key(told)), NOW);

        assertThat(result.missed()).isEmpty();
        assertThat(result.rows()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(7);
            assertThat(row.outageId()).isEqualTo(TWIN);
            assertThat(row.closedAt()).isNull();
            assertThat(row.notice()).isNull();
            assertThat(row.notifiedAt()).isEqualTo(told.notifiedAt());
            assertThat(row.lastSeenAt()).isEqualTo(NOW);
        });

        final AnnouncementRules.Result next = AnnouncementRules.reconcile(result.rows(), List.of(twin(START, END)),
            result.missed(), NOW.plusHours(1));

        assertThat(next.rows()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(7);
            assertThat(row.notice()).isNull();
        });
    }

    //two rows stored before the rule, only the newer still announced: the older is closed without a word - never told
    //as cancelled - and its run and skip go to the row that stays under its own id
    @Test
    void should_merge_a_stored_twin_that_is_no_longer_announced_into_the_row_still_announced() {
        final PlannedOutageEntity older = TauronSamples.told(7, START, END).withChargeRunId(40L).withSkipAutomation(true);
        final PlannedOutageEntity newer = TauronSamples.row(8, twin(START, END)).withFirstSeenAt(NOW.minusHours(6))
            .withNotifiedAt(NOW.minusHours(6));

        final AnnouncementRules.Result result = AnnouncementRules.reconcile(List.of(older, newer),
            List.of(twin(START, END)), Set.of(AnnouncementRules.key(older)), NOW);

        assertThat(result.missed()).isEmpty();
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0)).satisfies(row -> {
            assertThat(row.id()).isEqualTo(8);
            assertThat(row.outageId()).isEqualTo(TWIN);
            assertThat(row.closedAt()).isNull();
            assertThat(row.chargeRunId()).isEqualTo(40L);
            assertThat(row.skipAutomation()).isTrue();
        });
        assertThat(result.rows().get(1)).satisfies(row -> {
            assertThat(row.id()).isEqualTo(7);
            assertThat(row.closedAs()).isEqualTo("ended");
            assertThat(row.notice()).isNull();
        });
    }

    //the order of the answer must not decide: another day of the same work listed before the twin is new, not the
    //moved outage - the row goes on under its twin with its reminders and its run
    @Test
    void should_continue_under_the_twin_before_any_move_is_looked_for() {
        final PlannedOutageEntity told = TauronSamples.told(7, START, END).withChargeRunId(40L);

        final List<PlannedOutageEntity> rows = reconcile(List.of(told), List.of(
            TauronSamples.planned(WORK, START.plusDays(2), END.plusDays(2)), twin(START, END)), NOW);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).satisfies(row -> {
            assertThat(row.id()).isEqualTo(7);
            assertThat(row.outageId()).isEqualTo(TWIN);
            assertThat(row.closedAt()).isNull();
            assertThat(row.notice()).isNull();
            assertThat(row.chargeRunId()).isEqualTo(40L);
        });
        assertThat(rows.get(1)).satisfies(row -> {
            assertThat(row.id()).isNull();
            assertThat(row.notice()).isEqualTo("new");
            assertThat(row.movedFrom()).isNull();
        });
    }

    //the rows of 24.11: the same hours, other streets - two outages
    @Test
    void should_hold_two_outages_with_the_same_hours_and_other_words_apart() {
        final List<PlannedOutageEntity> rows = reconcile(List.of(), List.of(
            TauronSamples.planned(WORK, START, END),
            new Announcement(TWIN, Announcement.PLANNED, "town", START, END, "Testowo ul. Inna")), NOW);

        assertThat(rows).extracting(PlannedOutageEntity::outageId).containsExactly(WORK, TWIN);
    }

    //the same words at other hours, or a failure beside a planned outage, are not twins
    @Test
    void should_hold_the_same_words_at_another_end_or_of_another_kind_apart() {
        final List<PlannedOutageEntity> rows = reconcile(List.of(), List.of(
            TauronSamples.planned(WORK, START, END),
            twin(START, END.plusHours(1)),
            new Announcement("failure", Announcement.UNPLANNED, "street", START, END, "Testowo ul. Przykładowa")),
            NOW);

        assertThat(rows).hasSize(3);
    }
}
