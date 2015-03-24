/*****************************************************************************
 *
 * Copyright (C) Zenoss, Inc. 2011, all rights reserved.
 *
 * This content is made available according to terms specified in
 * License.zenoss under the directory where your Zenoss product is installed.
 *
 ****************************************************************************/


package org.zenoss.zep.dao.impl;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.zenoss.protobufs.zep.Zep.EventSummary;
import org.zenoss.zep.ZepException;
import org.zenoss.zep.annotations.TransactionalRollbackAllExceptions;
import org.zenoss.zep.dao.EventIndexHandler;
import org.zenoss.zep.dao.EventIndexQueueDao;
import org.zenoss.zep.dao.EventSummaryDao;
import org.zenoss.zep.dao.IndexQueueID;
import org.zenoss.zep.dao.impl.compat.DatabaseCompatibility;
import org.zenoss.zep.events.EventIndexQueueSizeEvent;
import org.zenoss.zep.index.WorkQueue;
import org.zenoss.zep.index.impl.EventIndexBackendTask;
import org.zenoss.zep.index.impl.RedisWorkQueueBuilder;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of EventIndexQueueDao.
 */
public class EventIndexQueueDaoImpl implements EventIndexQueueDao, ApplicationEventPublisherAware {

    private final String queueTableName;
    private final String tableName;
    private EventSummaryDao eventSummaryDao;
    private ApplicationEventPublisher applicationEventPublisher;


    private final boolean isArchive;

    private WorkQueue redisWorkQueue;

    private MetricRegistry metrics;
    private Counter indexedCounter;
    private long lastQueueSize = -1;

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    public EventIndexQueueDaoImpl(DataSource ds, boolean isArchive, EventDaoHelper daoHelper,
                                  DatabaseCompatibility databaseCompatibility) {
        this.isArchive = isArchive;
        if (isArchive) {
            this.tableName = EventConstants.TABLE_EVENT_ARCHIVE;
        } else {
            this.tableName = EventConstants.TABLE_EVENT_SUMMARY;
        }
        this.queueTableName = this.tableName + "_index_queue";
    }

    @Override
    @TransactionalRollbackAllExceptions
    public List<IndexQueueID> indexEvents(final EventIndexHandler handler, final int limit) throws ZepException {
        return indexEvents(handler, limit, -1L);
    }

    @Resource(name = "metrics")
    public void setBean(MetricRegistry metrics) {
        this.metrics = metrics;
        String metricName = "";
        if (this.isArchive) {
            metricName = MetricRegistry.name(this.getClass().getCanonicalName(), "archiveIndexQueueSize");
            this.indexedCounter = metrics.counter(MetricRegistry.name(this.getClass().getCanonicalName(), "summary.indexed"));
        } else {
            metricName = MetricRegistry.name(this.getClass().getCanonicalName(), "summaryIndexQueueSize");
            this.indexedCounter = metrics.counter(MetricRegistry.name(this.getClass().getCanonicalName(), "summary.indexed"));
        }
        this.metrics.register(metricName, new Gauge<Long>() {
            @Override
            public Long getValue() {
                return lastQueueSize;
            }
        });
    }

    @Override
    @TransactionalRollbackAllExceptions
    public List<IndexQueueID> indexEvents(final EventIndexHandler handler, final int limit,
                                          final long maxUpdateTime) throws ZepException {
        final Map<String, Object> selectFields = new HashMap<String, Object>();
        selectFields.put("_limit", limit);

        final String sql;

        // Used for partition pruning
        final String queryJoinLastSeen = (this.isArchive) ? "AND iq.last_seen=es.last_seen " : "";
        List<EventIndexBackendTask> eventsIDs;
        try {
            eventsIDs = this.redisWorkQueue.poll(limit, 250, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // Restore the interrupted status
            Thread.currentThread().interrupt();
            throw new ZepException(e);
        }

        //filter for events smaller than maxUpdateTime
        if (maxUpdateTime > 0) {
            List<EventIndexBackendTask> filteredEventIds = Lists.newArrayListWithCapacity(eventsIDs.size());
            for (EventIndexBackendTask et : eventsIDs) {
                if (et.lastSeen > maxUpdateTime) {
                    break;
                } else {
                    filteredEventIds.add(et);
                }
            }
            eventsIDs = filteredEventIds;
        }

        final Set<String> eventUuids = new HashSet<String>();
        final Set<String> found = new HashSet<String>();

        //clear out dups thought there shouldn't be any.
        for (EventIndexBackendTask et : eventsIDs) {
            String iqUuid = et.uuid;
            eventUuids.add(iqUuid);
        }

        //read in events and figure out which are deleted
        List<EventSummary> indexed = this.eventSummaryDao.findByUuids(Lists.newArrayList(eventUuids));
        for (EventSummary es : indexed) {
            found.add(es.getUuid());
        }
        Set<String> deleted = Sets.difference(eventUuids, found);

        if (!indexed.isEmpty()) {
            try {
                handler.prepareToHandle(indexed);
            } catch (Exception e) {
                throw new RuntimeException(e.getLocalizedMessage(), e);
            }
        }
        for (EventSummary summary : indexed) {
            try {
                handler.handle(summary);
            } catch (Exception e) {
                throw new RuntimeException(e.getLocalizedMessage(), e);
            }
        }
        for (String iqUuid : deleted) {
            try {
                handler.handleDeleted(iqUuid);
            } catch (Exception e) {
                throw new RuntimeException(e.getLocalizedMessage(), e);
            }
        }
        if (!eventsIDs.isEmpty()) {
            try {
                handler.handleComplete();
            } catch (Exception e) {
                throw new ZepException(e.getLocalizedMessage(), e);
            }
        }

        // publish current size of event_*_index_queue
        this.lastQueueSize = this.getQueueLength();
        this.applicationEventPublisher.publishEvent(
                new EventIndexQueueSizeEvent(this, tableName, this.lastQueueSize, limit)
        );

        this.indexedCounter.inc(eventsIDs.size());
        ArrayList<IndexQueueID> result = Lists.newArrayListWithCapacity(eventsIDs.size());
        for (EventIndexBackendTask id : eventsIDs) {
            result.add(new IndexQueueID(id));
        }
        return result;
    }

    @Override
    public void queueEvents(List<String> uuids, long timestamp) {
        if (uuids.isEmpty()) {
            return;
        }

        List<EventIndexBackendTask> tasks = Lists.newArrayListWithCapacity(uuids.size());
        for (String uuid : uuids) {
            tasks.add(EventIndexBackendTask.Index(uuid, timestamp));
        }
        this.redisWorkQueue.addAll(tasks);
    }

    @Override
    public long getQueueLength() {
        return this.redisWorkQueue.size();
    }

    @Override
    @TransactionalRollbackAllExceptions
    public void deleteIndexQueueIds(List<IndexQueueID> queueIds) throws ZepException {
        if (!queueIds.isEmpty()) {
            //TODO record metric counter
            //lock on worker
            ArrayList<EventIndexBackendTask> ids = Lists.newArrayListWithCapacity(queueIds.size());
            for (IndexQueueID eid : queueIds) {
                ids.add((EventIndexBackendTask) eid.id);
            }
            this.redisWorkQueue.completeAll(ids);
        }
    }

    public void setQueueBuilder(RedisWorkQueueBuilder queueBuilder) {
        this.redisWorkQueue = queueBuilder.build(this.queueTableName);
    }

    public void setEventSummaryDao(EventSummaryDao eventSummaryDao) {
        this.eventSummaryDao = eventSummaryDao;
    }
}
