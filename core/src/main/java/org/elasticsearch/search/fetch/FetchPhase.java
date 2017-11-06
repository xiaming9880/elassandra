/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.fetch;

import org.apache.cassandra.cql3.QueryOptions;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.ResultSet;
import org.apache.cassandra.cql3.UntypedResultSet;
import org.apache.cassandra.cql3.UntypedResultSet.Row;
import org.apache.cassandra.cql3.statements.ParsedStatement;
import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.QueryState;
import org.apache.cassandra.transport.messages.ResultMessage;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.BitSet;
import org.elassandra.index.mapper.internal.NodeFieldMapper;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.cluster.service.ClusterService.DocPrimaryKey;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.fieldvisitor.CustomFieldsVisitor;
import org.elasticsearch.index.fieldvisitor.FieldsVisitor;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.IdFieldMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.ObjectMapper;
import org.elasticsearch.index.mapper.SourceFieldMapper;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHitField;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.SearchPhase;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;
import org.elasticsearch.search.fetch.subphase.InnerHitsFetchSubPhase;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.lookup.SourceLookup;
import org.elasticsearch.tasks.TaskCancelledException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;

import static org.elasticsearch.common.xcontent.XContentFactory.contentBuilder;

/**
 * Fetch phase of a search request, used to fetch the actual top matching documents to be returned to the client, identified
 * after reducing all of the matches returned by the query phase
 */
public class FetchPhase implements SearchPhase {

    protected final FetchSubPhase[] fetchSubPhases;
    protected final ClusterService clusterService;
    
    public FetchPhase(List<FetchSubPhase> fetchSubPhases) {
        this(fetchSubPhases, null);
    }
    
    public FetchPhase(List<FetchSubPhase> fetchSubPhases, ClusterService clusterService) {
        this.fetchSubPhases = fetchSubPhases.toArray(new FetchSubPhase[fetchSubPhases.size() + 1]);
        this.fetchSubPhases[fetchSubPhases.size()] = new InnerHitsFetchSubPhase(this);
        this.clusterService = clusterService;
    }

    @Override
    public void preProcess(SearchContext context) {
    }

