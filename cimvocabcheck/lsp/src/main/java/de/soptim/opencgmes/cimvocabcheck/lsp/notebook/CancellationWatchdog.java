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

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls a {@link CancelChecker} on a background scheduler and runs a callback the first time it
 * reports cancellation.
 *
 * <p>Used by {@link HttpQueryExecutor} to react to a client-sent {@code $/cancelRequest}, since
 * Jena's blocking HTTP execution calls have no cancellation hook of their own — polling {@link
 * CancelChecker#isCanceled()} (a non-throwing check) is simpler here than reacting to {@link
 * CancelChecker#checkCanceled()}'s thrown exception on every poll.
 */
final class CancellationWatchdog {

  private static final Logger LOG = LoggerFactory.getLogger(CancellationWatchdog.class);
  private static final long POLL_INTERVAL_MS = 250;

  private CancellationWatchdog() {}

  /**
   * Starts polling {@code cancelChecker} every {@value #POLL_INTERVAL_MS}ms on {@code scheduler}.
   * The first poll that observes cancellation runs {@code onCancel} exactly once. The caller must
   * cancel the returned task (typically in a {@code finally} block) once the guarded operation
   * finishes, whether normally or exceptionally.
   */
  static ScheduledFuture<?> watch(
      ScheduledExecutorService scheduler, CancelChecker cancelChecker, Runnable onCancel) {
    AtomicBoolean triggered = new AtomicBoolean(false);
    return scheduler.scheduleWithFixedDelay(
        () -> {
          if (cancelChecker.isCanceled() && triggered.compareAndSet(false, true)) {
            try {
              onCancel.run();
            } catch (RuntimeException e) {
              // The guarded operation may already have finished on its own (e.g. the abort()
              // callback racing a just-completed execution) — this is a best-effort signal, not
              // a hard requirement, so a failure here is logged and swallowed rather than
              // propagated to the scheduler (which would otherwise silently stop future polls).
              LOG.debug(
                  "Cancellation callback failed (likely already finished): {}", e.getMessage());
            }
          }
        },
        POLL_INTERVAL_MS,
        POLL_INTERVAL_MS,
        TimeUnit.MILLISECONDS);
  }
}
