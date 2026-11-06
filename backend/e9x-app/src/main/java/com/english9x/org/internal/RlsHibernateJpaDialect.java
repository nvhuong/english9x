package com.english9x.org.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.sql.SQLException;
import org.hibernate.Session;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;

/**
 * Runs {@link RlsSessionConfigurer} at the start of every JPA / JDBC transaction: after BEGIN, before any query.
 * If it throws, {@code JpaTransactionManager.doBegin} rolls back and closes the EntityManager, so a transaction
 * with a refused or broken context never reaches application code.
 */
class RlsHibernateJpaDialect extends HibernateJpaDialect {

    private static final long serialVersionUID = 1L;

    private final transient RlsSessionConfigurer configurer;

    RlsHibernateJpaDialect(RlsSessionConfigurer configurer) {
        this.configurer = configurer;
    }

    @Override
    public Object beginTransaction(EntityManager entityManager, TransactionDefinition definition)
            throws PersistenceException, SQLException, TransactionException {
        Object transactionData = super.beginTransaction(entityManager, definition);
        entityManager.unwrap(Session.class).doWork(configurer::configure);
        return transactionData;
    }
}
