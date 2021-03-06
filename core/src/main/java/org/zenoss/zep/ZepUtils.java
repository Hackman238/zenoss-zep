/*****************************************************************************
 * 
 * Copyright (C) Zenoss, Inc. 2010, all rights reserved.
 * 
 * This content is made available according to terms specified in
 * License.zenoss under the directory where your Zenoss product is installed.
 * 
 ****************************************************************************/


package org.zenoss.zep;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zenoss.protobufs.zep.Zep.EventSeverity;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * General utility functions used by ZEP.
 */
public final class ZepUtils {
    private static final Logger logger = LoggerFactory
            .getLogger(ZepUtils.class);

    private ZepUtils() {
    }

    /**
     * Closes the resource and logs any exceptions.
     * 
     * @param closeable
     *            Closeable resource.
     */
    public static void close(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                logger.warn(e.getMessage(), e);
            }
        }
    }

    /**
     * Create a map which can store up to the specified amount of entries.
     * 
     * @param <K>
     *            Key type.
     * @param <V>
     *            Value type.
     * @param limit
     *            The maximum number of entries to store in the map.
     * @return A new bounded map.
     */
    public static <K, V> Map<K, V> createBoundedMap(final int limit) {
        return new LinkedHashMap<K, V>() {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<K, V> eldest) {
                return size() > limit;
            }
        };
    }
    
    /**
     * Severities ordered (in ascending order).
     */
    public static final List<EventSeverity> ORDERED_SEVERITIES = Collections.unmodifiableList(Arrays
            .asList(EventSeverity.SEVERITY_CLEAR, EventSeverity.SEVERITY_DEBUG,
                    EventSeverity.SEVERITY_INFO,
                    EventSeverity.SEVERITY_WARNING,
                    EventSeverity.SEVERITY_ERROR,
                    EventSeverity.SEVERITY_CRITICAL));

    /**
     * Returns a hex string of the byte array.
     *
     * @param bytes Bytes to convert to hex.
     * @return A hex string of the byte array.
     */
    public static String hexstr(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            final String s = Integer.toHexString(b & 0xff);
            if (s.length() == 1) {
                sb.append('0');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * Returns true if the exception (or its cause) is of the specified type.
     *
     * @param t The exception to test..
     * @param type The type to check.
     * @return True if the exception (or its cause) is of the specified type, false otherwise.
     */
    public static boolean isExceptionOfType(Throwable t, Class<? extends Throwable> type) {
        boolean isOfType = false;
        while (t != null) {
            if (type.isAssignableFrom(t.getClass())) {
                isOfType = true;
                break;
            }
            t = t.getCause();
        }
        return isOfType;
    }
}
