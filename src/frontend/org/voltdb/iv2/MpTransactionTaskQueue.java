/* This file is part of VoltDB.
 * Copyright (C) 2008-2017 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.iv2;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.voltcore.logging.VoltLogger;
import org.voltcore.utils.CoreUtils;
import org.voltdb.CatalogContext;
import org.voltdb.exceptions.TransactionRestartException;
import org.voltdb.messaging.FragmentResponseMessage;
import org.voltdb.messaging.FragmentTaskMessage;

/**
 * Provide an implementation of the TransactionTaskQueue specifically for the MPI.
 * This class will manage separating the stream of reads and writes to different
 * Sites and block appropriately so that reads and writes never execute concurrently.
 */
public class MpTransactionTaskQueue extends TransactionTaskQueue
{
    protected static final VoltLogger tmLog = new VoltLogger("MpTxnTskQ");
    protected static final VoltLogger npLog = new VoltLogger("MpTxnTskQnp");

    // Track the current writes and reads in progress.  If writes contains anything, reads must be empty,
    // and vice versa
    private final Map<Long, TransactionTask> m_currentMpWrites = new HashMap<Long, TransactionTask>();
    private final Map<Long, TransactionTask> m_currentMpReads = new HashMap<Long, TransactionTask>();

    private Deque<TransactionTask> m_backlog = new ArrayDeque<>(128);
    private Deque<TransactionTask> m_priorityBacklog = new ArrayDeque<>(128);

    private MpRoSitePool m_sitePool = null;
    private NpSitePool m_npSitePool = null;

    private final Map<Long, List<Integer>> m_npTxnIdToPartitions = new HashMap<>();

    private final Map<Integer, Map<Long, TransactionTask>> m_currentNpTxnsByPartition = new HashMap<>();
    private final int MAX_TASK_DEPTH = 20;

    MpTransactionTaskQueue(SiteTaskerQueue queue)
    {
        super(queue);
    }

    void setMpRoSitePool(MpRoSitePool sitePool)
    {
        m_sitePool = sitePool;
    }

    void setNpSitePool(NpSitePool sitePool)
    {
        m_npSitePool = sitePool;
    }

    synchronized void updateCatalog(String diffCmds, CatalogContext context)
    {
        m_sitePool.updateCatalog(diffCmds, context);
        m_npSitePool.updateCatalog(diffCmds, context);
    }

    synchronized void updateSettings(CatalogContext context)
    {
        m_sitePool.updateSettings(context);
        m_npSitePool.updateSettings(context);
    }

    void shutdown()
    {
        if (m_sitePool != null) {
            m_sitePool.shutdown();
        }
        if (m_npSitePool != null) {
            m_npSitePool.shutdown();
        }
    }

    /**
     * Stick this task in the backlog.
     * Many network threads may be racing to reach here, synchronize to
     * serialize queue order.
     * Always returns true in this case, side effect of extending
     * TransactionTaskQueue.
     */
    @Override
    synchronized boolean offer(TransactionTask task)
    {
        Iv2Trace.logTransactionTaskQueueOffer(task);
        m_backlog.addLast(task);
        taskQueueOffer(false);
        return true;
    }

