// Copyright (c) Keith D Gregory
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.kdgregory.logback.aws;

import java.net.URL;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder;

import com.kdgregory.logback.aws.testhelpers.MessageWriter;
import com.kdgregory.logging.aws.kinesis.KinesisLogWriter;
import com.kdgregory.logging.testhelpers.KinesisTestHelper;
import com.kdgregory.logging.testhelpers.CommonTestHelper;
import com.kdgregory.logging.testhelpers.KinesisTestHelper.RetrievedRecord;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import net.sf.kdgcommons.test.StringAsserts;


public class KinesisAppenderIntegrationTest
{
    // single client is shared by all tests
    private static AmazonKinesis kinesisClient;

    private KinesisTestHelper testHelper;

    // initialized here, and again by init() after the logging framework has been initialized
    private Logger localLogger = LoggerFactory.getLogger(getClass());

    // this is only set by smoketest
    private static boolean localFactoryUsed;

//----------------------------------------------------------------------------
//  Helpers
//----------------------------------------------------------------------------

    /**
     *  Factory method called by smoketest
     */
    public static AmazonKinesis createClient()
    {
        localFactoryUsed = true;
        return AmazonKinesisClientBuilder.defaultClient();
    }


    /**
     *  Loads the test-specific Logback configuration and resets the environment.
     */
    public void init(String testName) throws Exception
    {
        MDC.put("testName", testName);
        localLogger.info("starting");

        testHelper = new KinesisTestHelper(kinesisClient, testName);

        // this has to happen before the logger is initialized or we have a race condition
        testHelper.deleteStreamIfExists();

        String propertiesName = "KinesisAppenderIntegrationTest/" + testName + ".xml";
        URL config = ClassLoader.getSystemResource(propertiesName);
        assertNotNull("missing configuration: " + propertiesName, config);

        LoggerContext context = (LoggerContext)LoggerFactory.getILoggerFactory();
        context.reset();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(config);

        localLogger = LoggerFactory.getLogger(getClass());
    }

//----------------------------------------------------------------------------
//  JUnit Scaffolding
//----------------------------------------------------------------------------

    @BeforeClass
    public static void beforeClass()
    {
        kinesisClient = AmazonKinesisClientBuilder.defaultClient();
    }


    @Before
    public void setUp()
    {
        // this won't be updated by most tests
        localFactoryUsed = false;
    }


    @After
    public void tearDown()
    {
        localLogger.info("finished");
        MDC.clear();
    }

//----------------------------------------------------------------------------
//  Tests
//----------------------------------------------------------------------------

    @Test
    public void smoketest() throws Exception
    {
        final int numMessages = 1001;

        init("smoketest");

        ch.qos.logback.classic.Logger testLogger = (ch.qos.logback.classic.Logger)LoggerFactory.getLogger("TestLogger");
        KinesisAppender<ILoggingEvent> appender = (KinesisAppender<ILoggingEvent>)testLogger.getAppender("test");

        (new MessageWriter(testLogger, numMessages)).run();

        localLogger.info("reading messages");
        List<RetrievedRecord> messages = testHelper.retrieveAllMessages(numMessages);

        testHelper.assertMessages(messages, 1, numMessages);
        testHelper.assertPartitionKeys(messages, numMessages, "test");

        testHelper.assertShardCount(3);
        testHelper.assertRetentionPeriod(48);

        assertTrue("client factory should have been invoked", localFactoryUsed);

        testHelper.assertStats(appender.getAppenderStatistics(), numMessages);
    }


    @Test
    public void testMultipleThreadsSingleAppender() throws Exception
    {
        int messagesPerThread = 500;

        init("testMultipleThreadsSingleAppender");

        ch.qos.logback.classic.Logger testLogger = (ch.qos.logback.classic.Logger)LoggerFactory.getLogger("TestLogger");

        MessageWriter[] writers = new MessageWriter[]
        {
            new MessageWriter(testLogger, messagesPerThread),
            new MessageWriter(testLogger, messagesPerThread),
            new MessageWriter(testLogger, messagesPerThread),
            new MessageWriter(testLogger, messagesPerThread),
            new MessageWriter(testLogger, messagesPerThread)
        };

        MessageWriter.runOnThreads(writers);
        int expectedMessages = writers.length * messagesPerThread;

        localLogger.info("reading messages");
        List<RetrievedRecord> messages = testHelper.retrieveAllMessages(expectedMessages);

        testHelper.assertMessages(messages, writers.length, messagesPerThread);
        testHelper.assertPartitionKeys(messages, messagesPerThread * writers.length, "test");

        Map<String,List<RetrievedRecord>> groupedByShard = testHelper.groupByShard(messages);
        assertEquals("all messages written to same shard", 1, groupedByShard.size());

        testHelper.assertShardCount(2);
        testHelper.assertRetentionPeriod(24);

        assertFalse("client factory should not have been invoked", localFactoryUsed);
    }


