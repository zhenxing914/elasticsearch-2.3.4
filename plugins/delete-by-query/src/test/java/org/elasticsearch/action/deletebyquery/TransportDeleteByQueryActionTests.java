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

package org.elasticsearch.action.deletebyquery;

import com.google.common.base.Predicate;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ShardOperationFailedException;
import org.elasticsearch.action.admin.cluster.node.stats.NodeStats;
import org.elasticsearch.action.admin.cluster.node.stats.NodesStatsResponse;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.search.ClearScrollResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.support.QuerySourceBuilder;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.CollectionUtils;
import org.elasticsearch.common.util.concurrent.CountDown;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.internal.InternalSearchHit;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.junit.Test;

import static org.elasticsearch.index.query.QueryBuilders.boolQuery;
import static org.elasticsearch.index.query.QueryBuilders.rangeQuery;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.equalTo;

public class TransportDeleteByQueryActionTests extends ESSingleNodeTestCase {

    @Test
    public void testExecuteScanFailsOnMissingIndex() {
        DeleteByQueryRequest delete = new DeleteByQueryRequest().indices("none");
        TestActionListener listener = new TestActionListener();

        newAsyncAction(delete, listener).executeScan();
        waitForCompletion("scan request should fail on missing index", listener);

        assertFailure(listener, "no such index");
        assertSearchContextsClosed();
    }

    @Test
    public void testExecuteScanFailsOnMalformedQuery() {
        createIndex("test");

        DeleteByQueryRequest delete = new DeleteByQueryRequest().indices("test").source("{...}");
        TestActionListener listener = new TestActionListener();

        newAsyncAction(delete, listener).executeScan();
        waitForCompletion("scan request should fail on malformed query", listener);

        assertFailure(listener, "all shards failed");
        assertSearchContextsClosed();
    }

    @Test
    public void testExecuteScan() {
        createIndex("test");
        final int numDocs = randomIntBetween(1, 200);
        for (int i = 1; i <= numDocs; i++) {
            client().prepareIndex("test", "type").setSource("num", i).get();
        }
        client().admin().indices().prepareRefresh("test").get();
        assertHitCount(client().prepareCount("test").get(), numDocs);

        final long limit = randomIntBetween(0, numDocs);
        DeleteByQueryRequest delete = new DeleteByQueryRequest().indices("test").source(new QuerySourceBuilder().setQuery(boolQuery().must(rangeQuery("num").lte(limit))));
        TestActionListener listener = new TestActionListener();

        newAsyncAction(delete, listener).executeScan();
        waitForCompletion("scan request should return the exact number of documents", listener);

        assertNoFailures(listener);
        DeleteByQueryResponse response = listener.getResponse();
        assertNotNull(response);
        assertThat(response.getTotalFound(), equalTo(limit));
        assertThat(response.getTotalDeleted(), equalTo(limit));
        assertSearchContextsClosed();
    }

    @Test
    public void testExecuteScrollFailsOnMissingScrollId() {
        DeleteByQueryRequest delete = new DeleteByQueryRequest();
        TestActionListener listener = new TestActionListener();

        newAsyncAction(delete, listener).executeScroll(null);
        waitForCompletion("scroll request should fail on missing scroll id", listener);

        assertFailure(listener, "scrollId is missing");
        assertSearchContextsClosed();
    }

    @Test
    public void testExecuteScrollFailsOnMalformedScrollId() {
        DeleteByQueryRequest delete = new DeleteByQueryRequest();
        TestActionListener listener = new TestActionListener();

        newAsyncAction(delete, listener).executeScroll("123");
        waitForCompletion("scroll request should fail on malformed scroll id", listener);

        assertFailure(listener, "Failed to decode scrollId");
        assertSearchContextsClosed();
    }

    @Test
    public void testExecuteScrollFailsOnExpiredScrollId() {
        final long numDocs = randomIntBetween(1, 100);
        for (int i = 1; i <= numDocs; i++) {
            client().prepareIndex("test", "type").setSource("num", i).get();
        }
        client().admin().indices().prepareRefresh("test").get();
        assertHitCount(client().prepareCount("test").get(), numDocs);

        SearchResponse searchResponse = client().prepareSearch("test").setSearchType(SearchType.SCAN).setScroll(TimeValue.timeValueSeconds(10)).get();
        assertThat(searchResponse.getHits().getTotalHits(), equalTo(numDocs));

        String scrollId = searchResponse.getScrollId();
        assertTrue(Strings.hasText(scrollId));

        ClearScrollResponse clearScrollResponse = client().prepareClearScroll().addScrollId(scrollId).get();
        assertTrue(clearScrollResponse.isSucceeded());

        DeleteByQueryRequest delete = new DeleteByQueryRequest().indices("test");
        TestActionListener listener = new TestActionListener();

        newAsyncAction(delete, listener).executeScroll(searchResponse.getScrollId());
        waitForCompletion("scroll request returns zero documents on expired scroll id", listener);

        assertNull(listener.getError());
        assertShardFailuresContains(listener.getResponse().getShardFailures(), "No search context found");
        assertSearchContextsClosed();
    }

