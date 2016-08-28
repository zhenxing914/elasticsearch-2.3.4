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

package org.elasticsearch.script.expression;

import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.test.ESIntegTestCase;
import org.junit.Test;

import java.io.IOException;
import java.util.Collection;

import static org.hamcrest.Matchers.containsString;

//TODO: please convert to unit tests!
public class IndexedExpressionTests extends ESIntegTestCase {
    
    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        Settings.Builder builder = Settings.builder().put(super.nodeSettings(nodeOrdinal));
        builder.put("script.engine.expression.indexed.update", "off");
        builder.put("script.engine.expression.indexed.search", "off");
        builder.put("script.engine.expression.indexed.aggs", "off");
        builder.put("script.engine.expression.indexed.mapping", "off");
        return builder.build();
    }
    
    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return pluginList(ExpressionPlugin.class);
    }

    @Test
    public void testAllOpsDisabledIndexedScripts() throws IOException {
        if (randomBoolean()) {
            client().preparePutIndexedScript(ExpressionScriptEngineService.NAME, "script1", "{\"script\":\"2\"}").get();
        } else {
            client().prepareIndex(ScriptService.SCRIPT_INDEX, ExpressionScriptEngineService.NAME, "script1").setSource("{\"script\":\"2\"}").get();
        }
        client().prepareIndex("test", "scriptTest", "1").setSource("{\"theField\":\"foo\"}").get();
        try {
            client().prepareUpdate("test", "scriptTest", "1")
                    .setScript(new Script("script1", ScriptService.ScriptType.INDEXED, ExpressionScriptEngineService.NAME, null)).get();
            fail("update script should have been rejected");
        } catch(Exception e) {
            assertThat(e.getMessage(), containsString("failed to execute script"));
            assertThat(e.getCause().getMessage(), containsString("scripts of type [indexed], operation [update] and lang [expression] are disabled"));
        }
        try {
            String query = "{ \"script_fields\" : { \"test1\" : { \"script_id\" : \"script1\", \"lang\":\"expression\" }}}";
            client().prepareSearch().setSource(new BytesArray(query)).setIndices("test").setTypes("scriptTest").get();
            fail("search script should have been rejected");
        } catch(Exception e) {
            assertThat(e.toString(), containsString("scripts of type [indexed], operation [search] and lang [expression] are disabled"));
        }
        try {
            String source = "{\"aggs\": {\"test\": { \"terms\" : { \"script_id\":\"script1\", \"script_lang\":\"expression\" } } } }";
            client().prepareSearch("test").setSource(new BytesArray(source)).get();
        } catch(Exception e) {
            assertThat(e.toString(), containsString("scripts of type [indexed], operation [aggs] and lang [expression] are disabled"));
        }
    }
}