    @Override
    public void execute(SearchContext context) {
        final FieldsVisitor fieldsVisitor;
        Set<String> fieldNames = null;
        List<String> fieldNamePatterns = null;
        StoredFieldsContext storedFieldsContext = context.storedFieldsContext();

        if (storedFieldsContext == null) {
            // no fields specified, default to return source if no explicit indication
            if (!context.hasScriptFields() && !context.hasFetchSourceContext()) {
                context.fetchSourceContext(new FetchSourceContext(true));
            }
            fieldsVisitor = new FieldsVisitor(context.sourceRequested());
        } else if (storedFieldsContext.fetchFields() == false) {
            // disable stored fields entirely
            fieldsVisitor = null;
        } else {
            for (String fieldName : context.storedFieldsContext().fieldNames()) {
                if (fieldName.equals(SourceFieldMapper.NAME)) {
                    FetchSourceContext fetchSourceContext = context.hasFetchSourceContext() ? context.fetchSourceContext()
                        : FetchSourceContext.FETCH_SOURCE;
                    context.fetchSourceContext(new FetchSourceContext(true, fetchSourceContext.includes(), fetchSourceContext.excludes()));
                    continue;
                }
                if (Regex.isSimpleMatchPattern(fieldName)) {
                    if (fieldNamePatterns == null) {
                        fieldNamePatterns = new ArrayList<>();
                    }
                    fieldNamePatterns.add(fieldName);
                } else {
                    MappedFieldType fieldType = context.smartNameFieldType(fieldName);
                    if (fieldType == null) {
                        // Only fail if we know it is a object field, missing paths / fields shouldn't fail.
                        if (context.getObjectMapper(fieldName) != null) {
                            throw new IllegalArgumentException("field [" + fieldName + "] isn't a leaf field");
                        }
                    }
                    if (fieldNames == null) {
                        fieldNames = new HashSet<>();
                    }
                    fieldNames.add(fieldName);
                }
            }
            boolean loadSource = context.sourceRequested();
            if (fieldNames == null && fieldNamePatterns == null) {
                // empty list specified, default to disable _source if no explicit indication
                fieldsVisitor = new FieldsVisitor(loadSource);
            } else {
                fieldsVisitor = new CustomFieldsVisitor(fieldNames == null ? Collections.emptySet() : fieldNames,
                    fieldNamePatterns == null ? Collections.emptyList() : fieldNamePatterns, loadSource);
            }
        }

        SearchHit[] hits = new SearchHit[context.docIdsToLoadSize()];
        FetchSubPhase.HitContext hitContext = new FetchSubPhase.HitContext();
        for (int index = 0; index < context.docIdsToLoadSize(); index++) {
            if(context.isCancelled()) {
                throw new TaskCancelledException("cancelled");
            }
            int docId = context.docIdsToLoad()[context.docIdsToLoadFrom() + index];
            int readerIndex = ReaderUtil.subIndex(docId, context.searcher().getIndexReader().leaves());
            LeafReaderContext subReaderContext = context.searcher().getIndexReader().leaves().get(readerIndex);
            int subDocId = docId - subReaderContext.docBase;

            final SearchHit searchHit;
            try {
                int rootDocId = findRootDocumentIfNested(context, subReaderContext, subDocId);
                if (rootDocId != -1) {
                    searchHit = createNestedSearchHit(context, docId, subDocId, rootDocId, fieldNames, fieldNamePatterns, subReaderContext);
                } else {
                    searchHit = createSearchHit(context, fieldsVisitor, docId, subDocId, subReaderContext);
                }
            } catch (IOException e) {
                throw ExceptionsHelper.convertToElastic(e);
            }

            hits[index] = searchHit;
            hitContext.reset(searchHit, subReaderContext, subDocId, context.searcher());
            for (FetchSubPhase fetchSubPhase : fetchSubPhases) {
                fetchSubPhase.hitExecute(context, hitContext);
            }
        }

        for (FetchSubPhase fetchSubPhase : fetchSubPhases) {
            fetchSubPhase.hitsExecute(context, hits);
        }

        context.fetchResult().hits(new SearchHits(hits, context.queryResult().getTotalHits(), context.queryResult().getMaxScore()));
    }

    private int findRootDocumentIfNested(SearchContext context, LeafReaderContext subReaderContext, int subDocId) throws IOException {
        if (context.mapperService().hasNested()) {
            BitSet bits = context.bitsetFilterCache().getBitSetProducer(Queries.newNonNestedFilter()).getBitSet(subReaderContext);
            if (!bits.get(subDocId)) {
                return bits.nextSetBit(subDocId);
            }
        }
        return -1;
    }

    protected SearchHit createSearchHit(SearchContext context, FieldsVisitor fieldsVisitor, int docId, int subDocId, LeafReaderContext subReaderContext) {
        if (fieldsVisitor == null) {
            return new SearchHit(docId);
        }
        loadStoredFields(context, subReaderContext, fieldsVisitor, subDocId);
        fieldsVisitor.postProcess(context.mapperService());

        Map<String, SearchHitField> searchFields = null;
        if (!fieldsVisitor.fields().isEmpty()) {
            searchFields = new HashMap<>(fieldsVisitor.fields().size());
            for (Map.Entry<String, List<Object>> entry : fieldsVisitor.fields().entrySet()) {
                searchFields.put(entry.getKey(), new SearchHitField(entry.getKey(), entry.getValue()));
            }
        }

        DocumentMapper documentMapper = context.mapperService().documentMapper(fieldsVisitor.uid().type());
        Text typeText;
        if (documentMapper == null) {
            typeText = new Text(fieldsVisitor.uid().type());
        } else {
            typeText = documentMapper.typeText();
        }
        SearchHit searchHit = new SearchHit(docId, fieldsVisitor.uid().id(), typeText, searchFields);
        
        // Set _source if requested.
        SourceLookup sourceLookup = context.lookup().source();
        sourceLookup.setSegmentAndDocument(subReaderContext, subDocId);
        if (fieldsVisitor.source() != null) {
            sourceLookup.setSource(fieldsVisitor.source());
        }
        return searchHit;
    }