    @Test
    public void testExecuteScrollTimedOut() throws InterruptedException {
        client().prepareIndex("test", "type").setSource("num", "1").setRefresh(true).get();

        SearchResponse searchResponse = client().prepareSearch("test").setSearchType(SearchType.SCAN).setScroll(TimeValue.timeValueSeconds(10)).get();
        String scrollId = searchResponse.getScrollId();
        assertTrue(Strings.hasText(scrollId));

        DeleteByQueryRequest delete = new DeleteByQueryRequest().indices("test").timeout(TimeValue.timeValueSeconds(1));
        TestActionListener listener = new TestActionListener();

        final TransportDeleteByQueryAction.AsyncDeleteByQueryAction async = newAsyncAction(delete, listener);
        awaitBusy(new Predicate<Object>() {
            @Override
            public boolean apply(Object input) {
                // Wait until the action timed out
                return async.hasTimedOut();
            }
        });

        async.executeScroll(searchResponse.getScrollId());
        waitForCompletion("scroll request returns zero documents on expired scroll id", listener);

        assertNull(listener.getError());
        assertTrue(listener.getResponse().isTimedOut());
        assertThat(listener.getResponse().getTotalDeleted(), equalTo(0L));
        assertSearchContextsClosed();
    }

    @Test
    public void testExecuteScrollNoDocuments() {
        createIndex("test");
        SearchResponse searchResponse = client().prepareSearch("test").setSearchType(SearchType.SCAN).setScroll(TimeValue.timeValueSeconds(10)).get();
        String scrollId = searchResponse.getScrollId();
        assertTrue(Strings.hasText(scrollId));

        DeleteByQueryRequest delete = new DeleteByQueryRequest().indices("test");
        TestActionListener listener = new TestActionListener();

        newAsyncAction(delete, listener).executeScroll(searchResponse.getScrollId());
        waitForCompletion("scroll request returns zero documents", listener);

        assertNull(listener.getError());
        assertFalse(listener.getResponse().isTimedOut());
        assertThat(listener.getResponse().getTotalFound(), equalTo(0L));
        assertThat(listener.getResponse().getTotalDeleted(), equalTo(0L));
        assertSearchContextsClosed();
    }

    @Test
    public void testExecuteScroll() {
        final int numDocs = randomIntBetween(1, 100);
        for (int i = 1; i <= numDocs; i++) {
            client().prepareIndex("test", "type").setSource("num", i).get();
        }
        client().admin().indices().prepareRefresh("test").get();
        assertHitCount(client().prepareCount("test").get(), numDocs);

        final long limit = randomIntBetween(0, numDocs);

        SearchResponse searchResponse = client().prepareSearch("test").setSearchType(SearchType.SCAN)
                .setScroll(TimeValue.timeValueSeconds(10))
                .setQuery(boolQuery().must(rangeQuery("num").lte(limit)))
                .addFields("_routing", "_parent")
                .setFetchSource(false)
                .setVersion(true)
                .get();

        String scrollId = searchResponse.getScrollId();
        assertTrue(Strings.hasText(scrollId));
        assertThat(searchResponse.getHits().getTotalHits(), equalTo(limit));

        DeleteByQueryRequest delete = new DeleteByQueryRequest().indices("test").size(100).source(boolQuery().must(rangeQuery("num").lte(limit)).buildAsBytes());
        TestActionListener listener = new TestActionListener();

        newAsyncAction(delete, listener).executeScroll(searchResponse.getScrollId());
        waitForCompletion("scroll request should return all documents", listener);

        assertNull(listener.getError());
        assertFalse(listener.getResponse().isTimedOut());
        assertThat(listener.getResponse().getTotalDeleted(), equalTo(limit));
        assertSearchContextsClosed();
    }

