package org.tifo.footballmanager.newLogic.service;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.boot.Metadata;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.jpa.boot.spi.IntegratorProvider;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Keeps the metadata Hibernate booted with, so the schema can be synchronised again later.
 *
 * <h2>Why this is needed at all</h2>
 *
 * <p>Hibernate 6 does not expose the boot {@link Metadata} from a running session factory — there is no
 * {@code getMetadata()} — and {@code MetadataSources} will not accept a session-factory service registry,
 * only the bootstrap one. So the choice is either to capture the metadata at boot through the documented
 * hook, or to rebuild it from the managed types and hope the two agree. This is the hook.
 *
 * <p>{@link org.hibernate.integrator.spi.Integrator} is the supported way to be handed the metadata while
 * the session factory is being built, and {@link HibernatePropertiesCustomizer} is how a Spring Boot
 * application registers one. Being both in one class is what guarantees the integrator Hibernate runs is the
 * same object this bean later reads from — registering a second instance would leave {@link #metadata()}
 * permanently null, and the failure would be a synchroniser that quietly does nothing.
 */
@Slf4j
@Component
public class BootSchemaMetadata implements Integrator, HibernatePropertiesCustomizer {

    private volatile Metadata metadata;

    @Override
    public void customize(Map<String, Object> properties) {
        properties.put("hibernate.integrator_provider", (IntegratorProvider) () -> List.of(this));
    }

    @Override
    public void integrate(Metadata metadata, SessionFactoryImplementor sessionFactory,
                          SessionFactoryServiceRegistry registry) {
        this.metadata = metadata;
        log.debug("Boot schema metadata captured: {} entity binding(s).",
                metadata.getEntityBindings().size());
    }

    @Override
    public void disintegrate(SessionFactoryImplementor sessionFactory, SessionFactoryServiceRegistry registry) {
        this.metadata = null;
    }

    /** The metadata the running session factory was built from, or null before boot has finished. */
    public Metadata metadata() {
        return metadata;
    }
}
