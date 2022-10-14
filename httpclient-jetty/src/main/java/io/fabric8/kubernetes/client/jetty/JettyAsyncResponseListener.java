/**
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.kubernetes.client.jetty;

import io.fabric8.kubernetes.client.http.HttpClient;
import io.fabric8.kubernetes.client.http.HttpRequest;
import io.fabric8.kubernetes.client.http.HttpResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.util.Callback;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongConsumer;

public abstract class JettyAsyncResponseListener<T> extends Response.Listener.Adapter implements HttpClient.AsyncBody {

  private final HttpRequest httpRequest;
  private final HttpClient.BodyConsumer<T> bodyConsumer;
  private final CompletableFuture<HttpResponse<HttpClient.AsyncBody>> asyncResponse;
  private final CompletableFuture<Void> asyncBodyDone;
  private CompletableFuture<LongConsumer> demand = new CompletableFuture<>();
  private boolean initialConsumeCalled;
  private Runnable initialConsume;

  JettyAsyncResponseListener(HttpRequest httpRequest, HttpClient.BodyConsumer<T> bodyConsumer) {
    this.httpRequest = httpRequest;
    this.bodyConsumer = bodyConsumer;
    asyncResponse = new CompletableFuture<>();
    asyncBodyDone = new CompletableFuture<>();
  }

  @Override
  public void consume() {
    synchronized (this) {
      if (!this.initialConsumeCalled) {
        this.initialConsumeCalled = true;
        if (this.initialConsume != null) {
          this.initialConsume.run();
          this.initialConsume = null;
        }
      }
    }
    demand.thenAccept(l -> l.accept(1));
  }

  @Override
  public CompletableFuture<Void> done() {
    return asyncBodyDone;
  }

  @Override
  public void cancel() {
    asyncBodyDone.cancel(false);
  }

  @Override
  public void onBegin(Response response) {
    asyncResponse.complete(new JettyHttpResponse<>(httpRequest, response, this));
  }

  @Override
  public void onComplete(Result result) {
    asyncBodyDone.complete(null);
  }

  public CompletableFuture<HttpResponse<HttpClient.AsyncBody>> listen(Request request) {
    request.send(this);
    return asyncResponse;
  }

  @Override
  public void onContent(Response response, LongConsumer demand, ByteBuffer content, Callback callback) {
    synchronized (this) {
      if (!initialConsumeCalled) {
        // defer until consume is called
        this.initialConsume = () -> onContent(response, demand, content, callback);
        return;
      }
      this.demand.complete(demand);
    }
    try {
      bodyConsumer.consume(process(response, content), this);
      callback.succeeded();
    } catch (Exception e) {
      callback.failed(e);
    }
  }

  protected abstract T process(Response response, ByteBuffer content);
}
