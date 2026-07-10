/*
 *    Copyright (c) 2026 SOPTIM AG
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 *    SPDX-License-Identifier: Apache-2.0
 */

package de.soptim.opencgmes.cimvocabcheck.lsp.notebook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CancellationWatchdogTest {

  private ScheduledExecutorService scheduler;

  @Before
  public void setUp() {
    scheduler = Executors.newSingleThreadScheduledExecutor();
  }

  @After
  public void tearDown() {
    scheduler.shutdownNow();
  }

  @Test
  public void firesOnCancelWhenCancelCheckerReportsCancelled() throws InterruptedException {
    CountDownLatch fired = new CountDownLatch(1);
    FlagCancelChecker checker = new FlagCancelChecker();
    checker.cancel();

    ScheduledFuture<?> task = CancellationWatchdog.watch(scheduler, checker, fired::countDown);
    try {
      assertTrue(
          "onCancel should fire shortly after cancellation", fired.await(2, TimeUnit.SECONDS));
    } finally {
      task.cancel(true);
    }
  }

  @Test
  public void neverFiresWhenNeverCancelled() throws InterruptedException {
    CountDownLatch fired = new CountDownLatch(1);
    FlagCancelChecker checker = new FlagCancelChecker();

    ScheduledFuture<?> task = CancellationWatchdog.watch(scheduler, checker, fired::countDown);
    try {
      assertFalse(
          "onCancel must not fire while never cancelled", fired.await(600, TimeUnit.MILLISECONDS));
    } finally {
      task.cancel(true);
    }
  }

  @Test
  public void firesOnCancelExactlyOnceAcrossMultiplePolls() throws InterruptedException {
    AtomicInteger fireCount = new AtomicInteger();
    FlagCancelChecker checker = new FlagCancelChecker();
    checker.cancel();

    ScheduledFuture<?> task =
        CancellationWatchdog.watch(scheduler, checker, fireCount::incrementAndGet);
    try {
      // Let several poll intervals elapse; the guard must keep the callback to a single firing.
      Thread.sleep(900);
      assertEquals(1, fireCount.get());
    } finally {
      task.cancel(true);
    }
  }

  @Test
  public void exceptionFromOnCancelIsSwallowed() throws InterruptedException {
    FlagCancelChecker checker = new FlagCancelChecker();
    checker.cancel();
    CountDownLatch invoked = new CountDownLatch(1);

    ScheduledFuture<?> task =
        CancellationWatchdog.watch(
            scheduler,
            checker,
            () -> {
              invoked.countDown();
              throw new IllegalStateException("boom — guarded operation already finished");
            });
    try {
      // The callback running (and throwing) must not propagate out of the scheduler or prevent
      // the scheduled task itself from remaining alive/cancellable.
      assertTrue(invoked.await(2, TimeUnit.SECONDS));
      assertFalse(task.isDone());
    } finally {
      task.cancel(true);
    }
  }

  /** A simple settable {@link CancelChecker} test double. */
  private static final class FlagCancelChecker implements CancelChecker {
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    void cancel() {
      cancelled.set(true);
    }

    @Override
    public void checkCanceled() {
      if (cancelled.get()) {
        throw new RuntimeException("cancelled");
      }
    }

    @Override
    public boolean isCanceled() {
      return cancelled.get();
    }
  }
}
