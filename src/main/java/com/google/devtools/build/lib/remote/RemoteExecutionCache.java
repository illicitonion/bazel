// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote;

import static com.google.devtools.build.lib.remote.util.Utils.getFromFuture;
import static java.lang.String.format;

import build.bazel.remote.execution.v2.Digest;
import build.bazel.remote.execution.v2.Directory;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.actions.ActionInputDepOwnerMap;
import com.google.devtools.build.lib.actions.Artifact.DerivedArtifact;
import com.google.devtools.build.lib.actions.FileArtifactValue.RemoteFileArtifactValue;
import com.google.devtools.build.lib.actions.LostInputsActionExecutionException;
import com.google.devtools.build.lib.actions.LostInputsExecException;
import com.google.devtools.build.lib.remote.common.RemoteCacheClient;
import com.google.devtools.build.lib.remote.merkletree.MerkleTree;
import com.google.devtools.build.lib.remote.merkletree.MerkleTree.PathOrBytes;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.remote.util.DigestUtil;
import com.google.protobuf.Message;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A {@link RemoteCache} with additional functionality needed for remote execution. */
public class RemoteExecutionCache extends RemoteCache {

  public RemoteExecutionCache(
      RemoteCacheClient protocolImpl, RemoteOptions options, DigestUtil digestUtil) {
    super(protocolImpl, options, digestUtil);
  }

  /**
   * Ensures that the tree structure of the inputs, the input files themselves, and the command are
   * available in the remote cache, such that the tree can be reassembled and executed on another
   * machine given the root digest.
   *
   * <p>The cache may check whether files or parts of the tree structure are already present, and do
   * not need to be uploaded again.
   *
   * <p>Note that this method is only required for remote execution, not for caching itself.
   * However, remote execution uses a cache to store input files, and that may be a separate
   * end-point from the executor itself, so the functionality lives here.
   */
  public void ensureInputsPresent(MerkleTree merkleTree, Map<Digest, Message> additionalInputs, String actionId)
      throws IOException, InterruptedException {
    Iterable<Digest> allDigests =
        Iterables.concat(merkleTree.getAllDigests(), additionalInputs.keySet());
    ImmutableSet<Digest> missingDigests =
        getFromFuture(cacheProtocol.findMissingDigests(allDigests));

    List<ListenableFuture<Void>> uploadFutures = new ArrayList<>();
    for (Digest missingDigest : missingDigests) {
      uploadFutures.add(uploadBlob(missingDigest, merkleTree, additionalInputs, actionId));
    }

    waitForBulkTransfer(uploadFutures, /* cancelRemainingOnInterrupt=*/ false);
  }

  private ListenableFuture<Void> uploadBlob(
      Digest digest, MerkleTree merkleTree, Map<Digest, Message> additionalInputs, String actionId) {
    Directory node = merkleTree.getDirectoryByDigest(digest);
    if (node != null) {
      return cacheProtocol.uploadBlob(digest, node.toByteString());
    }

    PathOrBytes file = merkleTree.getFileByDigest(digest);
    if (file != null) {
      if (file.getBytes() != null) {
        return cacheProtocol.uploadBlob(digest, file.getBytes());
      }
      return Futures.catchingAsync(cacheProtocol.uploadFile(digest, file.getPath()),
          FileNotFoundException.class, e -> {
        if (file.getArtifact() != null) {
          ActionInputDepOwnerMap owners = new ActionInputDepOwnerMap(
              ImmutableList.of(file.getArtifact()));
          RemoteFileArtifactValue artifactValue = new RemoteFileArtifactValue(
              DigestUtil.toBinaryDigest(digest),
              digest.getSizeBytes(),
              /*locationIndex=*/ 1,
              actionId);
          owners.put(file.getArtifact(), artifactValue, (DerivedArtifact) file.getArtifact());
          return Futures.immediateFailedFuture(new LostInputsExecException(ImmutableMap.of(digest.getHash(), file.getArtifact()),
              owners));
        }
            return Futures.immediateFailedFuture(e);
          }, MoreExecutors.directExecutor());
    }

    Message message = additionalInputs.get(digest);
    if (message != null) {
      return cacheProtocol.uploadBlob(digest, message.toByteString());
    }

    return Futures.immediateFailedFuture(
        new IOException(
            format(
                "findMissingDigests returned a missing digest that has not been requested: %s",
                digest)));
  }
}
