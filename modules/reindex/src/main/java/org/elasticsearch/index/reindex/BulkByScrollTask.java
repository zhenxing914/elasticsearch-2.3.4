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

import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.tasks.CancellableTask;
import org.elasticsearch.tasks.Task;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Task storing information about a currently running BulkByScroll request.
 */
public class BulkByScrollTask extends CancellableTask {
    /**
     * The total number of documents this request will process. 0 means we don't yet know or, possibly, there are actually 0 documents
     * to process. Its ok that these have the same meaning because any request with 0 actual documents should be quite short lived.
     */
    private final AtomicLong total = new AtomicLong(0);
    private final AtomicLong updated = new AtomicLong(0);
    private final AtomicLong created = new AtomicLong(0);
    private final AtomicLong deleted = new AtomicLong(0);
    private final AtomicLong noops = new AtomicLong(0);
    private final AtomicInteger batch = new AtomicInteger(0);
    private final AtomicLong versionConflicts = new AtomicLong(0);
    private final AtomicLong retries = new AtomicLong(0);

    public BulkByScrollTask(long id, String type, String action, String description) {
        super(id, type, action, description);
    }

    @Override
    public Status getStatus() {
        return new Status(total.get(), updated.get(), created.get(), deleted.get(), batch.get(), versionConflicts.get(), noops.get(),
                retries.get(), getReasonCancelled());
    }

    /**
     * Total number of successfully processed documents.
     */
    public long getSuccessfullyProcessed() {
        return updated.get() + created.get() + deleted.get();
    }

    public static class Status implements Task.Status {
        public static final Status PROTOTYPE = new Status(0, 0, 0, 0, 0, 0, 0, 0, null);

        private final long total;
        private final long updated;
        private final long created;
        private final long deleted;
        private final int batches;
        private final long versionConflicts;
        private final long noops;
        private final long retries;
        private final String reasonCancelled;

        public Status(long total, long updated, long created, long deleted, int batches, long versionConflicts, long noops, long retries,
                @Nullable String reasonCancelled) {
            this.total = checkPositive(total, "total");
            this.updated = checkPositive(updated, "updated");
            this.created = checkPositive(created, "created");
            this.deleted = checkPositive(deleted, "deleted");
            this.batches = checkPositive(batches, "batches");
            this.versionConflicts = checkPositive(versionConflicts, "versionConflicts");
            this.noops = checkPositive(noops, "noops");
            this.retries = checkPositive(retries, "retries");
            this.reasonCancelled = reasonCancelled;
        }

        public Status(StreamInput in) throws IOException {
            total = in.readVLong();
            updated = in.readVLong();
            created = in.readVLong();
            deleted = in.readVLong();
            batches = in.readVInt();
            versionConflicts = in.readVLong();
            noops = in.readVLong();
            retries = in.readVLong();
            reasonCancelled = in.readOptionalString();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeVLong(total);
            out.writeVLong(updated);
            out.writeVLong(created);
            out.writeVLong(deleted);
            out.writeVInt(batches);
            out.writeVLong(versionConflicts);
            out.writeVLong(noops);
            out.writeVLong(retries);
            out.writeOptionalString(reasonCancelled);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            innerXContent(builder, params, true, true);
            return builder.endObject();
        }

        public XContentBuilder innerXContent(XContentBuilder builder, Params params, boolean includeCreated, boolean includeDeleted)
                throws IOException {
            builder.field("total", total);
            builder.field("updated", updated);
            if (includeCreated) {
                builder.field("created", created);
            }
            if (includeDeleted) {
                builder.field("deleted", deleted);
            }
            builder.field("batches", batches);
            builder.field("version_conflicts", versionConflicts);
            builder.field("noops", noops);
            builder.field("retries", retries);
            if (reasonCancelled != null) {
                builder.field("canceled", reasonCancelled);
            }
            return builder;
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder();
            builder.append("BulkIndexByScrollResponse[");
            innerToString(builder, true, true);
            return builder.append(']').toString();
        }

        public void innerToString(StringBuilder builder, boolean includeCreated, boolean includeDeleted) {
            builder.append("updated=").append(updated);
            if (includeCreated) {
                builder.append(",created=").append(created);
            }
            if (includeDeleted) {
                builder.append(",deleted=").append(deleted);
            }
            builder.append(",batches=").append(batches);
            builder.append(",versionConflicts=").append(versionConflicts);
            builder.append(",noops=").append(noops);
            builder.append(",retries=").append(retries);
            if (reasonCancelled != null) {
                builder.append(",canceled=").append(reasonCancelled);
            }
        }

        @Override
        public String getWriteableName() {
            return "bulk-by-scroll";
        }

        @Override
        public Status readFrom(StreamInput in) throws IOException {
            return new Status(in);
        }

        /**
         * The total number of documents this request will process. 0 means we don't yet know or, possibly, there are actually 0 documents
         * to process. Its ok that these have the same meaning because any request with 0 actual documents should be quite short lived.
         */
        public long getTotal() {
            return total;
        }

        /**
         * Count of documents updated.
         */
        public long getUpdated() {
            return updated;
        }

        /**
         * Count of documents created.
         */
        public long getCreated() {
            return created;
        }

        /**
         * Count of successful delete operations.
         */
        public long getDeleted() {
            return deleted;
        }

        /**
         * Number of scan responses this request has processed.
         */
        public int getBatches() {
            return batches;
        }

        /**
         * Number of version conflicts this request has hit.
         */
        public long getVersionConflicts() {
            return versionConflicts;
        }

        /**
         * Number of noops (skipped bulk items) as part of this request.
         */
        public long getNoops() {
            return noops;
        }

        /**
         * Number of retries that had to be attempted due to rejected executions.
         */
        public long getRetries() {
            return retries;
        }

        /**
         * The reason that the request was canceled or null if it hasn't been.
         */
        public String getReasonCancelled() {
            return reasonCancelled;
        }

        private int checkPositive(int value, String name) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " must be greater than 0 but was [" + value + "]");
            }
            return value;
        }

        private long checkPositive(long value, String name) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " must be greater than 0 but was [" + value + "]");
            }
            return value;
        }
    }

    void setTotal(long totalHits) {
        total.set(totalHits);
    }

    void countBatch() {
        batch.incrementAndGet();
    }

    void countNoop() {
        noops.incrementAndGet();
    }

    void countCreated() {
        created.incrementAndGet();
    }

    void countUpdated() {
        updated.incrementAndGet();
    }

    void countDeleted() {
        deleted.incrementAndGet();
    }

    void countVersionConflict() {
        versionConflicts.incrementAndGet();
    }

    void countRetry() {
        retries.incrementAndGet();
    }
}