    @Test
    public void testMultipleThreadsMultipleAppendersDistinctPartitions() throws Exception
    {
        int messagesPerThread = 500;

        init("testMultipleThreadsMultipleAppendersDistinctPartitions");

        Logger testLogger1 = (ch.qos.logback.classic.Logger)LoggerFactory.getLogger("TestLogger1");
        Logger testLogger2 = (ch.qos.logback.classic.Logger)LoggerFactory.getLogger("TestLogger2");
        Logger testLogger3 = (ch.qos.logback.classic.Logger)LoggerFactory.getLogger("TestLogger3");

        MessageWriter[] writers = new MessageWriter[]
        {
            new MessageWriter(testLogger1, messagesPerThread),
            new MessageWriter(testLogger2, messagesPerThread),
            new MessageWriter(testLogger3, messagesPerThread),
            new MessageWriter(testLogger1, messagesPerThread),
            new MessageWriter(testLogger2, messagesPerThread),
            new MessageWriter(testLogger3, messagesPerThread)
        };

        MessageWriter.runOnThreads(writers);
        int expectedMessages = writers.length * messagesPerThread;

        localLogger.info("reading messages");
        List<RetrievedRecord> messages = testHelper.retrieveAllMessages(expectedMessages);

        testHelper.assertMessages(messages, writers.length, messagesPerThread);
        testHelper.assertPartitionKeys(messages, messagesPerThread * 2, "test1", "test2", "test3");

        Map<String,List<RetrievedRecord>> groupedByShard = testHelper.groupByShard(messages);
        assertEquals("messages written to multiple shards", 2, groupedByShard.size());

        testHelper.assertShardCount(2);
        testHelper.assertRetentionPeriod(24);

        assertFalse("client factory should not have been invoked", localFactoryUsed);
    }


    @Test
    public void testRandomPartitionKeys() throws Exception
    {
        final int numMessages = 250;

        init("testRandomPartitionKeys");

        ch.qos.logback.classic.Logger testLogger = (ch.qos.logback.classic.Logger)LoggerFactory.getLogger("TestLogger");

        (new MessageWriter(testLogger, numMessages)).run();

        localLogger.info("reading messages");
        List<RetrievedRecord> messages = testHelper.retrieveAllMessages(numMessages);

        testHelper.assertShardCount(2);
        testHelper.assertMessages(messages, 1, numMessages);
        testHelper.assertRandomPartitionKeys(messages, numMessages);
    }


    @Test
    public void testFailsIfNoStreamPresent() throws Exception
    {
        final String streamName = "AppenderIntegrationTest-testFailsIfNoStreamPresent";
        final int numMessages = 1001;

        init("testFailsIfNoStreamPresent");

        ch.qos.logback.classic.Logger testLogger = (ch.qos.logback.classic.Logger)LoggerFactory.getLogger("TestLogger");
        KinesisAppender<ILoggingEvent> appender = (KinesisAppender<ILoggingEvent>)testLogger.getAppender("test");

        (new MessageWriter(testLogger, numMessages)).run();

        localLogger.info("waiting for writer initialization to finish");
        CommonTestHelper.waitUntilWriterInitialized(appender, KinesisLogWriter.class, 10000);
        String initializationMessage = appender.getAppenderStatistics().getLastErrorMessage();

        StringAsserts.assertRegex(
            "initialization message did not indicate missing stream (was \"" + initializationMessage + "\")",
            ".*stream.*" + streamName + ".* not exist .*",
            initializationMessage);
    }


    @Test
    public void testAlternateRegion() throws Exception
    {
        final int numMessages = 1001;

        init("testAlternateRegion");

        // BEWARE: my default region is us-east-1, so I use us-east-2 as the alternate
        //         if that is your default, then the test will fail
        AmazonKinesis altClient = AmazonKinesisClientBuilder.standard().withRegion("us-east-2").build();
        KinesisTestHelper altTestHelper = new KinesisTestHelper(altClient, "testAlternateRegion");

        altTestHelper.deleteStreamIfExists();

        ch.qos.logback.classic.Logger testLogger = (ch.qos.logback.classic.Logger)LoggerFactory.getLogger("TestLogger");

        localLogger.info("writing messages");
        (new MessageWriter(testLogger, numMessages)).run();

        localLogger.info("reading messages");
        List<RetrievedRecord> messages = altTestHelper.retrieveAllMessages(numMessages);

        testHelper.assertMessages(messages, 1, numMessages);
        assertNull("stream does not exist in default region", testHelper.describeStream());
    }
}