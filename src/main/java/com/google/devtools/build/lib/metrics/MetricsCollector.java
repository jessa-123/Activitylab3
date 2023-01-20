// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.metrics;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import com.google.devtools.build.lib.actions.ActionCompletionEvent;
import com.google.devtools.build.lib.actions.ActionResultReceivedEvent;
import com.google.devtools.build.lib.actions.AnalysisGraphStatsEvent;
import com.google.devtools.build.lib.actions.TotalAndConfiguredTargetOnlyMetric;
import com.google.devtools.build.lib.analysis.AnalysisPhaseCompleteEvent;
import com.google.devtools.build.lib.analysis.AnalysisPhaseStartedEvent;
import com.google.devtools.build.lib.analysis.NoBuildRequestFinishedEvent;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.ActionSummary;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.ActionSummary.ActionData;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.ActionSummary.RunnerCount;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.ArtifactMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.BuildGraphMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.CumulativeMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.MemoryMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.MemoryMetrics.GarbageMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.NetworkMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.PackageMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.TargetMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.TimingMetrics;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.WorkerMetrics;
import com.google.devtools.build.lib.buildtool.BuildPrecompleteEvent;
import com.google.devtools.build.lib.buildtool.buildevent.ExecutionStartingEvent;
import com.google.devtools.build.lib.clock.BlazeClock;
import com.google.devtools.build.lib.clock.BlazeClock.NanosToMillisSinceEpochConverter;
import com.google.devtools.build.lib.metrics.MetricsModule.Options;
import com.google.devtools.build.lib.metrics.PostGCMemoryUseRecorder.PeakHeap;
import com.google.devtools.build.lib.metrics.TriStateDurationAccumulator.State;
import com.google.devtools.build.lib.profiler.MemoryProfiler;
import com.google.devtools.build.lib.profiler.NetworkMetricsCollector;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.runtime.SpawnStats;
import com.google.devtools.build.lib.skyframe.ExecutionFinishedEvent;
import com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.TopLevelTargetPendingExecutionEvent;
import com.google.devtools.build.lib.worker.WorkerMetric;
import com.google.devtools.build.lib.worker.WorkerMetricsCollector;
import com.google.devtools.build.skyframe.SkyframeGraphStatsEvent;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.stream.Stream;

class TriStateDurationAccumulator {

  enum State {
    Empty,
    Valid,
    Invalid
  }

  private final State state;
  private final Duration duration;
  private TriStateDurationAccumulator(State state, Duration duration) {
    this.state = state;
    this.duration = duration;
  }
  private TriStateDurationAccumulator(State state) {
    this.state = state;
    this.duration = Duration.ZERO;
  }

  public TriStateDurationAccumulator plus(Duration duration) {
    if (state == State.Invalid) {
      return this;
    }
    if (duration != null) {
      return new TriStateDurationAccumulator(State.Valid, getDuration().plus(duration));
    } else {
      return new TriStateDurationAccumulator(State.Invalid);
    }
  }

  static public TriStateDurationAccumulator empty(){
    return new TriStateDurationAccumulator(State.Empty);
  }

  public State getState() {
    return state;
  }

  public Duration getDuration() {
    return duration;
  }
}

class MetricsCollector {
  private final CommandEnvironment env;
  private final boolean recordMetricsForAllMnemonics;
  // For ActionSummary.
  private final ConcurrentHashMap<String, ActionStats> actionStatsMap = new ConcurrentHashMap<>();

  // For CumulativeMetrics.
  private final AtomicInteger numAnalyses;
  private final AtomicInteger numBuilds;

  private final ActionSummary.Builder actionSummary = ActionSummary.newBuilder();
  private final TargetMetrics.Builder targetMetrics = TargetMetrics.newBuilder();
  private final PackageMetrics.Builder packageMetrics = PackageMetrics.newBuilder();
  private final TimingMetrics.Builder timingMetrics = TimingMetrics.newBuilder();
  private final ArtifactMetrics.Builder artifactMetrics = ArtifactMetrics.newBuilder();
  private final BuildGraphMetrics.Builder buildGraphMetrics = BuildGraphMetrics.newBuilder();
  private final SpawnStats spawnStats = new SpawnStats();
  // Skymeld-specific: we don't have an ExecutionStartingEvent for skymeld, so we have to use
  // TopLevelTargetExecutionStartedEvent. This AtomicBoolean is so that we only account for the
  // build once.
  private final AtomicBoolean buildAccountedFor;

