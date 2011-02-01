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

package org.zenoss.zep.index.impl;

import com.google.protobuf.ProtocolMessageEnum;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.TermAttribute;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermEnum;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MultiPhraseQuery;
import org.apache.lucene.search.NumericRangeQuery;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.WildcardQuery;
import org.zenoss.protobufs.util.Util.TimestampRange;
import org.zenoss.protobufs.zep.Zep.FilterOperator;
import org.zenoss.protobufs.zep.Zep.NumberRange;
import org.zenoss.zep.ZepException;
import org.zenoss.zep.ZepUtils;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

public class QueryBuilder {
    private List<Query> queries = new ArrayList<Query>();

    public QueryBuilder addField(String key, String value) {
        queries.add(new TermQuery(new Term(key, value)));
        return this;
    }

    private static String unquote(String str) {
        final int len = str.length();
        String unquoted = str;
        if (len >= 2 && str.charAt(0) == '"' && str.charAt(len-1) == '"') {
            unquoted = str.substring(1, len-1);
        }
        return unquoted;
    }

    /**
     * Special case queries for identifier fields. Queries that are enclosed in quotes result in
     * an exact query for the string in the non-analyzed field. Queries that end in an asterisk
     * result in a prefix query in the non-analyzed field. Queries of a length less than
     * the {@link IdentifierAnalyzer#MIN_NGRAM_SIZE} are converted to prefix queries on the
     * non-analyzed field. All other queries are send to the NGram analyzed field for efficient
     * substring matches.
     *
     * @param analyzedFieldName Analyzed field name.
     * @param nonAnalyzedFieldName Non-analyzed field name.
     * @param values Queries to search on.
     * @param analyzer The analyzer used for the fields (used to build the NGram queries).
     * @return This query builder instance (for chaining).
     * @throws ZepException If an exception occurs.
     */
    public QueryBuilder addIdentifierFields(String analyzedFieldName, String nonAnalyzedFieldName,
                                            Collection<String> values, Analyzer analyzer) throws ZepException {
        if (!values.isEmpty()) {
            final BooleanClause.Occur occur = BooleanClause.Occur.SHOULD;
            final BooleanQuery booleanQuery = new BooleanQuery();

            for (String value : values) {
                final Query query;
                final String unquoted = unquote(value);
                if (value.isEmpty() || !unquoted.equals(value)) {
                    query = new TermQuery(new Term(nonAnalyzedFieldName, unquoted));
                }
                else if (value.endsWith("*")) {
                    query = new PrefixQuery(new Term(nonAnalyzedFieldName, value.substring(0, value.length()-1)));
                }
                else if (value.length() < IdentifierAnalyzer.MIN_NGRAM_SIZE) {
                    query = new PrefixQuery(new Term(analyzedFieldName, value));
                }
                else {
                    final PhraseQuery pq = new PhraseQuery();
                    query = pq;
                    TokenStream ts = analyzer.tokenStream(analyzedFieldName, new StringReader(value));
                    TermAttribute term = ts.addAttribute(TermAttribute.class);
                    try {
                        while (ts.incrementToken()) {
                            pq.add(new Term(analyzedFieldName, term.term()));
                        }
                        ts.end();
                    } catch (IOException e) {
                        throw new ZepException(e.getLocalizedMessage(), e);
                    } finally {
                        ZepUtils.close(ts);
                    }
                }
                booleanQuery.add(query, occur);
            }
            queries.add(booleanQuery);
        }
        return this;
    }

