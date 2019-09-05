// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.remote.worker;

import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;
import static java.util.logging.Level.WARNING;

import build.bazel.remote.execution.v2.BatchUpdateBlobsRequest;
import build.bazel.remote.execution.v2.BatchUpdateBlobsResponse;
import build.bazel.remote.execution.v2.ContentAddressableStorageGrpc.ContentAddressableStorageImplBase;
import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import build.bazel.remote.execution.v2.DirectoryNode;
import build.bazel.remote.execution.v2.FindMissingBlobsRequest;
import build.bazel.remote.execution.v2.FindMissingBlobsResponse;
import build.bazel.remote.execution.v2.GetTreeRequest;
import build.bazel.remote.execution.v2.GetTreeResponse;
import com.google.devtools.build.lib.remote.CacheNotFoundException;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.Code;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/** A basic implementation of a {@link ContentAddressableStorageImplBase} service. */
final class CasServer extends ContentAddressableStorageImplBase {
  private static final Logger logger = Logger.getLogger(CasServer.class.getName());
  static final long MAX_BATCH_SIZE_BYTES = 1024 * 1024 * 4;
  private final OnDiskBlobStoreActionCache cache;

  public CasServer(OnDiskBlobStoreActionCache cache) {
    this.cache = cache;
  }

  @Override
  public void findMissingBlobs(
      FindMissingBlobsRequest request, StreamObserver<FindMissingBlobsResponse> responseObserver) {
    FindMissingBlobsResponse.Builder response = FindMissingBlobsResponse.newBuilder();

    for (Digest digest : request.getBlobDigestsList()) {
      if (!cache.containsKey(digest)) {
        response.addMissingBlobDigests(digest);
      }
    }
    responseObserver.onNext(response.build());
    responseObserver.onCompleted();
  }

  @Override
  public void batchUpdateBlobs(
      BatchUpdateBlobsRequest request, StreamObserver<BatchUpdateBlobsResponse> responseObserver) {
    BatchUpdateBlobsResponse.Builder batchResponse = BatchUpdateBlobsResponse.newBuilder();
    for (BatchUpdateBlobsRequest.Request r : request.getRequestsList()) {
      BatchUpdateBlobsResponse.Response.Builder resp = batchResponse.addResponsesBuilder();
      try {
        Digest digest = cache.getDigestUtil().compute(r.getData().toByteArray());
        getFromFuture(cache.uploadBlob(digest, r.getData()));
        if (!r.getDigest().equals(digest)) {
          String err =
              "Upload digest " + r.getDigest() + " did not match data digest: " + digest;
          resp.setStatus(StatusUtils.invalidArgumentStatus("digest", err));
          continue;
        }
        resp.getStatusBuilder().setCode(Code.OK.getNumber());
      } catch (Exception e) {
        resp.setStatus(StatusUtils.internalErrorStatus(e));
      }
    }
    responseObserver.onNext(batchResponse.build());
    responseObserver.onCompleted();
  }

  @Override
  public void getTree(GetTreeRequest request, StreamObserver<GetTreeResponse> responseObserver) {
    // Directories are returned in depth-first order.  We store all previously-traversed digests so
    // identical subtrees having the same digest will only be traversed and returned once.
    Set<Digest> seenDigests = new HashSet<>();
    Deque<Digest> pendingDigests = new ArrayDeque<>();
    seenDigests.add(request.getRootDigest());
    pendingDigests.push(request.getRootDigest());
    // The page token is implemented as the previous directory's digest.  If the client passes a
    // page token, we still do the complete traversal internally, but we skip sending the results to
    // the client until we see the page token's digest.
    boolean skipping = !request.getPageToken().isEmpty();
    while (!pendingDigests.isEmpty() && !Context.current().isCancelled()) {
      Digest digest = pendingDigests.pop();
      byte[] directoryBytes;
      try {
        directoryBytes = getFromFuture(cache.downloadBlob(digest));
      } catch (CacheNotFoundException e) {
        responseObserver.onError(StatusUtils.notFoundError(digest));
        return;
      } catch (Exception e) {
        logger.log(WARNING, "Read request failed.", e);
        responseObserver.onError(StatusUtils.internalError(e));
        return;
      }
      Directory directory;
      try {
        directory = Directory.parseFrom(directoryBytes);
      } catch (InvalidProtocolBufferException e) {
        logger.log(WARNING, "Failed to parse directory in tree.", e);
        responseObserver.onError(StatusUtils.internalError(e));
        return;
      }
      for (DirectoryNode directoryNode : directory.getDirectoriesList()) {
        if (!seenDigests.contains(directoryNode.getDigest())) {
          seenDigests.add(directoryNode.getDigest());
          pendingDigests.push(directoryNode.getDigest());
        }
      }
      GetTreeResponse response =
          GetTreeResponse.newBuilder()
              .addDirectories(directory)
              .setNextPageToken(pendingDigests.isEmpty() ? "" : digest.getHash())
              .build();
      if (!skipping) {
        responseObserver.onNext(response);
      }
      if (request.getPageToken().equals(digest.getHash())) {
        skipping = false;
      }
    }
    if (Context.current().isCancelled()) {
      responseObserver.onError(Status.CANCELLED.asException());
    } else {
      responseObserver.onCompleted();
    }
  }
}
