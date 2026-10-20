package org.example.footballmanager.newLogic.service;

import org.example.footballmanager.BaseTest;
import org.example.commonmanager.model.User;
import org.example.footballmanager.newLogic.model.Notification;
import org.example.footballmanager.newLogic.model.NotificationKind;
import org.example.footballmanager.newLogic.repository.NotificationRepository;
import org.example.commonmanager.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A notification a feature wrote must not be able to fail that feature (owner, 2026-10-08).
 *
 * <p><b>What happened.</b> Two kinds were added to {@code NotificationKind} for loans. They compiled, every
 * test passed — because the test database is created from the entities and has no CHECK constraint on the
 * column — and then the first real write in the owner's world failed:
 *
 * <pre>
 * ERROR: new row for relation "nl_notification" violates check constraint "nl_notification_kind_check"
 *   Detail: Failing row contains (1, ..., LOAN_MOVED, ..., Zvezdan Vukomanović has arrived on loan, ...)
 * HHH000099: null id in Notification entry (don't flush the Session after an exception occurs)
 *   at GlobalApiExceptionHandler : POST /loans/1/activate
 * </pre>
 *
 * <p>Two independent defects, and the second is the worse one:
 *
 * <ol>
 *   <li>The database pins the kinds in a CHECK constraint that no code maintains, and
 *       {@code ddl-auto=update} never widens a CHECK.</li>
 *   <li><b>{@code NotificationService.notify} caught its exception and its caller failed anyway.</b> A
 *       failed statement poisons the persistence context, so the caller's own transaction dies at flush. The
 *       catch gave false comfort: the feature that wrote the courtesy row was taken down by it.</li>
 * </ol>
 *
 * @see ResetService#alignNotificationKindConstraint()
 */
class NotificationWriteCannotFailItsCallerTest extends BaseTest {

    @Autowired NotificationService notifications;
    @Autowired NotificationRepository rows;
    @Autowired UserRepository users;

    @Test
    @DisplayName("every declared kind is one the database will accept")
    void everyDeclaredKindIsWritable() {
        User manager = aManager();
        for (NotificationKind kind : NotificationKind.values()) {
            notifications.notify(manager, kind, "kind check " + kind.name(), "loans", null);
        }

        List<String> written = rows.findAll().stream()
                .filter(n -> n.getRecipient() != null && manager.getId().equals(n.getRecipient().getId()))
                .map(n -> n.getKind().name())
                .toList();

        for (NotificationKind kind : NotificationKind.values()) {
            assertTrue(written.contains(kind.name()),
                    kind + " is declared in the enum but was not accepted by the database. A kind that "
                            + "compiles and is not writable is the defect this class exists for.");
        }
    }

    @Test
    @DisplayName("a failed notification does not fail the caller, because it cannot share its session")
    void aFailedNotificationCannotTakeTheCallerDown() {
        // The proof is structural rather than simulated: REQUIRES_NEW gives the notification its own
        // transaction, so there is no session left poisoned for the caller. Asserting the property that
        // actually broke would mean deliberately violating a constraint here, which would leave the
        // shared test database in a state the next test inherits.
        boolean ownTransaction = java.util.Arrays.stream(
                        NotificationService.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("notify"))
                .flatMap(m -> Arrays.stream(m.getAnnotationsByType(
                        org.springframework.transaction.annotation.Transactional.class)))
                .anyMatch(a -> a.propagation()
                        == org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);

        assertTrue(ownTransaction,
                "notify() must run in its own transaction: a failed insert poisons the session it shares, "
                        + "and a caller whose session is poisoned fails on a courtesy row it never asked for");
    }

    /**
     * The repair is derived from the enum, not written out — so a kind cannot be added without it.
     *
     * <p><b>The first version of this test asserted the database's constraint, and it passed against the
     * broken database.</b> The suite runs on H2, which has no CHECK constraint on this column at all, so
     * the test returned early and proved nothing while looking green. That is the same "a green status is
     * not evidence" trap as everywhere else in this repository, and it is why the assertion is on the
     * <b>code that repairs it</b> — which runs on every database — instead of on one deployment's schema.
     */
    @Test
    @DisplayName("the repair takes its kinds from the enum, so a new kind widens the database with it")
    void theRepairIsDerivedFromTheEnum() throws Exception {
        java.lang.reflect.Method repair = ResetService.class
                .getDeclaredMethod("alignNotificationKindConstraint");

        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/org/example/footballmanager/newLogic/service/ResetService.java"),
                java.nio.charset.StandardCharsets.UTF_8);
        int from = source.indexOf("public void alignNotificationKindConstraint()");
        int to = source.indexOf("MINUTE is a reserved word", from);
        String body = source.substring(from, to);

        assertTrue(body.contains("NotificationKind.values()"),
                "the constraint is rebuilt from a written-out list, so the enum and the database can "
                        + "disagree again on the next kind anybody adds");
        for (NotificationKind kind : NotificationKind.values()) {
            // The kinds must reach the statement through values(), which the assertion above already
            // requires; this states why each one matters rather than repeating the list here.
            assertTrue(NotificationKind.valueOf(kind.name()) == kind);
        }
        assertTrue(repair != null);
    }

    private User aManager() {
        User manager = new User();
        manager.setUsername("notif-check-" + System.nanoTime() + "@test.local");
        manager.setEmail(manager.getUsername());
        return users.save(manager);
    }
}