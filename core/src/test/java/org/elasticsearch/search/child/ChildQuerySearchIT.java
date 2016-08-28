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
package org.elasticsearch.search.child;

import org.apache.lucene.search.join.ScoreMode;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingResponse;
import org.elasticsearch.action.count.CountResponse;
import org.elasticsearch.action.explain.ExplainResponse;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.lucene.search.function.CombineFunction;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.cache.IndexCacheModule;
import org.elasticsearch.index.cache.query.index.IndexQueryCache;
import org.elasticsearch.index.mapper.MergeMappingException;
import org.elasticsearch.index.query.HasChildQueryBuilder;
import org.elasticsearch.index.query.IdsQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder;
import org.elasticsearch.index.search.child.ScoreType;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.filter.Filter;
import org.elasticsearch.search.aggregations.bucket.global.Global;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.elasticsearch.test.ESIntegTestCase.Scope;
import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.util.*;

import static com.google.common.collect.Maps.newHashMap;
import static org.elasticsearch.test.StreamsUtils.copyToStringFromClasspath;
import static org.elasticsearch.common.settings.Settings.settingsBuilder;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.*;
import static org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders.fieldValueFactorFunction;
import static org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders.weightFactorFunction;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.*;
import static org.hamcrest.Matchers.*;

/**
 *
 */
