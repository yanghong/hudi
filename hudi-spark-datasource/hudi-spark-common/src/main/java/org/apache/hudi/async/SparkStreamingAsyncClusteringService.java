/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hudi.async;

import org.apache.hudi.client.BaseClusterer;
import org.apache.hudi.client.BaseHoodieWriteClient;
import org.apache.hudi.client.HoodieSparkClusteringClient;

/**
 * Async clustering service for Spark structured streaming.
 * Here, async clustering is run in daemon mode to prevent blocking shutting down the Spark application.
 */
public class SparkStreamingAsyncClusteringService extends AsyncClusteringService {

  private static final long serialVersionUID = 1L;

  public SparkStreamingAsyncClusteringService(BaseHoodieWriteClient writeClient) {
    super(writeClient, true);
  }

  @Override
  protected BaseClusterer createClusteringClient(BaseHoodieWriteClient client) {
    return new HoodieSparkClusteringClient(client);
  }
}
