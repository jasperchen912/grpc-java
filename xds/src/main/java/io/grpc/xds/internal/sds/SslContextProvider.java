/*
 * Copyright 2019 The gRPC Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.xds.internal.sds;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import io.envoyproxy.envoy.api.v2.auth.CertificateValidationContext;
import io.envoyproxy.envoy.api.v2.auth.CommonTlsContext;
import io.envoyproxy.envoy.api.v2.auth.DownstreamTlsContext;
import io.envoyproxy.envoy.api.v2.auth.UpstreamTlsContext;
import io.grpc.xds.internal.sds.trust.SdsTrustManagerFactory;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import java.io.IOException;
import java.security.cert.CertStoreException;
import java.security.cert.CertificateException;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A SslContextProvider is a "container" or provider of SslContext. This is used by gRPC-xds to
 * obtain an SslContext, so is not part of the public API of gRPC. This "container" may represent a
 * stream that is receiving the requested secret(s) or it could represent file-system based
 * secret(s) that are dynamic.
 */
public abstract class SslContextProvider {

  private static final Logger logger = Logger.getLogger(SslContextProvider.class.getName());

  protected final TlsContextHolder tlsContextHolder;

  public interface Callback {
    /** Informs callee of new/updated SslContext. */
    void updateSecret(SslContext sslContext);

    /** Informs callee of an exception that was generated. */
    void onException(Throwable throwable);
  }

  SslContextProvider(TlsContextHolder tlsContextHolder) {
    this.tlsContextHolder = checkNotNull(tlsContextHolder, "tlsContextHolder");
  }

  CommonTlsContext getCommonTlsContext() {
    return tlsContextHolder.getCommonTlsContext();
  }

  protected void setClientAuthValues(
      SslContextBuilder sslContextBuilder, CertificateValidationContext localCertValidationContext)
      throws CertificateException, IOException, CertStoreException {
    DownstreamTlsContext downstreamTlsContext = getDownstreamTlsContext();
    if (localCertValidationContext != null) {
      sslContextBuilder.trustManager(new SdsTrustManagerFactory(localCertValidationContext));
      sslContextBuilder.clientAuth(
          downstreamTlsContext.hasRequireClientCertificate()
              ? ClientAuth.REQUIRE
              : ClientAuth.OPTIONAL);
    } else {
      sslContextBuilder.clientAuth(ClientAuth.NONE);
    }
  }

  /** Returns the DownstreamTlsContext in this SslContextProvider if this is server side. **/
  public DownstreamTlsContext getDownstreamTlsContext() {
    checkState(tlsContextHolder instanceof DownstreamTlsContextHolder,
        "expected DownstreamTlsContextHolder");
    return ((DownstreamTlsContextHolder) tlsContextHolder).getDownstreamTlsContext();
  }

  /** Returns the UpstreamTlsContext in this SslContextProvider if this is client side. **/
  public UpstreamTlsContext getUpstreamTlsContext() {
    checkState(tlsContextHolder instanceof UpstreamTlsContextHolder,
        "expected UpstreamTlsContextHolder");
    return ((UpstreamTlsContextHolder) tlsContextHolder).getUpstreamTlsContext();
  }

  /** Closes this provider and releases any resources. */
  void close() {}

  /**
   * Registers a callback on the given executor. The callback will run when SslContext becomes
   * available or immediately if the result is already available.
   */
  public abstract void addCallback(Callback callback, Executor executor);

  final void performCallback(
      final SslContextGetter sslContextGetter, final Callback callback, Executor executor) {
    checkNotNull(sslContextGetter, "sslContextGetter");
    checkNotNull(callback, "callback");
    checkNotNull(executor, "executor");
    executor.execute(
        new Runnable() {
          @Override
          public void run() {
            try {
              SslContext sslContext = sslContextGetter.get();
              try {
                callback.updateSecret(sslContext);
              } catch (Throwable t) {
                logger.log(Level.SEVERE, "Exception from callback.updateSecret", t);
              }
            } catch (Throwable e) {
              logger.log(Level.SEVERE, "Exception from sslContextGetter.get()", e);
              callback.onException(e);
            }
          }
        });
  }

  /** Allows implementations to compute or get SslContext. */
  protected interface SslContextGetter {
    SslContext get() throws Exception;
  }
}
