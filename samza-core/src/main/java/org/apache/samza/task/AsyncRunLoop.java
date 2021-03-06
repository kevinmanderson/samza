/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.samza.task;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.samza.SamzaException;
import org.apache.samza.container.SamzaContainerMetrics;
import org.apache.samza.container.TaskInstance;
import org.apache.samza.container.TaskInstanceMetrics;
import org.apache.samza.container.TaskName;
import org.apache.samza.system.IncomingMessageEnvelope;
import org.apache.samza.system.SystemConsumers;
import org.apache.samza.system.SystemStreamPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.JavaConversions;


/**
 * The AsyncRunLoop supports multithreading execution of Samza {@link AsyncStreamTask}s.
 */
public class AsyncRunLoop implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(AsyncRunLoop.class);

  private final Map<TaskName, AsyncTaskWorker> taskWorkers;
  private final SystemConsumers consumerMultiplexer;
  private final Map<SystemStreamPartition, List<AsyncTaskWorker>> sspToTaskWorkerMapping;
  private final ExecutorService threadPool;
  private final CoordinatorRequests coordinatorRequests;
  private final Object latch;
  private final int maxConcurrency;
  private final long windowMs;
  private final long commitMs;
  private final long callbackTimeoutMs;
  private final SamzaContainerMetrics containerMetrics;
  private final ScheduledExecutorService workerTimer;
  private final ScheduledExecutorService callbackTimer;
  private volatile boolean shutdownNow = false;
  private volatile Throwable throwable = null;

  public AsyncRunLoop(Map<TaskName, TaskInstance<AsyncStreamTask>> taskInstances,
      ExecutorService threadPool,
      SystemConsumers consumerMultiplexer,
      int maxConcurrency,
      long windowMs,
      long commitMs,
      long callbackTimeoutMs,
      SamzaContainerMetrics containerMetrics) {

    this.threadPool = threadPool;
    this.consumerMultiplexer = consumerMultiplexer;
    this.containerMetrics = containerMetrics;
    this.windowMs = windowMs;
    this.commitMs = commitMs;
    this.maxConcurrency = maxConcurrency;
    this.callbackTimeoutMs = callbackTimeoutMs;
    this.callbackTimer = (callbackTimeoutMs > 0) ? Executors.newSingleThreadScheduledExecutor() : null;
    this.coordinatorRequests = new CoordinatorRequests(taskInstances.keySet());
    this.latch = new Object();
    this.workerTimer = Executors.newSingleThreadScheduledExecutor();
    Map<TaskName, AsyncTaskWorker> workers = new HashMap<>();
    for (TaskInstance<AsyncStreamTask> task : taskInstances.values()) {
      workers.put(task.taskName(), new AsyncTaskWorker(task));
    }
    // Partions and tasks assigned to the container will not change during the run loop life time
    this.taskWorkers = Collections.unmodifiableMap(workers);
    this.sspToTaskWorkerMapping = Collections.unmodifiableMap(getSspToAsyncTaskWorkerMap(taskInstances, taskWorkers));
  }

  /**
   * Returns mapping of the SystemStreamPartition to the AsyncTaskWorkers to efficiently route the envelopes
   */
  private static Map<SystemStreamPartition, List<AsyncTaskWorker>> getSspToAsyncTaskWorkerMap(
      Map<TaskName, TaskInstance<AsyncStreamTask>> taskInstances, Map<TaskName, AsyncTaskWorker> taskWorkers) {
    Map<SystemStreamPartition, List<AsyncTaskWorker>> sspToWorkerMap = new HashMap<>();
    for (TaskInstance<AsyncStreamTask> task : taskInstances.values()) {
      Set<SystemStreamPartition> ssps = JavaConversions.asJavaSet(task.systemStreamPartitions());
      for (SystemStreamPartition ssp : ssps) {
        if (sspToWorkerMap.get(ssp) == null) {
          sspToWorkerMap.put(ssp, new ArrayList<AsyncTaskWorker>());
        }
        sspToWorkerMap.get(ssp).add(taskWorkers.get(task.taskName()));
      }
    }
    return sspToWorkerMap;
  }

  /**
   * The run loop chooses messages from the SystemConsumers, and run the ready tasks asynchronously.
   * Window and commit are run in a thread pool, and they are mutual exclusive with task process.
   * The loop thread will block if all tasks are busy, and resume if any task finishes.
   */
  @Override
  public void run() {
    try {
      for (AsyncTaskWorker taskWorker : taskWorkers.values()) {
        taskWorker.init();
      }

      long prevNs = System.nanoTime();

      while (!shutdownNow) {
        if (throwable != null) {
          log.error("Caught throwable and stopping run loop", throwable);
          throw new SamzaException(throwable);
        }

        long startNs = System.nanoTime();

        IncomingMessageEnvelope envelope = chooseEnvelope();

        long chooseNs = System.nanoTime();

        containerMetrics.chooseNs().update(chooseNs - startNs);

        runTasks(envelope);

        long blockNs = System.nanoTime();

        blockIfBusy(envelope);

        long currentNs = System.nanoTime();
        long activeNs = blockNs - chooseNs;
        long totalNs = currentNs - prevNs;
        prevNs = currentNs;
        containerMetrics.blockNs().update(currentNs - blockNs);
        containerMetrics.utilization().set(((double) activeNs) / totalNs);
      }
    } finally {
      workerTimer.shutdown();
      if (callbackTimer != null) callbackTimer.shutdown();
    }
  }

  public void shutdown() {
    shutdownNow = true;
  }

  /**
   * Chooses an envelope from messageChooser without updating it. This enables flow control
   * on the SSP level, meaning the task will not get further messages for the SSP if it cannot
   * process it. The chooser is updated only after the callback to process is invoked, then the task
   * is able to process more messages. This flow control does not block. so in case of empty message chooser,
   * it will return null immediately without blocking, and the chooser will not poll the underlying system
   * consumer since there are still messages in the SystemConsumers buffer.
   */
  private IncomingMessageEnvelope chooseEnvelope() {
    IncomingMessageEnvelope envelope = consumerMultiplexer.choose(false);
    if (envelope != null) {
      log.trace("Choose envelope ssp {} offset {} for processing", envelope.getSystemStreamPartition(), envelope.getOffset());
      containerMetrics.envelopes().inc();
    } else {
      log.trace("No envelope is available");
      containerMetrics.nullEnvelopes().inc();
    }
    return envelope;
  }

  /**
   * Insert the envelope into the task pending queues and run all the tasks
   */
  private void runTasks(IncomingMessageEnvelope envelope) {
    if (envelope != null) {
      PendingEnvelope pendingEnvelope = new PendingEnvelope(envelope);
      for (AsyncTaskWorker worker : sspToTaskWorkerMapping.get(envelope.getSystemStreamPartition())) {
        worker.state.insertEnvelope(pendingEnvelope);
      }
    }

    for (AsyncTaskWorker worker: taskWorkers.values()) {
      worker.run();
    }
  }

  /**
   * Block the runloop thread if all tasks are busy. Due to limitation of non-blocking for the flow control,
   * we block the run loop when there are no runnable tasks, or all tasks are idle (no pending messages) while
   * chooser is empty too. When a task worker finishes or window/commit completes, it will resume the runloop.
   */
  private void blockIfBusy(IncomingMessageEnvelope envelope) {
    synchronized (latch) {
      while (!shutdownNow && throwable == null) {
        for (AsyncTaskWorker worker : taskWorkers.values()) {
          if (worker.state.isReady() && (envelope != null || worker.state.hasPendingOps())) {
            // should continue running since the worker state is ready and there is either new message
            // or some pending operations for the worker
            return;
          }
        }

        try {
          log.trace("Block loop thread");

          if (envelope == null) {
            // If the envelope is null then we will wait for a poll interval, otherwise next choose() will
            // return null immediately and we will have a busy loop
            latch.wait(consumerMultiplexer.pollIntervalMs());
            return;
          } else {
            latch.wait();
          }
        } catch (InterruptedException e) {
          throw new SamzaException("Run loop is interrupted", e);
        }
      }
    }
  }

  /**
   * Resume the runloop thread. It is triggered once a task becomes ready again or has failure.
   */
  private void resume() {
    log.trace("Resume loop thread");
    if (coordinatorRequests.shouldShutdownNow() && coordinatorRequests.commitRequests().isEmpty()) {
      shutdownNow = true;
    }
    synchronized (latch) {
      latch.notifyAll();
    }
  }

  /**
   * Set the throwable and abort run loop. The throwable will be thrown from the run loop thread
   * @param t throwable
   */
  private void abort(Throwable t) {
    throwable = t;
  }

  /**
   * PendingEnvenlope contains an envelope that is not processed by this task, and
   * a flag indicating whether it has been processed by any tasks.
   */
  private static final class PendingEnvelope {
    private final IncomingMessageEnvelope envelope;
    private boolean processed = false;

    PendingEnvelope(IncomingMessageEnvelope envelope) {
      this.envelope = envelope;
    }

    /**
     * Returns true if the envelope has not been processed.
     */
    private boolean markProcessed() {
      boolean oldValue = processed;
      processed = true;
      return !oldValue;
    }
  }


  private enum WorkerOp {
    WINDOW,
    COMMIT,
    PROCESS,
    NO_OP
  }

  /**
   * The AsyncTaskWorker encapsulates the states of an {@link AsyncStreamTask}. If the task becomes ready, it
   * will run the task asynchronously. It runs window and commit in the provided thread pool.
   */
  private class AsyncTaskWorker implements TaskCallbackListener {
    private final TaskInstance<AsyncStreamTask> task;
    private final TaskCallbackManager callbackManager;
    private volatile AsyncTaskState state;

    AsyncTaskWorker(TaskInstance<AsyncStreamTask> task) {
      this.task = task;
      this.callbackManager = new TaskCallbackManager(this, task.metrics(), callbackTimer, callbackTimeoutMs);
      this.state = new AsyncTaskState(task.taskName(), task.metrics());
    }

    private void init() {
      // schedule the timer for windowing and commiting
      if (task.isWindowableTask() && windowMs > 0L) {
        workerTimer.scheduleAtFixedRate(new Runnable() {
          @Override
          public void run() {
            log.trace("Task {} need window", task.taskName());
            state.needWindow();
            resume();
          }
        }, windowMs, windowMs, TimeUnit.MILLISECONDS);
      }

      if (commitMs > 0L) {
        workerTimer.scheduleAtFixedRate(new Runnable() {
          @Override
          public void run() {
            log.trace("Task {} need commit", task.taskName());
            state.needCommit();
            resume();
          }
        }, commitMs, commitMs, TimeUnit.MILLISECONDS);
      }
    }

    /**
     * Invoke next task operation based on its state
     */
    private void run() {
      switch (state.nextOp()) {
        case PROCESS:
          process();
          break;
        case WINDOW:
          window();
          break;
        case COMMIT:
          commit();
          break;
        default:
          //no op
          break;
      }
    }

    /**
     * Process asynchronously. The callback needs to be fired once the processing is done.
     */
    private void process() {
      final IncomingMessageEnvelope envelope = state.fetchEnvelope();
      log.trace("Process ssp {} offset {}", envelope.getSystemStreamPartition(), envelope.getOffset());

      final ReadableCoordinator coordinator = new ReadableCoordinator(task.taskName());
      TaskCallbackFactory callbackFactory = new TaskCallbackFactory() {
        @Override
        public TaskCallback createCallback() {
          state.startProcess();
          containerMetrics.processes().inc();
          return callbackManager.createCallback(task.taskName(), envelope, coordinator);
        }
      };

      task.process(envelope, coordinator, callbackFactory);
    }

    /**
     * Invoke window. Run window in thread pool if not the single thread mode.
     */
    private void window() {
      state.startWindow();
      Runnable windowWorker = new Runnable() {
        @Override
        public void run() {
          try {
            containerMetrics.windows().inc();

            ReadableCoordinator coordinator = new ReadableCoordinator(task.taskName());
            long startTime = System.nanoTime();
            task.window(coordinator);
            containerMetrics.windowNs().update(System.nanoTime() - startTime);
            coordinatorRequests.update(coordinator);

            state.doneWindowOrCommit();
          } catch (Throwable t) {
            log.error("Task {} window failed", task.taskName(), t);
            abort(t);
          } finally {
            log.trace("Task {} window completed", task.taskName());
            resume();
          }
        }
      };

      if (threadPool != null) {
        log.trace("Task {} window on the thread pool", task.taskName());
        threadPool.submit(windowWorker);
      } else {
        log.trace("Task {} window on the run loop thread", task.taskName());
        windowWorker.run();
      }
    }

    /**
     * Invoke commit. Run commit in thread pool if not the single thread mode.
     */
    private void commit() {
      state.startCommit();
      Runnable commitWorker = new Runnable() {
        @Override
        public void run() {
          try {
            containerMetrics.commits().inc();

            long startTime = System.nanoTime();
            task.commit();
            containerMetrics.commitNs().update(System.nanoTime() - startTime);

            state.doneWindowOrCommit();
          } catch (Throwable t) {
            log.error("Task {} commit failed", task.taskName(), t);
            abort(t);
          } finally {
            log.trace("Task {} commit completed", task.taskName());
            resume();
          }
        }
      };

      if (threadPool != null) {
        log.trace("Task {} commits on the thread pool", task.taskName());
        threadPool.submit(commitWorker);
      } else {
        log.trace("Task {} commits on the run loop thread", task.taskName());
        commitWorker.run();
      }
    }



    /**
     * Task process completes successfully, update the offsets based on the high-water mark.
     * Then it will trigger the listener for task state change.
     * * @param callback AsyncSteamTask.processAsync callback
     */
    @Override
    public void onComplete(TaskCallback callback) {
      try {
        state.doneProcess();
        TaskCallbackImpl callbackImpl = (TaskCallbackImpl) callback;
        containerMetrics.processNs().update(System.nanoTime() - callbackImpl.timeCreatedNs);
        log.trace("Got callback complete for task {}, ssp {}", callbackImpl.taskName, callbackImpl.envelope.getSystemStreamPartition());

        TaskCallbackImpl callbackToUpdate = callbackManager.updateCallback(callbackImpl, true);
        if (callbackToUpdate != null) {
          IncomingMessageEnvelope envelope = callbackToUpdate.envelope;
          log.trace("Update offset for ssp {}, offset {}", envelope.getSystemStreamPartition(), envelope.getOffset());

          // update offset
          task.offsetManager().update(task.taskName(), envelope.getSystemStreamPartition(), envelope.getOffset());

          // update coordinator
          coordinatorRequests.update(callbackToUpdate.coordinator);
        }
      } catch (Throwable t) {
        log.error(t.getMessage(), t);
        abort(t);
      } finally {
        resume();
      }
    }

    /**
     * Task process fails. Trigger the listener indicating failure.
     * @param callback AsyncSteamTask.processAsync callback
     * @param t throwable of the failure
     */
    @Override
    public void onFailure(TaskCallback callback, Throwable t) {
      try {
        state.doneProcess();
        abort(t);
        // update pending count, but not offset
        TaskCallbackImpl callbackImpl = (TaskCallbackImpl) callback;
        callbackManager.updateCallback(callbackImpl, false);
        log.error("Got callback failure for task {}", callbackImpl.taskName);
      } catch (Throwable e) {
        log.error(e.getMessage(), e);
      } finally {
        resume();
      }
    }
  }


  /**
   * AsyncTaskState manages the state of the AsyncStreamTask. In summary, a worker has the following states:
   * ready - ready for window, commit or process next incoming message.
   * busy - doing window, commit or not able to process next message.
   * idle - no pending messages, and no window/commit
   */
  private final class AsyncTaskState {
    private volatile boolean needWindow = false;
    private volatile boolean needCommit = false;
    private volatile boolean windowOrCommitInFlight = false;
    private final AtomicInteger messagesInFlight = new AtomicInteger(0);
    private final ArrayDeque<PendingEnvelope> pendingEnvelopQueue;
    private final TaskName taskName;
    private final TaskInstanceMetrics taskMetrics;

    AsyncTaskState(TaskName taskName, TaskInstanceMetrics taskMetrics) {
      this.taskName = taskName;
      this.taskMetrics = taskMetrics;
      this.pendingEnvelopQueue = new ArrayDeque<>();
    }

    /**
     * Returns whether the task is ready to do process/window/commit.
     */
    private boolean isReady() {
      needCommit |= coordinatorRequests.commitRequests().remove(taskName);
      if (needWindow || needCommit) {
        // ready for window or commit only when no messages are in progress and
        // no window/commit in flight
        return messagesInFlight.get() == 0 && !windowOrCommitInFlight;
      } else {
        // ready for process only when the inflight message count does not exceed threshold
        // and no window/commit in flight
        return messagesInFlight.get() < maxConcurrency && !windowOrCommitInFlight;
      }
    }

    private boolean hasPendingOps() {
      return !pendingEnvelopQueue.isEmpty() || needCommit || needWindow;
    }

    /**
     * Returns the next operation by this taskWorker
     */
    private WorkerOp nextOp() {
      if (isReady()) {
        if (needCommit) return WorkerOp.COMMIT;
        else if (needWindow) return WorkerOp.WINDOW;
        else if (!pendingEnvelopQueue.isEmpty()) return WorkerOp.PROCESS;
      }
      return WorkerOp.NO_OP;
    }

    private void needWindow() {
      needWindow = true;
    }

    private void needCommit() {
      needCommit = true;
    }

    private void startWindow() {
      needWindow = false;
      windowOrCommitInFlight = true;
    }

    private void startCommit() {
      needCommit = false;
      windowOrCommitInFlight = true;
    }

    private void startProcess() {
      messagesInFlight.incrementAndGet();
    }

    private void doneWindowOrCommit() {
      windowOrCommitInFlight = false;
    }

    private void doneProcess() {
      messagesInFlight.decrementAndGet();
    }

    /**
     * Insert an PendingEnvelope into the pending envelope queue.
     * The function will be called in the run loop thread so no synchronization.
     * @param pendingEnvelope
     */
    private void insertEnvelope(PendingEnvelope pendingEnvelope) {
      pendingEnvelopQueue.add(pendingEnvelope);
      int queueSize = pendingEnvelopQueue.size();
      taskMetrics.pendingMessages().set(queueSize);
      log.trace("Insert envelope to task {} queue.", taskName);
      log.debug("Task {} pending envelope count is {} after insertion.", taskName, queueSize);
    }

    /**
     * Fetch the pending envelope in the pending queue for the task to process.
     * Update the chooser for flow control on the SSP level. Once it's updated, the AsyncRunLoop
     * will be able to choose new messages from this SSP for the task to process. Note that we
     * update only when the envelope is first time being processed. This solves the issue in
     * Broadcast stream where a message need to be processed by multiple tasks. In that case,
     * the envelope will be in the pendingEnvelopeQueue of each task. Only the first fetch updates
     * the chooser with the next envelope in the broadcast stream partition.
     * The function will be called in the run loop thread so no synchronization.
     * @return
     */
    private IncomingMessageEnvelope fetchEnvelope() {
      PendingEnvelope pendingEnvelope = pendingEnvelopQueue.remove();
      int queueSize = pendingEnvelopQueue.size();
      taskMetrics.pendingMessages().set(queueSize);
      log.trace("fetch envelope ssp {} offset {} to process.", pendingEnvelope.envelope.getSystemStreamPartition(), pendingEnvelope.envelope.getOffset());
      log.debug("Task {} pending envelopes count is {} after fetching.", taskName, queueSize);

      if (pendingEnvelope.markProcessed()) {
        SystemStreamPartition partition = pendingEnvelope.envelope.getSystemStreamPartition();
        consumerMultiplexer.tryUpdate(partition);
        log.debug("Update chooser for " + partition);
      }
      return pendingEnvelope.envelope;
    }
  }
}