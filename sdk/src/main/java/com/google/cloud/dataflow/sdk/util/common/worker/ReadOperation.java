/*******************************************************************************
 * Copyright (C) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/

package com.google.cloud.dataflow.sdk.util.common.worker;

import static com.google.cloud.dataflow.sdk.util.common.Counter.AggregationKind.SUM;

import com.google.cloud.dataflow.sdk.util.common.Counter;
import com.google.cloud.dataflow.sdk.util.common.CounterSet;
import com.google.common.base.Preconditions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Observable;
import java.util.Observer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A read operation.
 *
 * Its start() method iterates through all elements of the source
 * and emits them on its output.
 */
public class ReadOperation extends Operation {
  private static final Logger LOG = LoggerFactory.getLogger(ReadOperation.class);
  private static final long DEFAULT_PROGRESS_UPDATE_PERIOD_MS = TimeUnit.SECONDS.toMillis(1);

  /** The Source this operation reads from. */
  public final Source<?> source;

  /** The total byte counter for all data read by this operation. */
  final Counter<Long> byteCount;

  /** StateSampler state for advancing the SourceIterator. */
  private final int readState;

  /**
   * The Source's reader this operation reads from, created by start().
   * Guarded by sourceIteratorLock.
   */
  volatile Source.SourceIterator<?> sourceIterator = null;
  private final Object sourceIteratorLock = new Object();

  /**
   * A cache of sourceIterator.getProgress() updated inside the read loop at a bounded rate.
   * <p>
   * Necessary so that ReadOperation.getProgress() can return immediately, rather than potentially
   * wait for a read to complete (which can take an unbounded time, delay a worker progress update,
   * and cause lease expiration and all sorts of trouble).
   */
  private AtomicReference<Source.Progress> progress = new AtomicReference<>();

  /**
   * On every iteration of the read loop, "progress" is fetched from sourceIterator if requested.
   */
  private long progressUpdatePeriodMs = DEFAULT_PROGRESS_UPDATE_PERIOD_MS;

  /**
   * Signals whether the next iteration of the read loop should update the progress.
   * Set to true every progressUpdatePeriodMs.
   */
  private AtomicBoolean isProgressUpdateRequested = new AtomicBoolean(true);


  public ReadOperation(String operationName, Source<?> source, OutputReceiver[] receivers,
      String counterPrefix, CounterSet.AddCounterMutator addCounterMutator,
      StateSampler stateSampler) {
    super(operationName, receivers, counterPrefix, addCounterMutator, stateSampler);
    this.source = source;
    this.byteCount = addCounterMutator.addCounter(
        Counter.longs(bytesCounterName(counterPrefix, operationName), SUM));
    readState = stateSampler.stateForName(operationName + "-read");
  }

  /** Invoked by tests. */
  ReadOperation(Source<?> source, OutputReceiver outputReceiver, String counterPrefix,
      CounterSet.AddCounterMutator addCounterMutator, StateSampler stateSampler) {
    this("ReadOperation", source, new OutputReceiver[] {outputReceiver}, counterPrefix,
         addCounterMutator, stateSampler);
  }

  /**
   * Invoked by tests. A value of 0 means "update progress on each iteration".
   */
  void setProgressUpdatePeriodMs(long millis) {
    Preconditions.checkArgument(millis >= 0, "Progress update period must be non-negative");
    progressUpdatePeriodMs = millis;
  }

  protected String bytesCounterName(String counterPrefix, String operationName) {
    return operationName + "-ByteCount";
  }

  public Source<?> getSource() {
    return source;
  }

  @Override
  public void start() throws Exception {
    try (StateSampler.ScopedState start = stateSampler.scopedState(startState)) {
      super.start();
      runReadLoop();
    }
  }

  protected void runReadLoop() throws Exception {
    Receiver receiver = receivers[0];
    if (receiver == null) {
      // No consumer of this data; don't do anything.
      return;
    }

    source.addObserver(new SourceObserver());

    try (StateSampler.ScopedState process = stateSampler.scopedState(processState)) {
      synchronized (sourceIteratorLock) {
        sourceIterator = source.iterator();
      }

      // TODO: Consider using the ExecutorService from PipelineOptions instead.
      Thread updateRequester = new Thread() {
        @Override
        public void run() {
          while (true) {
            isProgressUpdateRequested.set(true);
            try {
              Thread.sleep(progressUpdatePeriodMs);
            } catch (InterruptedException e) {
              break;
            }
          }
        }
      };
      if (progressUpdatePeriodMs != 0) {
        updateRequester.start();
      }

      try {
        // Force a progress update at the beginning and at the end.
        synchronized (sourceIteratorLock) {
          progress.set(sourceIterator.getProgress());
        }
        while (true) {
          Object value;
          // Stop position update request comes concurrently.
          // Accesses to iterator need to be synchronized.
          try (StateSampler.ScopedState read = stateSampler.scopedState(readState)) {
            synchronized (sourceIteratorLock) {
              if (!sourceIterator.hasNext()) {
                break;
              }
              value = sourceIterator.next();

              if (isProgressUpdateRequested.getAndSet(false) || progressUpdatePeriodMs == 0) {
                progress.set(sourceIterator.getProgress());
              }
            }
          }
          receiver.process(value);
        }
        synchronized (sourceIteratorLock) {
          progress.set(sourceIterator.getProgress());
        }
      } finally {
        synchronized (sourceIteratorLock) {
          sourceIterator.close();
        }
        if (progressUpdatePeriodMs != 0) {
          updateRequester.interrupt();
          updateRequester.join();
        }
      }
    }
  }

  /**
   * Returns a (possibly slightly stale) value of the progress of the task.
   * Guaranteed to not block indefinitely.
   *
   * @return the task progress, or {@code null} if the source iterator has not
   * been initialized
   */
  public Source.Progress getProgress() {
    return progress.get();
  }

  /**
   * Relays the request to update the stop position to {@code SourceIterator}.
   *
   * @param proposedStopPosition the proposed stop position
   * @return the new stop position updated in {@code SourceIterator}, or
   * {@code null} if the source iterator has not been initialized
   */
  public Source.Position proposeStopPosition(Source.Progress proposedStopPosition) {
    synchronized (sourceIteratorLock) {
      if (sourceIterator == null) {
        LOG.warn("Iterator has not been initialized, returning null stop position.");
        return null;
      }
      return sourceIterator.updateStopPosition(proposedStopPosition);
    }
  }

  /**
   * This is an observer on the instance of the source. Whenever source reads
   * an element, update() gets called with the byte size of the element, which
   * gets added up into the ReadOperation's byte counter.
   */
  private class SourceObserver implements Observer {
    @Override
    public void update(Observable obs, Object obj) {
      Preconditions.checkArgument(obs == source, "unexpected observable" + obs);
      Preconditions.checkArgument(obj instanceof Long, "unexpected parameter object: " + obj);
      byteCount.addValue((long) obj);
    }
  }
}
