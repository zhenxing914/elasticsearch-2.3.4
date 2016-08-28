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

package org.elasticsearch.gateway;

import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.routing.allocation.decider.ThrottlingAllocationDecider;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.elasticsearch.test.ESIntegTestCase.Scope;
import org.elasticsearch.test.InternalTestCluster.RestartCallback;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.elasticsearch.test.store.MockFSDirectoryService;
import org.junit.Test;

import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_NUMBER_OF_REPLICAS;
import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_NUMBER_OF_SHARDS;
import static org.elasticsearch.common.settings.Settings.settingsBuilder;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@ClusterScope(numDataNodes = 0, scope = Scope.TEST)
public class RecoveryFromGatewayIT extends ESIntegTestCase {

    @Test
    public void testOneNodeRecoverFromGateway() throws Exception {

        internalCluster().startNode();

        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type1")
                .startObject("properties").startObject("appAccountIds").field("type", "string").endObject().endObject()
                .endObject().endObject().string();
        assertAcked(prepareCreate("test").addMapping("type1", mapping));

        client().prepareIndex("test", "type1", "10990239").setSource(jsonBuilder().startObject()
                .startArray("appAccountIds").value(14).value(179).endArray().endObject()).execute().actionGet();
        client().prepareIndex("test", "type1", "10990473").setSource(jsonBuilder().startObject()
                .startArray("appAccountIds").value(14).endArray().endObject()).execute().actionGet();
        client().prepareIndex("test", "type1", "10990513").setSource(jsonBuilder().startObject()
                .startArray("appAccountIds").value(14).value(179).endArray().endObject()).execute().actionGet();
        client().prepareIndex("test", "type1", "10990695").setSource(jsonBuilder().startObject()
                .startArray("appAccountIds").value(14).endArray().endObject()).execute().actionGet();
        client().prepareIndex("test", "type1", "11026351").setSource(jsonBuilder().startObject()
                .startArray("appAccountIds").value(14).endArray().endObject()).execute().actionGet();

        refresh();
        assertHitCount(client().prepareCount().setQuery(termQuery("appAccountIds", 179)).execute().actionGet(), 2);
        ensureYellow("test"); // wait for primary allocations here otherwise if we have a lot of shards we might have a
        // shard that is still in post recovery when we restart and the ensureYellow() below will timeout
        internalCluster().fullRestart();

        logger.info("Running Cluster Health (wait for the shards to startup)");
        ensureYellow();

        client().admin().indices().prepareRefresh().execute().actionGet();
        assertHitCount(client().prepareCount().setQuery(termQuery("appAccountIds", 179)).execute().actionGet(), 2);

        internalCluster().fullRestart();

        logger.info("Running Cluster Health (wait for the shards to startup)");
        ensureYellow();

        client().admin().indices().prepareRefresh().execute().actionGet();
        assertHitCount(client().prepareCount().setQuery(termQuery("appAccountIds", 179)).execute().actionGet(), 2);
    }

    @Test
    public void testSingleNodeNoFlush() throws Exception {

        internalCluster().startNode();

        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type1")
                .startObject("properties").startObject("field").field("type", "string").endObject().startObject("num").field("type", "integer").endObject().endObject()
                .endObject().endObject().string();
        // note: default replica settings are tied to #data nodes-1 which is 0 here. We can do with 1 in this test.
        int numberOfShards = numberOfShards();
        assertAcked(prepareCreate("test").setSettings(
                SETTING_NUMBER_OF_SHARDS, numberOfShards(),
                SETTING_NUMBER_OF_REPLICAS, randomIntBetween(0, 1)
        ).addMapping("type1", mapping));

        int value1Docs;
        int value2Docs;
        boolean indexToAllShards = randomBoolean();

        if (indexToAllShards) {
            // insert enough docs so all shards will have a doc
            value1Docs = randomIntBetween(numberOfShards * 10, numberOfShards * 20);
            value2Docs = randomIntBetween(numberOfShards * 10, numberOfShards * 20);

        } else {
            // insert a two docs, some shards will not have anything
            value1Docs = 1;
            value2Docs = 1;
        }


        for (int i = 0; i < 1 + randomInt(100); i++) {
            for (int id = 0; id < Math.max(value1Docs, value2Docs); id++) {
                if (id < value1Docs) {
                    index("test", "type1", "1_" + id,
                            jsonBuilder().startObject().field("field", "value1").startArray("num").value(14).value(179).endArray().endObject()
                    );
                }
                if (id < value2Docs) {
                    index("test", "type1", "2_" + id,
                            jsonBuilder().startObject().field("field", "value2").startArray("num").value(14).endArray().endObject()
                    );
                }
            }

        }

        refresh();

        for (int i = 0; i <= randomInt(10); i++) {
            assertHitCount(client().prepareCount().setQuery(matchAllQuery()).get(), value1Docs + value2Docs);
            assertHitCount(client().prepareCount().setQuery(termQuery("field", "value1")).get(), value1Docs);
            assertHitCount(client().prepareCount().setQuery(termQuery("field", "value2")).get(), value2Docs);
            assertHitCount(client().prepareCount().setQuery(termQuery("num", 179)).get(), value1Docs);
        }
        if (!indexToAllShards) {
            // we have to verify primaries are started for them to be restored
            logger.info("Ensure all primaries have been started");
            ensureYellow();
        }
        internalCluster().fullRestart();

        logger.info("Running Cluster Health (wait for the shards to startup)");
        ensureYellow();

        for (int i = 0; i <= randomInt(10); i++) {
            assertHitCount(client().prepareCount().setQuery(matchAllQuery()).get(), value1Docs + value2Docs);
            assertHitCount(client().prepareCount().setQuery(termQuery("field", "value1")).get(), value1Docs);
            assertHitCount(client().prepareCount().setQuery(termQuery("field", "value2")).get(), value2Docs);
            assertHitCount(client().prepareCount().setQuery(termQuery("num", 179)).get(), value1Docs);
        }

        internalCluster().fullRestart();


        logger.info("Running Cluster Health (wait for the shards to startup)");
        ensureYellow();

        for (int i = 0; i <= randomInt(10); i++) {
            assertHitCount(client().prepareCount().setQuery(matchAllQuery()).get(), value1Docs + value2Docs);
            assertHitCount(client().prepareCount().setQuery(termQuery("field", "value1")).get(), value1Docs);
            assertHitCount(client().prepareCount().setQuery(termQuery("field", "value2")).get(), value2Docs);
            assertHitCount(client().prepareCount().setQuery(termQuery("num", 179)).get(), value1Docs);
        }
    }

