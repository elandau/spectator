/**
 * Copyright 2015 Netflix, Inc.
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
package com.netflix.spectator.spark;

import com.netflix.spectator.api.AbstractRegistry;
import com.netflix.spectator.api.Clock;
import com.netflix.spectator.api.Counter;
import com.netflix.spectator.api.DistributionSummary;
import com.netflix.spectator.api.Id;
import com.netflix.spectator.api.Measurement;
import com.netflix.spectator.api.Meter;
import com.netflix.spectator.api.Tag;
import com.netflix.spectator.api.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Registry that reports values to a sidecar process via an HTTP call.
 */
public class SidecarRegistry extends AbstractRegistry {

  private static final Logger LOGGER = LoggerFactory.getLogger(SidecarRegistry.class);

  private ScheduledExecutorService executor;

  private final Counter numMessages;
  private final Counter numMeasurements;

  /** Create a new instance. */
  public SidecarRegistry() {
    this(Clock.SYSTEM);
  }

  /** Create a new instance. */
  public SidecarRegistry(Clock clock) {
    super(clock);
    numMessages = counter(createId("spectator.sidecar.numMessages"));
    numMeasurements = counter(createId("spectator.sidecar.numMeasurements"));
  }

  /**
   * Start sending data to the sidecar.
   *
   * @param url
   *     Location of the sidecar endpoint.
   * @param pollPeriod
   *     How frequently to poll the data and send to the sidecar.
   * @param pollUnit
   *     Unit for the {@code pollPeriod}.
   */
  public void start(final URL url, long pollPeriod, TimeUnit pollUnit) {
    LOGGER.info("starting sidecar registry with url {} and poll period {} {}",
        url, pollPeriod, pollUnit);
    executor = Executors.newSingleThreadScheduledExecutor(
        new ThreadFactory() {
          @Override public Thread newThread(Runnable r) {
            final Thread t = new Thread(r, "spectator-sidecar");
            t.setDaemon(true);
            return t;
          }
        }
    );

    final SidecarRegistry self = this;
    Runnable task = new Runnable() {
      @Override public void run() {
        try {
          List<Measurement> ms = new ArrayList<>();
          for (Meter meter : self) {
            for (Measurement m : meter.measure()) {
              ms.add(m);
            }
          }
          postJson(url, ms);
        } catch (Exception e) {
          LOGGER.error("failed to send data to sidecar", e);
        }
      }
    };
    executor.scheduleWithFixedDelay(task, pollPeriod, pollPeriod, pollUnit);
  }

  /**
   * Stop sending data to the sidecar and shutdown the executor.
   */
  public void stop() {
    executor.shutdown();
    executor = null;
  }

  private String toJson(List<Measurement> ms) {
    StringBuilder buf = new StringBuilder();
    buf.append('[');
    appendJson(buf, ms.get(0));
    for (int i = 1; i < ms.size(); ++i) {
      buf.append(',');
      appendJson(buf, ms.get(i));
    }
    buf.append(']');
    return buf.toString();
  }

  private void appendJson(StringBuilder buf, Measurement m) {
    if (!Double.isNaN(m.value()) && !Double.isInfinite(m.value())) {
      buf.append('{');
      appendJsonString(buf, "timestamp");
      buf.append(':').append(m.timestamp());
      buf.append(',');

      appendJsonString(buf, "type");
      buf.append(':');
      appendJsonString(buf, getType(m.id()));
      buf.append(',');

      appendJsonString(buf, "name");
      buf.append(':');
      appendJsonString(buf, m.id().name());
      buf.append(',');

      appendJsonString(buf, "tags");
      buf.append(":{");
      boolean first = true;
      for (Tag t : m.id().tags()) {
        if (first) {
          first = false;
        } else {
          buf.append(',');
        }
        appendJsonString(buf, t.key());
        buf.append(':');
        appendJsonString(buf, t.value());
      }
      buf.append("},");

      appendJsonString(buf, "value");
      buf.append(':');
      buf.append(m.value());

      buf.append('}');
    }
  }

  private String getType(Id id) {
    for (Tag t : id.tags()) {
      if (t.key().equals("type")) {
        return t.value();
      }
    }
    return DataType.GAUGE.value();
  }

  private void appendJsonString(StringBuilder buf, String s) {
    buf.append('"');
    final int length = s.length();
    for (int i = 0; i < length; ++i) {
      final char c = s.charAt(i);
      switch (s.charAt(i)) {
        case '"':
          buf.append("\\\"");
          break;
        case '\b':
          buf.append("\\b");
          break;
        case '\f':
          buf.append("\\f");
          break;
        case '\n':
          buf.append("\\n");
          break;
        case '\r':
          buf.append("\\r");
          break;
        case '\t':
          buf.append("\\t");
          break;
        default:
          buf.append(c);
          break;
      }
    }
    buf.append('"');
  }

  private void postJson(URL url, List<Measurement> ms) throws Exception {
    if (!ms.isEmpty()) {
      LOGGER.debug("sending {} messages to sidecar {}", ms.size(), url.toString());
      numMessages.increment();
      numMeasurements.increment(ms.size());
      String json = toJson(ms);
      HttpURLConnection con = (HttpURLConnection) url.openConnection();
      try {
        con.setRequestMethod("POST");
        con.setDoInput(true);
        con.setDoOutput(true);
        try (OutputStream out = con.getOutputStream()) {
          out.write(json.getBytes("UTF-8"));
        }
        con.connect();

        int status = con.getResponseCode();
        if (status != 200) {
          throw new IOException("post to sidecar failed with status: " + status);
        }
      } finally {
        con.disconnect();
      }
    }
  }

  @Override protected Counter newCounter(Id id) {
    return new SidecarCounter(clock(), id);
  }

  @Override protected DistributionSummary newDistributionSummary(Id id) {
    return new SidecarDistributionSummary(clock(), id);
  }

  @Override protected Timer newTimer(Id id) {
    return new SidecarTimer(clock(), id);
  }
}