    /**
     * Special case queries for event classes. Queries that begin with a slash and end
     * with a slash or an asterisk result in a starts-with query on the non-analyzed
     * field name. Queries that begin with a slash and end with anything else are an
     * exact match on the non-analyzed field name. Otherwise, the query is run through
     * the analyzer and substring matching is performed.
     *
     * @param analyzedFieldName Analyzed field name.
     * @param nonAnalyzedFieldName Non-analyzed field name.
     * @param values Queries to search on.
     * @param analyzer The analyzer used for the fields.
     * @param reader The reader (used to query terms).
     * @return This query builder instance (for chaining).
     * @throws ZepException If an exception occurs.
     */
    public QueryBuilder addEventClassFields(String analyzedFieldName, String nonAnalyzedFieldName,
                                            Collection<String> values, Analyzer analyzer,
                                            IndexReader reader) throws ZepException {
        if (!values.isEmpty()) {
            final BooleanClause.Occur occur = BooleanClause.Occur.SHOULD;
            final BooleanQuery booleanQuery = new BooleanQuery();

            for (String value : values) {
                final Query query;
                if (value.startsWith("/")) {
                    value = value.toLowerCase();
                    // Starts-with
                    if (value.endsWith("/")) {
                        query = new PrefixQuery(new Term(nonAnalyzedFieldName, value));
                    }
                    // Prefix
                    else if (value.endsWith("*")) {
                        value = value.substring(0, value.length()-1);
                        query = new PrefixQuery(new Term(nonAnalyzedFieldName, value));
                    }
                    // Exact match
                    else {
                        query = new TermQuery(new Term(nonAnalyzedFieldName, value + "/"));
                    }
                }
                else {
                    final MultiPhraseQuery pq = new MultiPhraseQuery();
                    query = pq;

                    // Get all terms stored in index for this field
                    TermEnum termEnum = null;
                    TreeSet<String> allTerms = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
                    try {
                        termEnum = reader.terms(new Term(analyzedFieldName, ""));
                        do {
                            Term term = termEnum.term();
                            if (!analyzedFieldName.equals(term.field())) {
                                break;
                            }
                            allTerms.add(term.text());
                        } while (termEnum.next());
                    } catch (IOException e) {
                        throw new ZepException(e.getLocalizedMessage(), e);
                    } finally {
                        ZepUtils.close(termEnum);
                    }

                    TokenStream ts = analyzer.tokenStream(analyzedFieldName, new StringReader(value));
                    TermAttribute term = ts.addAttribute(TermAttribute.class);
                    try {
                        while (ts.incrementToken()) {
                            String t = term.term();
                            if (t.endsWith("*")) {
                                t = t.substring(0, t.length()-1);

                                List<Term> terms = new ArrayList<Term>();
                                SortedSet<String> tailSet = allTerms.tailSet(t);
                                for (String termText : tailSet) {
                                    if (!termText.startsWith(t)) {
                                        break;
                                    }
                                    terms.add(new Term(analyzedFieldName, termText));
                                }

                                // Ensure that we don't return results if term doesn't exist
                                if (terms.isEmpty()) {
                                    pq.add(new Term(analyzedFieldName, t));
                                }
                                else {
                                    pq.add(terms.toArray(new Term[terms.size()]));
                                }
                            }
                            else {
                                pq.add(new Term(analyzedFieldName, term.term()));
                            }
                        }
                        ts.end();
                    } catch (IOException e) {
                        throw new ZepException(e.getLocalizedMessage(), e);
                    } finally {
                        ZepUtils.close(ts);
                    }
                }
                booleanQuery.add(query, occur);
            }
            queries.add(booleanQuery);
        }
        return this;
    }

    public QueryBuilder addWildcardFields(String key, Collection<String> values, boolean lowerCase) {
        if (!values.isEmpty()) {
            final BooleanClause.Occur occur = BooleanClause.Occur.SHOULD;
            final BooleanQuery booleanQuery = new BooleanQuery();

            for (String value : values) {
                if (lowerCase) {
                    value = value.toLowerCase();
                }
                booleanQuery.add(new WildcardQuery(new Term(key, value)), occur);
            }
            queries.add(booleanQuery);
        }
        return this;
    }

    public QueryBuilder addField(String key, List<String> values) throws ZepException {
        return addField(key, values, FilterOperator.OR);
    }