  @CanIgnoreReturnValue
  private MetricsCollector(
      CommandEnvironment env, AtomicInteger numAnalyses, AtomicInteger numBuilds) {
    this.env = env;
    Options options = env.getOptions().getOptions(Options.class);
    this.recordMetricsForAllMnemonics = options != null && options.recordMetricsForAllMnemonics;
    this.numAnalyses = numAnalyses;
    this.numBuilds = numBuilds;
    env.getEventBus().register(this);
    WorkerMetricsCollector.instance().setClock(env.getClock());
    this.buildAccountedFor = new AtomicBoolean();
  }

  static void installInEnv(
      CommandEnvironment env, AtomicInteger numAnalyses, AtomicInteger numBuilds) {
    new MetricsCollector(env, numAnalyses, numBuilds);
  }

  @SuppressWarnings("unused")
  @Subscribe
  public synchronized void logAnalysisStartingEvent(AnalysisPhaseStartedEvent event) {
    numAnalyses.getAndIncrement();
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void onAnalysisPhaseComplete(AnalysisPhaseCompleteEvent event) {
    TotalAndConfiguredTargetOnlyMetric actionsConstructed = event.getActionsConstructed();
    actionSummary
        .setActionsCreated(actionsConstructed.total())
        .setActionsCreatedNotIncludingAspects(actionsConstructed.configuredTargetsOnly());
    TotalAndConfiguredTargetOnlyMetric targetsConfigured = event.getTargetsConfigured();
    targetMetrics
        .setTargetsConfigured(targetsConfigured.total())
        .setTargetsConfiguredNotIncludingAspects(targetsConfigured.configuredTargetsOnly());
    packageMetrics.setPackagesLoaded(event.getPkgManagerStats().getPackagesSuccessfullyLoaded());
    timingMetrics.setAnalysisPhaseTimeInMs(event.getTimeInMs());
  }

  @SuppressWarnings("unused")
  @Subscribe
  public synchronized void logAnalysisGraphStats(AnalysisGraphStatsEvent event) {
    // Check only one event per build. No proto3 check for presence, so check for not-default value.
    if (buildGraphMetrics.getActionLookupValueCount() > 0) {
      BugReport.sendBugReport(
          new IllegalStateException(
              "Already initialized build graph metrics builder: "
                  + buildGraphMetrics
                  + ", "
                  + event.getBuildGraphMetrics()));
    }
    buildGraphMetrics.mergeFrom(event.getBuildGraphMetrics());
  }

  @SuppressWarnings("unused")
  @Subscribe
  public synchronized void logExecutionStartingEvent(ExecutionStartingEvent event) {
    numBuilds.getAndIncrement();
  }

  @Subscribe
  public synchronized void accountForBuild(
      @SuppressWarnings("unused") TopLevelTargetPendingExecutionEvent event) {
    if (buildAccountedFor.compareAndSet(/*expectedValue=*/ false, /*newValue=*/ true)) {
      numBuilds.getAndIncrement();
    }
  }

  @SuppressWarnings("unused")
  @Subscribe
  @AllowConcurrentEvents
  public void onActionComplete(ActionCompletionEvent event) {
    ActionStats actionStats =
        actionStatsMap.computeIfAbsent(event.getAction().getMnemonic(), ActionStats::new);
    actionStats.numActions.incrementAndGet();
    actionStats.firstStarted.accumulate(event.getRelativeActionStartTime());
    actionStats.lastEnded.accumulate(BlazeClock.nanoTime());
    spawnStats.incrementActionCount();
  }

  @Subscribe
  @AllowConcurrentEvents
  public void actionResultReceived(ActionResultReceivedEvent event) {
    spawnStats.countActionResult(event.getActionResult());
    ActionStats actionStats =
        actionStatsMap.computeIfAbsent(event.getAction().getMnemonic(), ActionStats::new);
    Optional<Duration> systemTime = event.getActionResult()
        .cumulativeCommandExecutionSystemTime();
    actionStats.systemTime.updateAndGet(t->t.plus(systemTime.orElse(null)));
    Optional<Duration> userTime = event.getActionResult()
        .cumulativeCommandExecutionUserTime();
    if (userTime.isPresent()) {
      actionStats.userTime.updateAndGet(t->t.plus(userTime.orElse(null)));
    }
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void onExecutionComplete(ExecutionFinishedEvent event) {
    artifactMetrics
        .setSourceArtifactsRead(event.sourceArtifactsRead())
        .setOutputArtifactsSeen(event.outputArtifactsSeen())
        .setOutputArtifactsFromActionCache(event.outputArtifactsFromActionCache())
        .setTopLevelArtifacts(event.topLevelArtifacts());
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void onSkyframeGraphStats(SkyframeGraphStatsEvent event) {
    buildGraphMetrics.setPostInvocationSkyframeNodeCount(event.getGraphSize());
  }

  @SuppressWarnings("unused")
  @Subscribe
  public void onBuildComplete(BuildPrecompleteEvent event) {
    postBuildMetricsEvent();
  }

  @SuppressWarnings("unused") // Used reflectively
  @Subscribe
  public void onNoBuildRequestFinishedEvent(NoBuildRequestFinishedEvent event) {
    postBuildMetricsEvent();
  }

  private void postBuildMetricsEvent() {
    env.getEventBus().post(new BuildMetricsEvent(createBuildMetrics()));
  }

  private ImmutableList<WorkerMetrics> createWorkerMetrics() {
    return WorkerMetricsCollector.instance().collectMetrics().stream()
        .map(WorkerMetric::toProto)
        .collect(toImmutableList());
  }

  private BuildMetrics createBuildMetrics() {
    BuildMetrics.Builder buildMetrics =
        BuildMetrics.newBuilder()
            .setActionSummary(finishActionSummary())
            .setMemoryMetrics(createMemoryMetrics())
            .setTargetMetrics(targetMetrics.build())
            .setPackageMetrics(packageMetrics.build())
            .setTimingMetrics(finishTimingMetrics())
            .setCumulativeMetrics(createCumulativeMetrics())
            .setArtifactMetrics(artifactMetrics.build())
            .setBuildGraphMetrics(buildGraphMetrics.build())
            .addAllWorkerMetrics(createWorkerMetrics());

    NetworkMetrics networkMetrics = NetworkMetricsCollector.instance().collectMetrics();
    if (networkMetrics != null) {
      buildMetrics.setNetworkMetrics(networkMetrics);
    }

    return buildMetrics.build();
  }

  private ActionData buildActionData(ActionStats actionStats) {
    NanosToMillisSinceEpochConverter nanosToMillisSinceEpochConverter =
        BlazeClock.createNanosToMillisSinceEpochConverter();
    ActionData.Builder builder = ActionData.newBuilder()
        .setMnemonic(actionStats.mnemonic)
        .setFirstStartedMs(
            nanosToMillisSinceEpochConverter.toEpochMillis(
                actionStats.firstStarted.longValue()))
        .setLastEndedMs(
            nanosToMillisSinceEpochConverter.toEpochMillis(
                actionStats.lastEnded.longValue()))
        .setActionsExecuted(actionStats.numActions.get());
    TriStateDurationAccumulator systemTime = actionStats.systemTime.get();
    if (systemTime.getState() == State.Valid) {
      builder.setSystemTime(com.google.protobuf.Duration.newBuilder()
          .setSeconds(systemTime.getDuration().getSeconds())
          .setNanos(systemTime.getDuration().getNano()));
    }
    TriStateDurationAccumulator userTime = actionStats.userTime.get();
    if (userTime.getState() == State.Valid) {
      builder.setUserTime(com.google.protobuf.Duration.newBuilder()
          .setSeconds(userTime.getDuration().getSeconds())
          .setNanos(userTime.getDuration().getNano()));
    }
    return builder.build();
  }

  private static final int MAX_ACTION_DATA = 20;

  private ActionSummary finishActionSummary() {
    NanosToMillisSinceEpochConverter nanosToMillisSinceEpochConverter =
        BlazeClock.createNanosToMillisSinceEpochConverter();
    Stream<ActionStats> actionStatsStream = actionStatsMap.values().stream();
    if (!recordMetricsForAllMnemonics) {
      actionStatsStream =
          actionStatsStream
              .sorted(Comparator.comparingLong(a -> -a.numActions.get()))
              .limit(MAX_ACTION_DATA);
    }
    actionStatsStream.forEach(
        action ->
            actionSummary.addActionData(
                buildActionData(action)));

    ImmutableMap<String, Integer> spawnSummary = spawnStats.getSummary();
    actionSummary.setActionsExecuted(spawnSummary.getOrDefault("total", 0));
    spawnSummary
        .entrySet()
        .forEach(
            e ->
                actionSummary.addRunnerCount(
                    RunnerCount.newBuilder().setName(e.getKey()).setCount(e.getValue()).build()));
    return actionSummary.build();
  }

  private MemoryMetrics createMemoryMetrics() {
    MemoryMetrics.Builder memoryMetrics = MemoryMetrics.newBuilder();
    if (MemoryProfiler.instance().getHeapUsedMemoryAtFinish() > 0) {
      memoryMetrics.setUsedHeapSizePostBuild(MemoryProfiler.instance().getHeapUsedMemoryAtFinish());
    }
    PostGCMemoryUseRecorder.get()
        .getPeakPostGcHeap()
        .map(PeakHeap::bytes)
        .ifPresent(memoryMetrics::setPeakPostGcHeapSize);

    if (memoryMetrics.getPeakPostGcHeapSize() < memoryMetrics.getUsedHeapSizePostBuild()) {
      // If we just did a GC and computed the heap size, update the one we got from the GC
      // notification (which may arrive too late for this specific GC).
      memoryMetrics.setPeakPostGcHeapSize(memoryMetrics.getUsedHeapSizePostBuild());
    }

    PostGCMemoryUseRecorder.get()
        .getPeakPostGcHeapTenuredSpace()
        .map(PeakHeap::bytes)
        .ifPresent(memoryMetrics::setPeakPostGcTenuredSpaceHeapSize);

    Map<String, Long> garbageStats = PostGCMemoryUseRecorder.get().getGarbageStats();
    for (Map.Entry<String, Long> garbageEntry : garbageStats.entrySet()) {
      GarbageMetrics.Builder garbageMetrics = GarbageMetrics.newBuilder();
      garbageMetrics.setType(garbageEntry.getKey()).setGarbageCollected(garbageEntry.getValue());
      memoryMetrics.addGarbageMetrics(garbageMetrics.build());
    }

    return memoryMetrics.build();
  }

  private CumulativeMetrics createCumulativeMetrics() {
    return CumulativeMetrics.newBuilder()
        .setNumAnalyses(numAnalyses.get())
        .setNumBuilds(numBuilds.get())
        .build();
  }

  private TimingMetrics finishTimingMetrics() {
    Duration elapsedWallTime = Profiler.elapsedTimeMaybe();
    if (elapsedWallTime != null) {
      timingMetrics.setWallTimeInMs(elapsedWallTime.toMillis());
    }
    Duration cpuTime = Profiler.getProcessCpuTimeMaybe();
    if (cpuTime != null) {
      timingMetrics.setCpuTimeInMs(cpuTime.toMillis());
    }
    return timingMetrics.build();
  }

  private static class ActionStats {
    final LongAccumulator firstStarted;
    final LongAccumulator lastEnded;
    final AtomicLong numActions;
    final String mnemonic;
    final AtomicReference<TriStateDurationAccumulator> systemTime;
    final AtomicReference<TriStateDurationAccumulator> userTime;

    ActionStats(String mnemonic) {
      this.mnemonic = mnemonic;
      firstStarted = new LongAccumulator(Math::min, Long.MAX_VALUE);
      lastEnded = new LongAccumulator(Math::max, 0);
      numActions = new AtomicLong();
      systemTime = new AtomicReference<>(TriStateDurationAccumulator.empty());
      userTime = new AtomicReference<>(TriStateDurationAccumulator.empty());
    }
  }
}