    // repair is used by MPI repair to inject a repair task into the
    // SiteTaskerQueue.  Before it does this, it unblocks the MP transaction
    // that may be running in the Site thread and causes it to rollback by
    // faking an unsuccessful FragmentResponseMessage.
    synchronized void repair(SiteTasker task, List<Long> masters, Map<Integer, Long> partitionMasters, boolean balanceSPI)
    {
        // We know that every Site assigned to the MPI (either the main writer or
        // any of the MP read pool) will only have one active transaction at a time,
        // and that we either have active reads or active writes, but never both.
        // Figure out which we're doing, and then poison all of the appropriate sites.
        Map<Long, TransactionTask> currentSet;
        boolean readonly = true;
        if (!m_currentMpReads.isEmpty()) {
            assert(m_currentMpWrites.isEmpty());
            if (tmLog.isDebugEnabled()) {
                tmLog.debug("MpTTQ: repairing reads. MigratePartitionLeader:" + balanceSPI);
            }
            for (Long txnId : m_currentMpReads.keySet()) {
                m_sitePool.repair(txnId, task);
            }
            currentSet = m_currentMpReads;
        }
        else {
            if (tmLog.isDebugEnabled()) {
                tmLog.debug("MpTTQ: repairing writes. MigratePartitionLeader:" + balanceSPI);
            }
            m_taskQueue.offer(task);
            currentSet = m_currentMpWrites;
            readonly = false;
        }
        for (Entry<Long, TransactionTask> e : currentSet.entrySet()) {
            if (e.getValue() instanceof MpProcedureTask) {
                MpProcedureTask next = (MpProcedureTask)e.getValue();
                if (tmLog.isDebugEnabled()) {
                    tmLog.debug("MpTTQ: poisoning task: " + next);
                }
                next.doRestart(masters, partitionMasters);

                if (!balanceSPI || readonly) {
                    MpTransactionState txn = (MpTransactionState)next.getTransactionState();
                    // inject poison pill
                    FragmentTaskMessage dummy = new FragmentTaskMessage(0L, 0L, 0L, 0L, false, false, false);
                    FragmentResponseMessage poison =
                            new FragmentResponseMessage(dummy, 0L); // Don't care about source HSID here
                    // Provide a TransactionRestartException which will be converted
                    // into a ClientResponse.RESTART, so that the MpProcedureTask can
                    // detect the restart and take the appropriate actions.
                    TransactionRestartException restart = new TransactionRestartException(
                            "Transaction being restarted due to fault recovery or shutdown.", next.getTxnId());
                    restart.setMisrouted(false);
                    poison.setStatus(FragmentResponseMessage.UNEXPECTED_ERROR, restart);
                    txn.offerReceivedFragmentResponse(poison);
                    if (tmLog.isDebugEnabled()) {
                        tmLog.debug("MpTTQ: restarting:" + next);
                    }
                }
            }
        }
        // Now, iterate through the backlog and update the partition masters
        // for all ProcedureTasks
        Iterator<TransactionTask> iter = m_backlog.iterator();
        while (iter.hasNext()) {
            TransactionTask tt = iter.next();
            if (tt instanceof MpProcedureTask) {
                MpProcedureTask next = (MpProcedureTask)tt;

                if (tmLog.isDebugEnabled()) {
                    tmLog.debug("Repair updating task: " + next + " with masters: " + CoreUtils.hsIdCollectionToString(masters));
                }
                next.updateMasters(masters, partitionMasters);
            }
            else if (tt instanceof EveryPartitionTask) {
                EveryPartitionTask next = (EveryPartitionTask)tt;
                if (tmLog.isDebugEnabled())  {
                    tmLog.debug("Repair updating EPT task: " + next + " with masters: " + CoreUtils.hsIdCollectionToString(masters));
                }
                next.updateMasters(masters);
            }
        }
    }

    private void taskQueueOffer(TransactionTask task)
    {
        Iv2Trace.logSiteTaskerQueueOffer(task);
        if (task instanceof NpProcedureTask) {
            m_npSitePool.doWork(task.getTxnId(), task);
        }
        else if (!task.getTransactionState().isReadOnly()) {
            m_taskQueue.offer(task);
        }
        else {
            m_sitePool.doWork(task.getTxnId(), task);
        }
    }

    private boolean allowToRun(MpTransactionState state, boolean isNpTxn) {
        if (!m_currentMpWrites.isEmpty()) {
            return false;
        }

        if (isNpTxn) {
            Set<Integer> partitions = state.getInvolvedPartitionIds();
            for (Integer pid: partitions) {
                Map<Long, TransactionTask> partitionMap = m_currentNpTxnsByPartition.get(pid);
                if (partitionMap != null && !partitionMap.isEmpty()) {
                    npLog.trace(TxnEgo.txnIdToString(state.txnId) + " not able to run on partitions: "
                            + Arrays.toString(partitions.toArray()));

                    return false;
                }
            }
            return true;
        }
        // for MP reads or writes task
        if (!m_npTxnIdToPartitions.isEmpty()) {
            return false;
        }
        return true;
    }

    private TransactionTask pollFirstTask(boolean isPriorityTask) {
        if (isPriorityTask) {
            return m_priorityBacklog.pollFirst();
        } else {
            return m_backlog.pollFirst();
        }
    }