    @Test
    public void testOnBulkResponse() {
        final int nbItems = randomIntBetween(0, 20);
        long deleted = 0;
        long missing = 0;
        long failed = 0;

        BulkItemResponse[] items = new BulkItemResponse[nbItems];
        for (int i = 0; i < nbItems; i++) {
            if (randomBoolean()) {
                boolean delete = true;
                if (rarely()) {
                    delete = false;
                    missing++;
                } else {
                    deleted++;
                }
                items[i] = new BulkItemResponse(i, "delete", new DeleteResponse("test", "type", String.valueOf(i), 1, delete));
            } else {
                items[i] = new BulkItemResponse(i, "delete", new BulkItemResponse.Failure("test", "type", String.valueOf(i), new Throwable("item failed")));
                failed++;
            }
        }

        // We just need a valid scroll id
        createIndex("test");
        SearchResponse searchResponse = client().prepareSearch().setSearchType(SearchType.SCAN).setScroll(TimeValue.timeValueSeconds(10)).get();
        String scrollId = searchResponse.getScrollId();
        assertTrue(Strings.hasText(scrollId));

        try {
            DeleteByQueryRequest delete = new DeleteByQueryRequest();
            TestActionListener listener = new TestActionListener();

            newAsyncAction(delete, listener).onBulkResponse(scrollId, new BulkResponse(items, 0L));
            waitForCompletion("waiting for bulk response to complete", listener);

            assertNoFailures(listener);
            assertThat(listener.getResponse().getTotalDeleted(), equalTo(deleted));
            assertThat(listener.getResponse().getTotalFailed(), equalTo(failed));
            assertThat(listener.getResponse().getTotalMissing(), equalTo(missing));
        } finally {
            client().prepareClearScroll().addScrollId(scrollId).get();
        }
    }

    @Test
    public void testOnBulkResponseMultipleIndices() {
        final int nbIndices = randomIntBetween(2, 5);

        // Holds counters for the total + all indices
        final long[] found = new long[1 + nbIndices];
        final long[] deleted = new long[1 + nbIndices];
        final long[] missing = new long[1 + nbIndices];
        final long[] failed = new long[1 + nbIndices];

        final int nbItems = randomIntBetween(0, 100);
        found[0] = nbItems;

        BulkItemResponse[] items = new BulkItemResponse[nbItems];
        for (int i = 0; i < nbItems; i++) {
            int index = randomIntBetween(1, nbIndices);
            found[index] = found[index] + 1;

            if (randomBoolean()) {
                boolean delete = true;
                if (rarely()) {
                    delete = false;
                    missing[0] = missing[0] + 1;
                    missing[index] = missing[index] + 1;
                } else {
                    deleted[0] = deleted[0] + 1;
                    deleted[index] = deleted[index] + 1;
                }
                items[i] = new BulkItemResponse(i, "delete", new DeleteResponse("test-" + index, "type", String.valueOf(i), 1, delete));
            } else {
                items[i] = new BulkItemResponse(i, "delete", new BulkItemResponse.Failure("test-" + index, "type", String.valueOf(i), new Throwable("item failed")));
                failed[0] = failed[0] + 1;
                failed[index] = failed[index] + 1;
            }
        }

        // We just need a valid scroll id
        createIndex("test");
        SearchResponse searchResponse = client().prepareSearch().setSearchType(SearchType.SCAN).setScroll(TimeValue.timeValueSeconds(10)).get();
        String scrollId = searchResponse.getScrollId();
        assertTrue(Strings.hasText(scrollId));

        try {
            DeleteByQueryRequest delete = new DeleteByQueryRequest();
            TestActionListener listener = new TestActionListener();

            newAsyncAction(delete, listener).onBulkResponse(scrollId, new BulkResponse(items, 0L));
            waitForCompletion("waiting for bulk response to complete", listener);

            assertNoFailures(listener);
            assertThat(listener.getResponse().getTotalDeleted(), equalTo(deleted[0]));
            assertThat(listener.getResponse().getTotalFailed(), equalTo(failed[0]));
            assertThat(listener.getResponse().getTotalMissing(), equalTo(missing[0]));

            for (int i = 1; i <= nbIndices; i++) {
                IndexDeleteByQueryResponse indexResponse = listener.getResponse().getIndex("test-" + i);
                if (found[i] >= 1) {
                    assertNotNull(indexResponse);
                    assertThat(indexResponse.getFound(), equalTo(found[i]));
                    assertThat(indexResponse.getDeleted(), equalTo(deleted[i]));
                    assertThat(indexResponse.getFailed(), equalTo(failed[i]));
                    assertThat(indexResponse.getMissing(), equalTo(missing[i]));
                } else {
                    assertNull(indexResponse);
                }
            }
        } finally {
            client().prepareClearScroll().addScrollId(scrollId).get();
        }
    }

    @Test
    public void testOnBulkFailureNoDocuments() {
        DeleteByQueryRequest delete = new DeleteByQueryRequest();
        TestActionListener listener = new TestActionListener();

        newAsyncAction(delete, listener).onBulkFailure(null, new SearchHit[0], new Throwable("This is a bulk failure"));
        waitForCompletion("waiting for bulk failure to complete", listener);

        assertFailure(listener, "This is a bulk failure");
    }

