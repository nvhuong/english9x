package com.english9x.org.internal;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaDialect;
import org.springframework.orm.jpa.JpaTransactionManager;

/**
 * The application's one transaction manager. It replaces Spring Boot's default so that every transaction, JPA or
 * JdbcTemplate, starts with the RLS session context (see {@link RlsSessionConfigurer}).
 */
@Configuration(proxyBeanMethods = false)
class RlsTransactionConfiguration {

    @Bean
    JpaTransactionManager transactionManager(
            EntityManagerFactory entityManagerFactory,
            RlsSessionConfigurer configurer,
            ObjectProvider<TransactionManagerCustomizers> customizers) {
        RlsJpaTransactionManager tm =
                new RlsJpaTransactionManager(entityManagerFactory, new RlsHibernateJpaDialect(configurer));
        customizers.ifAvailable(c -> c.customize(tm));
        return tm;
    }

    /**
     * {@link JpaTransactionManager#afterPropertiesSet()} copies the EntityManagerFactory's own dialect over any dialect
     * set by hand, and the container calls it after construction. Re-assert ours afterwards, otherwise the RLS hook
     * silently never runs.
     */
    static final class RlsJpaTransactionManager extends JpaTransactionManager {
        private static final long serialVersionUID = 1L;

        private final transient JpaDialect rlsDialect;

        RlsJpaTransactionManager(EntityManagerFactory emf, JpaDialect rlsDialect) {
            super(emf);
            this.rlsDialect = rlsDialect;
            setJpaDialect(rlsDialect);
        }

        @Override
        public void afterPropertiesSet() {
            super.afterPropertiesSet();
            if (rlsDialect != null) {
                setJpaDialect(rlsDialect);
            }
        }
    }
}
