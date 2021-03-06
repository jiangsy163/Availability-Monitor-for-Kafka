//*********************************************************
// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.
//*********************************************************

package com.microsoft.kafkaavailability.threads;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SlidingWindowReservoir;
import com.google.gson.Gson;
import com.microsoft.kafkaavailability.*;
import com.microsoft.kafkaavailability.discovery.CommonUtils;
import com.microsoft.kafkaavailability.metrics.AvailabilityGauge;
import com.microsoft.kafkaavailability.metrics.MetricNameEncoded;
import com.microsoft.kafkaavailability.metrics.MetricsFactory;
import com.microsoft.kafkaavailability.properties.AppProperties;
import com.microsoft.kafkaavailability.properties.MetaDataManagerProperties;
import com.microsoft.kafkaavailability.properties.ProducerProperties;
import kafka.javaapi.TopicMetadata;
import org.apache.curator.framework.CuratorFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.concurrent.Phaser;

import static com.microsoft.kafkaavailability.discovery.Constants.DEFAULT_ELAPSED_TIME;

public class AvailabilityThread implements Runnable {

    final static Logger m_logger = LoggerFactory.getLogger(AvailabilityThread.class);
    Phaser m_phaser;
    CuratorFramework m_curatorFramework;
    long m_threadSleepTime;
    String m_clusterName;
    MetricsFactory metricsFactory;

    public AvailabilityThread(Phaser phaser, CuratorFramework curatorFramework, long threadSleepTime, String clusterName) {
        this.m_phaser = phaser;
        this.m_curatorFramework = curatorFramework;
        //this.m_phaser.register(); //Registers/Add a new unArrived party to this phaser.
        //CommonUtils.dumpPhaserState("After register", phaser);
        m_threadSleepTime = threadSleepTime;
        m_clusterName = clusterName;
    }

    @Override
    public void run() {
        int sleepDuration = 1000;
        do {
            long lStartTime = System.nanoTime();
            MetricRegistry metrics;
            m_logger.info(Thread.currentThread().getName() +
                    " - Availability party has arrived and is working in "
                    + "Phase-" + m_phaser.getPhase());

            try {
                metricsFactory = new MetricsFactory();
                metricsFactory.configure(m_clusterName);

                metricsFactory.start();
                metrics = metricsFactory.getRegistry();
                RunAvailability(metrics);
                metricsFactory.report();
                CommonUtils.sleep(1000);
            } catch (Exception e) {
                m_logger.error(e.getMessage(), e);
            } finally {
                try {
                    metricsFactory.stop();
                } catch (Exception e) {
                    m_logger.error(e.getMessage(), e);
                }
            }

            long elapsedTime = CommonUtils.stopWatch(lStartTime);
            m_logger.info("Availability Elapsed: " + elapsedTime + " milliseconds.");

            while (elapsedTime < m_threadSleepTime && !m_phaser.isTerminated()) {
                try {
                    Thread.currentThread().sleep(sleepDuration);
                    elapsedTime = elapsedTime + sleepDuration;
                } catch (InterruptedException ie) {
                    m_logger.error(ie.getMessage(), ie);
                }
            }
        } while (!m_phaser.isTerminated());
        m_logger.info("AvailabilityThread (run()) has been COMPLETED.");
    }