    private SearchHit createNestedSearchHit(SearchContext context, int nestedTopDocId, int nestedSubDocId, int rootSubDocId, Set<String> fieldNames, List<String> fieldNamePatterns, LeafReaderContext subReaderContext) throws IOException {
        // Also if highlighting is requested on nested documents we need to fetch the _source from the root document,
        // otherwise highlighting will attempt to fetch the _source from the nested doc, which will fail,
        // because the entire _source is only stored with the root document.
        final FieldsVisitor rootFieldsVisitor = new FieldsVisitor(context.sourceRequested() || context.highlight() != null);
        loadStoredFields(context, subReaderContext, rootFieldsVisitor, rootSubDocId);
        rootFieldsVisitor.postProcess(context.mapperService());

        Map<String, SearchHitField> searchFields = getSearchFields(context, nestedSubDocId, fieldNames, fieldNamePatterns, subReaderContext);
        DocumentMapper documentMapper = context.mapperService().documentMapper(rootFieldsVisitor.uid().type());
        SourceLookup sourceLookup = context.lookup().source();
        sourceLookup.setSegmentAndDocument(subReaderContext, nestedSubDocId);

        ObjectMapper nestedObjectMapper = documentMapper.findNestedObjectMapper(nestedSubDocId, context, subReaderContext);
        assert nestedObjectMapper != null;
        SearchHit.NestedIdentity nestedIdentity = getInternalNestedIdentity(context, nestedSubDocId, subReaderContext, documentMapper, nestedObjectMapper);

        BytesReference source = rootFieldsVisitor.source();
        if (source != null) {
            Tuple<XContentType, Map<String, Object>> tuple = XContentHelper.convertToMap(source, true);
            Map<String, Object> sourceAsMap = tuple.v2();

            // Isolate the nested json array object that matches with nested hit and wrap it back into the same json
            // structure with the nested json array object being the actual content. The latter is important, so that
            // features like source filtering and highlighting work consistent regardless of whether the field points
            // to a json object array for consistency reasons on how we refer to fields
            Map<String, Object> nestedSourceAsMap = new HashMap<>();
            Map<String, Object> current = nestedSourceAsMap;
            for (SearchHit.NestedIdentity nested = nestedIdentity; nested != null; nested = nested.getChild()) {
                String nestedPath = nested.getField().string();
                current.put(nestedPath, new HashMap<>());
                Object extractedValue = XContentMapValues.extractValue(nestedPath, sourceAsMap);
                List<Map<String, Object>> nestedParsedSource;
                if (extractedValue instanceof List) {
                    // nested field has an array value in the _source
                    nestedParsedSource = (List<Map<String, Object>>) extractedValue;
                } else if (extractedValue instanceof Map) {
                    // nested field has an object value in the _source. This just means the nested field has just one inner object, which is valid, but uncommon.
                    nestedParsedSource = Collections.singletonList((Map<String, Object>) extractedValue);
                } else {
                    throw new IllegalStateException("extracted source isn't an object or an array");
                }
                sourceAsMap = nestedParsedSource.get(nested.getOffset());
                if (nested.getChild() == null) {
                    current.put(nestedPath, sourceAsMap);
                } else {
                    Map<String, Object> next = new HashMap<>();
                    current.put(nestedPath, next);
                    current = next;
                }
            }
            context.lookup().source().setSource(nestedSourceAsMap);
            XContentType contentType = tuple.v1();
            BytesReference nestedSource = contentBuilder(contentType).map(sourceAsMap).bytes();
            context.lookup().source().setSource(nestedSource);
            context.lookup().source().setSourceContentType(contentType);
        }

        return new SearchHit(nestedTopDocId, rootFieldsVisitor.uid().id(), documentMapper.typeText(), nestedIdentity, searchFields);
    }