    @Test
    public void testOnBulkFailure() {
        final int nbDocs = randomIntBetween(0, 20);
        SearchHit[] docs = new SearchHit[nbDocs];
        for (int i = 0; i < nbDocs; i++) {
            InternalSearchHit doc = new InternalSearchHit(randomInt(), String.valueOf(i), new Text("type"), null);
            doc.shard(new SearchShardTarget("node", "test", randomInt()));
            docs[i] = doc;
        }

        DeleteByQueryRequest delete = new DeleteByQueryRequest();
        TestActionListener listener = new TestActionListener();

        TransportDeleteByQueryAction.AsyncDeleteByQueryAction async = newAsyncAction(delete, listener);
        async.onBulkFailure(null, docs, new Throwable("This is a bulk failure"));
        waitForCompletion("waiting for bulk failure to complete", listener);
        assertFailure(listener, "This is a bulk failure");

        DeleteByQueryResponse response = async.buildResponse();
        assertThat(response.getTotalFailed(), equalTo((long) nbDocs));
        assertThat(response.getTotalDeleted(), equalTo(0L));
    }

    @Test
    public void testFinishHim() {
        TestActionListener listener = new TestActionListener();
        newAsyncAction(new DeleteByQueryRequest(), listener).finishHim(null, false, null);
        waitForCompletion("waiting for finishHim to complete with success", listener);
        assertNoFailures(listener);
        assertNotNull(listener.getResponse());
        assertFalse(listener.getResponse().isTimedOut());

        listener = new TestActionListener();
        newAsyncAction(new DeleteByQueryRequest(), listener).finishHim(null, true, null);
        waitForCompletion("waiting for finishHim to complete with timed out = true", listener);
        assertNoFailures(listener);
        assertNotNull(listener.getResponse());
        assertTrue(listener.getResponse().isTimedOut());

        listener = new TestActionListener();
        newAsyncAction(new DeleteByQueryRequest(), listener).finishHim(null, false, new Throwable("Fake error"));
        waitForCompletion("waiting for finishHim to complete with error", listener);
        assertFailure(listener, "Fake error");
        assertNull(listener.getResponse());
    }

    private TransportDeleteByQueryAction.AsyncDeleteByQueryAction newAsyncAction(DeleteByQueryRequest request, TestActionListener listener) {
        TransportDeleteByQueryAction action = getInstanceFromNode(TransportDeleteByQueryAction.class);
        assertNotNull(action);
        return action.new AsyncDeleteByQueryAction(request, listener);
    }

    private void waitForCompletion(String testName, final TestActionListener listener) {
        logger.info(" --> waiting for delete-by-query [{}] to complete", testName);
        try {
            awaitBusy(new Predicate<Object>() {
                @Override
                public boolean apply(Object input) {
                    return listener.isTerminated();
                }
            });
        } catch (InterruptedException e) {
            fail("exception when waiting for delete-by-query [" + testName + "] to complete: " + e.getMessage());
            logger.error("exception when waiting for delete-by-query [{}] to complete", e, testName);
        }
    }

    private void assertFailure(TestActionListener listener, String expectedMessage) {
        Throwable t = listener.getError();
        assertNotNull(t);
        assertTrue(Strings.hasText(expectedMessage));
        assertTrue("error message should contain [" + expectedMessage + "] but got [" + t.getMessage() + "]", t.getMessage().contains(expectedMessage));
    }

    private void assertNoFailures(TestActionListener listener) {
        assertNull(listener.getError());
        assertTrue(CollectionUtils.isEmpty(listener.getResponse().getShardFailures()));
    }

    private void assertSearchContextsClosed() {
        NodesStatsResponse nodesStats = client().admin().cluster().prepareNodesStats().setIndices(true).get();
        for (NodeStats nodeStat : nodesStats.getNodes()){
            assertThat(nodeStat.getIndices().getSearch().getOpenContexts(), equalTo(0L));
        }
    }

    private void assertShardFailuresContains(ShardOperationFailedException[] shardFailures, String expectedFailure) {
        assertNotNull(shardFailures);
        for (ShardOperationFailedException failure : shardFailures) {
            if (failure.reason().contains(expectedFailure)) {
                return;
            }
        }
        fail("failed to find shard failure [" + expectedFailure + "]");
    }

    private class TestActionListener implements ActionListener<DeleteByQueryResponse> {

        private final CountDown count = new CountDown(1);

        private DeleteByQueryResponse response;
        private Throwable error;

        @Override
        public void onResponse(DeleteByQueryResponse response) {
            try {
                this.response = response;
            } finally {
                count.countDown();
            }
        }

        @Override
        public void onFailure(Throwable e) {
            try {
                this.error = e;
            } finally {
                count.countDown();
            }
        }

        public boolean isTerminated() {
            return count.isCountedDown();
        }

        public DeleteByQueryResponse getResponse() {
            return response;
        }

        public Throwable getError() {
            return error;
        }
    }
}
