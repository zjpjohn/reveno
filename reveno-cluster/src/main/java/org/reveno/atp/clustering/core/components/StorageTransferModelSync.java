/**
 *  Copyright (c) 2015 The original author or authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0

 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.reveno.atp.clustering.core.components;

import org.reveno.atp.clustering.api.ClusterView;
import org.reveno.atp.clustering.api.InetAddress;
import org.reveno.atp.clustering.api.SyncMode;
import org.reveno.atp.clustering.core.RevenoClusterConfiguration;
import org.reveno.atp.clustering.core.api.ClusterExecutor;
import org.reveno.atp.clustering.core.messages.NodeState;
import org.reveno.atp.core.api.channel.Channel;
import org.reveno.atp.core.api.storage.JournalsStorage;

import org.reveno.atp.clustering.core.components.StorageTransferModelSync.TransferContext;
import org.reveno.atp.core.api.storage.SnapshotStorage;
import org.reveno.atp.utils.Exceptions;
import org.reveno.atp.utils.MeasureUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class StorageTransferModelSync implements ClusterExecutor<Boolean, TransferContext> {

    @Override
    public Boolean execute(ClusterView view, TransferContext context) {
        String host = ((InetAddress) context.latestNode.address()).getHost();
        int port = context.latestNode.syncPort;
        SocketAddress sad = new InetSocketAddress(host, port);

        if (context.latestNode.syncMode == SyncMode.JOURNALS.getType()) {
            JournalsStorage.JournalStore tempStore = storage.nextTempStore();
            JournalsStorage.JournalStore store = storage.nextStore(context.latestNode.transactionId);

            if (receiveStore(view, context, sad, TRANSACTIONS, storage.channel(tempStore.getTransactionCommitsAddress())) &&
                    receiveStore(view, context, sad, EVENTS, storage.channel(tempStore.getEventsCommitsAddress()))) {
                storage.mergeStores(new JournalsStorage.JournalStore[]{tempStore}, store);
                return true;
            } else {
                storage.deleteStore(tempStore);
                storage.deleteStore(store);
                return false;
            }
        } else if (context.latestNode.syncMode == SyncMode.SNAPSHOT.getType()) {
            SnapshotStorage.SnapshotStore tempStore = snapshots.nextTempSnapshotStore();
            SnapshotStorage.SnapshotStore snapshotStore = snapshots.nextSnapshotAfter(storage.getLastStoreVersion());

            if (receiveStore(view, context, sad, (byte) 0, snapshots.snapshotChannel(tempStore.getSnapshotPath()))) {
                snapshots.move(tempStore, snapshotStore);
                return true;
            } else {
                snapshots.removeSnapshotStore(tempStore);
                snapshots.removeSnapshotStore(snapshotStore);
            }
        }
        throw new IllegalArgumentException("Unknown transfer mode.");
    }

    protected boolean receiveStore(ClusterView view, TransferContext context, SocketAddress sad, byte type, Channel channel) {
        try {
            SocketChannel sc = SocketChannel.open();
            if (!sc.connect(sad)) {
                LOG.error("STF SYNC: can't establish connection to {}", sad);
                return false;
            }
            sc.configureBlocking(true);

            ByteBuffer message = ByteBuffer.allocate(17);
            message.putLong(view.viewId());
            message.put(type);
            message.putLong(context.transactionId);
            message.flip();
            int size = sc.write(message);
            LOG.debug("STF SYNC: sent {} to StorageTransfer server {}", size, sc);

            ByteBuffer data = ByteBuffer.allocate(MeasureUtils.kb(64));
            int nread = 0;
            while (nread != -1)  {
                try {
                    nread = sc.read(data);
                    data.flip();
                    LOG.debug("STF SYNC: received next {} bytes from {}", data.limit(), sad);
                    channel.write(b -> b.writeFromBuffer(data), true);
                } catch (IOException e) {
                    LOG.error(e.getMessage(), e);
                    throw Exceptions.runtime(e);
                }
                data.clear();
            }
        } catch (Throwable t) {
            LOG.error("STF SYNC: Can't sync with remote node " + sad, t);
            return false;
        } finally {
            LOG.debug("STF SYNC: received latest store from StoreServer.");
            channel.close();
        }
        return true;
    }


    public StorageTransferModelSync(RevenoClusterConfiguration config, JournalsStorage storage,
                                    SnapshotStorage snapshots) {
        this.config = config;
        this.storage = storage;
        this.snapshots = snapshots;
    }

    protected RevenoClusterConfiguration config;
    protected JournalsStorage storage;
    protected SnapshotStorage snapshots;

    protected static final Logger LOG = LoggerFactory.getLogger(StorageTransferModelSync.class);
    public static final byte TRANSACTIONS = 1;
    public static final byte EVENTS = 2;

    public static class TransferContext {
        public final long transactionId;
        public final NodeState latestNode;

        public TransferContext(long transactionId, NodeState latestNode) {
            this.transactionId = transactionId;
            this.latestNode = latestNode;
        }
    }

}
