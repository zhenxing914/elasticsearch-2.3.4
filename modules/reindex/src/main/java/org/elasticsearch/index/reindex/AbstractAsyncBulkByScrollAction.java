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

package org.elasticsearch.index.reindex;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshResponse;
import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkItemResponse.Failure;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.bulk.Retry;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.ClearScrollRequest;
import org.elasticsearch.action.search.ClearScrollResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchScrollRequest;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.Collections.unmodifiableList;
import static org.elasticsearch.action.bulk.BackoffPolicy.exponentialBackoff;
import static org.elasticsearch.common.unit.TimeValue.timeValueNanos;
import static org.elasticsearch.index.reindex.AbstractBulkByScrollRequest.SIZE_ALL_MATCHES;
import static org.elasticsearch.rest.RestStatus.CONFLICT;

/**
 * Abstract base for scrolling across a search and executing bulk actions on all
 * results.
 */
public abstract class AbstractAsyncBulkByScrollAction<Request extends AbstractBulkByScrollRequest<Request>, Response> {
    /**
     * The request for this action. Named mainRequest because we create lots of <code>request</code> variables all representing child
     * requests of this mainRequest.
     */
    protected final Request mainRequest;
    protected final BulkByScrollTask task;

    private final AtomicLong startTime = new AtomicLong(-1);
    private final AtomicReference<String> scroll = new AtomicReference<>();
    private final Set<String> destinationIndices = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private final ESLogger logger;
    private final Client client;
    private final ThreadPool threadPool;
    private final SearchRequest firstSearchRequest;
    private final ActionListener<Response> listener;
    private final Retry retry;

    public AbstractAsyncBulkByScrollAction(BulkByScrollTask task, ESLogger logger, Client client, ThreadPool threadPool,
            Version smallestNonClientVersion, Request mainRequest, SearchRequest firstSearchRequest, ActionListener<Response> listener) {
        if (smallestNonClientVersion.before(Version.V_2_3_0)) {
            throw new IllegalStateException(
                    "Refusing to execute [" + mainRequest + "] because the entire cluster has not been upgraded to 2.3");
        }
        this.task = task;
        this.logger = logger;
        this.client = client;
        this.threadPool = threadPool;
        this.mainRequest = mainRequest;
        this.firstSearchRequest = firstSearchRequest;
        this.listener = listener;
        retry = Retry.on(EsRejectedExecutionException.class).policy(wrapBackoffPolicy(backoffPolicy()));
        mainRequest.applyDefaults();
    }

    protected abstract BulkRequest buildBulk(Iterable<SearchHit> docs);

    protected abstract Response buildResponse(TimeValue took, List<Failure> indexingFailures, List<ShardSearchFailure> searchFailures,
            boolean timedOut);

    public void start() {
        initialSearch();
    }

    public BulkByScrollTask getTask() {
        return task;
    }

    void initialSearch() {
        if (task.isCancelled()) {
            finishHim(null);
            return;
        }
        try {
            startTime.set(System.nanoTime());
            if (logger.isDebugEnabled()) {
                logger.debug("executing initial scroll against {}{}",
                        firstSearchRequest.indices() == null || firstSearchRequest.indices().length == 0 ? "all indices"
                                : firstSearchRequest.indices(),
                        firstSearchRequest.types() == null || firstSearchRequest.types().length == 0 ? ""
                                : firstSearchRequest.types());
            }
            // Copy firstSearchRequest to give it mainRequest's context
            client.search(new SearchRequest(firstSearchRequest, mainRequest), new ActionListener<SearchResponse>() {
                @Override
                public void onResponse(SearchResponse response) {
                    logger.debug("[{}] documents match query", response.getHits().getTotalHits());
                    onScrollResponse(response);
                }

                @Override
                public void onFailure(Throwable e) {
                    finishHim(e);
                }
            });
        } catch (Throwable t) {
            finishHim(t);
        }
    }

    /**
     * Set the last returned scrollId. Package private for testing.
     */
    void setScroll(String scroll) {
        this.scroll.set(scroll);
    }