    private Map<String, SearchHitField> getSearchFields(SearchContext context, int nestedSubDocId, Set<String> fieldNames, List<String> fieldNamePatterns, LeafReaderContext subReaderContext) {
        Map<String, SearchHitField> searchFields = null;
        if (context.hasStoredFields() && !context.storedFieldsContext().fieldNames().isEmpty()) {
            FieldsVisitor nestedFieldsVisitor = new CustomFieldsVisitor(fieldNames == null ? Collections.emptySet() : fieldNames,
                fieldNamePatterns == null ? Collections.emptyList() : fieldNamePatterns, false);
            if (nestedFieldsVisitor != null) {
                loadStoredFields(context, subReaderContext, nestedFieldsVisitor, nestedSubDocId);
                nestedFieldsVisitor.postProcess(context.mapperService());
                if (!nestedFieldsVisitor.fields().isEmpty()) {
                    searchFields = new HashMap<>(nestedFieldsVisitor.fields().size());
                    for (Map.Entry<String, List<Object>> entry : nestedFieldsVisitor.fields().entrySet()) {
                        searchFields.put(entry.getKey(), new SearchHitField(entry.getKey(), entry.getValue()));
                    }
                }
            }
        }
        return searchFields;
    }

    private SearchHit.NestedIdentity getInternalNestedIdentity(SearchContext context, int nestedSubDocId, LeafReaderContext subReaderContext, DocumentMapper documentMapper, ObjectMapper nestedObjectMapper) throws IOException {
        int currentParent = nestedSubDocId;
        ObjectMapper nestedParentObjectMapper;
        ObjectMapper current = nestedObjectMapper;
        String originalName = nestedObjectMapper.name();
        SearchHit.NestedIdentity nestedIdentity = null;
        do {
            Query parentFilter;
            nestedParentObjectMapper = documentMapper.findParentObjectMapper(current);
            if (nestedParentObjectMapper != null) {
                if (nestedParentObjectMapper.nested().isNested() == false) {
                    current = nestedParentObjectMapper;
                    continue;
                }
                parentFilter = nestedParentObjectMapper.nestedTypeFilter();
            } else {
                parentFilter = Queries.newNonNestedFilter();
            }

            Query childFilter = nestedObjectMapper.nestedTypeFilter();
            if (childFilter == null) {
                current = nestedParentObjectMapper;
                continue;
            }
            final Weight childWeight = context.searcher().createNormalizedWeight(childFilter, false);
            Scorer childScorer = childWeight.scorer(subReaderContext);
            if (childScorer == null) {
                current = nestedParentObjectMapper;
                continue;
            }
            DocIdSetIterator childIter = childScorer.iterator();

            BitSet parentBits = context.bitsetFilterCache().getBitSetProducer(parentFilter).getBitSet(subReaderContext);

            int offset = 0;
            int nextParent = parentBits.nextSetBit(currentParent);
            for (int docId = childIter.advance(currentParent + 1); docId < nextParent && docId != DocIdSetIterator.NO_MORE_DOCS; docId = childIter.nextDoc()) {
                offset++;
            }
            currentParent = nextParent;
            current = nestedObjectMapper = nestedParentObjectMapper;
            int currentPrefix = current == null ? 0 : current.name().length() + 1;
            nestedIdentity = new SearchHit.NestedIdentity(originalName.substring(currentPrefix), offset, nestedIdentity);
            if (current != null) {
                originalName = current.name();
            }
        } while (current != null);
        return nestedIdentity;
    }

    protected ParsedStatement.Prepared getCqlPreparedStatement(SearchContext searchContext, IndexService indexService, FieldsVisitor fieldVisitor, String typeKey, boolean staticDocument) throws IOException {
        ParsedStatement.Prepared cqlStatement = searchContext.getCqlPreparedStatement( typeKey );
        if (cqlStatement == null) {
            // fetch from requested stored_fields.
            NavigableSet<String> requiredColumns = fieldVisitor.requiredColumns(clusterService, searchContext);
            if (requiredColumns.size() > 0) {
                IndexMetaData indexMetaData = clusterService.state().metaData().index(searchContext.request().shardId().getIndexName());
                if (requiredColumns.contains(NodeFieldMapper.NAME)) {
                    searchContext.includeNode(indexMetaData.getSettings().getAsBoolean(IndexMetaData.SETTING_INCLUDE_NODE_ID, clusterService.settings().getAsBoolean(ClusterService.SETTING_CLUSTER_INCLUDE_NODE_ID, false)));
                    requiredColumns.remove(NodeFieldMapper.NAME);
                }
                DocumentMapper docMapper = searchContext.mapperService().documentMapper(fieldVisitor.uid().type());
                if (fieldVisitor.loadSource() && docMapper.sourceMapper().enabled()) {
                    requiredColumns.add(SourceFieldMapper.NAME);
                }
                if (requiredColumns.size() > 0) {
                    String query = clusterService.buildFetchQuery(
                            indexService, fieldVisitor.uid().type(),
                            requiredColumns.toArray(new String[requiredColumns.size()]), staticDocument, docMapper.getColumnDefinitions());
                    Logger logger = Loggers.getLogger(FetchPhase.class);
                    if (logger.isTraceEnabled())
                        logger.trace("new statement={}",query);
                    cqlStatement = QueryProcessor.prepareInternal(query);
                    searchContext.putCqlPreparedStatement(typeKey, cqlStatement);
                }
            }
        }
        return cqlStatement;
    }
    
