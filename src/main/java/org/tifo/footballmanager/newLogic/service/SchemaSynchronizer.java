package org.tifo.footballmanager.newLogic.service;

import jakarta.persistence.EntityManagerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.persistence.metamodel.ManagedType;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.tool.schema.spi.SchemaManagementToolCoordinator;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Brings the database up to the entities, on demand (owner, 2026-10-18).
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code spring.jpa.hibernate.ddl-auto=update} runs <b>once, at startup</b>. Two admin actions can leave
 * the database older than the code while the application keeps running, and neither of them can be fixed by
 * the mechanism that normally does it:
 *
 * <ul>
 *   <li><b>Restore a backup.</b> The restore <i>drops and recreates the whole database</i> and replays a
 *       dump. A dump taken before a column was added does not have that column, so the application comes back
 *       to a schema it cannot query — {@code ERROR: column gc1_0.anchor_date does not exist}, a game-clock
 *       column added after that backup was taken.</li>
 *   <li><b>Reset the database.</b> A reset truncates and re-seeds, and it fails on the first query that
 *       touches a table the code has moved on from — {@code ERROR: column "sponsor_name" of relation
 *       "stadium" does not exist}. The reset did not cause that drift; it was the first thing to run into it,
 *       and it reported it as "Database operation failed" with nothing to suggest a restart would fix it.</li>
 * </ul>
 *
 * <p>So the schema is synchronised as a <b>step of the action</b> and not only as a boot step. It is the
 * same call Hibernate makes at startup — {@link SchemaManagementToolCoordinator#process} with
 * {@code update} — so there is one implementation of "what the schema should be" rather than a second one
 * that drifts from the first.
 *
 * <p><b>Idempotent and cheap when there is nothing to do.</b> Hibernate reads the existing schema and emits
 * only what is missing, so calling this before every reset costs a few catalogue reads. It never drops
 * anything: {@code update} adds what is absent and leaves the data alone.
 *
 * <p><b>Not transactional.</b> The schema tool manages its own connections and commits each statement as it
 * goes, and a failure halfway through should leave the statements that already succeeded in place rather
 * than roll them back into a state nobody can see.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaSynchronizer {

    private final EntityManagerFactory entityManagerFactory;
    private final BootSchemaMetadata bootMetadata;

    /** Applies every entity change the database is missing. */
    public void synchronize() {
        Metadata metadata = bootMetadata.metadata();
        if (metadata == null) {
            // Before boot has finished there is nothing to synchronise against. Said out loud rather than
            // swallowed: a synchroniser that silently does nothing is worse than one that is not called.
            log.warn("Schema synchronisation skipped: the boot metadata is not available yet.");
            return;
        }
        ServiceRegistry registry = entityManagerFactory.unwrap(SessionFactoryImplementor.class)
                .getServiceRegistry();

        Map<String, Object> settings = new HashMap<>();
        // The same value `ddl-auto=update` puts in the configuration, so this is the same code path rather
        // than a parallel one.
        settings.put(AvailableSettings.HBM2DDL_AUTO, "update");
        // Hibernate's own default for `update`, restated because the value read here is not the one from the
        // configuration file: without it a changed unique constraint would be left as it stands.
        settings.put(AvailableSettings.UNIQUE_CONSTRAINT_SCHEMA_UPDATE_STRATEGY, "drop-recreate-quietly");

        try {
            SchemaManagementToolCoordinator.process(metadata, registry, settings, null);
            log.info("Schema synchronisation finished.");
        } catch (RuntimeException e) {
            // Reported and swallowed on purpose. This runs before a reset or after a restore, and a schema
            // that cannot be brought fully up to date must not stop the action that called it — the action
            // will fail on its own terms, with its own message, if the schema really is unusable.
            log.warn("Schema synchronisation did not complete: {}", e.getMessage());
        }
    }
}