    void onScrollResponse(final SearchResponse searchResponse) {
        if (task.isCancelled()) {
            finishHim(null);
            return;
        }
        setScroll(searchResponse.getScrollId());
        if (    // If any of the shards failed that should abort the request.
                (searchResponse.getShardFailures() != null && searchResponse.getShardFailures().length > 0)
                // Timeouts aren't shard failures but we still need to pass them back to the user.
                || searchResponse.isTimedOut()
                ) {
            startNormalTermination(Collections.<Failure>emptyList(), unmodifiableList(Arrays.asList(searchResponse.getShardFailures())),
                    searchResponse.isTimedOut());
            return;
        }
        long total = searchResponse.getHits().totalHits();
        if (mainRequest.getSize() > 0) {
            total = min(total, mainRequest.getSize());
        }
        task.setTotal(total);
        threadPool.generic().execute(new AbstractRunnable() {
            @Override
            protected void doRun() throws Exception {
                SearchHit[] docs = searchResponse.getHits().getHits();
                logger.debug("scroll returned [{}] documents with a scroll id of [{}]", docs.length, searchResponse.getScrollId());
                if (docs.length == 0) {
                    startNormalTermination(Collections.<Failure>emptyList(), Collections.<ShardSearchFailure>emptyList(), false);
                    return;
                }
                task.countBatch();
                List<SearchHit> docsIterable = Arrays.asList(docs);
                if (mainRequest.getSize() != SIZE_ALL_MATCHES) {
                    // Truncate the docs if we have more than the request size
                    long remaining = max(0, mainRequest.getSize() - task.getSuccessfullyProcessed());
                    if (remaining < docs.length) {
                        docsIterable = docsIterable.subList(0, (int) remaining);
                    }
                }
                BulkRequest request = buildBulk(docsIterable);
                if (request.requests().isEmpty()) {
                    /*
                     * If we noop-ed the entire batch then just skip to the next batch or the BulkRequest would fail validation.
                     */
                    startNextScroll();
                    return;
                }
                request.timeout(mainRequest.getTimeout());
                request.consistencyLevel(mainRequest.getConsistency());
                if (logger.isDebugEnabled()) {
                    logger.debug("sending [{}] entry, [{}] bulk request", request.requests().size(),
                            new ByteSizeValue(request.estimatedSizeInBytes()));
                }
                sendBulkRequest(request);
            }

            @Override
            public void onFailure(Throwable t) {
                finishHim(t);
            }
        });
    }

    void sendBulkRequest(BulkRequest request) {
        if (task.isCancelled()) {
            finishHim(null);
            return;
        }
        retry.withAsyncBackoff(client, request, new ActionListener<BulkResponse>() {
            @Override
            public void onResponse(BulkResponse response) {
                onBulkResponse(response);
            }

            @Override
            public void onFailure(Throwable e) {
                finishHim(e);
            }
        });
    }

    void onBulkResponse(BulkResponse response) {
        if (task.isCancelled()) {
            finishHim(null);
            return;
        }
        try {
            List<Failure> failures = new ArrayList<Failure>();
            Set<String> destinationIndicesThisBatch = new HashSet<>();
            for (BulkItemResponse item : response) {
                if (item.isFailed()) {
                    recordFailure(item.getFailure(), failures);
                    continue;
                }

                switch (item.getOpType()) {
                case "index":
                case "create":
                    IndexResponse ir = item.getResponse();
                    if (ir.isCreated()) {
                        task.countCreated();
                    } else {
                        task.countUpdated();
                    }
                    break;
                case "delete":
                    task.countDeleted();
                    break;
                default:
                    throw new IllegalArgumentException("Unknown op type:  " + item.getOpType());
                }
                // Track the indexes we've seen so we can refresh them if requested
                destinationIndicesThisBatch.add(item.getIndex());
            }
            addDestinationIndices(destinationIndicesThisBatch);

            if (false == failures.isEmpty()) {
                startNormalTermination(unmodifiableList(failures), Collections.<ShardSearchFailure>emptyList(), false);
                return;
            }

            if (mainRequest.getSize() != SIZE_ALL_MATCHES && task.getSuccessfullyProcessed() >= mainRequest.getSize()) {
                // We've processed all the requested docs.
                startNormalTermination(Collections.<Failure>emptyList(), Collections.<ShardSearchFailure>emptyList(), false);
                return;
            }
            startNextScroll();
        } catch (Throwable t) {
            finishHim(t);
        }
    }

    void startNextScroll() {
        if (task.isCancelled()) {
            finishHim(null);
            return;
        }
        SearchScrollRequest request = new SearchScrollRequest(mainRequest);
        request.scrollId(scroll.get()).scroll(firstSearchRequest.scroll());
        client.searchScroll(request, new ActionListener<SearchResponse>() {
            @Override
            public void onResponse(SearchResponse response) {
                onScrollResponse(response);
            }

            @Override
            public void onFailure(Throwable e) {
                finishHim(e);
            }
        });
    }

