/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.metrics;

import static org.junit.Assert.assertEquals;

import alluxio.clock.ManualClock;
import alluxio.grpc.MetricType;
import alluxio.heartbeat.HeartbeatContext;
import alluxio.heartbeat.HeartbeatScheduler;
import alluxio.heartbeat.ManuallyScheduleHeartbeat;
import alluxio.master.MasterRegistry;
import alluxio.master.MasterTestUtils;
import alluxio.metrics.Metric;
import alluxio.metrics.MetricsSystem;
import alluxio.metrics.aggregator.SingleTagValueAggregator;
import alluxio.metrics.aggregator.SumInstancesAggregator;
import alluxio.util.ThreadFactoryUtils;
import alluxio.util.executor.ExecutorServiceFactories;

import com.google.common.collect.Lists;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Unit tests for {@link MetricsMaster}.
 */
public class MetricsMasterTest {
  @ClassRule
  public static ManuallyScheduleHeartbeat sManuallyScheduleRule = new ManuallyScheduleHeartbeat(
      HeartbeatContext.MASTER_CLUSTER_METRICS_UPDATER);

  private DefaultMetricsMaster mMetricsMaster;
  private MasterRegistry mRegistry;
  private ManualClock mClock;
  private ExecutorService mExecutorService;

  @Before
  public void before() throws Exception {
    mRegistry = new MasterRegistry();
    mClock = new ManualClock();
    mExecutorService =
        Executors.newFixedThreadPool(2, ThreadFactoryUtils.build("TestMetricsMaster-%d", true));
    mMetricsMaster = new DefaultMetricsMaster(MasterTestUtils.testMasterContext(), mClock,
        ExecutorServiceFactories.constantExecutorServiceFactory(mExecutorService));
    mRegistry.add(MetricsMaster.class, mMetricsMaster);
    mRegistry.start(true);
  }

  /**
   * Stops the master after a test ran.
   */
  @After
  public void after() throws Exception {
    mRegistry.stop();
  }

  @Test
  public void testAggregator() {
    mMetricsMaster.addAggregator(
        new SumInstancesAggregator("metricA", MetricsSystem.InstanceType.WORKER, "metricA"));
    mMetricsMaster.addAggregator(
        new SumInstancesAggregator("metricB", MetricsSystem.InstanceType.WORKER, "metricB"));

    List<Metric> metrics1 = Lists.newArrayList(
        Metric.from("worker.metricA.192_1_1_1", 10, MetricType.GAUGE),
        Metric.from("worker.metricB.192_1_1_1", 20, MetricType.GAUGE));
    mMetricsMaster.workerHeartbeat("192_1_1_1", metrics1);

    List<Metric> metrics2 = Lists.newArrayList(
        Metric.from("worker.metricA.192_1_1_2", 1, MetricType.GAUGE),
        Metric.from("worker.metricB.192_1_1_2", 2, MetricType.GAUGE));
    mMetricsMaster.workerHeartbeat("192_1_1_2", metrics2);
    assertEquals(11L, getGauge("metricA"));
    assertEquals(22L, getGauge("metricB"));

    // override metrics from hostname 192_1_1_2
    List<Metric> metrics3 = Lists.newArrayList(
        Metric.from("worker.metricA.192_1_1_2", 3, MetricType.GAUGE));
    mMetricsMaster.workerHeartbeat("192_1_1_2", metrics3);
    assertEquals(13L, getGauge("metricA"));
    assertEquals(22L, getGauge("metricB"));
  }

  @Test
  public void testMultiValueAggregator() throws Exception {
    mMetricsMaster.addAggregator(
        new SingleTagValueAggregator("metric", MetricsSystem.InstanceType.WORKER, "metric", "tag"));
    List<Metric> metrics1 = Lists.newArrayList(
        Metric.from("worker.metric.192_1_1_1.tag:v1", 10, MetricType.GAUGE),
        Metric.from("worker.metric.192_1_1_1.tag:v2", 20, MetricType.GAUGE));
    mMetricsMaster.workerHeartbeat("192_1_1_1", metrics1);
    List<Metric> metrics2 = Lists.newArrayList(
        Metric.from("worker.metric.192_1_1_2.tag:v1", 1, MetricType.GAUGE),
        Metric.from("worker.metric.192_1_1_2.tag:v2", 2, MetricType.GAUGE));
    mMetricsMaster.workerHeartbeat("192_1_1_2", metrics2);
    HeartbeatScheduler.execute(HeartbeatContext.MASTER_CLUSTER_METRICS_UPDATER);
    assertEquals(11L, getGauge("metric", "tag", "v1"));
    assertEquals(22L, getGauge("metric", "tag", "v2"));
  }

  @Test
  public void testClientHeartbeat() {
    mMetricsMaster.addAggregator(
        new SumInstancesAggregator("metric1", MetricsSystem.InstanceType.CLIENT, "metric1"));
    mMetricsMaster.addAggregator(
        new SumInstancesAggregator("metric2", MetricsSystem.InstanceType.CLIENT, "metric2"));
    List<Metric> metrics1 = Lists.newArrayList(
        Metric.from("client.metric1.192_1_1_1:A", 10, MetricType.GAUGE),
        Metric.from("client.metric2.192_1_1_1:A", 20, MetricType.GAUGE));
    mMetricsMaster.clientHeartbeat("A", "192.1.1.1", metrics1);
    List<Metric> metrics2 = Lists.newArrayList(
        Metric.from("client.metric1.192_1_1_1:B", 15, MetricType.GAUGE),
        Metric.from("client.metric2.192_1_1_1:B", 25, MetricType.GAUGE));
    mMetricsMaster.clientHeartbeat("B", "192.1.1.1", metrics2);
    List<Metric> metrics3 = Lists.newArrayList(
        Metric.from("client.metric1.192_1_1_2:C", 1, MetricType.GAUGE),
        Metric.from("client.metric2.192_1_1_2:C", 2, MetricType.GAUGE));
    mMetricsMaster.clientHeartbeat("C", "192.1.1.2", metrics3);
    assertEquals(26L, getGauge("metric1"));
    assertEquals(47L, getGauge("metric2"));
  }

  private Object getGauge(String name) {
    return MetricsSystem.METRIC_REGISTRY.getGauges().get(name)
        .getValue();
  }

  private Object getGauge(String metricName, String tagName, String tagValue) {
    return MetricsSystem.METRIC_REGISTRY.getGauges()
        .get(Metric.getMetricNameWithTags(metricName, tagName, tagValue))
        .getValue();
  }
}
