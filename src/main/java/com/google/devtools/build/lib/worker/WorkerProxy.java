// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.worker;

import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.UserExecException;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxInputs;
import com.google.devtools.build.lib.sandbox.SandboxHelpers.SandboxOutputs;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkRequest;
import com.google.devtools.build.lib.worker.WorkerProtocol.WorkResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/** A proxy that talks to the multiplexer */
final class WorkerProxy extends Worker {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private final WorkerMultiplexer workerMultiplexer;
  /** The execution root of the worker. */
  private final Path workDir;

  private Thread shutdownHook;

  WorkerProxy(
      WorkerKey workerKey,
      int workerId,
      Path workDir,
      Path logFile,
      WorkerMultiplexer workerMultiplexer) {
    super(workerKey, workerId, logFile);
    this.workDir = workDir;
    this.workerMultiplexer = workerMultiplexer;
    final WorkerProxy self = this;
    this.shutdownHook =
        new Thread(
            () -> {
              self.shutdownHook = null;
              self.destroy();
            });
    Runtime.getRuntime().addShutdownHook(shutdownHook);
  }

  @Override
  public void prepareExecution(
      SandboxInputs inputFiles, SandboxOutputs outputs, Set<PathFragment> workerFiles)
      throws IOException {
    workerMultiplexer.createProcess(workerKey, workDir);
  }

  /** Send the WorkRequest to multiplexer. */
  @Override
  synchronized void destroy() {
    try {
      WorkerMultiplexerManager.removeInstance(workerKey);
    } catch (InterruptedException e) {
      logger.atWarning().withCause(e).log(
          "InterruptedException was caught while destroying multiplexer. "
              + "It could because the multiplexer was interrupted.");
    } catch (UserExecException e) {
      logger.atWarning().withCause(e).log("Exception");
    }
  }

  /** Send the WorkRequest to multiplexer. */
  @Override
  void putRequest(WorkRequest request) throws IOException, InterruptedException {
    workerMultiplexer.resetResponseChecker(request.getRequestId());
    workerMultiplexer.putRequest(request);
  }

  /** Wait for WorkResponse from multiplexer. */
  @Override
  WorkResponse getResponse(int requestId) throws IOException, InterruptedException {
    return workerMultiplexer.getResponse(requestId);
  }

  @Override
  public void finishExecution(Path execRoot) throws IOException {}

  @Override
  boolean diedUnexpectedly() {
    return workerMultiplexer.diedUnexpectedly();
  }

  @Override
  public Optional<Integer> getExitValue() {
    return workerMultiplexer.getExitValue();
  }

  @Override
  String getRecordingStreamMessage() {
    return workerMultiplexer.getRecordingStreamMessage();
  }
}
