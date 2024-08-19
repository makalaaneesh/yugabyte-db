// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//

package org.yb.pgsql;

import com.google.common.net.HostAndPort;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import com.yugabyte.ysql.ClusterAwareLoadBalancer;
import com.yugabyte.jdbc.PgConnection;
import org.yb.AssertionWrappers;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yb.client.TestUtils;
import org.yb.minicluster.MiniYBClusterBuilder;
import org.yb.minicluster.MiniYBDaemon;
import org.yb.YBTestRunner;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(value = YBTestRunner.class)
public class TestYbServersMetrics extends BasePgSQLTest {
  private static final Logger LOG = LoggerFactory.getLogger(TestYbServersMetrics.class);
  private static final int NUM_TSERVERS = 3;
  private static final int RF = 3;
  private static ArrayList<String> expectedKeys = new ArrayList<String>(Arrays.asList(
    "memory_free", "memory_available", "memory_total",
    "tserver_root_memory_limit", "tserver_root_memory_soft_limit", "tserver_root_memory_consumption", 
    "cpu_usage_user", "cpu_usage_system"));

  @Override
  public ConnectionBuilder getConnectionBuilder() {
    ConnectionBuilder cb = new ConnectionBuilder(miniCluster);
    cb.setLoadBalance(true);
    return cb;
  }

  @Override
  protected void customizeMiniClusterBuilder(MiniYBClusterBuilder builder){
    super.customizeMiniClusterBuilder(builder);
    builder.numTservers(NUM_TSERVERS);
    builder.replicationFactor(RF);
    builder.tserverHeartbeatTimeoutMs(7000);
  } 

  private void assertYbServersMetricsOutput(int expectedRows, int expectedStatusOkRows) throws Exception{
    Connection conn = getConnectionBuilder().connect();
    try {
      Statement st = conn.createStatement();
      final long startTimeMillis = System.currentTimeMillis();
      ResultSet rs = st.executeQuery("select * from yb_servers_metrics()");
      final long result = System.currentTimeMillis() - startTimeMillis;
      // There is a timeout of 5000ms for each RPC call to tserver.
      AssertionWrappers.assertLessThan(result, Long.valueOf(6000));
      int row_count = 0;
      int ok_count = 0;
      while (rs.next()) {
        String uuid = rs.getString(1);
        String metrics = rs.getString(2);
        String status = rs.getString(3);
        String error = rs.getString(4);
        if (status.equals("OK")) {
          ++ok_count;
          JSONObject metricsJson = new JSONObject(metrics);
          ArrayList<String> metricKeys = new ArrayList<String>(metricsJson.keySet());
          AssertionWrappers.assertTrue("Expected keys are not present. Present keys are:" + metricKeys , metricKeys.containsAll(expectedKeys));
        } else {
          AssertionWrappers.assertEquals("{}", metrics);
        }
        ++row_count;
      }
      AssertionWrappers.assertTrue("Expected " + expectedRows + " tservers, found " + row_count, row_count == expectedRows);
      AssertionWrappers.assertTrue("Expected status OK for " + expectedStatusOkRows + " tservers, found " + ok_count, ok_count == expectedStatusOkRows);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to execute yb_servers_metrics query", e);
    } finally {
      conn.close();
    }
  }

  @Test
  public void testYBServersMetricsFunction() throws Exception {
    assertYbServersMetricsOutput(NUM_TSERVERS, NUM_TSERVERS);

    // add a new tserver
    miniCluster.startTServer(getTServerFlags());
    AssertionWrappers.assertTrue(miniCluster.waitForTabletServers(4));
    waitForTServerHeartbeat();
    assertYbServersMetricsOutput(NUM_TSERVERS + 1, NUM_TSERVERS + 1);

    // kill a tserver
    miniCluster.killTabletServerOnHostPort(miniCluster.getTabletServers().keySet().iterator().next());
    // Initially we will get NUM_TSERVERS + 1 rows, with one of them having status as "ERROR"
    assertYbServersMetricsOutput(NUM_TSERVERS + 1, NUM_TSERVERS);

    // After the tserver is removed and updated in cache,
    // we will get NUM_TSERVERS rows, with all of them having status as "OK"
    Thread.sleep(2 * miniCluster.getClusterParameters().getTServerHeartbeatTimeoutMs());
    assertYbServersMetricsOutput(NUM_TSERVERS, NUM_TSERVERS);
  }

}
