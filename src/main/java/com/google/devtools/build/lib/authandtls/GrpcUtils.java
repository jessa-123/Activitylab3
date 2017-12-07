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

package com.google.devtools.build.lib.authandtls;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import io.grpc.CallCredentials;
import io.grpc.ManagedChannel;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NegotiationType;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.util.RoundRobinLoadBalancerFactory;
import io.netty.handler.ssl.SslContext;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import javax.annotation.Nullable;

/**
 * Utility methods for using {@link AuthAndTLSOptions} with gRPC.
 */
public final class GrpcUtils {

  /**
   * Create a new gRPC {@link ManagedChannel}.
   *
   * @throws IOException  in case the channel can't be constructed.
   */
  public static ManagedChannel newChannel(String target, AuthAndTLSOptions options)
      throws IOException {
    Preconditions.checkNotNull(target);
    Preconditions.checkNotNull(options);

    final SslContext sslContext =
        options.tlsEnabled ? createSSlContext(options.tlsCertificate) : null;

    try {
      NettyChannelBuilder builder =
          NettyChannelBuilder.forTarget(target)
              .negotiationType(
                  options.tlsEnabled ? NegotiationType.TLS : NegotiationType.PLAINTEXT)
              .loadBalancerFactory(RoundRobinLoadBalancerFactory.getInstance());
      if (sslContext != null) {
        builder.sslContext(sslContext);
        if (options.tlsAuthorityOverride != null) {
          builder.overrideAuthority(options.tlsAuthorityOverride);
        }
      }
      return builder.build();
    } catch (RuntimeException e) {
      // gRPC might throw all kinds of RuntimeExceptions: StatusRuntimeException,
      // IllegalStateException, NullPointerException, ...
      String message = "Failed to connect to '%s': %s";
      throw new IOException(String.format(message, target, e.getMessage()));
    }
  }

  private static SslContext createSSlContext(@Nullable String rootCert) throws IOException {
    if (rootCert == null) {
      try {
        return GrpcSslContexts.forClient().build();
      } catch (Exception e) {
        String message = "Failed to init TLS infrastructure: "
            + e.getMessage();
        throw new IOException(message, e);
      }
    } else {
      try {
        return GrpcSslContexts.forClient().trustManager(new File(rootCert)).build();
      } catch (Exception e) {
        String message = "Failed to init TLS infrastructure using '%s' as root certificate: %s";
        message = String.format(message, rootCert, e.getMessage());
        throw new IOException(message, e);
      }
    }
  }

  /**
   * Create a new {@link CallCredentials} object.
   *
   * @throws IOException  in case the call credentials can't be constructed.
   */
  public static CallCredentials newCallCredentials(AuthAndTLSOptions options) throws IOException {
    if (!options.authEnabled) {
      return null;
    }

    if (options.authCredentials != null) {
      // Credentials from file
      try (InputStream authFile = new FileInputStream(options.authCredentials)) {
        return newCallCredentials(authFile, options.authScope);
      } catch (FileNotFoundException e) {
        String message = String.format("Could not open auth credentials file '%s': %s",
            options.authCredentials, e.getMessage());
        throw new IOException(message, e);
      }
    }
    // Google Application Default Credentials
    return newCallCredentials(null, options.authScope);
  }

  @VisibleForTesting
  public static CallCredentials newCallCredentials(@Nullable InputStream credentialsFile,
      @Nullable String authScope) throws IOException {
    try {
      GoogleCredentials creds =
          credentialsFile == null
              ? GoogleCredentials.getApplicationDefault()
              : GoogleCredentials.fromStream(credentialsFile);
      if (authScope != null) {
        creds = creds.createScoped(ImmutableList.of(authScope));
      }
      return MoreCallCredentials.from(creds);
    } catch (IOException e) {
      String message = "Failed to init auth credentials: "
          + e.getMessage();
      throw new IOException(message, e);
    }
  }
}
