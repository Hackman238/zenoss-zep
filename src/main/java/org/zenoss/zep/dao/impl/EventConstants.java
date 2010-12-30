/*
 * This program is part of Zenoss Core, an open source monitoring platform.
 * Copyright (C) 2010, Zenoss Inc.
 * 
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 as published by
 * the Free Software Foundation.
 * 
 * For complete information please visit: http://www.zenoss.com/oss/
 */
package org.zenoss.zep.dao.impl;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public class EventConstants {
    // Table names
    public static final String TABLE_EVENT = "event";
    public static final String TABLE_EVENT_ARCHIVE = "event_archive";
    public static final String TABLE_EVENT_SUMMARY = "event_summary";
    public static final String TABLE_CLEAR_EVENTS = "clear_events";
    public static final String TABLE_INDEX_VERSION = "index_version";

    // Shared column names
    public static final String COLUMN_ELEMENT_SUB_IDENTIFIER = "element_sub_identifier";
    public static final String COLUMN_ELEMENT_SUB_TYPE_ID = "element_sub_type_id";
    public static final String COLUMN_ELEMENT_SUB_UUID = "element_sub_uuid";
    public static final String COLUMN_ELEMENT_IDENTIFIER = "element_identifier";
    public static final String COLUMN_ELEMENT_TYPE_ID = "element_type_id";
    public static final String COLUMN_ELEMENT_UUID = "element_uuid";
    public static final String COLUMN_DETAILS_JSON = "details_json";
    public static final String COLUMN_TAGS_JSON = "tags_json";
    public static final String COLUMN_MESSAGE = "message";
    public static final String COLUMN_SUMMARY = "summary";
    public static final String COLUMN_NT_EVENT_CODE = "nt_event_code";
    public static final String COLUMN_SYSLOG_PRIORITY = "syslog_priority";
    public static final String COLUMN_SYSLOG_FACILITY = "syslog_facility";
    public static final String COLUMN_AGENT_ID = "agent_id";
    public static final String COLUMN_MONITOR_ID = "monitor_id";
    public static final String COLUMN_SEVERITY_ID = "severity_id";
    public static final String COLUMN_EVENT_CLASS_MAPPING_UUID = "event_class_mapping_uuid";
    public static final String COLUMN_EVENT_CLASS_KEY_ID = "event_class_key_id";
    public static final String COLUMN_EVENT_CLASS_ID = "event_class_id";
    public static final String COLUMN_EVENT_GROUP_ID = "event_group_id";
    public static final String COLUMN_EVENT_KEY_ID = "event_key_id";
    public static final String COLUMN_FINGERPRINT = "fingerprint";
    public static final String COLUMN_FINGERPRINT_HASH = "fingerprint_hash";
    public static final String COLUMN_UUID = "uuid";

    // Only in Event
    public static final String COLUMN_CREATED = "created";

    // Summary / Archive fields
    public static final String COLUMN_STATUS_ID = "status_id";
    public static final String COLUMN_UPDATE_TIME = "update_time";
    public static final String COLUMN_FIRST_SEEN = "first_seen";
    public static final String COLUMN_STATUS_CHANGE = "status_change";
    public static final String COLUMN_LAST_SEEN = "last_seen";
    public static final String COLUMN_EVENT_COUNT = "event_count";
    public static final String COLUMN_ACKNOWLEDGED_BY_USER_UUID = "acknowledged_by_user_uuid";
    public static final String COLUMN_ACKNOWLEDGED_BY_USER_NAME = "acknowledged_by_user_name";
    public static final String COLUMN_CLEAR_FINGERPRINT_HASH = "clear_fingerprint_hash";
    public static final String COLUMN_CLEARED_BY_EVENT_UUID = "cleared_by_event_uuid";
    public static final String COLUMN_NOTES_JSON = "notes_json";

    // clear_events
    public static final String COLUMN_EVENT_SUMMARY_UUID = "event_summary_uuid";

    // Maximum lengths for CHAR/VARCHAR columns
    public static final int MAX_FINGERPRINT = 255;
    public static final int MAX_ELEMENT_IDENTIFIER = 255;
    public static final int MAX_ELEMENT_SUB_IDENTIFIER = MAX_ELEMENT_IDENTIFIER;
    public static final int MAX_EVENT_CLASS = 128;
    public static final int MAX_EVENT_CLASS_KEY = 32;
    public static final int MAX_EVENT_KEY = 128;
    public static final int MAX_MONITOR = 128;
    public static final int MAX_AGENT = 64;
    public static final int MAX_EVENT_GROUP = 64;
    public static final int MAX_SUMMARY = 255;
    public static final int MAX_MESSAGE = 4096;
    public static final int MAX_ACKNOWLEDGED_BY_USER_NAME = 32;

    private static final Set<String> SUMMARY_COLUMN_NAMES = new LinkedHashSet<String>();
    static {
        SUMMARY_COLUMN_NAMES.add(COLUMN_UUID);
        SUMMARY_COLUMN_NAMES.add(COLUMN_FINGERPRINT_HASH);
        SUMMARY_COLUMN_NAMES.add(COLUMN_FINGERPRINT);
        SUMMARY_COLUMN_NAMES.add(COLUMN_STATUS_ID);
        SUMMARY_COLUMN_NAMES.add(COLUMN_EVENT_GROUP_ID);
        SUMMARY_COLUMN_NAMES.add(COLUMN_EVENT_CLASS_ID);
        SUMMARY_COLUMN_NAMES.add(COLUMN_EVENT_CLASS_KEY_ID);
        SUMMARY_COLUMN_NAMES.add(COLUMN_EVENT_CLASS_MAPPING_UUID);
        SUMMARY_COLUMN_NAMES.add(COLUMN_EVENT_KEY_ID);
        SUMMARY_COLUMN_NAMES.add(COLUMN_SEVERITY_ID);
        SUMMARY_COLUMN_NAMES.add(COLUMN_ELEMENT_UUID);
        SUMMARY_COLUMN_NAMES.add(COLUMN_ELEMENT_TYPE_ID);
        SUMMARY_COLUMN_NAMES.add(COLUMN_ELEMENT_IDENTIFIER);
        SUMMARY_COLUMN_NAMES.add(COLUMN_ELEMENT_SUB_UUID);
        SUMMARY_COLUMN_NAMES.add(COLUMN_ELEMENT_SUB_TYPE_ID);
        SUMMARY_COLUMN_NAMES.add(COLUMN_ELEMENT_SUB_IDENTIFIER);
        // Don't include update_time - we always want this to have latest timestamp
        SUMMARY_COLUMN_NAMES.add(COLUMN_FIRST_SEEN);
        SUMMARY_COLUMN_NAMES.add(COLUMN_STATUS_CHANGE);
        SUMMARY_COLUMN_NAMES.add(COLUMN_UPDATE_TIME);
        SUMMARY_COLUMN_NAMES.add(COLUMN_LAST_SEEN);
        SUMMARY_COLUMN_NAMES.add(COLUMN_EVENT_COUNT);
        SUMMARY_COLUMN_NAMES.add(COLUMN_MONITOR_ID);
        SUMMARY_COLUMN_NAMES.add(COLUMN_AGENT_ID);
        SUMMARY_COLUMN_NAMES.add(COLUMN_SYSLOG_FACILITY);
        SUMMARY_COLUMN_NAMES.add(COLUMN_SYSLOG_PRIORITY);
        SUMMARY_COLUMN_NAMES.add(COLUMN_NT_EVENT_CODE);
        SUMMARY_COLUMN_NAMES.add(COLUMN_ACKNOWLEDGED_BY_USER_UUID);
        SUMMARY_COLUMN_NAMES.add(COLUMN_ACKNOWLEDGED_BY_USER_NAME);
        SUMMARY_COLUMN_NAMES.add(COLUMN_CLEAR_FINGERPRINT_HASH);
        SUMMARY_COLUMN_NAMES.add(COLUMN_CLEARED_BY_EVENT_UUID);
        SUMMARY_COLUMN_NAMES.add(COLUMN_SUMMARY);
        SUMMARY_COLUMN_NAMES.add(COLUMN_MESSAGE);
        SUMMARY_COLUMN_NAMES.add(COLUMN_DETAILS_JSON);
        SUMMARY_COLUMN_NAMES.add(COLUMN_TAGS_JSON);
        SUMMARY_COLUMN_NAMES.add(COLUMN_NOTES_JSON);
    }

    public static Set<String> getSummaryColumnNames() {
        return Collections.unmodifiableSet(SUMMARY_COLUMN_NAMES);
    }
}