    protected void processCqlResultSet(SearchContext searchContext, IndexService indexService, FieldsVisitor fieldVisitor, ResultSet resultSet) throws IOException {
        UntypedResultSet rs = UntypedResultSet.create(resultSet);
        if (!rs.isEmpty()) {
            Row row = rs.one();
            Map<String, Object> mapObject = clusterService.rowAsMap(indexService, fieldVisitor.uid().type(), row);
            if (searchContext.includeNode()) {
                mapObject.put(NodeFieldMapper.NAME, clusterService.state().nodes().getLocalNodeId());
            }
            if (fieldVisitor.requestedFields() != null && fieldVisitor.requestedFields().size() > 0) {
                Map<String, List<Object>> flatMap = new HashMap<String, List<Object>>();
                clusterService.flattenTree(fieldVisitor.requestedFields(), "", mapObject, flatMap);
                for (String field :  fieldVisitor.requestedFields()) {
                    if (flatMap.get(field) != null && field != IdFieldMapper.NAME) 
                        fieldVisitor.setValues(field, flatMap.get(field));
                }
            }
            if (fieldVisitor.loadSource()) {
                fieldVisitor.source( clusterService.source(indexService, searchContext.mapperService().documentMapper(fieldVisitor.uid().type()), mapObject, fieldVisitor.uid()) );
            }
        }
    }
    
    private void loadStoredFields(SearchContext searchContext, LeafReaderContext readerContext, FieldsVisitor fieldVisitor, int docId) {
        fieldVisitor.reset();
        try {
            readerContext.reader().document(docId, fieldVisitor);
        } catch (IOException e) {
            throw new FetchPhaseExecutionException(searchContext, "Failed to fetch doc id [" + docId + "]", e);
        }
        
        // load field from cassandra
        IndexService indexService = searchContext.indexShard().indexService();
        try {
            DocPrimaryKey docPk = clusterService.parseElasticId(indexService, fieldVisitor.uid().type(), fieldVisitor.uid().id());
            String typeKey = fieldVisitor.uid().type();
            if (docPk.isStaticDocument) 
                typeKey += "_static";
            
            ParsedStatement.Prepared cqlStatement = getCqlPreparedStatement(searchContext, indexService, fieldVisitor, typeKey, docPk.isStaticDocument);
            if (cqlStatement != null) {
                ResultMessage result = cqlStatement.statement.executeInternal(new QueryState(ClientState.forInternalCalls()), QueryOptions.forInternalCalls(ConsistencyLevel.ONE, docPk.serialize(cqlStatement)));
                if (result instanceof ResultMessage.Rows) {
                    processCqlResultSet(searchContext, indexService, fieldVisitor, ((ResultMessage.Rows)result).result);
                }
            } else {
                // when only requesting for field _node
                if (searchContext.includeNode()) {
                    List<Object> values = new ArrayList<Object>(1);
                    values.add(clusterService.state().nodes().getLocalNodeId());
                    fieldVisitor.setValues(NodeFieldMapper.NAME, values);
                }
            }
        } catch (Exception e) {
            Loggers.getLogger(FetchPhase.class).error("Fetch failed id=" + fieldVisitor.uid().id(), e);
            throw new FetchPhaseExecutionException(searchContext, "Failed to fetch doc id [" + fieldVisitor.uid().id() + "] from cassandra", e);
        }
    }
}