    private void RunAvailability(MetricRegistry metrics) throws IOException, MetaDataManagerException {

        m_logger.info("Starting AvailabilityLatency");

        IPropertiesManager producerPropertiesManager = new PropertiesManager<ProducerProperties>("producerProperties.json", ProducerProperties.class);
        IPropertiesManager metaDataPropertiesManager = new PropertiesManager<MetaDataManagerProperties>("metadatamanagerProperties.json", MetaDataManagerProperties.class);
        IMetaDataManager metaDataManager = new MetaDataManager(m_curatorFramework, metaDataPropertiesManager);
        MetaDataManagerProperties metaDataProperties = (MetaDataManagerProperties) metaDataPropertiesManager.getProperties();

        IProducer producer = new Producer(producerPropertiesManager, metaDataManager);

        IPropertiesManager appPropertiesManager = new PropertiesManager<AppProperties>("appProperties.json", AppProperties.class);
        AppProperties appProperties = (AppProperties) appPropertiesManager.getProperties();

        int gtmIPStatusTryCount = 0;
        int gtmIPStatusFailCount = 0;
        long startTime, endTime;
        int numMessages = 100;
        int windowSize = 0;
        int failureThreshold = 10;

        List<String> gtmList = new ArrayList<String>();
        String[] gtmArray = appProperties.kafkaGTMIP.split(",");
        if(!(gtmArray.length == 0)) {
            gtmList.addAll(Arrays.asList(gtmArray));
        }


        //This is full list of topics
        List<TopicMetadata> totalTopicMetadata = metaDataManager.getAllTopicPartition();

        List<kafka.javaapi.TopicMetadata> whiteListTopicMetadata = new ArrayList<TopicMetadata>();

        for (kafka.javaapi.TopicMetadata topic : totalTopicMetadata) {
            for (String whiteListTopic : metaDataProperties.canaryTestTopics)
                // java string compare while ignoring case
                if (topic.topic().equalsIgnoreCase(whiteListTopic)) {
                    whiteListTopicMetadata.add(topic);
                }
        }

        windowSize = gtmList.size() * numMessages * ((whiteListTopicMetadata.size() > 0) ? (gtmList.size() * whiteListTopicMetadata.size()) : 1);

        final SlidingWindowReservoir gtmAvailabilityLatencyWindow = new SlidingWindowReservoir(windowSize);
        Histogram histogramGTMAvailabilityLatency = new Histogram(gtmAvailabilityLatencyWindow);
        MetricNameEncoded gtmAvailabilityLatency = new MetricNameEncoded("KafkaGTMIP.Availability.Latency", "all");
        if (!metrics.getNames().contains(new Gson().toJson(gtmAvailabilityLatency))) {
            if (appProperties.sendGTMAvailabilityLatency && !gtmList.isEmpty())
                metrics.register(new Gson().toJson(gtmAvailabilityLatency), histogramGTMAvailabilityLatency);
        }

        m_logger.info("Starting KafkaGTM (VIP) prop check." + appProperties.reportKafkaGTMAvailability);

        for (String gtm : gtmList) {
            for (kafka.javaapi.TopicMetadata item : whiteListTopicMetadata) {
                m_logger.info("Posting to Topic: {} using GTM: {};", item.topic(), gtm);
                int tryCount = 0, failCount = 0;
                for (int i = 0; i < numMessages; i++) {
                    if (appProperties.reportKafkaGTMAvailability) {
                        startTime = System.currentTimeMillis();
                        try {
                            tryCount++;
                            producer.SendCanaryToKafkaIP(gtm, item.topic(), false);
                            endTime = System.currentTimeMillis();
                        } catch (Exception e) {
                            failCount++;
                            m_logger.error("GTM -- Error Writing to Topic: {} using GTM: {}; Exception: {}", item.topic(), gtm, e);
                            endTime = System.currentTimeMillis() + DEFAULT_ELAPSED_TIME;
                        }
                        histogramGTMAvailabilityLatency.update(endTime - startTime);
                    }
                    if (failCount >= 10) {
                        m_logger.error("GTM: {} has failed more than {} times. Giving up!!!.", gtm, failureThreshold);
                        tryCount = failCount = 100;
                        break;
                    }
                }
                gtmIPStatusTryCount = gtmIPStatusTryCount + tryCount;
                gtmIPStatusFailCount = gtmIPStatusFailCount + failCount;
            }
        }

        m_logger.info("done with VIP prop check.");
        if (appProperties.reportKafkaGTMAvailability && !gtmList.isEmpty()) {
            m_logger.info("About to report kafkaGTMIPAvailability-- TryCount:" + gtmIPStatusTryCount + " FailCount:" + gtmIPStatusFailCount);
            MetricNameEncoded kafkaGTMIPAvailability = new MetricNameEncoded("KafkaGTMIP.Availability", "all");
            if (!metrics.getNames().contains(new Gson().toJson(kafkaGTMIPAvailability))) {
                metrics.register(new Gson().toJson(kafkaGTMIPAvailability), new AvailabilityGauge(gtmIPStatusTryCount, gtmIPStatusTryCount - gtmIPStatusFailCount));
            }
        }

        ((MetaDataManager) metaDataManager).close();
        m_logger.info("Finished AvailabilityLatency");
    }
}