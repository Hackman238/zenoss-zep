/*
 * Copyright (C) 2010, Zenoss Inc.  All Rights Reserved.
 */

package org.zenoss.zep.index.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.zenoss.protobufs.zep.Zep.EventDetailItem;
import org.zenoss.protobufs.zep.Zep.EventSummary;
import org.zenoss.zep.EventPostProcessingPlugin;
import org.zenoss.zep.PluginService;
import org.zenoss.zep.ZepException;
import org.zenoss.zep.dao.EventArchiveDao;
import org.zenoss.zep.dao.EventIndexHandler;
import org.zenoss.zep.dao.EventDetailsConfigDao;
import org.zenoss.zep.dao.EventIndexQueueDao;
import org.zenoss.zep.dao.EventSummaryBaseDao;
import org.zenoss.zep.dao.EventSummaryDao;
import org.zenoss.zep.dao.IndexMetadata;
import org.zenoss.zep.dao.IndexMetadataDao;
import org.zenoss.zep.dao.impl.DaoUtils;
import org.zenoss.zep.index.EventIndexDao;
import org.zenoss.zep.index.EventIndexer;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class EventIndexerImpl implements EventIndexer {
    private static final Logger logger = LoggerFactory.getLogger(EventIndexerImpl.class);

    private static final int INDEX_LIMIT = 1000;
    
    private EventSummaryDao eventSummaryDao;
    private EventIndexDao eventSummaryIndexDao;
    private EventIndexQueueDao eventSummaryQueueDao;
    
    private EventArchiveDao eventArchiveDao;
    private EventIndexDao eventArchiveIndexDao;
    private EventIndexQueueDao eventArchiveQueueDao;
    
    private PluginService pluginService;
    private IndexMetadataDao indexMetadataDao;
    private EventDetailsConfigDao eventDetailsConfigDao;
    private byte[] indexVersionHash;

    public void setEventSummaryDao(EventSummaryDao eventSummaryDao) {
        this.eventSummaryDao = eventSummaryDao;
    }
    
    public void setEventSummaryIndexDao(EventIndexDao eventSummaryIndexDao) {
        this.eventSummaryIndexDao = eventSummaryIndexDao;
    }
    
    public void setEventSummaryQueueDao(EventIndexQueueDao eventSummaryQueueDao) {
        this.eventSummaryQueueDao = eventSummaryQueueDao;
    }
    
    public void setEventArchiveDao(EventArchiveDao eventArchiveDao) {
        this.eventArchiveDao = eventArchiveDao;
    }

    public void setEventArchiveIndexDao(EventIndexDao eventIndexDao) {
        this.eventArchiveIndexDao = eventIndexDao;
    }
    
    public void setEventArchiveQueueDao(EventIndexQueueDao eventArchiveQueueDao) {
        this.eventArchiveQueueDao = eventArchiveQueueDao;
    }
    
    public void setPluginService(PluginService pluginService) {
        this.pluginService = pluginService;
    }

    public void setIndexMetadataDao(IndexMetadataDao indexMetadataDao) {
        this.indexMetadataDao = indexMetadataDao;
    }

    public void setEventDetailsConfigDao(EventDetailsConfigDao eventDetailsConfigDao) {
        this.eventDetailsConfigDao = eventDetailsConfigDao;
    }

    private static byte[] calculateIndexVersionHash(Map<String,EventDetailItem> detailItems) throws ZepException {
        TreeMap<String,EventDetailItem> sorted = new TreeMap<String,EventDetailItem>(detailItems);
        StringBuilder indexConfigStr = new StringBuilder();
        for (EventDetailItem item : sorted.values()) {
            // Only key and type affect the indexing behavior - ignore changes to display name
            indexConfigStr.append('|');
            indexConfigStr.append(item.getKey());
            indexConfigStr.append('|');
            indexConfigStr.append(item.getType().name());
            indexConfigStr.append('|');
        }
        if (indexConfigStr.length() == 0) {
            return null;
        }
        return DaoUtils.sha1(indexConfigStr.toString());
    }

    @Override
    @Transactional
    public synchronized void init() throws ZepException {
        Map<String,EventDetailItem> detailItems = this.eventDetailsConfigDao.getEventDetailItemsByName();
        for (EventDetailItem item : detailItems.values()) {
            logger.info("Indexed event detail: {}", item);
        }
        this.indexVersionHash = calculateIndexVersionHash(detailItems);
        this.eventSummaryIndexDao.setIndexDetails(detailItems);
        this.eventArchiveIndexDao.setIndexDetails(detailItems);

        rebuildIndex(this.eventSummaryDao, this.eventSummaryIndexDao);
        rebuildIndex(this.eventArchiveDao, this.eventArchiveIndexDao);
    }

    private void rebuildIndex(final EventSummaryBaseDao baseDao, final EventIndexDao indexDao)
            throws ZepException {
        final IndexMetadata indexMetadata = this.indexMetadataDao.findIndexMetadata(indexDao.getName());
        final int numDocs = indexDao.getNumDocs();
        
        // Rebuild index if we detect that we have never indexed before.
        if (indexMetadata == null) {
            if (numDocs > 0) {
                logger.info("Inconsistent state between index and database. Clearing index.");
                indexDao.clear();
            }
            /* Recreate the index */
            recreateIndex(baseDao, indexDao);
        }
        // Rebuild index if the version changed.
        else if (indexVersionChanged(indexMetadata)) {
            indexDao.reindex();
            this.indexMetadataDao.updateIndexVersion(indexDao.getName(), IndexConstants.INDEX_VERSION,
                    this.indexVersionHash);
        }
        // Rebuild index if it has potentially been manually wiped from disk.
        else if (numDocs == 0) {
            /* Recreate the index */
            recreateIndex(baseDao, indexDao);
        }
    }

    private boolean indexVersionChanged(IndexMetadata indexMetadata) {
        boolean changed = false;
        if (IndexConstants.INDEX_VERSION != indexMetadata.getIndexVersion()) {
            logger.info("Index version changed: previous={}, new={}", indexMetadata.getIndexVersion(),
                    IndexConstants.INDEX_VERSION);
            changed = true;
        }
        else if (!Arrays.equals(this.indexVersionHash, indexMetadata.getIndexVersionHash())) {
            logger.info("Index configuration changed.");
            changed = true;
        }
        return changed;
    }

    private void recreateIndex(final EventSummaryBaseDao baseDao, final EventIndexDao indexDao) throws ZepException {
        logger.info("Recreating index for table {}", indexDao.getName());
        String startingUuid = null;
        final long throughTime = System.currentTimeMillis();
        int numIndexed = 0;
        List<EventSummary> events;
        
        do {
            events = baseDao.listBatch(startingUuid, throughTime, INDEX_LIMIT);
            for (EventSummary event : events) {
                indexDao.stage(event);
                startingUuid = event.getUuid();
                ++numIndexed;
            }
        } while (!events.isEmpty());
        
        if (numIndexed > 0) {
            logger.info("Committing changes to index on table: {}", indexDao.getName());
            indexDao.commit(true);
        }
        this.indexMetadataDao.updateIndexVersion(indexDao.getName(), IndexConstants.INDEX_VERSION,
                this.indexVersionHash);
        logger.info("Finished recreating index for {} events on table: {}", numIndexed, indexDao.getName());
    }
    
    @Override
    public synchronized int index() throws ZepException {
        int totalIndexed = doIndex(eventSummaryQueueDao, eventSummaryIndexDao, -1L);
        totalIndexed += doIndex(eventArchiveQueueDao, eventArchiveIndexDao, -1L);
        return totalIndexed;
    }

    @Override
    public synchronized int indexFully() throws ZepException {
        int totalIndexed = 0;
        final long now = System.currentTimeMillis();
        int numIndexed;
        do {
            numIndexed = doIndex(eventSummaryQueueDao, eventSummaryIndexDao, now);
            numIndexed += doIndex(eventArchiveQueueDao, eventArchiveIndexDao, now);
            totalIndexed += numIndexed;
        } while (numIndexed > 0);
        
        return totalIndexed;
    }

    private int doIndex(final EventIndexQueueDao queueDao, final EventIndexDao indexDao, long throughTime)
            throws ZepException {
        final List<EventPostProcessingPlugin> plugins = this.pluginService.getPostProcessingPlugins();
        
        int numIndexed = queueDao.indexEvents(new EventIndexHandler() {
            @Override
            public void handle(EventSummary event) throws Exception {
                indexDao.stage(event);
                for (EventPostProcessingPlugin plugin : plugins) {
                    try {
                        plugin.processEvent(event);
                    } catch (Exception e) {
                        // Post-processing plug-in failures are not fatal errors.
                        logger.warn("Failed to run post-processing plug-in on event: " + event, e);
                    }
                }
            }

            @Override
            public void handleDeleted(String uuid) throws Exception {
                indexDao.stageDelete(uuid);
            }
            
            @Override
            public void handleComplete() throws Exception {
                indexDao.commit();
            }
        }, INDEX_LIMIT, throughTime);
        
        if (numIndexed > 0) {
            logger.debug("Completed indexing {} events on {}", numIndexed, indexDao.getName());            
        }

        return numIndexed;
    }
}