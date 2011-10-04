/*
 * Copyright (C) 2010, Zenoss Inc.  All Rights Reserved.
 */
package org.zenoss.zep.dao.impl;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameter;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.zenoss.zep.annotations.TransactionalReadOnly;
import org.zenoss.zep.annotations.TransactionalRollbackAllExceptions;
import org.zenoss.zep.dao.DaoCache;
import org.zenoss.zep.dao.impl.compat.DatabaseCompatibility;
import org.zenoss.zep.dao.impl.compat.DatabaseType;

import javax.sql.DataSource;
import java.sql.Types;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class DaoCacheImpl implements DaoCache {

    private static final int DEFAULT_MAX_CACHE_ENTRIES = 500;
    //private static final Logger logger = LoggerFactory.getLogger(DaoCacheImpl.class);

    private final DaoTableStringLruCache eventClassCache;
    private final DaoTableStringLruCache eventClassKeyCache;
    private final DaoTableStringLruCache monitorCache;
    private final DaoTableStringLruCache agentCache;
    private final DaoTableStringLruCache groupCache;
    private final DaoTableStringLruCache eventKeyCache;

    public DaoCacheImpl(DataSource ds, DatabaseCompatibility databaseCompatibility) {
        JdbcTemplate template = new JdbcTemplate(ds);
        this.eventClassCache = new DaoTableStringLruCache(template, databaseCompatibility, "event_class");
        this.eventClassKeyCache = new DaoTableStringLruCache(template, databaseCompatibility, "event_class_key");
        this.monitorCache = new DaoTableStringLruCache(template, databaseCompatibility, "monitor");
        this.agentCache = new DaoTableStringLruCache(template, databaseCompatibility, "agent");
        this.groupCache = new DaoTableStringLruCache(template, databaseCompatibility, "event_group");
        this.eventKeyCache = new DaoTableStringLruCache(template, databaseCompatibility, "event_key");
    }

    public void init() {
        this.eventClassCache.init();
        this.eventClassKeyCache.init();
        this.monitorCache.init();
        this.agentCache.init();
        this.groupCache.init();
        this.eventKeyCache.init();
    }

    private <T> int getIdFromName(final DaoTableCache<T> cache, final T name) {
        Integer cached = cache.getCache().getIdFromName(name);
        if (cached == null) {
            final int id = cache.insertOrSelect(name);
            TransactionSynchronizationManager
                    .registerSynchronization(new TransactionSynchronizationAdapter() {
                        @Override
                        public void afterCommit() {
                            cache.getCache().cache(name, id);
                        }
                    });
            cached = id;
        }
        return cached;
    }

    private <T> T getNameFromId(final DaoTableCache<T> cache, final int id) {
        T cached = cache.getCache().getNameFromId(id);
        if (cached == null) {
            final T name = cache.findNameFromId(id);
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronizationAdapter() {
                            @Override
                            public void afterCommit() {
                                cache.getCache().cache(name, id);
                            }
                        });
            }
            else {
                // Not part of a transaction - can safely cache
                cache.getCache().cache(name, id);
            }
            cached = name;
        }
        return cached;
    }

    @Override
    @TransactionalRollbackAllExceptions
    public int getEventClassId(String eventClass) {
        return getIdFromName(this.eventClassCache, eventClass);
    }

    @Override
    @TransactionalReadOnly
    public String getEventClassFromId(int id) {
        return getNameFromId(this.eventClassCache, id);
    }

    @Override
    @TransactionalRollbackAllExceptions
    public int getEventClassKeyId(String eventClassKey) {
        return getIdFromName(this.eventClassKeyCache, eventClassKey);
    }

    @Override
    @TransactionalReadOnly
    public String getEventClassKeyFromId(int id) {
        return getNameFromId(this.eventClassKeyCache, id);
    }

    @Override
    @TransactionalRollbackAllExceptions
    public int getMonitorId(String monitor) {
        return getIdFromName(this.monitorCache, monitor);
    }

    @Override
    @TransactionalReadOnly
    public String getMonitorFromId(int id) {
        return getNameFromId(this.monitorCache, id);
    }

    @Override
    @TransactionalRollbackAllExceptions
    public int getAgentId(String agent) {
        return getIdFromName(this.agentCache, agent);
    }

    @Override
    @TransactionalReadOnly
    public String getAgentFromId(int id) {
        return getNameFromId(this.agentCache, id);
    }

    @Override
    @TransactionalRollbackAllExceptions
    public int getEventGroupId(String eventGroup) {
        return getIdFromName(this.groupCache, eventGroup);
    }

    @Override
    @TransactionalReadOnly
    public String getEventGroupFromId(int id) {
        return getNameFromId(this.groupCache, id);
    }

    @Override
    @TransactionalRollbackAllExceptions
    public int getEventKeyId(String eventKey) {
        return getIdFromName(this.eventKeyCache, eventKey);
    }

    @Override
    @TransactionalReadOnly
    public String getEventKeyFromId(int id) {
        return getNameFromId(this.eventKeyCache, id);
    }

    private static class BiMap<T> {
        private final Map<T, Integer> nameToIdMap;
        private final Map<Integer, T> idToNameMap;
        private final int maxCache;

        /**
         * Creates a BiMap which is optionally bounded by the specified size.
         * 
         * @param maxCache
         *            The maximum size of the maps to keep. If this is greater
         *            than zero, then this BiMap will function as a LRU cache.
         */
        public BiMap(int maxCache) {
            this.nameToIdMap = new HashMap<T, Integer>();
            if (maxCache <= 0) {
                this.idToNameMap = new HashMap<Integer, T>();
            } else {
                this.idToNameMap = new LinkedHashMap<Integer, T>();
            }
            this.maxCache = maxCache;
        }

        public synchronized Integer getIdFromName(T name) {
            return this.nameToIdMap.get(name);
        }

        public synchronized T getNameFromId(int id) {
            return this.idToNameMap.get(id);
        }

        public synchronized void cache(T name, int id) {
            this.idToNameMap.put(id, name);
            this.nameToIdMap.put(name, id);
            if (maxCache > 0 && idToNameMap.size() > maxCache) {
                final Iterator<Map.Entry<Integer, T>> it = this.idToNameMap
                        .entrySet().iterator();
                final Map.Entry<Integer, T> lru = it.next();
                it.remove();
                this.nameToIdMap.remove(lru.getValue());
            }
        }
    }

    private static abstract class DaoTableCache<T> {
        protected final JdbcTemplate template;
        protected final DatabaseCompatibility databaseCompatibility;
        protected final String tableName;
        protected final BiMap<T> cache;
        protected final String selectNameByIdSql;
        protected final String selectIdByNameSql;
        static final String COLUMN_ID = "id";
        static final String COLUMN_NAME = "name";

        public DaoTableCache(JdbcTemplate template, DatabaseCompatibility databaseCompatibility,
                             String tableName, BiMap<T> cache) {
            this.template = template;
            this.databaseCompatibility = databaseCompatibility;
            this.tableName = tableName;
            this.cache = cache;
            this.selectNameByIdSql = "SELECT " + COLUMN_NAME + " FROM " + this.tableName + " WHERE " + COLUMN_ID + "=?";
            this.selectIdByNameSql = "SELECT " + COLUMN_ID + " FROM " + this.tableName + " WHERE " + COLUMN_NAME + "=?";
        }

        public void init() {
        }

        public BiMap<T> getCache() {
            return cache;
        }

        public abstract int insertOrSelect(T name);

        public abstract T findNameFromId(int id);
    }

    private static class DaoTableStringLruCache extends DaoTableCache<String> {
        private SimpleJdbcInsert mysqlInsert;
        private SimpleJdbcCall postgresqlCall;

        public DaoTableStringLruCache(JdbcTemplate template, DatabaseCompatibility databaseCompatibility,
                                      String tableName) {
            this(template, databaseCompatibility, tableName, DEFAULT_MAX_CACHE_ENTRIES);
        }

        public DaoTableStringLruCache(JdbcTemplate template, DatabaseCompatibility databaseCompatibility,
                                      String tableName, final int limit) {
            super(template, databaseCompatibility, tableName, new BiMap<String>(limit));
        }

        public void init() {
            super.init();
            if (databaseCompatibility.getDatabaseType() == DatabaseType.MYSQL) {
                this.mysqlInsert = new SimpleJdbcInsert(template).withTableName(tableName)
                    .usingColumns(COLUMN_NAME).usingGeneratedKeyColumns(COLUMN_ID);
            }
            else if (databaseCompatibility.getDatabaseType() == DatabaseType.POSTGRESQL) {
                this.postgresqlCall = new SimpleJdbcCall(this.template).withFunctionName("dao_cache_insert")
                        .declareParameters(new SqlParameter("p_table_name", Types.VARCHAR),
                                new SqlParameter("p_name", Types.VARCHAR)).withReturnValue();
            }
        }

        @Override
        public int insertOrSelect(String name) {
            if (databaseCompatibility.getDatabaseType() == DatabaseType.MYSQL) {
                try {
                    final Map<String, Object> args = Collections.<String, Object> singletonMap(COLUMN_NAME, name);
                    return this.mysqlInsert.executeAndReturnKey(args).intValue();
                } catch (DuplicateKeyException e) {
                    return this.template.queryForInt(selectIdByNameSql, name);
                }
            }
            else if (databaseCompatibility.getDatabaseType() == DatabaseType.POSTGRESQL) {
                return this.postgresqlCall.executeFunction(Integer.class, this.tableName, name);
            }
            else {
                throw new IllegalStateException("Unsupported database type: " + databaseCompatibility.getDatabaseType());
            }
        }

        @Override
        public String findNameFromId(int id) {
            return template.queryForObject(this.selectNameByIdSql, String.class, id);
        }
    }
}