    @Test
    public void testSingleNodeWithFlush() throws Exception {

        internalCluster().startNode();
        client().prepareIndex("test", "type1", "1").setSource(jsonBuilder().startObject().field("field", "value1").endObject()).execute().actionGet();
        flush();
        client().prepareIndex("test", "type1", "2").setSource(jsonBuilder().startObject().field("field", "value2").endObject()).execute().actionGet();
        refresh();

        assertHitCount(client().prepareCount().setQuery(matchAllQuery()).execute().actionGet(), 2);

        ensureYellow("test"); // wait for primary allocations here otherwise if we have a lot of shards we might have a
        // shard that is still in post recovery when we restart and the ensureYellow() below will timeout

        internalCluster().fullRestart();

        logger.info("Running Cluster Health (wait for the shards to startup)");
        ensureYellow();

        for (int i = 0; i < 10; i++) {
            assertHitCount(client().prepareCount().setQuery(matchAllQuery()).execute().actionGet(), 2);
        }

        internalCluster().fullRestart();

        logger.info("Running Cluster Health (wait for the shards to startup)");
        ensureYellow();

        for (int i = 0; i < 10; i++) {
            assertHitCount(client().prepareCount().setQuery(matchAllQuery()).execute().actionGet(), 2);
        }
    }

    @Test
    public void testTwoNodeFirstNodeCleared() throws Exception {

        final String firstNode = internalCluster().startNode();
        internalCluster().startNode();

        client().prepareIndex("test", "type1", "1").setSource(jsonBuilder().startObject().field("field", "value1").endObject()).execute().actionGet();
        flush();
        client().prepareIndex("test", "type1", "2").setSource(jsonBuilder().startObject().field("field", "value2").endObject()).execute().actionGet();
        refresh();

        logger.info("Running Cluster Health (wait for the shards to startup)");
        ensureGreen();

        for (int i = 0; i < 10; i++) {
            assertHitCount(client().prepareCount().setQuery(matchAllQuery()).execute().actionGet(), 2);
        }

        internalCluster().fullRestart(new RestartCallback() {
            @Override
            public Settings onNodeStopped(String nodeName) throws Exception {
                return settingsBuilder().put("gateway.recover_after_nodes", 2).build();
            }

            @Override
            public boolean clearData(String nodeName) {
                return firstNode.equals(nodeName);
            }

        });

        logger.info("Running Cluster Health (wait for the shards to startup)");
        ensureGreen();

        for (int i = 0; i < 10; i++) {
            assertHitCount(client().prepareCount().setQuery(matchAllQuery()).execute().actionGet(), 2);
        }
    }

