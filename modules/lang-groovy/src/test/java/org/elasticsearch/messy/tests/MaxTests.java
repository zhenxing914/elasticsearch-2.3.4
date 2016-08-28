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
package org.elasticsearch.messy.tests;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptService.ScriptType;
import org.elasticsearch.script.groovy.GroovyPlugin;
import org.elasticsearch.search.aggregations.bucket.filter.Filter;
import org.elasticsearch.search.aggregations.bucket.global.Global;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms.Order;
import org.elasticsearch.search.aggregations.metrics.AbstractNumericTestCase;
import org.elasticsearch.search.aggregations.metrics.max.Max;

import org.junit.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.elasticsearch.search.aggregations.AggregationBuilders.filter;
import static org.elasticsearch.search.aggregations.AggregationBuilders.global;
import static org.elasticsearch.search.aggregations.AggregationBuilders.histogram;
import static org.elasticsearch.search.aggregations.AggregationBuilders.max;
import static org.elasticsearch.search.aggregations.AggregationBuilders.terms;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 *
 */
public class MaxTests extends AbstractNumericTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return pluginList(GroovyPlugin.class);
    }

    @Override
    @Test
    public void testEmptyAggregation() throws Exception {

        SearchResponse searchResponse = client().prepareSearch("empty_bucket_idx")
                .setQuery(matchAllQuery())
                .addAggregation(histogram("histo").field("value").interval(1l).minDocCount(0).subAggregation(max("max")))
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(2l));
        Histogram histo = searchResponse.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        Histogram.Bucket bucket = histo.getBuckets().get(1);
        assertThat(bucket, notNullValue());

        Max max = bucket.getAggregations().get("max");
        assertThat(max, notNullValue());
        assertThat(max.getName(), equalTo("max"));
        assertThat(max.getValue(), equalTo(Double.NEGATIVE_INFINITY));
    }
    @Override
    @Test
    public void testUnmapped() throws Exception {

        SearchResponse searchResponse = client().prepareSearch("idx_unmapped")
                .setQuery(matchAllQuery())
                .addAggregation(max("max").field("value"))
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(0l));

        Max max = searchResponse.getAggregations().get("max");
        assertThat(max, notNullValue());
        assertThat(max.getName(), equalTo("max"));
        assertThat(max.getValue(), equalTo(Double.NEGATIVE_INFINITY));
    }

    @Override
    @Test
    public void testSingleValuedField() throws Exception {
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(max("max").field("value"))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        Max max = searchResponse.getAggregations().get("max");
        assertThat(max, notNullValue());
        assertThat(max.getName(), equalTo("max"));
        assertThat(max.getValue(), equalTo(10.0));
    }

    @Test
    public void testSingleValuedField_WithFormatter() throws Exception {
        SearchResponse searchResponse = client().prepareSearch("idx").setQuery(matchAllQuery())
                .addAggregation(max("max").format("0000.0").field("value")).execute().actionGet();

        assertHitCount(searchResponse, 10);

        Max max = searchResponse.getAggregations().get("max");
        assertThat(max, notNullValue());
        assertThat(max.getName(), equalTo("max"));
        assertThat(max.getValue(), equalTo(10.0));
        assertThat(max.getValueAsString(), equalTo("0010.0"));
    }

    @Override
    @Test
    public void testSingleValuedField_getProperty() throws Exception {

        SearchResponse searchResponse = client().prepareSearch("idx").setQuery(matchAllQuery())
                .addAggregation(global("global").subAggregation(max("max").field("value"))).execute().actionGet();

        assertHitCount(searchResponse, 10);

        Global global = searchResponse.getAggregations().get("global");
        assertThat(global, notNullValue());
        assertThat(global.getName(), equalTo("global"));
        assertThat(global.getDocCount(), equalTo(10l));
        assertThat(global.getAggregations(), notNullValue());
        assertThat(global.getAggregations().asMap().size(), equalTo(1));

        Max max = global.getAggregations().get("max");
        assertThat(max, notNullValue());
        assertThat(max.getName(), equalTo("max"));
        double expectedMaxValue = 10.0;
        assertThat(max.getValue(), equalTo(expectedMaxValue));
        assertThat((Max) global.getProperty("max"), equalTo(max));
        assertThat((double) global.getProperty("max.value"), equalTo(expectedMaxValue));
        assertThat((double) max.getProperty("value"), equalTo(expectedMaxValue));
    }

    @Override
    @Test
    public void testSingleValuedField_PartiallyUnmapped() throws Exception {
        SearchResponse searchResponse = client().prepareSearch("idx", "idx_unmapped")
                .setQuery(matchAllQuery())
                .addAggregation(max("max").field("value"))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        Max max = searchResponse.getAggregations().get("max");
        assertThat(max, notNullValue());
        assertThat(max.getName(), equalTo("max"));
        assertThat(max.getValue(), equalTo(10.0));
    }

    @Override
    @Test
    public void testSingleValuedField_WithValueScript() throws Exception {
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(max("max").field("value").script(new Script("_value + 1")))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        Max max = searchResponse.getAggregations().get("max");
        assertThat(max, notNullValue());
        assertThat(max.getName(), equalTo("max"));
        assertThat(max.getValue(), equalTo(11.0));
    }

    @Override
    @Test
    public void testSingleValuedField_WithValueScript_WithParams() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("inc", 1);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(max("max").field("value").script(new Script("_value + inc", ScriptType.INLINE, null, params)))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        Max max = searchResponse.getAggregations().get("max");
        assertThat(max, notNullValue());
        assertThat(max.getName(), equalTo("max"));
        assertThat(max.getValue(), equalTo(11.0));
    }

    @Override
    @Test
    public void testMultiValuedField() throws Exception {
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(max("max").field("values"))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        Max max = searchResponse.getAggregations().get("max");
        assertThat(max, notNullValue());
        assertThat(max.getName(), equalTo("max"));
        assertThat(max.getValue(), equalTo(12.0));
    }

    @Override
    @Test
    public void testMultiValuedField_WithValueScript() throws Exception {
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(max("max").field("values").script(new Script("_value + 1")))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        Max max = searchResponse.getAggregations().get("max");
        assertThat(max, notNullValue());
        assertThat(max.getName(), equalTo("max"));
        assertThat(max.getValue(), equalTo(13.0));
    }

    @Override
    @Test
    public void testMultiValuedField_WithValueScript_WithParams() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("inc", 1);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(max("max").field("values").script(new Script("_value + inc", ScriptType.INLINE, null, params)))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        Max max = searchResponse.getAggregations().get("max");
        assertThat(max, notNullValue());
        assertThat(max.getName(), equalTo("max"));
        assertThat(max.getValue(), equalTo(13.0));
    }

    @Override
    @Test
    public void testScript_SingleValued() throws Exception {
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(max("max").script(new Script("doc['value'].value")))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        Max max = searchResponse.getAggregations().get("max");
        assertThat(max, notNullValue());
        assertThat(max.getName(), equalTo("max"));
        assertThat(max.getValue(), equalTo(10.0));
    }

    @Override
    @Test
    public void testScript_SingleValued_WithParams() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("inc", 1);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(max("max").script(new Script("doc['value'].value + inc", ScriptType.INLINE, null, params)))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        Max max = searchResponse.getAggregations().get("max");
        assertThat(max, notNullValue());
        assertThat(max.getName(), equalTo("max"));
        assertThat(max.getValue(), equalTo(11.0));
    }

    @Override
    public void testScriptMultiValued() throws Exception {
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(max("max").script(new Script("doc['values'].values")))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        Max max = searchResponse.getAggregations().get("max");
        assertThat(max, notNullValue());
        assertThat(max.getName(), equalTo("max"));
        assertThat(max.getValue(), equalTo(12.0));
    }

    @Override
    public void testScriptMultiValuedWithParams() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("inc", 1);
        SearchResponse searchResponse = client().prepareSearch("idx").setQuery(matchAllQuery())
                .addAggregation(
                        max("max").script(new Script("[ doc['value'].value, doc['value'].value + inc ]", ScriptType.INLINE, null, params)))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        Max max = searchResponse.getAggregations().get("max");
        assertThat(max, notNullValue());
        assertThat(max.getName(), equalTo("max"));
        assertThat(max.getValue(), equalTo(11.0));
    }

    @Override
    public void testOrderByEmptyAggregation() throws Exception {
        SearchResponse searchResponse = client().prepareSearch("idx").setQuery(matchAllQuery())
                .addAggregation(terms("terms").field("value").order(Order.compound(Order.aggregation("filter>max", true)))
                        .subAggregation(filter("filter").filter(termQuery("value", 100)).subAggregation(max("max").field("value"))))
                .get();

        assertHitCount(searchResponse, 10);

        Terms terms = searchResponse.getAggregations().get("terms");
        assertThat(terms, notNullValue());
        List<Terms.Bucket> buckets = terms.getBuckets();
        assertThat(buckets, notNullValue());
        assertThat(buckets.size(), equalTo(10));

        for (int i = 0; i < 10; i++) {
            Terms.Bucket bucket = buckets.get(i);
            assertThat(bucket, notNullValue());
            assertThat(bucket.getKeyAsNumber(), equalTo((Number) Long.valueOf(i + 1)));
            assertThat(bucket.getDocCount(), equalTo(1L));
            Filter filter = bucket.getAggregations().get("filter");
            assertThat(filter, notNullValue());
            assertThat(filter.getDocCount(), equalTo(0L));
            Max max = filter.getAggregations().get("max");
            assertThat(max, notNullValue());
            assertThat(max.value(), equalTo(Double.NEGATIVE_INFINITY));

        }
    }
}