    public QueryBuilder addField(String key, List<String> values, FilterOperator op) throws ZepException {
        if (!values.isEmpty()) {
            final BooleanClause.Occur occur;
            final BooleanQuery booleanQuery = new BooleanQuery();
            if (op == FilterOperator.AND) {
                occur = BooleanClause.Occur.MUST;
            } else {
                occur = BooleanClause.Occur.SHOULD;
            }

            for (String value : values) {
                booleanQuery.add(new TermQuery(new Term(key, value)), occur);
            }
            queries.add(booleanQuery);
        }
        return this;
    }

    public QueryBuilder addFieldOfIntegers(String key, List<Integer> values) throws ZepException {
        if (!values.isEmpty()) {
            final BooleanClause.Occur occur = BooleanClause.Occur.SHOULD;
            final BooleanQuery booleanQuery = new BooleanQuery();

            // Condense adjacent values into one range
            Collections.sort(values);
            Iterator<Integer> it = values.iterator();
            int from = it.next();
            int to = from;
            while (it.hasNext()) {
                int value = it.next();
                if (value == to + 1) {
                    to = value;
                }
                else {
                    booleanQuery.add(NumericRangeQuery.newIntRange(key, from, to, true, true), occur);
                    from = to = value;
                }
            }
            booleanQuery.add(NumericRangeQuery.newIntRange(key, from, to, true, true), occur);

            queries.add(booleanQuery);
        }
        return this;
    }

    public QueryBuilder addFieldOfEnumNumbers(String key, List<? extends ProtocolMessageEnum> values) throws ZepException {
        List<Integer> valuesList = new ArrayList<Integer>(values.size());
        for ( ProtocolMessageEnum e : values ) {
            valuesList.add(e.getNumber());
        }
        addFieldOfIntegers(key, valuesList);
        return this;
    }

    public QueryBuilder addRange(String key, Long from, Long to) {
        this.queries.add(NumericRangeQuery.newLongRange(key, from, to, true, true));
        return this;
    }

    public QueryBuilder addTimestampRanges(String key, List<TimestampRange> ranges) {
        if (!ranges.isEmpty()) {
            final BooleanClause.Occur occur = BooleanClause.Occur.SHOULD;
            final BooleanQuery booleanQuery = new BooleanQuery();

            Long from = null, to = null;
            for (TimestampRange range : ranges) {
                if (range.hasStartTime()) {
                    from = range.getStartTime();
                }
                if (range.hasEndTime()) {
                    to = range.getEndTime();
                }
                booleanQuery.add(NumericRangeQuery.newLongRange(key, from, to, true, true), occur);
            }
            this.queries.add(booleanQuery);
        }
        return this;
    }

    public QueryBuilder addRanges(String key, Collection<NumberRange> ranges) throws ZepException {
        if (!ranges.isEmpty()) {
            final BooleanClause.Occur occur = BooleanClause.Occur.SHOULD;
            final BooleanQuery booleanQuery = new BooleanQuery();

            for (NumberRange range : ranges) {
                Integer from = null, to = null;
                if (range.hasFrom()) {
                    Long f = range.getFrom();
                    if (f < Integer.MIN_VALUE) {
                        throw new ZepException("NumberRange value 'from' must be an integer.");
                    }
                    from = f.intValue();
                }
                if (range.hasTo()) {
                    Long t = range.getTo();
                    if (t > Integer.MAX_VALUE) {
                        throw new ZepException("NumberRange value 'to' must be an integer.");
                    }
                    to = t.intValue();
                }
                booleanQuery.add(NumericRangeQuery.newIntRange(key, from, to, true, true), occur);
            }
            queries.add(booleanQuery);
        }
        return this;
    }

    public BooleanQuery build() {
        if (this.queries.isEmpty()) {
            return null;
        }
        
        BooleanQuery booleanQuery = new BooleanQuery();
        final BooleanClause.Occur occur = BooleanClause.Occur.MUST;
        for (Query query : this.queries) {
            booleanQuery.add(query, occur);
        }
        this.queries.clear();
        return booleanQuery;
    }

    @Override
    public String toString() {
        return "QueryBuilder{" +
                "queries=" + queries +
                '}';
    }
}