    @Test
    public void testLatestVersionLoaded() throws Exception {
        // clean two nodes
        internalCluster().startNodesAsync(2, settingsBuilder().put("gateway.recover_after_nodes", 2).build()).get();

        client().prepareIndex("test", "type1", "1").setSource(jsonBuilder().startObject().field("field", "value1").endObject()).execute().actionGet();
        client().admin().indices().prepareFlush().execute().actionGet();
        client().prepareIndex("test", "type1", "2").setSource(jsonBuilder().startObject().field("field", "value2").endObject()).execute().actionGet();
        client().admin().indices().prepareRefresh().execute().actionGet();

        logger.info("--> running cluster_health (wait for the shards to startup)");
        ensureGreen();

        for (int i = 0; i < 10; i++) {
            assertHitCount(client().prepareCount().setQuery(matchAllQuery()).execute().actionGet(), 2);
        }

        String metaDataUuid = client().admin().cluster().prepareState().execute().get().getState().getMetaData().clusterUUID();
        assertThat(metaDataUuid, not(equalTo("_na_")));

        logger.info("--> closing first node, and indexing more data to the second node");
        internalCluster().fullRestart(new RestartCallback() {

            @Override
            public void doAfterNodes(int numNodes, Client client) throws Exception {
                if (numNodes == 1) {
                    logger.info("--> one node is closed - start indexing data into the second one");
                    client.prepareIndex("test", "type1", "3").setSource(jsonBuilder().startObject().field("field", "value3").endObject()).execute().actionGet();
                    // TODO: remove once refresh doesn't fail immediately if there a master block:
                    // https://github.com/elasticsearch/elasticsearch/issues/9997
                    client.admin().cluster().prepareHealth("test").setWaitForYellowStatus().get();
                    client.admin().indices().prepareRefresh().execute().actionGet();

                    for (int i = 0; i < 10; i++) {
                        assertHitCount(client.prepareCount().setQuery(matchAllQuery()).execute().actionGet(), 3);
                    }

                    logger.info("--> add some metadata, additional type and template");
                    client.admin().indices().preparePutMapping("test").setType("type2")
                            .setSource(jsonBuilder().startObject().startObject("type2").endObject().endObject())
                            .execute().actionGet();
                    client.admin().indices().preparePutTemplate("template_1")
                            .setTemplate("te*")
                            .setOrder(0)
                            .addMapping("type1", XContentFactory.jsonBuilder().startObject().startObject("type1").startObject("properties")
                                    .startObject("field1").field("type", "string").field("store", "yes").endObject()
                                    .startObject("field2").field("type", "string").field("store", "yes").field("index", "not_analyzed").endObject()
                                    .endObject().endObject().endObject())
                            .execute().actionGet();
                    client.admin().indices().prepareAliases().addAlias("test", "test_alias", QueryBuilders.termQuery("field", "value")).execute().actionGet();
                    logger.info("--> starting two nodes back, verifying we got the latest version");
                }

            }

        });

        logger.info("--> running cluster_health (wait for the shards to startup)");
        ensureGreen();

        assertThat(client().admin().cluster().prepareState().execute().get().getState().getMetaData().clusterUUID(), equalTo(metaDataUuid));

        for (int i = 0; i < 10; i++) {
            assertHitCount(client().prepareCount().setQuery(matchAllQuery()).execute().actionGet(), 3);
        }

        ClusterState state = client().admin().cluster().prepareState().execute().actionGet().getState();
        assertThat(state.metaData().index("test").mapping("type2"), notNullValue());
        assertThat(state.metaData().templates().get("template_1").template(), equalTo("te*"));
        assertThat(state.metaData().index("test").getAliases().get("test_alias"), notNullValue());
        assertThat(state.metaData().index("test").getAliases().get("test_alias").filter(), notNullValue());
    }

    @Test
    @TestLogging("gateway:TRACE,indices.recovery:TRACE,index.engine:TRACE")
    public void testReusePeerRecovery() throws Exception {
        final Settings settings = settingsBuilder()
                .put("action.admin.cluster.node.shutdown.delay", "10ms")
                .put(MockFSDirectoryService.CHECK_INDEX_ON_CLOSE, false)
                .put("gateway.recover_after_nodes", 4)

                .put(ThrottlingAllocationDecider.CLUSTER_ROUTING_ALLOCATION_CONCURRENT_RECOVERIES, 4)
                .put(MockFSDirectoryService.CRASH_INDEX, false).build();

        internalCluster().startNodesAsync(4, settings).get();
        Runnable restartCluster = new Runnable() {
            @Override
            public void run() {
                try {
                    internalCluster().fullRestart();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
        ReusePeerRecoverySharedTest.testCase(indexSettings(), restartCluster, logger, randomBoolean());
    }

    @Test
    public void testRecoveryDifferentNodeOrderStartup() throws Exception {
        // we need different data paths so we make sure we start the second node fresh

        final String node_1 = internalCluster().startNode(settingsBuilder().put("path.data", createTempDir()).build());

        client().prepareIndex("test", "type1", "1").setSource("field", "value").execute().actionGet();

        internalCluster().startNode(settingsBuilder().put("path.data", createTempDir()).build());

        ensureGreen();

        internalCluster().fullRestart(new RestartCallback() {

            @Override
            public boolean doRestart(String nodeName) {
                return !node_1.equals(nodeName);
            }
        });

        ensureYellow();

        assertThat(client().admin().indices().prepareExists("test").execute().actionGet().isExists(), equalTo(true));
        assertHitCount(client().prepareCount("test").setQuery(QueryBuilders.matchAllQuery()).execute().actionGet(), 1);
    }

}
