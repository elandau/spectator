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
package com.netflix.spectator.tdigest;

import com.netflix.spectator.api.*;
import com.netflix.spectator.api.Counter;


/** Registry that maps spectator types to servo. */
public class TDigestRegistry extends AbstractRegistry {

  private static final Registry NOOP = new NoopRegistry();

  /** Create a new instance. */
  public TDigestRegistry() {
    this(Clock.SYSTEM);
  }

  /** Create a new instance. */
  public TDigestRegistry(Clock clock) {
    super(clock);
  }

  @Override protected Counter newCounter(Id id) {
    return NOOP.counter(id);
  }

  @Override protected TDigestDistributionSummary newDistributionSummary(Id id) {
    return new TDigestDistributionSummary(clock(), id);
  }

  @Override protected TDigestTimer newTimer(Id id) {
    return new TDigestTimer(clock(), id);
  }
}