    private void recordFailure(Failure failure, List<Failure> failures) {
        if (failure.getStatus() == CONFLICT) {
            task.countVersionConflict();
            if (false == mainRequest.isAbortOnVersionConflict()) {
                return;
            }
        }
        failures.add(failure);
    }

    /**
     * Start terminating a request that finished non-catastrophically.
     */
    void startNormalTermination(final List<Failure> indexingFailures, final List<ShardSearchFailure> searchFailures,
            final boolean timedOut) {
        if (task.isCancelled() || false == mainRequest.isRefresh() || destinationIndices.isEmpty()) {
            finishHim(null, indexingFailures, searchFailures, timedOut);
            return;
        }
        RefreshRequest refresh = new RefreshRequest(mainRequest);
        refresh.indices(destinationIndices.toArray(new String[destinationIndices.size()]));
        client.admin().indices().refresh(refresh, new ActionListener<RefreshResponse>() {
            @Override
            public void onResponse(RefreshResponse response) {
                finishHim(null, indexingFailures, searchFailures, timedOut);
            }

            @Override
            public void onFailure(Throwable e) {
                finishHim(e);
            }
        });
    }

    /**
     * Finish the request.
     *
     * @param failure if non null then the request failed catastrophically with this exception
     */
    void finishHim(Throwable failure) {
        finishHim(failure, Collections.<Failure>emptyList(), Collections.<ShardSearchFailure>emptyList(), false);
    }

    /**
     * Finish the request.
     *
     * @param failure if non null then the request failed catastrophically with this exception
     * @param indexingFailures any indexing failures accumulated during the request
     * @param searchFailures any search failures accumulated during the request
     * @param timedOut have any of the sub-requests timed out?
     */
    void finishHim(Throwable failure, List<Failure> indexingFailures, List<ShardSearchFailure> searchFailures, boolean timedOut) {
        final String scrollId = scroll.get();
        if (Strings.hasLength(scrollId)) {
            /*
             * Fire off the clear scroll but don't wait for it it return before
             * we send the use their response.
             */
            ClearScrollRequest clearScrollRequest = new ClearScrollRequest(mainRequest);
            clearScrollRequest.addScrollId(scrollId);
            client.clearScroll(clearScrollRequest, new ActionListener<ClearScrollResponse>() {
                @Override
                public void onResponse(ClearScrollResponse response) {
                    logger.debug("Freed [{}] contexts", response.getNumFreed());
                }

                @Override
                public void onFailure(Throwable e) {
                    logger.warn("Failed to clear scroll [" + scrollId + ']', e);
                }
            });
        }
        if (failure == null) {
            listener.onResponse(
                    buildResponse(timeValueNanos(System.nanoTime() - startTime.get()), indexingFailures, searchFailures, timedOut));
        } else {
            listener.onFailure(failure);
        }
    }

    /**
     * Build the backoff policy for use with retries. Package private for testing.
     */
    BackoffPolicy backoffPolicy() {
        return exponentialBackoff(mainRequest.getRetryBackoffInitialTime(), mainRequest.getMaxRetries());
    }

    /**
     * Add to the list of indices that were modified by this request. This is the list of indices refreshed at the end of the request if the
     * request asks for a refresh.
     */
    void addDestinationIndices(Collection<String> indices) {
        destinationIndices.addAll(indices);
    }

    /**
     * Wraps a backoffPolicy in another policy that counts the number of backoffs acquired.
     */
    private BackoffPolicy wrapBackoffPolicy(final BackoffPolicy backoffPolicy) {
        return new BackoffPolicy() {
            @Override
            public Iterator<TimeValue> iterator() {
                return new Iterator<TimeValue>() {
                    private final Iterator<TimeValue> delegate = backoffPolicy.iterator();
                    @Override
                    public boolean hasNext() {
                        return delegate.hasNext();
                    }

                    @Override
                    public TimeValue next() {
                        if (false == delegate.hasNext()) {
                            return null;
                        }
                        task.countRetry();
                        return delegate.next();
                    }

                    @Override
                    public void remove() {
                        throw new UnsupportedOperationException();
                    }
                };
            }
        };
    }
}
