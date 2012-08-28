/*****************************************************************************
 * 
 * Copyright (C) Zenoss, Inc. 2011, all rights reserved.
 * 
 * This content is made available according to terms specified in
 * License.zenoss under the directory where your Zenoss product is installed.
 * 
 ****************************************************************************/


package org.zenoss.zep.impl;

import com.google.protobuf.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.zenoss.amqp.AmqpException;
import org.zenoss.amqp.Channel;
import org.zenoss.amqp.Consumer;
import org.zenoss.protobufs.zep.Zep.EventSummary;
import org.zenoss.zep.ZepUtils;
import org.zenoss.zep.dao.EventSummaryBaseDao;

/**
 * Used to import migrated events into ZEP.
 */
public class MigratedEventQueueListener extends AbstractQueueListener {

    private static final Logger logger = LoggerFactory.getLogger(MigratedEventQueueListener.class);

    private final String queueIdentifier;
    private int prefetchCount = 100;
    private EventSummaryBaseDao eventSummaryBaseDao;

    public MigratedEventQueueListener(String queueIdentifier) {
        this.queueIdentifier = queueIdentifier;
    }

    public void setPrefetchCount(int prefetchCount) {
        this.prefetchCount = prefetchCount;
    }

    public void setEventSummaryBaseDao(EventSummaryBaseDao eventSummaryBaseDao) {
        this.eventSummaryBaseDao = eventSummaryBaseDao;
    }

    @Override
    protected void configureChannel(Channel channel) throws AmqpException {
        logger.debug("Using prefetch count: {} for queue: {}", this.prefetchCount, getQueueIdentifier());
        channel.setQos(0, this.prefetchCount);
    }

    @Override
    protected String getQueueIdentifier() {
        return this.queueIdentifier;
    }

    @Override
    public void handle(Message message) throws Exception {
        EventSummary summary = (EventSummary) message;
        try {
            this.eventSummaryBaseDao.importEvent(summary);
        } catch (DuplicateKeyException e) {
            // Create event summary entry - if we get a duplicate key exception just skip importing this event as it
            // either has already been imported or there is already an active event with the same fingerprint.
            logger.info("Event with UUID {} already exists in database - skipping", summary.getUuid());
        }
    }

    @Override
    protected void receive(org.zenoss.amqp.Message<Message> message, Consumer<Message> consumer) throws Exception {
        try {
            handle(message.getBody());
            consumer.ackMessage(message);
        } catch (Exception e) {
            if (ZepUtils.isExceptionOfType(e, TransientDataAccessException.class)) {
                /* Re-queue the message if we get a temporary database failure */
                logger.debug("Transient database exception", e);
                logger.debug("Re-queueing message due to transient failure: {}", message);
                rejectMessage(consumer, message, true);
            } else {
                /* TODO: Dead letter queue or other safety net? */
                logger.warn("Failed processing message: " + message, e);
                rejectMessage(consumer, message, false);
            }
        }
    }
}