    private boolean taskQueueOfferInternal(TransactionTask task, boolean isPriorityTask) {
        boolean isReadOnly = task.getTransactionState().isReadOnly();
        boolean isNpTxn = task instanceof NpProcedureTask;
        MpTransactionState state = ((MpTransactionState) task.getTransactionState());

        // read only task optimization for MP reads currently
        // no 2p read only pool yet for further optimization
        if (! allowToRun(state, isNpTxn)) {
            return false;
        }

        // Np task or MP write task
        if (isNpTxn || !isReadOnly) {
            if (!m_currentMpReads.isEmpty()) {
                // there are mp reads not drained yet, can not run any write
                return false;
            }
            if (isNpTxn) {
                if (!m_npSitePool.canAcceptWork()) {
                    return false;
                }
                Set<Integer> partitions = state.getInvolvedPartitionIds();
                for (Integer pid: partitions) {
                    Map<Long, TransactionTask> txnsMap = m_currentNpTxnsByPartition.get(pid);
                    if (txnsMap == null) {
                        txnsMap = new HashMap<Long, TransactionTask>();
                    }
                    txnsMap.put(task.getTxnId(), task);
                    m_currentNpTxnsByPartition.put(pid, txnsMap);
                }
                m_npTxnIdToPartitions.put(task.getTxnId(), new ArrayList<>(partitions));
            } else {
                m_currentMpWrites.put(task.getTxnId(), task);
            }

            task = pollFirstTask(isPriorityTask);
            taskQueueOffer(task);
            return true;
        }

        assert(isReadOnly);
        // handle read only tasks
        if (!m_sitePool.canAcceptWork()) {
            return false;
        }
        task = pollFirstTask(isPriorityTask);
        m_currentMpReads.put(task.getTxnId(), task);
        taskQueueOffer(task);
        return true;
    }

    /**
     *
     * @return how many tasks get offered
     */
    private int taskQueueOffer(boolean isFlush)
    {
//        npLog.trace("[taskQueueOffer]: \n" + this.toString());
        int tasksTaken = 0;
        if (m_priorityBacklog.isEmpty() && m_backlog.isEmpty()) {
            return tasksTaken;
        }
        TransactionTask task;
        // check priority backlog first
        int counts = m_priorityBacklog.size();
        for (int i = 0; i < counts && !m_priorityBacklog.isEmpty(); i++) {
            if (!m_currentMpWrites.isEmpty()) {
                return tasksTaken;
            }

            task = m_priorityBacklog.peekFirst();
            if (taskQueueOfferInternal(task, true)) {
                tasksTaken++;
//                npLog.trace("task " + TxnEgo.txnIdToString(task.getTxnId()) + " taken from priority queue");
                if (isFlush) {
                    // early return, do not be aggressive to schedule tasks
                    return tasksTaken;
                }
                continue;
            }
//            npLog.trace("task " + TxnEgo.txnIdToString(task.getTxnId()) + " NOT taken from priority queue, queue it back to normal");
            m_priorityBacklog.pollFirst();
            m_backlog.addLast(task);
        }

        // start to process MAX_TASK_DEPTH tasks from normal backlog, stop when hitting MP task
        for (int i = 0; !m_backlog.isEmpty() && i < MAX_TASK_DEPTH; i++) {
            // early return for any schedule work if MP writes are not empty
            if (!m_currentMpWrites.isEmpty()) {
                return tasksTaken;
            }

            // We may not queue the next task, just peek to get the read-only state
            task = m_backlog.peekFirst();

            if (taskQueueOfferInternal(task, false)) {
                tasksTaken++;
//                npLog.trace("task " + TxnEgo.txnIdToString(task.getTxnId()) + " taken from normal queue");
                if (isFlush) {
                    // early return, do not be aggressive to schedule tasks
                    return tasksTaken;
                }
            } else {
//                npLog.trace("task " + TxnEgo.txnIdToString(task.getTxnId()) +
//                        " NOT taken from normal queue, queue it to priority");
                task = m_backlog.pollFirst();
                m_priorityBacklog.add(task);
            }
        }
        return tasksTaken;
    }