@ClusterScope(scope = Scope.SUITE)
public class ChildQuerySearchIT extends ESIntegTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.settingsBuilder().put(super.nodeSettings(nodeOrdinal))
                // aggressive filter caching so that we can assert on the filter cache size
                .put(IndexCacheModule.QUERY_CACHE_TYPE, IndexCacheModule.INDEX_QUERY_CACHE)
                .put(IndexCacheModule.QUERY_CACHE_EVERYTHING, true)
                .build();
    }

    @Test
    public void testSelfReferentialIsForbidden() {
        try {
            prepareCreate("test").addMapping("type", "_parent", "type=type").get();
            fail("self referential should be forbidden");
        } catch (Exception e) {
            Throwable cause = e.getCause();
            assertThat(cause, instanceOf(IllegalArgumentException.class));
            assertThat(cause.getMessage(), equalTo("The [_parent.type] option can't point to the same type"));
        }
    }

    @Test
    public void multiLevelChild() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent")
                .addMapping("grandchild", "_parent", "type=child"));
        ensureGreen();

        client().prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1").get();
        client().prepareIndex("test", "child", "c1").setSource("c_field", "c_value1").setParent("p1").get();
        client().prepareIndex("test", "grandchild", "gc1").setSource("gc_field", "gc_value1")
                .setParent("c1").setRouting("p1").get();
        refresh();

        SearchResponse searchResponse = client()
                .prepareSearch("test")
                .setQuery(
                        filteredQuery(
                                matchAllQuery(),
                                hasChildQuery(
                                        "child",
                                        filteredQuery(termQuery("c_field", "c_value1"),
                                                hasChildQuery("grandchild", termQuery("gc_field", "gc_value1")))))).get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("p1"));

        searchResponse = client().prepareSearch("test")
                .setQuery(filteredQuery(matchAllQuery(), hasParentQuery("parent", termQuery("p_field", "p_value1")))).execute()
                .actionGet();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("c1"));

        searchResponse = client().prepareSearch("test")
                .setQuery(filteredQuery(matchAllQuery(), hasParentQuery("child", termQuery("c_field", "c_value1")))).execute()
                .actionGet();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("gc1"));

        searchResponse = client().prepareSearch("test").setQuery(hasParentQuery("parent", termQuery("p_field", "p_value1"))).execute()
                .actionGet();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("c1"));

        searchResponse = client().prepareSearch("test").setQuery(hasParentQuery("child", termQuery("c_field", "c_value1"))).execute()
                .actionGet();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("gc1"));
    }

    @Test
    // see #6722
    public void test6722() throws IOException {
        assertAcked(prepareCreate("test")
                .addMapping("foo")
                .addMapping("test", "_parent", "type=foo"));
        ensureGreen();

        // index simple data
        client().prepareIndex("test", "foo", "1").setSource("foo", 1).get();
        client().prepareIndex("test", "test", "2").setSource("foo", 1).setParent("1").get();
        refresh();
        String query = copyToStringFromClasspath("/org/elasticsearch/search/child/bool-query-with-empty-clauses.json");
        SearchResponse searchResponse = client().prepareSearch("test").setSource(query).get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
        assertThat(searchResponse.getHits().getAt(0).getId(), equalTo("2"));
    }

    @Test
    // see #2744
    public void test2744() throws IOException {
        assertAcked(prepareCreate("test")
                .addMapping("foo")
                .addMapping("test", "_parent", "type=foo"));
        ensureGreen();

        // index simple data
        client().prepareIndex("test", "foo", "1").setSource("foo", 1).get();
        client().prepareIndex("test", "test").setSource("foo", 1).setParent("1").get();
        refresh();
        SearchResponse searchResponse = client().prepareSearch("test").setQuery(hasChildQuery("test", matchQuery("foo", 1))).execute()
                .actionGet();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("1"));

    }

    @Test
    public void simpleChildQuery() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();

        // index simple data
        client().prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1").get();
        client().prepareIndex("test", "child", "c1").setSource("c_field", "red").setParent("p1").get();
        client().prepareIndex("test", "child", "c2").setSource("c_field", "yellow").setParent("p1").get();
        client().prepareIndex("test", "parent", "p2").setSource("p_field", "p_value2").get();
        client().prepareIndex("test", "child", "c3").setSource("c_field", "blue").setParent("p2").get();
        client().prepareIndex("test", "child", "c4").setSource("c_field", "red").setParent("p2").get();
        refresh();

        // TEST FETCHING _parent from child
        SearchResponse searchResponse = client().prepareSearch("test").setQuery(idsQuery("child").ids("c1")).addFields("_parent").execute()
                .actionGet();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("c1"));
        assertThat(searchResponse.getHits().getAt(0).field("_parent").value().toString(), equalTo("p1"));

        // TEST matching on parent
        searchResponse = client().prepareSearch("test").setQuery(termQuery("_parent", "p1")).addFields("_parent").get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(2l));
        assertThat(searchResponse.getHits().getAt(0).id(), anyOf(equalTo("c1"), equalTo("c2")));
        assertThat(searchResponse.getHits().getAt(0).field("_parent").value().toString(), equalTo("p1"));
        assertThat(searchResponse.getHits().getAt(1).id(), anyOf(equalTo("c1"), equalTo("c2")));
        assertThat(searchResponse.getHits().getAt(1).field("_parent").value().toString(), equalTo("p1"));

        searchResponse = client().prepareSearch("test").setQuery(queryStringQuery("_parent:p1")).addFields("_parent").get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(2l));
        assertThat(searchResponse.getHits().getAt(0).id(), anyOf(equalTo("c1"), equalTo("c2")));
        assertThat(searchResponse.getHits().getAt(0).field("_parent").value().toString(), equalTo("p1"));
        assertThat(searchResponse.getHits().getAt(1).id(), anyOf(equalTo("c1"), equalTo("c2")));
        assertThat(searchResponse.getHits().getAt(1).field("_parent").value().toString(), equalTo("p1"));

        // HAS CHILD
        searchResponse = client().prepareSearch("test").setQuery(randomHasChild("child", "c_field", "yellow"))
                .get();
        assertHitCount(searchResponse, 1l);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("p1"));

        searchResponse = client().prepareSearch("test").setQuery(randomHasChild("child", "c_field", "blue")).execute()
                .actionGet();
        assertHitCount(searchResponse, 1l);
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("p2"));

        searchResponse = client().prepareSearch("test").setQuery(randomHasChild("child", "c_field", "red")).get();
        assertHitCount(searchResponse, 2l);
        assertThat(searchResponse.getHits().getAt(0).id(), anyOf(equalTo("p2"), equalTo("p1")));
        assertThat(searchResponse.getHits().getAt(1).id(), anyOf(equalTo("p2"), equalTo("p1")));

        // HAS PARENT
        searchResponse = client().prepareSearch("test")
                .setQuery(randomHasParent("parent", "p_field", "p_value2")).get();
        assertNoFailures(searchResponse);
        assertHitCount(searchResponse, 2l);
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("c3"));
        assertThat(searchResponse.getHits().getAt(1).id(), equalTo("c4"));

        searchResponse = client().prepareSearch("test")
                .setQuery(randomHasParent("parent", "p_field", "p_value1")).get();
        assertHitCount(searchResponse, 2l);
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("c1"));
        assertThat(searchResponse.getHits().getAt(1).id(), equalTo("c2"));
    }

    @Test
    // See: https://github.com/elasticsearch/elasticsearch/issues/3290
    public void testCachingBug_withFqueryFilter() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();
        List<IndexRequestBuilder> builders = new ArrayList<>();
        // index simple data
        for (int i = 0; i < 10; i++) {
            builders.add(client().prepareIndex("test", "parent", Integer.toString(i)).setSource("p_field", i));
        }
        indexRandom(randomBoolean(), builders);
        builders.clear();
        for (int j = 0; j < 2; j++) {
            for (int i = 0; i < 10; i++) {
                builders.add(client().prepareIndex("test", "child", Integer.toString(i)).setSource("c_field", i).setParent("" + 0));
            }
            for (int i = 0; i < 10; i++) {
                builders.add(client().prepareIndex("test", "child", Integer.toString(i + 10)).setSource("c_field", i + 10).setParent(Integer.toString(i)));
            }

            if (randomBoolean()) {
                break; // randomly break out and dont' have deletes / updates
            }
        }
        indexRandom(true, builders);

        for (int i = 1; i <= 10; i++) {
            logger.info("Round {}", i);
            SearchResponse searchResponse = client().prepareSearch("test")
                    .setQuery(constantScoreQuery(hasChildQuery("child", matchAllQuery()).scoreType("max")))
                    .get();
            assertNoFailures(searchResponse);
            searchResponse = client().prepareSearch("test")
                    .setQuery(constantScoreQuery(hasParentQuery("parent", matchAllQuery()).scoreType("score")))
                    .get();
            assertNoFailures(searchResponse);
        }
    }

    @Test
    public void testHasParentFilter() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();
        Map<String, Set<String>> parentToChildren = newHashMap();
        // Childless parent
        client().prepareIndex("test", "parent", "p0").setSource("p_field", "p0").get();
        parentToChildren.put("p0", new HashSet<String>());

        String previousParentId = null;
        int numChildDocs = 32;
        int numChildDocsPerParent = 0;
        List<IndexRequestBuilder> builders = new ArrayList<>();
        for (int i = 1; i <= numChildDocs; i++) {

            if (previousParentId == null || i % numChildDocsPerParent == 0) {
                previousParentId = "p" + i;
                builders.add(client().prepareIndex("test", "parent", previousParentId).setSource("p_field", previousParentId));
                numChildDocsPerParent++;
            }

            String childId = "c" + i;
            builders.add(client().prepareIndex("test", "child", childId).setSource("c_field", childId).setParent(previousParentId));

            if (!parentToChildren.containsKey(previousParentId)) {
                parentToChildren.put(previousParentId, new HashSet<String>());
            }
            assertThat(parentToChildren.get(previousParentId).add(childId), is(true));
        }
        indexRandom(true, builders.toArray(new IndexRequestBuilder[builders.size()]));

        assertThat(parentToChildren.isEmpty(), equalTo(false));
        for (Map.Entry<String, Set<String>> parentToChildrenEntry : parentToChildren.entrySet()) {
            SearchResponse searchResponse = client().prepareSearch("test")
                    .setQuery(constantScoreQuery(hasParentQuery("parent", termQuery("p_field", parentToChildrenEntry.getKey()))))
                    .setSize(numChildDocsPerParent).get();

            assertNoFailures(searchResponse);
            Set<String> childIds = parentToChildrenEntry.getValue();
            assertThat(searchResponse.getHits().totalHits(), equalTo((long) childIds.size()));
            for (int i = 0; i < searchResponse.getHits().totalHits(); i++) {
                assertThat(childIds.remove(searchResponse.getHits().getAt(i).id()), is(true));
                assertThat(searchResponse.getHits().getAt(i).score(), is(1.0f));
            }
            assertThat(childIds.size(), is(0));
        }
    }

    @Test
    public void simpleChildQueryWithFlush() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();

        // index simple data with flushes, so we have many segments
        client().prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1").get();
        client().admin().indices().prepareFlush().get();
        client().prepareIndex("test", "child", "c1").setSource("c_field", "red").setParent("p1").get();
        client().admin().indices().prepareFlush().get();
        client().prepareIndex("test", "child", "c2").setSource("c_field", "yellow").setParent("p1").get();
        client().admin().indices().prepareFlush().get();
        client().prepareIndex("test", "parent", "p2").setSource("p_field", "p_value2").get();
        client().admin().indices().prepareFlush().get();
        client().prepareIndex("test", "child", "c3").setSource("c_field", "blue").setParent("p2").get();
        client().admin().indices().prepareFlush().get();
        client().prepareIndex("test", "child", "c4").setSource("c_field", "red").setParent("p2").get();
        client().admin().indices().prepareFlush().get();
        refresh();

        // HAS CHILD QUERY

        SearchResponse searchResponse = client().prepareSearch("test").setQuery(hasChildQuery("child", termQuery("c_field", "yellow"))).execute()
                .actionGet();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("p1"));

        searchResponse = client().prepareSearch("test").setQuery(hasChildQuery("child", termQuery("c_field", "blue"))).execute()
                .actionGet();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("p2"));

        searchResponse = client().prepareSearch("test").setQuery(hasChildQuery("child", termQuery("c_field", "red"))).get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(2l));
        assertThat(searchResponse.getHits().getAt(0).id(), anyOf(equalTo("p2"), equalTo("p1")));
        assertThat(searchResponse.getHits().getAt(1).id(), anyOf(equalTo("p2"), equalTo("p1")));

        // HAS CHILD FILTER

        searchResponse = client().prepareSearch("test")
                .setQuery(constantScoreQuery(hasChildQuery("child", termQuery("c_field", "yellow")))).get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("p1"));

        searchResponse = client().prepareSearch("test").setQuery(constantScoreQuery(hasChildQuery("child", termQuery("c_field", "blue"))))
                .get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("p2"));

        searchResponse = client().prepareSearch("test").setQuery(constantScoreQuery(hasChildQuery("child", termQuery("c_field", "red"))))
                .get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(2l));
        assertThat(searchResponse.getHits().getAt(0).id(), anyOf(equalTo("p2"), equalTo("p1")));
        assertThat(searchResponse.getHits().getAt(1).id(), anyOf(equalTo("p2"), equalTo("p1")));
    }

    @Test
    public void testScopedFacet() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();

        // index simple data
        client().prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1").get();
        client().prepareIndex("test", "child", "c1").setSource("c_field", "red").setParent("p1").get();
        client().prepareIndex("test", "child", "c2").setSource("c_field", "yellow").setParent("p1").get();
        client().prepareIndex("test", "parent", "p2").setSource("p_field", "p_value2").get();
        client().prepareIndex("test", "child", "c3").setSource("c_field", "blue").setParent("p2").get();
        client().prepareIndex("test", "child", "c4").setSource("c_field", "red").setParent("p2").get();

        refresh();

        SearchResponse searchResponse = client()
                .prepareSearch("test")
                .setQuery(hasChildQuery("child", boolQuery().should(termQuery("c_field", "red")).should(termQuery("c_field", "yellow"))))
                .addAggregation(AggregationBuilders.global("global").subAggregation(
                        AggregationBuilders.filter("filter").filter(boolQuery().should(termQuery("c_field", "red")).should(termQuery("c_field", "yellow"))).subAggregation(
                                AggregationBuilders.terms("facet1").field("c_field")))).get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(2l));
        assertThat(searchResponse.getHits().getAt(0).id(), anyOf(equalTo("p2"), equalTo("p1")));
        assertThat(searchResponse.getHits().getAt(1).id(), anyOf(equalTo("p2"), equalTo("p1")));

        Global global = searchResponse.getAggregations().get("global");
        Filter filter = global.getAggregations().get("filter");
        Terms termsFacet = filter.getAggregations().get("facet1");
        assertThat(termsFacet.getBuckets().size(), equalTo(2));
        assertThat(termsFacet.getBuckets().get(0).getKeyAsString(), equalTo("red"));
        assertThat(termsFacet.getBuckets().get(0).getDocCount(), equalTo(2L));
        assertThat(termsFacet.getBuckets().get(1).getKeyAsString(), equalTo("yellow"));
        assertThat(termsFacet.getBuckets().get(1).getDocCount(), equalTo(1L));
    }

    @Test
    public void testDeletedParent() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();
        // index simple data
        client().prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1").get();
        client().prepareIndex("test", "child", "c1").setSource("c_field", "red").setParent("p1").get();
        client().prepareIndex("test", "child", "c2").setSource("c_field", "yellow").setParent("p1").get();
        client().prepareIndex("test", "parent", "p2").setSource("p_field", "p_value2").get();
        client().prepareIndex("test", "child", "c3").setSource("c_field", "blue").setParent("p2").get();
        client().prepareIndex("test", "child", "c4").setSource("c_field", "red").setParent("p2").get();

        refresh();

        SearchResponse searchResponse = client().prepareSearch("test")
                .setQuery(constantScoreQuery(hasChildQuery("child", termQuery("c_field", "yellow")))).get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("p1"));
        assertThat(searchResponse.getHits().getAt(0).sourceAsString(), containsString("\"p_value1\""));

        // update p1 and see what that we get updated values...

        client().prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1_updated").get();
        client().admin().indices().prepareRefresh().get();

        searchResponse = client().prepareSearch("test")
                .setQuery(constantScoreQuery(hasChildQuery("child", termQuery("c_field", "yellow")))).get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("p1"));
        assertThat(searchResponse.getHits().getAt(0).sourceAsString(), containsString("\"p_value1_updated\""));
    }

    @Test
    public void testDfsSearchType() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();

        // index simple data
        client().prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1").get();
        client().prepareIndex("test", "child", "c1").setSource("c_field", "red").setParent("p1").get();
        client().prepareIndex("test", "child", "c2").setSource("c_field", "yellow").setParent("p1").get();
        client().prepareIndex("test", "parent", "p2").setSource("p_field", "p_value2").get();
        client().prepareIndex("test", "child", "c3").setSource("c_field", "blue").setParent("p2").get();
        client().prepareIndex("test", "child", "c4").setSource("c_field", "red").setParent("p2").get();

        refresh();

        SearchResponse searchResponse = client().prepareSearch("test").setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                .setQuery(boolQuery().mustNot(hasChildQuery("child", boolQuery().should(queryStringQuery("c_field:*"))))).get();
        assertNoFailures(searchResponse);

        searchResponse = client().prepareSearch("test").setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                .setQuery(boolQuery().mustNot(hasParentQuery("parent", boolQuery().should(queryStringQuery("p_field:*"))))).execute()
                .actionGet();
        assertNoFailures(searchResponse);
    }

    @Test
    public void testHasChildAndHasParentFailWhenSomeSegmentsDontContainAnyParentOrChildDocs() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();

        client().prepareIndex("test", "parent", "1").setSource("p_field", 1).get();
        client().prepareIndex("test", "child", "1").setParent("1").setSource("c_field", 1).get();
        client().admin().indices().prepareFlush("test").get();

        client().prepareIndex("test", "type1", "1").setSource("p_field", 1).get();
        client().admin().indices().prepareFlush("test").get();

        SearchResponse searchResponse = client().prepareSearch("test")
                .setQuery(filteredQuery(matchAllQuery(), hasChildQuery("child", matchAllQuery()))).get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));

        searchResponse = client().prepareSearch("test")
                .setQuery(filteredQuery(matchAllQuery(), hasParentQuery("parent", matchAllQuery()))).get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
    }

    @Test
    public void testCountApiUsage() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();

        String parentId = "p1";
        client().prepareIndex("test", "parent", parentId).setSource("p_field", "1").get();
        client().prepareIndex("test", "child", "c1").setSource("c_field", "1").setParent(parentId).get();
        refresh();

        CountResponse countResponse = client().prepareCount("test").setQuery(hasChildQuery("child", termQuery("c_field", "1")).scoreType("max"))
                .get();
        assertHitCount(countResponse, 1l);

        countResponse = client().prepareCount("test").setQuery(hasParentQuery("parent", termQuery("p_field", "1")).scoreType("score"))
                .get();
        assertHitCount(countResponse, 1l);

        countResponse = client().prepareCount("test").setQuery(constantScoreQuery(hasChildQuery("child", termQuery("c_field", "1"))))
                .get();
        assertHitCount(countResponse, 1l);

        countResponse = client().prepareCount("test").setQuery(constantScoreQuery(hasParentQuery("parent", termQuery("p_field", "1"))))
                .get();
        assertHitCount(countResponse, 1l);
    }

    @Test
    public void testExplainUsage() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();

        String parentId = "p1";
        client().prepareIndex("test", "parent", parentId).setSource("p_field", "1").get();
        client().prepareIndex("test", "child", "c1").setSource("c_field", "1").setParent(parentId).get();
        refresh();

        SearchResponse searchResponse = client().prepareSearch("test")
                .setExplain(true)
                .setQuery(hasChildQuery("child", termQuery("c_field", "1")).scoreType("max"))
                .get();
        assertHitCount(searchResponse, 1l);
        assertThat(searchResponse.getHits().getAt(0).explanation().getDescription(), equalTo("Score based on join value p1"));

        searchResponse = client().prepareSearch("test")
                .setExplain(true)
                .setQuery(hasParentQuery("parent", termQuery("p_field", "1")).scoreType("score"))
                .get();
        assertHitCount(searchResponse, 1l);
        assertThat(searchResponse.getHits().getAt(0).explanation().getDescription(), equalTo("Score based on join value p1"));

        ExplainResponse explainResponse = client().prepareExplain("test", "parent", parentId)
                .setQuery(hasChildQuery("child", termQuery("c_field", "1")).scoreType("max"))
                .get();
        assertThat(explainResponse.isExists(), equalTo(true));
        assertThat(explainResponse.getExplanation().getDetails()[0].getDescription(), equalTo("Score based on join value p1"));
    }

    List<IndexRequestBuilder> createDocBuilders() {
        List<IndexRequestBuilder> indexBuilders = new ArrayList<>();
        // Parent 1 and its children
        indexBuilders.add(client().prepareIndex().setType("parent").setId("1").setIndex("test").setSource("p_field", "p_value1"));
        indexBuilders.add(client().prepareIndex().setType("child").setId("1").setIndex("test")
                .setSource("c_field1", 1, "c_field2", 0).setParent("1"));
        indexBuilders.add(client().prepareIndex().setType("child").setId("2").setIndex("test")
                .setSource("c_field1", 1, "c_field2", 0).setParent("1"));
        indexBuilders.add(client().prepareIndex().setType("child").setId("3").setIndex("test")
                .setSource("c_field1", 2, "c_field2", 0).setParent("1"));
        indexBuilders.add(client().prepareIndex().setType("child").setId("4").setIndex("test")
                .setSource("c_field1", 2, "c_field2", 0).setParent("1"));
        indexBuilders.add(client().prepareIndex().setType("child").setId("5").setIndex("test")
                .setSource("c_field1", 1, "c_field2", 1).setParent("1"));
        indexBuilders.add(client().prepareIndex().setType("child").setId("6").setIndex("test")
                .setSource("c_field1", 1, "c_field2", 2).setParent("1"));

        // Parent 2 and its children
        indexBuilders.add(client().prepareIndex().setType("parent").setId("2").setIndex("test").setSource("p_field", "p_value2"));
        indexBuilders.add(client().prepareIndex().setType("child").setId("7").setIndex("test")
                .setSource("c_field1", 3, "c_field2", 0).setParent("2"));
        indexBuilders.add(client().prepareIndex().setType("child").setId("8").setIndex("test")
                .setSource("c_field1", 1, "c_field2", 1).setParent("2"));
        indexBuilders.add(client().prepareIndex().setType("child").setId("9").setIndex("test")
                .setSource("c_field1", 1, "c_field2", 1).setParent("p")); // why
        // "p"????
        indexBuilders.add(client().prepareIndex().setType("child").setId("10").setIndex("test")
                .setSource("c_field1", 1, "c_field2", 1).setParent("2"));
        indexBuilders.add(client().prepareIndex().setType("child").setId("11").setIndex("test")
                .setSource("c_field1", 1, "c_field2", 1).setParent("2"));
        indexBuilders.add(client().prepareIndex().setType("child").setId("12").setIndex("test")
                .setSource("c_field1", 1, "c_field2", 2).setParent("2"));

        // Parent 3 and its children

        indexBuilders.add(client().prepareIndex().setType("parent").setId("3").setIndex("test")
                .setSource("p_field1", "p_value3", "p_field2", 5));
        indexBuilders.add(client().prepareIndex().setType("child").setId("13").setIndex("test")
                .setSource("c_field1", 4, "c_field2", 0, "c_field3", 0).setParent("3"));
        indexBuilders.add(client().prepareIndex().setType("child").setId("14").setIndex("test")
                .setSource("c_field1", 1, "c_field2", 1, "c_field3", 1).setParent("3"));
        indexBuilders.add(client().prepareIndex().setType("child").setId("15").setIndex("test")
                .setSource("c_field1", 1, "c_field2", 2, "c_field3", 2).setParent("3")); // why
        // "p"????
        indexBuilders.add(client().prepareIndex().setType("child").setId("16").setIndex("test")
                .setSource("c_field1", 1, "c_field2", 2, "c_field3", 3).setParent("3"));
        indexBuilders.add(client().prepareIndex().setType("child").setId("17").setIndex("test")
                .setSource("c_field1", 1, "c_field2", 2, "c_field3", 4).setParent("3"));
        indexBuilders.add(client().prepareIndex().setType("child").setId("18").setIndex("test")
                .setSource("c_field1", 1, "c_field2", 2, "c_field3", 5).setParent("3"));
        indexBuilders.add(client().prepareIndex().setType("child1").setId("1").setIndex("test")
                .setSource("c_field1", 1, "c_field2", 2, "c_field3", 6).setParent("3"));

        return indexBuilders;
    }

    @Test
    public void testScoreForParentChildQueries_withFunctionScore() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent")
                .addMapping("child1", "_parent", "type=parent"));
        ensureGreen();

        indexRandom(true, createDocBuilders().toArray(new IndexRequestBuilder[0]));
        SearchResponse response = client()
                .prepareSearch("test")
                .setQuery(
                        QueryBuilders.hasChildQuery(
                                "child",
                                QueryBuilders.functionScoreQuery(matchQuery("c_field2", 0),
                                        fieldValueFactorFunction("c_field1"))
                                        .boostMode(CombineFunction.REPLACE)).scoreMode("sum")).get();

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("1"));
        assertThat(response.getHits().hits()[0].score(), equalTo(6f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(4f));
        assertThat(response.getHits().hits()[2].id(), equalTo("2"));
        assertThat(response.getHits().hits()[2].score(), equalTo(3f));

        response = client()
                .prepareSearch("test")
                .setQuery(
                        QueryBuilders.hasChildQuery(
                                "child",
                                QueryBuilders.functionScoreQuery(matchQuery("c_field2", 0),
                                        fieldValueFactorFunction("c_field1"))
                                        .boostMode(CombineFunction.REPLACE)).scoreMode("max")).get();

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("3"));
        assertThat(response.getHits().hits()[0].score(), equalTo(4f));
        assertThat(response.getHits().hits()[1].id(), equalTo("2"));
        assertThat(response.getHits().hits()[1].score(), equalTo(3f));
        assertThat(response.getHits().hits()[2].id(), equalTo("1"));
        assertThat(response.getHits().hits()[2].score(), equalTo(2f));

        response = client()
                .prepareSearch("test")
                .setQuery(
                        QueryBuilders.hasChildQuery(
                                "child",
                                QueryBuilders.functionScoreQuery(matchQuery("c_field2", 0),
                                        fieldValueFactorFunction("c_field1"))
                                        .boostMode(CombineFunction.REPLACE)).scoreMode("avg")).get();

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("3"));
        assertThat(response.getHits().hits()[0].score(), equalTo(4f));
        assertThat(response.getHits().hits()[1].id(), equalTo("2"));
        assertThat(response.getHits().hits()[1].score(), equalTo(3f));
        assertThat(response.getHits().hits()[2].id(), equalTo("1"));
        assertThat(response.getHits().hits()[2].score(), equalTo(1.5f));

        response = client()
                .prepareSearch("test")
                .setQuery(
                        QueryBuilders.hasParentQuery(
                                "parent",
                                QueryBuilders.functionScoreQuery(matchQuery("p_field1", "p_value3"),
                                        fieldValueFactorFunction("p_field2"))
                                        .boostMode(CombineFunction.REPLACE)).scoreType("score"))
                .addSort(SortBuilders.fieldSort("c_field3")).addSort(SortBuilders.scoreSort()).get();

        assertThat(response.getHits().totalHits(), equalTo(7l));
        assertThat(response.getHits().hits()[0].id(), equalTo("13"));
        assertThat(response.getHits().hits()[0].score(), equalTo(5f));
        assertThat(response.getHits().hits()[1].id(), equalTo("14"));
        assertThat(response.getHits().hits()[1].score(), equalTo(5f));
        assertThat(response.getHits().hits()[2].id(), equalTo("15"));
        assertThat(response.getHits().hits()[2].score(), equalTo(5f));
        assertThat(response.getHits().hits()[3].id(), equalTo("16"));
        assertThat(response.getHits().hits()[3].score(), equalTo(5f));
        assertThat(response.getHits().hits()[4].id(), equalTo("17"));
        assertThat(response.getHits().hits()[4].score(), equalTo(5f));
        assertThat(response.getHits().hits()[5].id(), equalTo("18"));
        assertThat(response.getHits().hits()[5].score(), equalTo(5f));
        assertThat(response.getHits().hits()[6].id(), equalTo("1"));
        assertThat(response.getHits().hits()[6].score(), equalTo(5f));
    }

    @Test
    // https://github.com/elasticsearch/elasticsearch/issues/2536
    public void testParentChildQueriesCanHandleNoRelevantTypesInIndex() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();

        SearchResponse response = client().prepareSearch("test")
                .setQuery(QueryBuilders.hasChildQuery("child", matchQuery("text", "value"))).get();
        assertNoFailures(response);
        assertThat(response.getHits().totalHits(), equalTo(0l));

        client().prepareIndex("test", "child1").setSource(jsonBuilder().startObject().field("text", "value").endObject()).setRefresh(true)
                .get();

        response = client().prepareSearch("test").setQuery(QueryBuilders.hasChildQuery("child", matchQuery("text", "value"))).get();
        assertNoFailures(response);
        assertThat(response.getHits().totalHits(), equalTo(0l));

        response = client().prepareSearch("test").setQuery(QueryBuilders.hasChildQuery("child", matchQuery("text", "value")).scoreType("max"))
                .get();
        assertNoFailures(response);
        assertThat(response.getHits().totalHits(), equalTo(0l));

        response = client().prepareSearch("test").setQuery(QueryBuilders.hasParentQuery("parent", matchQuery("text", "value"))).get();
        assertNoFailures(response);
        assertThat(response.getHits().totalHits(), equalTo(0l));

        response = client().prepareSearch("test").setQuery(QueryBuilders.hasParentQuery("parent", matchQuery("text", "value")).scoreType("score"))
                .get();
        assertNoFailures(response);
        assertThat(response.getHits().totalHits(), equalTo(0l));
    }

    @Test
    public void testHasChildAndHasParentFilter_withFilter() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();

        client().prepareIndex("test", "parent", "1").setSource("p_field", 1).get();
        client().prepareIndex("test", "child", "2").setParent("1").setSource("c_field", 1).get();
        client().admin().indices().prepareFlush("test").get();

        client().prepareIndex("test", "type1", "3").setSource("p_field", 2).get();
        client().admin().indices().prepareFlush("test").get();

        SearchResponse searchResponse = client().prepareSearch("test")
                .setQuery(filteredQuery(matchAllQuery(), hasChildQuery("child", termQuery("c_field", 1)))).get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
        assertThat(searchResponse.getHits().hits()[0].id(), equalTo("1"));

        searchResponse = client().prepareSearch("test")
                .setQuery(filteredQuery(matchAllQuery(), hasParentQuery("parent", termQuery("p_field", 1)))).get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
        assertThat(searchResponse.getHits().hits()[0].id(), equalTo("2"));
    }

    @Test
    public void testHasChildAndHasParentWrappedInAQueryFilter() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();

        // query filter in case for p/c shouldn't execute per segment, but rather
        client().prepareIndex("test", "parent", "1").setSource("p_field", 1).get();
        client().admin().indices().prepareFlush("test").setForce(true).get();
        client().prepareIndex("test", "child", "2").setParent("1").setSource("c_field", 1).get();
        refresh();

        SearchResponse searchResponse = client().prepareSearch("test")
                .setQuery(filteredQuery(matchAllQuery(), hasChildQuery("child", matchQuery("c_field", 1)))).get();
        assertSearchHit(searchResponse, 1, hasId("1"));

        searchResponse = client().prepareSearch("test")
                .setQuery(filteredQuery(matchAllQuery(), hasParentQuery("parent", matchQuery("p_field", 1)))).get();
        assertSearchHit(searchResponse, 1, hasId("2"));

        searchResponse = client().prepareSearch("test")
                .setQuery(filteredQuery(matchAllQuery(), boolQuery().must(hasChildQuery("child", matchQuery("c_field", 1))))).get();
        assertSearchHit(searchResponse, 1, hasId("1"));

        searchResponse = client().prepareSearch("test")
                .setQuery(filteredQuery(matchAllQuery(), boolQuery().must(hasParentQuery("parent", matchQuery("p_field", 1))))).get();
        assertSearchHit(searchResponse, 1, hasId("2"));
    }

    @Test
    public void testSimpleQueryRewrite() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent", "p_field", "type=string")
                .addMapping("child", "_parent", "type=parent", "c_field", "type=string"));
        ensureGreen();

        // index simple data
        int childId = 0;
        for (int i = 0; i < 10; i++) {
            String parentId = String.format(Locale.ROOT, "p%03d", i);
            client().prepareIndex("test", "parent", parentId).setSource("p_field", parentId).get();
            int j = childId;
            for (; j < childId + 50; j++) {
                String childUid = String.format(Locale.ROOT, "c%03d", j);
                client().prepareIndex("test", "child", childUid).setSource("c_field", childUid).setParent(parentId).get();
            }
            childId = j;
        }
        refresh();

        SearchType[] searchTypes = new SearchType[]{SearchType.QUERY_THEN_FETCH, SearchType.DFS_QUERY_THEN_FETCH};
        for (SearchType searchType : searchTypes) {
            SearchResponse searchResponse = client().prepareSearch("test").setSearchType(searchType)
                    .setQuery(hasChildQuery("child", prefixQuery("c_field", "c")).scoreType("max")).addSort("p_field", SortOrder.ASC)
                    .setSize(5).get();
            assertNoFailures(searchResponse);
            assertThat(searchResponse.getHits().totalHits(), equalTo(10L));
            assertThat(searchResponse.getHits().hits()[0].id(), equalTo("p000"));
            assertThat(searchResponse.getHits().hits()[1].id(), equalTo("p001"));
            assertThat(searchResponse.getHits().hits()[2].id(), equalTo("p002"));
            assertThat(searchResponse.getHits().hits()[3].id(), equalTo("p003"));
            assertThat(searchResponse.getHits().hits()[4].id(), equalTo("p004"));

            searchResponse = client().prepareSearch("test").setSearchType(searchType)
                    .setQuery(hasParentQuery("parent", prefixQuery("p_field", "p")).scoreType("score")).addSort("c_field", SortOrder.ASC)
                    .setSize(5).get();
            assertNoFailures(searchResponse);
            assertThat(searchResponse.getHits().totalHits(), equalTo(500L));
            assertThat(searchResponse.getHits().hits()[0].id(), equalTo("c000"));
            assertThat(searchResponse.getHits().hits()[1].id(), equalTo("c001"));
            assertThat(searchResponse.getHits().hits()[2].id(), equalTo("c002"));
            assertThat(searchResponse.getHits().hits()[3].id(), equalTo("c003"));
            assertThat(searchResponse.getHits().hits()[4].id(), equalTo("c004"));
        }
    }

    @Test
    // See also issue:
    // https://github.com/elasticsearch/elasticsearch/issues/3144
    public void testReIndexingParentAndChildDocuments() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();

        // index simple data
        client().prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1").get();
        client().prepareIndex("test", "child", "c1").setSource("c_field", "red").setParent("p1").get();
        client().prepareIndex("test", "child", "c2").setSource("c_field", "yellow").setParent("p1").get();
        client().prepareIndex("test", "parent", "p2").setSource("p_field", "p_value2").get();
        client().prepareIndex("test", "child", "c3").setSource("c_field", "x").setParent("p2").get();
        client().prepareIndex("test", "child", "c4").setSource("c_field", "x").setParent("p2").get();

        refresh();

        SearchResponse searchResponse = client().prepareSearch("test")
                .setQuery(hasChildQuery("child", termQuery("c_field", "yellow")).scoreType("sum")).get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("p1"));
        assertThat(searchResponse.getHits().getAt(0).sourceAsString(), containsString("\"p_value1\""));

        searchResponse = client()
                .prepareSearch("test")
                .setQuery(
                        boolQuery().must(matchQuery("c_field", "x")).must(
                                hasParentQuery("parent", termQuery("p_field", "p_value2")).scoreType("score"))).get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(2l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("c3"));
        assertThat(searchResponse.getHits().getAt(1).id(), equalTo("c4"));

        // re-index
        for (int i = 0; i < 10; i++) {
            client().prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1").get();
            client().prepareIndex("test", "child", "d" + i).setSource("c_field", "red").setParent("p1").get();
            client().prepareIndex("test", "parent", "p2").setSource("p_field", "p_value2").get();
            client().prepareIndex("test", "child", "c3").setSource("c_field", "x").setParent("p2").get();
            client().admin().indices().prepareRefresh("test").get();
        }

        searchResponse = client().prepareSearch("test").setQuery(hasChildQuery("child", termQuery("c_field", "yellow")).scoreType("sum"))
                .get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("p1"));
        assertThat(searchResponse.getHits().getAt(0).sourceAsString(), containsString("\"p_value1\""));

        searchResponse = client()
                .prepareSearch("test")
                .setQuery(
                        boolQuery().must(matchQuery("c_field", "x")).must(
                                hasParentQuery("parent", termQuery("p_field", "p_value2")).scoreType("score"))).get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(2l));
        assertThat(searchResponse.getHits().getAt(0).id(), Matchers.anyOf(equalTo("c3"), equalTo("c4")));
        assertThat(searchResponse.getHits().getAt(1).id(), Matchers.anyOf(equalTo("c3"), equalTo("c4")));
    }

    @Test
    // See also issue:
    // https://github.com/elasticsearch/elasticsearch/issues/3203
    public void testHasChildQueryWithMinimumScore() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();

        // index simple data
        client().prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1").get();
        client().prepareIndex("test", "child", "c1").setSource("c_field", "x").setParent("p1").get();
        client().prepareIndex("test", "parent", "p2").setSource("p_field", "p_value2").get();
        client().prepareIndex("test", "child", "c3").setSource("c_field", "x").setParent("p2").get();
        client().prepareIndex("test", "child", "c4").setSource("c_field", "x").setParent("p2").get();
        client().prepareIndex("test", "child", "c5").setSource("c_field", "x").setParent("p2").get();
        refresh();

        SearchResponse searchResponse = client().prepareSearch("test").setQuery(hasChildQuery("child", matchAllQuery()).scoreType("sum"))
                .setMinScore(3) // Score needs to be 3 or above!
                .get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("p2"));
        assertThat(searchResponse.getHits().getAt(0).score(), equalTo(3.0f));
    }

    @Test
    public void testParentFieldFilter() throws Exception {
        assertAcked(prepareCreate("test")
                .setSettings(settingsBuilder().put(indexSettings())
                        .put("index.refresh_interval", -1))
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent")
                .addMapping("child2", "_parent", "type=parent"));
        ensureGreen();

        // test term filter
        SearchResponse response = client().prepareSearch("test").setQuery(filteredQuery(matchAllQuery(), termQuery("_parent", "p1")))
                .get();
        assertHitCount(response, 0l);

        client().prepareIndex("test", "some_type", "1").setSource("field", "value").get();
        client().prepareIndex("test", "parent", "p1").setSource("p_field", "value").get();
        client().prepareIndex("test", "child", "c1").setSource("c_field", "value").setParent("p1").get();

        response = client().prepareSearch("test").setQuery(filteredQuery(matchAllQuery(), termQuery("_parent", "p1"))).execute()
                .actionGet();
        assertHitCount(response, 0l);
        refresh();

        response = client().prepareSearch("test").setQuery(filteredQuery(matchAllQuery(), termQuery("_parent", "p1"))).execute()
                .actionGet();
        assertHitCount(response, 1l);

        response = client().prepareSearch("test").setQuery(filteredQuery(matchAllQuery(), termQuery("_parent", "parent#p1"))).execute()
                .actionGet();
        assertHitCount(response, 1l);

        client().prepareIndex("test", "parent2", "p1").setSource("p_field", "value").setRefresh(true).get();

        response = client().prepareSearch("test").setQuery(filteredQuery(matchAllQuery(), termQuery("_parent", "p1"))).execute()
                .actionGet();
        assertHitCount(response, 1l);

        response = client().prepareSearch("test").setQuery(filteredQuery(matchAllQuery(), termQuery("_parent", "parent#p1"))).execute()
                .actionGet();
        assertHitCount(response, 1l);

        // test terms filter
        client().prepareIndex("test", "child2", "c1").setSource("c_field", "value").setParent("p1").get();
        response = client().prepareSearch("test").setQuery(filteredQuery(matchAllQuery(), termsQuery("_parent", "p1"))).execute()
                .actionGet();
        assertHitCount(response, 1l);

        response = client().prepareSearch("test").setQuery(filteredQuery(matchAllQuery(), termsQuery("_parent", "parent#p1"))).execute()
                .actionGet();
        assertHitCount(response, 1l);

        refresh();
        response = client().prepareSearch("test").setQuery(filteredQuery(matchAllQuery(), termsQuery("_parent", "p1"))).execute()
                .actionGet();
        assertHitCount(response, 2l);

        refresh();
        response = client().prepareSearch("test").setQuery(filteredQuery(matchAllQuery(), termsQuery("_parent", "p1", "p1"))).execute()
                .actionGet();
        assertHitCount(response, 2l);

        response = client().prepareSearch("test")
                .setQuery(filteredQuery(matchAllQuery(), termsQuery("_parent", "parent#p1", "parent2#p1"))).get();
        assertHitCount(response, 2l);
    }

    @Test
    public void testHasChildNotBeingCached() throws IOException {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();

        // index simple data
        client().prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1").get();
        client().prepareIndex("test", "parent", "p2").setSource("p_field", "p_value2").get();
        client().prepareIndex("test", "parent", "p3").setSource("p_field", "p_value3").get();
        client().prepareIndex("test", "parent", "p4").setSource("p_field", "p_value4").get();
        client().prepareIndex("test", "parent", "p5").setSource("p_field", "p_value5").get();
        client().prepareIndex("test", "parent", "p6").setSource("p_field", "p_value6").get();
        client().prepareIndex("test", "parent", "p7").setSource("p_field", "p_value7").get();
        client().prepareIndex("test", "parent", "p8").setSource("p_field", "p_value8").get();
        client().prepareIndex("test", "parent", "p9").setSource("p_field", "p_value9").get();
        client().prepareIndex("test", "parent", "p10").setSource("p_field", "p_value10").get();
        client().prepareIndex("test", "child", "c1").setParent("p1").setSource("c_field", "blue").get();
        client().admin().indices().prepareFlush("test").get();
        client().admin().indices().prepareRefresh("test").get();

        SearchResponse searchResponse = client().prepareSearch("test")
                .setQuery(constantScoreQuery(hasChildQuery("child", termQuery("c_field", "blue"))))
                .get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));

        client().prepareIndex("test", "child", "c2").setParent("p2").setSource("c_field", "blue").get();
        client().admin().indices().prepareRefresh("test").get();

        searchResponse = client().prepareSearch("test")
                .setQuery(constantScoreQuery(hasChildQuery("child", termQuery("c_field", "blue"))))
                .get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(2l));
    }

    private QueryBuilder randomHasChild(String type, String field, String value) {
        if (randomBoolean()) {
            if (randomBoolean()) {
                return constantScoreQuery(hasChildQuery(type, termQuery(field, value)));
            } else {
                return filteredQuery(matchAllQuery(), hasChildQuery(type, termQuery(field, value)));
            }
        } else {
            return hasChildQuery(type, termQuery(field, value));
        }
    }

    private QueryBuilder randomHasParent(String type, String field, String value) {
        if (randomBoolean()) {
            if (randomBoolean()) {
                return constantScoreQuery(hasParentQuery(type, termQuery(field, value)));
            } else {
                return filteredQuery(matchAllQuery(), hasParentQuery(type, termQuery(field, value)));
            }
        } else {
            return hasParentQuery(type, termQuery(field, value));
        }
    }

    @Test
    // Relates to bug: https://github.com/elasticsearch/elasticsearch/issues/3818
    public void testHasChildQueryOnlyReturnsSingleChildType() {
        assertAcked(prepareCreate("grandissue")
                .addMapping("grandparent", "name", "type=string")
                .addMapping("parent", "_parent", "type=grandparent")
                .addMapping("child_type_one", "_parent", "type=parent")
                .addMapping("child_type_two", "_parent", "type=parent"));

        client().prepareIndex("grandissue", "grandparent", "1").setSource("name", "Grandpa").get();
        client().prepareIndex("grandissue", "parent", "2").setParent("1").setSource("name", "Dana").get();
        client().prepareIndex("grandissue", "child_type_one", "3").setParent("2").setRouting("1")
                .setSource("name", "William")
                .get();
        client().prepareIndex("grandissue", "child_type_two", "4").setParent("2").setRouting("1")
                .setSource("name", "Kate")
                .get();
        refresh();

        SearchResponse searchResponse = client().prepareSearch("grandissue").setQuery(
                boolQuery().must(
                        hasChildQuery(
                                "parent",
                                boolQuery().must(
                                        hasChildQuery(
                                                "child_type_one",
                                                boolQuery().must(
                                                        queryStringQuery("name:William*").analyzeWildcard(true)
                                                )
                                        )
                                )
                        )
                )
        ).get();
        assertHitCount(searchResponse, 1l);

        searchResponse = client().prepareSearch("grandissue").setQuery(
                boolQuery().must(
                        hasChildQuery(
                                "parent",
                                boolQuery().must(
                                        hasChildQuery(
                                                "child_type_two",
                                                boolQuery().must(
                                                        queryStringQuery("name:William*").analyzeWildcard(true)
                                                )
                                        )
                                )
                        )
                )
        ).get();
        assertHitCount(searchResponse, 0l);
    }

    @Test
    public void indexChildDocWithNoParentMapping() throws IOException {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child1"));
        ensureGreen();

        client().prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1").get();
        try {
            client().prepareIndex("test", "child1", "c1").setParent("p1").setSource("c_field", "blue").get();
            fail();
        } catch (IllegalArgumentException e) {
            assertThat(e.toString(), containsString("Can't specify parent if no parent field has been configured"));
        }
        try {
            client().prepareIndex("test", "child2", "c2").setParent("p1").setSource("c_field", "blue").get();
            fail();
        } catch (IllegalArgumentException e) {
            assertThat(e.toString(), containsString("Can't specify parent if no parent field has been configured"));
        }

        refresh();
    }

    @Test
    public void testAddingParentToExistingMapping() throws IOException {
        createIndex("test");
        ensureGreen();

        PutMappingResponse putMappingResponse = client().admin().indices().preparePutMapping("test").setType("child").setSource("number", "type=integer")
                .get();
        assertThat(putMappingResponse.isAcknowledged(), equalTo(true));

        GetMappingsResponse getMappingsResponse = client().admin().indices().prepareGetMappings("test").get();
        Map<String, Object> mapping = getMappingsResponse.getMappings().get("test").get("child").getSourceAsMap();
        assertThat(mapping.size(), greaterThanOrEqualTo(1)); // there are potentially some meta fields configured randomly
        assertThat(mapping.get("properties"), notNullValue());

        try {
            // Adding _parent metadata field to existing mapping is prohibited:
            client().admin().indices().preparePutMapping("test").setType("child").setSource(jsonBuilder().startObject().startObject("child")
                    .startObject("_parent").field("type", "parent").endObject()
                    .endObject().endObject()).get();
            fail();
        } catch (IllegalArgumentException e) {
            assertThat(e.toString(), containsString("The _parent field's type option can't be changed: [null]->[parent]"));
        }
    }

    @Test
    public void testHasChildQueryWithNestedInnerObjects() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent", "objects", "type=nested")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();

        client().prepareIndex("test", "parent", "p1")
                .setSource(jsonBuilder().startObject().field("p_field", "1").startArray("objects")
                        .startObject().field("i_field", "1").endObject()
                        .startObject().field("i_field", "2").endObject()
                        .startObject().field("i_field", "3").endObject()
                        .startObject().field("i_field", "4").endObject()
                        .startObject().field("i_field", "5").endObject()
                        .startObject().field("i_field", "6").endObject()
                        .endArray().endObject())
                .get();
        client().prepareIndex("test", "parent", "p2")
                .setSource(jsonBuilder().startObject().field("p_field", "2").startArray("objects")
                        .startObject().field("i_field", "1").endObject()
                        .startObject().field("i_field", "2").endObject()
                        .endArray().endObject())
                .get();
        client().prepareIndex("test", "child", "c1").setParent("p1").setSource("c_field", "blue").get();
        client().prepareIndex("test", "child", "c2").setParent("p1").setSource("c_field", "red").get();
        client().prepareIndex("test", "child", "c3").setParent("p2").setSource("c_field", "red").get();
        refresh();

        String scoreMode = ScoreType.values()[getRandom().nextInt(ScoreType.values().length)].name().toLowerCase(Locale.ROOT);
        SearchResponse searchResponse = client().prepareSearch("test")
                .setQuery(filteredQuery(QueryBuilders.hasChildQuery("child", termQuery("c_field", "blue")).scoreType(scoreMode), notQuery(termQuery("p_field", "3"))))
                .get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));

        searchResponse = client().prepareSearch("test")
                .setQuery(filteredQuery(QueryBuilders.hasChildQuery("child", termQuery("c_field", "red")).scoreType(scoreMode), notQuery(termQuery("p_field", "3"))))
                .get();
        assertNoFailures(searchResponse);
        assertThat(searchResponse.getHits().totalHits(), equalTo(2l));
    }

    @Test
    public void testNamedFilters() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();

        String parentId = "p1";
        client().prepareIndex("test", "parent", parentId).setSource("p_field", "1").get();
        client().prepareIndex("test", "child", "c1").setSource("c_field", "1").setParent(parentId).get();
        refresh();

        SearchResponse searchResponse = client().prepareSearch("test").setQuery(hasChildQuery("child", termQuery("c_field", "1")).scoreType("max").queryName("test"))
                .get();
        assertHitCount(searchResponse, 1l);
        assertThat(searchResponse.getHits().getAt(0).getMatchedQueries().length, equalTo(1));
        assertThat(searchResponse.getHits().getAt(0).getMatchedQueries()[0], equalTo("test"));

        searchResponse = client().prepareSearch("test").setQuery(hasParentQuery("parent", termQuery("p_field", "1")).scoreType("score").queryName("test"))
                .get();
        assertHitCount(searchResponse, 1l);
        assertThat(searchResponse.getHits().getAt(0).getMatchedQueries().length, equalTo(1));
        assertThat(searchResponse.getHits().getAt(0).getMatchedQueries()[0], equalTo("test"));

        searchResponse = client().prepareSearch("test").setQuery(constantScoreQuery(hasChildQuery("child", termQuery("c_field", "1")).queryName("test")))
                .get();
        assertHitCount(searchResponse, 1l);
        assertThat(searchResponse.getHits().getAt(0).getMatchedQueries().length, equalTo(1));
        assertThat(searchResponse.getHits().getAt(0).getMatchedQueries()[0], equalTo("test"));

        searchResponse = client().prepareSearch("test").setQuery(constantScoreQuery(hasParentQuery("parent", termQuery("p_field", "1")).queryName("test")))
                .get();
        assertHitCount(searchResponse, 1l);
        assertThat(searchResponse.getHits().getAt(0).getMatchedQueries().length, equalTo(1));
        assertThat(searchResponse.getHits().getAt(0).getMatchedQueries()[0], equalTo("test"));
    }

    @Test
    public void testParentChildQueriesNoParentType() throws Exception {
        assertAcked(prepareCreate("test")
                .setSettings(settingsBuilder()
                        .put(indexSettings())
                        .put("index.refresh_interval", -1)));
        ensureGreen();

        String parentId = "p1";
        client().prepareIndex("test", "parent", parentId).setSource("p_field", "1").get();
        refresh();

        try {
            client().prepareSearch("test")
                    .setQuery(hasChildQuery("child", termQuery("c_field", "1")))
                    .get();
            fail();
        } catch (SearchPhaseExecutionException e) {
            assertThat(e.status(), equalTo(RestStatus.BAD_REQUEST));
        }

        try {
            client().prepareSearch("test")
                    .setQuery(hasChildQuery("child", termQuery("c_field", "1")).scoreType("max"))
                    .get();
            fail();
        } catch (SearchPhaseExecutionException e) {
            assertThat(e.status(), equalTo(RestStatus.BAD_REQUEST));
        }

        try {
            client().prepareSearch("test")
                    .setPostFilter(hasChildQuery("child", termQuery("c_field", "1")))
                    .get();
            fail();
        } catch (SearchPhaseExecutionException e) {
            assertThat(e.status(), equalTo(RestStatus.BAD_REQUEST));
        }

        try {
            client().prepareSearch("test")
                    .setQuery(hasParentQuery("parent", termQuery("p_field", "1")).scoreType("score"))
                    .get();
            fail();
        } catch (SearchPhaseExecutionException e) {
            assertThat(e.status(), equalTo(RestStatus.BAD_REQUEST));
        }

        try {
            client().prepareSearch("test")
                    .setPostFilter(hasParentQuery("parent", termQuery("p_field", "1")))
                    .get();
            fail();
        } catch (SearchPhaseExecutionException e) {
            assertThat(e.status(), equalTo(RestStatus.BAD_REQUEST));
        }
    }

    @Test
    public void testAdd_ParentFieldAfterIndexingParentDocButBeforeIndexingChildDoc() throws Exception {
        assertAcked(prepareCreate("test")
                .setSettings(settingsBuilder()
                        .put(indexSettings())
                        .put("index.refresh_interval", -1)));
        ensureGreen();

        String parentId = "p1";
        client().prepareIndex("test", "parent", parentId).setSource("p_field", "1").get();
        refresh();

        try {
            assertAcked(client().admin()
                    .indices()
                    .preparePutMapping("test")
                    .setType("child")
                    .setSource("_parent", "type=parent"));
            fail("Shouldn't be able the add the _parent field pointing to an already existing parent type");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), equalTo("can't add a _parent field that points to an already existing type"));
        }
    }

    @Test
    public void testParentChildCaching() throws Exception {
        assertAcked(prepareCreate("test")
                .setSettings(
                        settingsBuilder()
                                .put(indexSettings())
                                .put("index.refresh_interval", -1)
                )
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();

        // index simple data
        client().prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1").get();
        client().prepareIndex("test", "parent", "p2").setSource("p_field", "p_value2").get();
        client().prepareIndex("test", "child", "c1").setParent("p1").setSource("c_field", "blue").get();
        client().prepareIndex("test", "child", "c2").setParent("p1").setSource("c_field", "red").get();
        client().prepareIndex("test", "child", "c3").setParent("p2").setSource("c_field", "red").get();
        client().admin().indices().prepareForceMerge("test").setMaxNumSegments(1).setFlush(true).get();
        client().prepareIndex("test", "parent", "p3").setSource("p_field", "p_value3").get();
        client().prepareIndex("test", "parent", "p4").setSource("p_field", "p_value4").get();
        client().prepareIndex("test", "child", "c4").setParent("p3").setSource("c_field", "green").get();
        client().prepareIndex("test", "child", "c5").setParent("p3").setSource("c_field", "blue").get();
        client().prepareIndex("test", "child", "c6").setParent("p4").setSource("c_field", "blue").get();
        client().admin().indices().prepareFlush("test").get();
        client().admin().indices().prepareRefresh("test").get();

        for (int i = 0; i < 2; i++) {
            SearchResponse searchResponse = client().prepareSearch()
                    .setQuery(filteredQuery(matchAllQuery(), boolQuery()
                            .must(QueryBuilders.hasChildQuery("child", matchQuery("c_field", "red")))
                            .must(matchAllQuery())))
                    .get();
            assertThat(searchResponse.getHits().totalHits(), equalTo(2l));
        }


        client().prepareIndex("test", "child", "c3").setParent("p2").setSource("c_field", "blue").get();
        client().admin().indices().prepareRefresh("test").get();

        SearchResponse searchResponse = client().prepareSearch()
                .setQuery(filteredQuery(matchAllQuery(), boolQuery()
                        .must(QueryBuilders.hasChildQuery("child", matchQuery("c_field", "red")))
                        .must(matchAllQuery())))
                .get();

        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
    }

    @Test
    public void testParentChildQueriesViaScrollApi() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();
        for (int i = 0; i < 10; i++) {
            client().prepareIndex("test", "parent", "p" + i).setSource("{}").get();
            client().prepareIndex("test", "child", "c" + i).setSource("{}").setParent("p" + i).get();
        }

        refresh();

        QueryBuilder[] queries = new QueryBuilder[]{
                hasChildQuery("child", matchAllQuery()),
                filteredQuery(matchAllQuery(), hasChildQuery("child", matchAllQuery())),
                hasParentQuery("parent", matchAllQuery()),
                filteredQuery(matchAllQuery(), hasParentQuery("parent", matchAllQuery()))
        };

        for (QueryBuilder query : queries) {
            SearchResponse scrollResponse = client().prepareSearch("test")
                    .setScroll(TimeValue.timeValueSeconds(30))
                    .setSize(1)
                    .addField("_id")
                    .setQuery(query)
                    .setSearchType("scan")
                    .execute()
                    .actionGet();

            assertNoFailures(scrollResponse);
            assertThat(scrollResponse.getHits().totalHits(), equalTo(10l));
            int scannedDocs = 0;
            do {
                scrollResponse = client()
                        .prepareSearchScroll(scrollResponse.getScrollId())
                        .setScroll(TimeValue.timeValueSeconds(30)).get();
                assertThat(scrollResponse.getHits().totalHits(), equalTo(10l));
                scannedDocs += scrollResponse.getHits().getHits().length;
            } while (scrollResponse.getHits().getHits().length > 0);
            assertThat(scannedDocs, equalTo(10));
        }
    }

    // https://github.com/elasticsearch/elasticsearch/issues/5783
    @Test
    public void testQueryBeforeChildType() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("features")
                .addMapping("posts", "_parent", "type=features")
                .addMapping("specials"));
        ensureGreen();

        client().prepareIndex("test", "features", "1").setSource("field", "foo").get();
        client().prepareIndex("test", "posts", "1").setParent("1").setSource("field", "bar").get();
        refresh();

        SearchResponse resp;
        resp = client().prepareSearch("test")
                .setSource("{\"query\": {\"has_child\": {\"type\": \"posts\", \"query\": {\"match\": {\"field\": \"bar\"}}}}}").get();
        assertHitCount(resp, 1L);

        // Now reverse the order for the type after the query
        resp = client().prepareSearch("test")
                .setSource("{\"query\": {\"has_child\": {\"query\": {\"match\": {\"field\": \"bar\"}}, \"type\": \"posts\"}}}").get();
        assertHitCount(resp, 1L);

    }

    @Test
    // https://github.com/elasticsearch/elasticsearch/issues/6256
    public void testParentFieldInMultiMatchField() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("type1")
                .addMapping("type2", "_parent", "type=type1")
        );
        ensureGreen();

        client().prepareIndex("test", "type2", "1").setParent("1").setSource("field", "value").get();
        refresh();

        SearchResponse response = client().prepareSearch("test")
                .setQuery(multiMatchQuery("1", "_parent"))
                .get();

        assertThat(response.getHits().totalHits(), equalTo(1l));
        assertThat(response.getHits().getAt(0).id(), equalTo("1"));
    }

    @Test
    public void testTypeIsAppliedInHasParentInnerQuery() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();

        List<IndexRequestBuilder> indexRequests = new ArrayList<>();
        indexRequests.add(client().prepareIndex("test", "parent", "1").setSource("field1", "a"));
        indexRequests.add(client().prepareIndex("test", "child", "1").setParent("1").setSource("{}"));
        indexRequests.add(client().prepareIndex("test", "child", "2").setParent("1").setSource("{}"));
        indexRandom(true, indexRequests);

        SearchResponse searchResponse = client().prepareSearch("test")
                .setQuery(constantScoreQuery(hasParentQuery("parent", notQuery(termQuery("field1", "a")))))
                .get();
        assertHitCount(searchResponse, 0l);

        searchResponse = client().prepareSearch("test")
                .setQuery(hasParentQuery("parent", constantScoreQuery(notQuery(termQuery("field1", "a")))))
                .get();
        assertHitCount(searchResponse, 0l);

        searchResponse = client().prepareSearch("test")
                .setQuery(constantScoreQuery(hasParentQuery("parent", termQuery("field1", "a"))))
                .get();
        assertHitCount(searchResponse, 2l);

        searchResponse = client().prepareSearch("test")
                .setQuery(hasParentQuery("parent", constantScoreQuery(termQuery("field1", "a"))))
                .get();
        assertHitCount(searchResponse, 2l);
    }

    private List<IndexRequestBuilder> createMinMaxDocBuilders() {
        List<IndexRequestBuilder> indexBuilders = new ArrayList<>();
        // Parent 1 and its children
        indexBuilders.add(client().prepareIndex().setType("parent").setId("1").setIndex("test").setSource("id",1));
        indexBuilders.add(client().prepareIndex().setType("child").setId("10").setIndex("test")
                .setSource("foo", "one").setParent("1"));

        // Parent 2 and its children
        indexBuilders.add(client().prepareIndex().setType("parent").setId("2").setIndex("test").setSource("id",2));
        indexBuilders.add(client().prepareIndex().setType("child").setId("11").setIndex("test")
                .setSource("foo", "one").setParent("2"));
        indexBuilders.add(client().prepareIndex().setType("child").setId("12").setIndex("test")
                .setSource("foo", "one two").setParent("2"));

        // Parent 3 and its children
        indexBuilders.add(client().prepareIndex().setType("parent").setId("3").setIndex("test").setSource("id",3));
        indexBuilders.add(client().prepareIndex().setType("child").setId("13").setIndex("test")
                .setSource("foo", "one").setParent("3"));
        indexBuilders.add(client().prepareIndex().setType("child").setId("14").setIndex("test")
                .setSource("foo", "one two").setParent("3"));
        indexBuilders.add(client().prepareIndex().setType("child").setId("15").setIndex("test")
                .setSource("foo", "one two three").setParent("3"));

        // Parent 4 and its children
        indexBuilders.add(client().prepareIndex().setType("parent").setId("4").setIndex("test").setSource("id",4));
        indexBuilders.add(client().prepareIndex().setType("child").setId("16").setIndex("test")
                .setSource("foo", "one").setParent("4"));
        indexBuilders.add(client().prepareIndex().setType("child").setId("17").setIndex("test")
                .setSource("foo", "one two").setParent("4"));
        indexBuilders.add(client().prepareIndex().setType("child").setId("18").setIndex("test")
                .setSource("foo", "one two three").setParent("4"));
        indexBuilders.add(client().prepareIndex().setType("child").setId("19").setIndex("test")
                .setSource("foo", "one two three four").setParent("4"));

        return indexBuilders;
    }

    private SearchResponse minMaxQuery(String scoreType, int minChildren, int maxChildren, int cutoff) throws SearchPhaseExecutionException {
        return client()
                .prepareSearch("test")
                .setQuery(
                        QueryBuilders
                                .hasChildQuery(
                                        "child",
                                        QueryBuilders.functionScoreQuery(constantScoreQuery(QueryBuilders.termQuery("foo", "two"))).boostMode("replace").scoreMode("sum")
                                                .add(QueryBuilders.matchAllQuery(), weightFactorFunction(1))
                                                .add(QueryBuilders.termQuery("foo", "three"), weightFactorFunction(1))
                                                .add(QueryBuilders.termQuery("foo", "four"), weightFactorFunction(1))).scoreType(scoreType)
                                .minChildren(minChildren).maxChildren(maxChildren).setShortCircuitCutoff(cutoff))
                .addSort("_score", SortOrder.DESC).addSort("id", SortOrder.ASC).get();
    }

    private SearchResponse minMaxFilter(int minChildren, int maxChildren, int cutoff) throws SearchPhaseExecutionException {
        return client()
                .prepareSearch("test")
                .setQuery(
                        QueryBuilders.constantScoreQuery(QueryBuilders.hasChildQuery("child", termQuery("foo", "two"))
                                .minChildren(minChildren).maxChildren(maxChildren).setShortCircuitCutoff(cutoff)))
                .addSort("id", SortOrder.ASC).setTrackScores(true).get();
    }

    @Test
    public void testMinMaxChildren() throws Exception {
        assertAcked(prepareCreate("test")
                .addMapping("parent", "id", "type=long")
                .addMapping("child", "_parent", "type=parent"));
        ensureGreen();

        indexRandom(true, createMinMaxDocBuilders().toArray(new IndexRequestBuilder[0]));
        SearchResponse response;
        int cutoff = getRandom().nextInt(4);

        // Score mode = NONE
        response = minMaxQuery("none", 0, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("2"));
        assertThat(response.getHits().hits()[0].score(), equalTo(1f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(1f));
        assertThat(response.getHits().hits()[2].id(), equalTo("4"));
        assertThat(response.getHits().hits()[2].score(), equalTo(1f));

        response = minMaxQuery("none", 1, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("2"));
        assertThat(response.getHits().hits()[0].score(), equalTo(1f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(1f));
        assertThat(response.getHits().hits()[2].id(), equalTo("4"));
        assertThat(response.getHits().hits()[2].score(), equalTo(1f));

        response = minMaxQuery("none", 2, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(2l));
        assertThat(response.getHits().hits()[0].id(), equalTo("3"));
        assertThat(response.getHits().hits()[0].score(), equalTo(1f));
        assertThat(response.getHits().hits()[1].id(), equalTo("4"));
        assertThat(response.getHits().hits()[1].score(), equalTo(1f));

        response = minMaxQuery("none", 3, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(1l));
        assertThat(response.getHits().hits()[0].id(), equalTo("4"));
        assertThat(response.getHits().hits()[0].score(), equalTo(1f));

        response = minMaxQuery("none", 4, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(0l));

        response = minMaxQuery("none", 0, 4, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("2"));
        assertThat(response.getHits().hits()[0].score(), equalTo(1f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(1f));
        assertThat(response.getHits().hits()[2].id(), equalTo("4"));
        assertThat(response.getHits().hits()[2].score(), equalTo(1f));

        response = minMaxQuery("none", 0, 3, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("2"));
        assertThat(response.getHits().hits()[0].score(), equalTo(1f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(1f));
        assertThat(response.getHits().hits()[2].id(), equalTo("4"));
        assertThat(response.getHits().hits()[2].score(), equalTo(1f));

        response = minMaxQuery("none", 0, 2, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(2l));
        assertThat(response.getHits().hits()[0].id(), equalTo("2"));
        assertThat(response.getHits().hits()[0].score(), equalTo(1f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(1f));

        response = minMaxQuery("none", 2, 2, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(1l));
        assertThat(response.getHits().hits()[0].id(), equalTo("3"));
        assertThat(response.getHits().hits()[0].score(), equalTo(1f));

        try {
            response = minMaxQuery("none", 3, 2, cutoff);
            fail();
        } catch (SearchPhaseExecutionException e) {
            assertThat(e.toString(), containsString("[has_child] 'max_children' is less than 'min_children'"));
        }

        // Score mode = SUM
        response = minMaxQuery("sum", 0, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("4"));
        assertThat(response.getHits().hits()[0].score(), equalTo(6f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(3f));
        assertThat(response.getHits().hits()[2].id(), equalTo("2"));
        assertThat(response.getHits().hits()[2].score(), equalTo(1f));

        response = minMaxQuery("sum", 1, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("4"));
        assertThat(response.getHits().hits()[0].score(), equalTo(6f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(3f));
        assertThat(response.getHits().hits()[2].id(), equalTo("2"));
        assertThat(response.getHits().hits()[2].score(), equalTo(1f));

        response = minMaxQuery("sum", 2, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(2l));
        assertThat(response.getHits().hits()[0].id(), equalTo("4"));
        assertThat(response.getHits().hits()[0].score(), equalTo(6f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(3f));

        response = minMaxQuery("sum", 3, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(1l));
        assertThat(response.getHits().hits()[0].id(), equalTo("4"));
        assertThat(response.getHits().hits()[0].score(), equalTo(6f));

        response = minMaxQuery("sum", 4, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(0l));

        response = minMaxQuery("sum", 0, 4, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("4"));
        assertThat(response.getHits().hits()[0].score(), equalTo(6f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(3f));
        assertThat(response.getHits().hits()[2].id(), equalTo("2"));
        assertThat(response.getHits().hits()[2].score(), equalTo(1f));

        response = minMaxQuery("sum", 0, 3, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("4"));
        assertThat(response.getHits().hits()[0].score(), equalTo(6f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(3f));
        assertThat(response.getHits().hits()[2].id(), equalTo("2"));
        assertThat(response.getHits().hits()[2].score(), equalTo(1f));

        response = minMaxQuery("sum", 0, 2, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(2l));
        assertThat(response.getHits().hits()[0].id(), equalTo("3"));
        assertThat(response.getHits().hits()[0].score(), equalTo(3f));
        assertThat(response.getHits().hits()[1].id(), equalTo("2"));
        assertThat(response.getHits().hits()[1].score(), equalTo(1f));

        response = minMaxQuery("sum", 2, 2, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(1l));
        assertThat(response.getHits().hits()[0].id(), equalTo("3"));
        assertThat(response.getHits().hits()[0].score(), equalTo(3f));

        try {
            response = minMaxQuery("sum", 3, 2, cutoff);
            fail();
        } catch (SearchPhaseExecutionException e) {
            assertThat(e.toString(), containsString("[has_child] 'max_children' is less than 'min_children'"));
        }

        // Score mode = MAX
        response = minMaxQuery("max", 0, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("4"));
        assertThat(response.getHits().hits()[0].score(), equalTo(3f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(2f));
        assertThat(response.getHits().hits()[2].id(), equalTo("2"));
        assertThat(response.getHits().hits()[2].score(), equalTo(1f));

        response = minMaxQuery("max", 1, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("4"));
        assertThat(response.getHits().hits()[0].score(), equalTo(3f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(2f));
        assertThat(response.getHits().hits()[2].id(), equalTo("2"));
        assertThat(response.getHits().hits()[2].score(), equalTo(1f));

        response = minMaxQuery("max", 2, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(2l));
        assertThat(response.getHits().hits()[0].id(), equalTo("4"));
        assertThat(response.getHits().hits()[0].score(), equalTo(3f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(2f));

        response = minMaxQuery("max", 3, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(1l));
        assertThat(response.getHits().hits()[0].id(), equalTo("4"));
        assertThat(response.getHits().hits()[0].score(), equalTo(3f));

        response = minMaxQuery("max", 4, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(0l));

        response = minMaxQuery("max", 0, 4, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("4"));
        assertThat(response.getHits().hits()[0].score(), equalTo(3f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(2f));
        assertThat(response.getHits().hits()[2].id(), equalTo("2"));
        assertThat(response.getHits().hits()[2].score(), equalTo(1f));

        response = minMaxQuery("max", 0, 3, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("4"));
        assertThat(response.getHits().hits()[0].score(), equalTo(3f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(2f));
        assertThat(response.getHits().hits()[2].id(), equalTo("2"));
        assertThat(response.getHits().hits()[2].score(), equalTo(1f));

        response = minMaxQuery("max", 0, 2, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(2l));
        assertThat(response.getHits().hits()[0].id(), equalTo("3"));
        assertThat(response.getHits().hits()[0].score(), equalTo(2f));
        assertThat(response.getHits().hits()[1].id(), equalTo("2"));
        assertThat(response.getHits().hits()[1].score(), equalTo(1f));

        response = minMaxQuery("max", 2, 2, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(1l));
        assertThat(response.getHits().hits()[0].id(), equalTo("3"));
        assertThat(response.getHits().hits()[0].score(), equalTo(2f));

        try {
            response = minMaxQuery("max", 3, 2, cutoff);
            fail();
        } catch (SearchPhaseExecutionException e) {
            assertThat(e.toString(), containsString("[has_child] 'max_children' is less than 'min_children'"));
        }

        // Score mode = AVG
        response = minMaxQuery("avg", 0, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("4"));
        assertThat(response.getHits().hits()[0].score(), equalTo(2f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(1.5f));
        assertThat(response.getHits().hits()[2].id(), equalTo("2"));
        assertThat(response.getHits().hits()[2].score(), equalTo(1f));

        response = minMaxQuery("avg", 1, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("4"));
        assertThat(response.getHits().hits()[0].score(), equalTo(2f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(1.5f));
        assertThat(response.getHits().hits()[2].id(), equalTo("2"));
        assertThat(response.getHits().hits()[2].score(), equalTo(1f));

        response = minMaxQuery("avg", 2, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(2l));
        assertThat(response.getHits().hits()[0].id(), equalTo("4"));
        assertThat(response.getHits().hits()[0].score(), equalTo(2f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(1.5f));

        response = minMaxQuery("avg", 3, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(1l));
        assertThat(response.getHits().hits()[0].id(), equalTo("4"));
        assertThat(response.getHits().hits()[0].score(), equalTo(2f));

        response = minMaxQuery("avg", 4, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(0l));

        response = minMaxQuery("avg", 0, 4, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("4"));
        assertThat(response.getHits().hits()[0].score(), equalTo(2f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(1.5f));
        assertThat(response.getHits().hits()[2].id(), equalTo("2"));
        assertThat(response.getHits().hits()[2].score(), equalTo(1f));

        response = minMaxQuery("avg", 0, 3, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("4"));
        assertThat(response.getHits().hits()[0].score(), equalTo(2f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(1.5f));
        assertThat(response.getHits().hits()[2].id(), equalTo("2"));
        assertThat(response.getHits().hits()[2].score(), equalTo(1f));

        response = minMaxQuery("avg", 0, 2, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(2l));
        assertThat(response.getHits().hits()[0].id(), equalTo("3"));
        assertThat(response.getHits().hits()[0].score(), equalTo(1.5f));
        assertThat(response.getHits().hits()[1].id(), equalTo("2"));
        assertThat(response.getHits().hits()[1].score(), equalTo(1f));

        response = minMaxQuery("avg", 2, 2, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(1l));
        assertThat(response.getHits().hits()[0].id(), equalTo("3"));
        assertThat(response.getHits().hits()[0].score(), equalTo(1.5f));

        try {
            response = minMaxQuery("avg", 3, 2, cutoff);
            fail();
        } catch (SearchPhaseExecutionException e) {
            assertThat(e.toString(), containsString("[has_child] 'max_children' is less than 'min_children'"));
        }

        // HasChildFilter
        response = minMaxFilter(0, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("2"));
        assertThat(response.getHits().hits()[0].score(), equalTo(1f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(1f));
        assertThat(response.getHits().hits()[2].id(), equalTo("4"));
        assertThat(response.getHits().hits()[2].score(), equalTo(1f));

        response = minMaxFilter(1, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("2"));
        assertThat(response.getHits().hits()[0].score(), equalTo(1f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(1f));
        assertThat(response.getHits().hits()[2].id(), equalTo("4"));
        assertThat(response.getHits().hits()[2].score(), equalTo(1f));

        response = minMaxFilter(2, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(2l));
        assertThat(response.getHits().hits()[0].id(), equalTo("3"));
        assertThat(response.getHits().hits()[0].score(), equalTo(1f));
        assertThat(response.getHits().hits()[1].id(), equalTo("4"));
        assertThat(response.getHits().hits()[1].score(), equalTo(1f));

        response = minMaxFilter(3, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(1l));
        assertThat(response.getHits().hits()[0].id(), equalTo("4"));
        assertThat(response.getHits().hits()[0].score(), equalTo(1f));

        response = minMaxFilter(4, 0, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(0l));

        response = minMaxFilter(0, 4, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("2"));
        assertThat(response.getHits().hits()[0].score(), equalTo(1f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(1f));
        assertThat(response.getHits().hits()[2].id(), equalTo("4"));
        assertThat(response.getHits().hits()[2].score(), equalTo(1f));

        response = minMaxFilter(0, 3, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(3l));
        assertThat(response.getHits().hits()[0].id(), equalTo("2"));
        assertThat(response.getHits().hits()[0].score(), equalTo(1f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(1f));
        assertThat(response.getHits().hits()[2].id(), equalTo("4"));
        assertThat(response.getHits().hits()[2].score(), equalTo(1f));

        response = minMaxFilter(0, 2, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(2l));
        assertThat(response.getHits().hits()[0].id(), equalTo("2"));
        assertThat(response.getHits().hits()[0].score(), equalTo(1f));
        assertThat(response.getHits().hits()[1].id(), equalTo("3"));
        assertThat(response.getHits().hits()[1].score(), equalTo(1f));

        response = minMaxFilter(2, 2, cutoff);

        assertThat(response.getHits().totalHits(), equalTo(1l));
        assertThat(response.getHits().hits()[0].id(), equalTo("3"));
        assertThat(response.getHits().hits()[0].score(), equalTo(1f));

        try {
            response = minMaxFilter(3, 2, cutoff);
            fail();
        } catch (SearchPhaseExecutionException e) {
            assertThat(e.toString(), containsString("[has_child] 'max_children' is less than 'min_children'"));
        }

    }

    @Test
    public void testParentFieldToNonExistingType() {
        assertAcked(prepareCreate("test").addMapping("parent").addMapping("child", "_parent", "type=parent2"));
        client().prepareIndex("test", "parent", "1").setSource("{}").get();
        client().prepareIndex("test", "child", "1").setParent("1").setSource("{}").get();
        refresh();

        try {
            client().prepareSearch("test")
                    .setQuery(QueryBuilders.hasChildQuery("child", matchAllQuery()))
                    .get();
            fail();
        } catch (SearchPhaseExecutionException e) {
        }
    }

    public void testHasParentInnerQueryType() {
        assertAcked(prepareCreate("test").addMapping("parent-type").addMapping("child-type", "_parent", "type=parent-type"));
        client().prepareIndex("test", "child-type", "child-id").setParent("parent-id").setSource("{}").get();
        client().prepareIndex("test", "parent-type", "parent-id").setSource("{}").get();
        refresh();
        //make sure that when we explicitly set a type, the inner query is executed in the context of the parent type instead
        SearchResponse searchResponse = client().prepareSearch("test").setTypes("child-type").setQuery(
                QueryBuilders.hasParentQuery("parent-type", new IdsQueryBuilder().addIds("parent-id"))).get();
        assertSearchHits(searchResponse, "child-id");
    }

    public void testHasChildInnerQueryType() {
        assertAcked(prepareCreate("test").addMapping("parent-type").addMapping("child-type", "_parent", "type=parent-type"));
        client().prepareIndex("test", "child-type", "child-id").setParent("parent-id").setSource("{}").get();
        client().prepareIndex("test", "parent-type", "parent-id").setSource("{}").get();
        refresh();
        //make sure that when we explicitly set a type, the inner query is executed in the context of the child type instead
        SearchResponse searchResponse = client().prepareSearch("test").setTypes("parent-type").setQuery(
                QueryBuilders.hasChildQuery("child-type", new IdsQueryBuilder().addIds("child-id"))).get();
        assertSearchHits(searchResponse, "parent-id");
    }

    // Tests #16550
    public void testHasChildWithNonDefaultGlobalSimilarity() {
        assertAcked(prepareCreate("test").setSettings(settingsBuilder().put(indexSettings())
                .put("index.similarity.default.type", "BM25"))
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent", "c_field", "type=string"));
        ensureGreen();

        verifyNonDefaultSimilarity();
    }

    // Tests #16550
    public void testHasChildWithNonDefaultFieldSimilarity() {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent", "c_field", "type=string,similarity=BM25"));
        ensureGreen();

        verifyNonDefaultSimilarity();
    }

    // Tests #16550
    private void verifyNonDefaultSimilarity() {
        client().prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1").get();
        client().prepareIndex("test", "child", "c1").setSource("c_field", "c_value").setParent("p1").get();
        client().prepareIndex("test", "child", "c2").setSource("c_field", "c_value").setParent("p1").get();
        refresh();

        // baseline: sum of scores of matching child docs outside of has_child query
        SearchResponse searchResponse = client().prepareSearch("test")
                .setTypes("child")
                .setQuery(matchQuery("c_field", "c_value"))
                .get();
        assertSearchHits(searchResponse, "c1", "c2");
        float childSum = 0f;
        for (SearchHit hit : searchResponse.getHits()) {
            childSum += hit.getScore();
        }
        // compare baseline to has_child with 'total' score_mode
        searchResponse = client().prepareSearch("test")
                .setQuery(hasChildQuery("child", matchQuery("c_field", "c_value")).scoreMode("total"))
                .get();
        assertSearchHits(searchResponse, "p1");
        assertThat(searchResponse.getHits().hits()[0].score(), equalTo(childSum));
    }

    static HasChildQueryBuilder hasChildQuery(String type, QueryBuilder queryBuilder) {
        HasChildQueryBuilder hasChildQueryBuilder = QueryBuilders.hasChildQuery(type, queryBuilder);
        hasChildQueryBuilder.setShortCircuitCutoff(randomInt(10));
        return hasChildQueryBuilder;
    }


    public void testParentWithoutChildTypes() {
        assertAcked(prepareCreate("test").addMapping("parent").addMapping("child", "_parent", "type=parent"));
        ensureGreen();

        try {
            client().prepareSearch("test").setQuery(hasParentQuery("child", matchAllQuery())).get();
            fail();
        } catch (SearchPhaseExecutionException e) {
            assertThat(e.status(), equalTo(RestStatus.BAD_REQUEST));
            assertThat(e.toString(), containsString("[has_parent] no child types found for type [child]"));
        }
    }
}