    /**
     * Indicate that the transaction associated with txnId is complete.  Perform
     * management of reads/writes in progress then call taskQueueOffer() to
     * submit additional tasks to be done, determined by whatever the current state is.
     * See giant comment at top of taskQueueOffer() for what happens.
     */
    @Override
    synchronized int flush(long txnId)
    {
        int offered = 0;
        if (m_currentMpReads.containsKey(txnId)) {
            m_currentMpReads.remove(txnId);
            m_sitePool.completeWork(txnId);
        }
        else if (m_currentMpWrites.containsKey(txnId)) {
            m_currentMpWrites.remove(txnId);
            assert(m_currentMpWrites.isEmpty());
        } else {
            assert(m_npTxnIdToPartitions.containsKey(txnId));
            List<Integer> partitions = m_npTxnIdToPartitions.get(txnId);
            for (Integer pid: partitions) {
                Map<Long, TransactionTask> txnsMap = m_currentNpTxnsByPartition.get(pid);
                txnsMap.remove(txnId);
            }
            m_npTxnIdToPartitions.remove(txnId);
            m_npSitePool.completeWork(txnId);
        }
        offered += taskQueueOffer(true);
        return offered;
    }

    /**
     * Restart the current task at the head of the queue.  This will be called
     * instead of flush by the currently blocking MP transaction in the event a
     * restart is necessary.
     */
    @Override
    synchronized void restart()
    {
        if (!m_currentMpReads.isEmpty()) {
            // re-submit all the tasks in the current read set to the pool.
            // the pool will ensure that things submitted with the same
            // txnID will go to the the MpRoSite which is currently running it
            for (TransactionTask task : m_currentMpReads.values()) {
                taskQueueOffer(task);
            }
        }
        else if (!m_currentMpWrites.isEmpty()) {
            TransactionTask task;
            // There currently should only ever be one current write.  This
            // is the awkward way to get a single value out of a Map
            task = m_currentMpWrites.entrySet().iterator().next().getValue();
            taskQueueOffer(task);
        } else {
            // restart Np transactions
            for (Long txnId: m_npTxnIdToPartitions.keySet()) {
                Integer pid = m_npTxnIdToPartitions.get(txnId).get(0);

                TransactionTask task = m_currentNpTxnsByPartition.get(pid).get(txnId);
                taskQueueOffer(task);
            }
        }
    }

    /**
     * How many Tasks are un-runnable?
     * @return
     */
    @Override
    synchronized int size()
    {
        return m_backlog.size();
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("MpTransactionTaskQueue:").append("\n");
        sb.append("\tcurrent mp reads size: " + m_currentMpReads.size()).append("\n");
        sb.append("\tcurrent mp writes size: ").append(m_currentMpWrites.size()).append("\n");
        sb.append("\tcurrent np transaction size: ").append(m_npTxnIdToPartitions.size()).append("\n");
        if (!m_npTxnIdToPartitions.isEmpty()) {
            for (Long txnId: m_npTxnIdToPartitions.keySet()) {
                sb.append("\t\tnp txn ").append(TxnEgo.txnIdToString(txnId)).append(" -> ");
                m_npTxnIdToPartitions.get(txnId).forEach(item -> sb.append(item).append(" "));
            }
            sb.append("\n");
            for (Integer pid: m_currentNpTxnsByPartition.keySet()) {
                sb.append("\t\tPartition ").append(pid).append(" -> ");
                m_currentNpTxnsByPartition.get(pid).keySet().forEach(
                        txnId -> sb.append(TxnEgo.txnIdToString(txnId)).append(" "));
            }
            sb.append("\n");
        }
        sb.append("\tpriority backlog size: ").append(m_priorityBacklog.size()).append(", ");
        if (!m_priorityBacklog.isEmpty()) {
            sb.append("Priority queue HEAD: ").append(TxnEgo.txnIdToString(m_priorityBacklog.getFirst().getTxnId()));
        }
        sb.append("\tnormal backlog size: ").append(m_backlog.size()).append(", ");
        if (!m_backlog.isEmpty()) {
            sb.append("backlog queue HEAD: ").append(TxnEgo.txnIdToString(m_backlog.getFirst().getTxnId())).append("\n");
        }

        return sb.toString();
    }
}
