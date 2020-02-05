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

package com.kdgregory.logging.test;

import org.slf4j.LoggerFactory;

import net.sf.kdgcommons.lang.ClassUtil;
import net.sf.kdgcommons.test.StringAsserts;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

import org.slf4j.Logger;

import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder;

import com.kdgregory.logging.aws.kinesis.KinesisLogWriter;
import com.kdgregory.logging.aws.kinesis.KinesisWriterStatistics;
import com.kdgregory.logging.testhelpers.KinesisTestHelper;
import com.kdgregory.logging.testhelpers.MessageWriter;
import com.kdgregory.logging.testhelpers.KinesisTestHelper.RetrievedRecord;


public abstract class AbstractKinesisAppenderIntegrationTest
{
    // this client is shared by all tests
    protected static AmazonKinesis helperClient;

    // this one is used solely by the static factory test
    protected static AmazonKinesis factoryClient;

    // initialized here, and again by init() after the logging framework has been initialized
    protected Logger localLogger = LoggerFactory.getLogger(getClass());

    protected KinesisTestHelper testHelper;


//----------------------------------------------------------------------------
//  Helpers
//----------------------------------------------------------------------------

    /**
     *  Subclasses must implement this to give the common tests access to the
     *  logger components.
     */
    public interface LoggerAccessor
    {
        MessageWriter createMessageWriter(int numMessages);

        KinesisLogWriter getWriter() throws Exception;
        KinesisWriterStatistics getStats();

        boolean supportsConfigurationChanges();

        String waitUntilWriterInitialized() throws Exception;
    }


    /**
     *  Called by writer in testFactoryMethod().
     */
    public static AmazonKinesis createClient()
    {
        factoryClient = AmazonKinesisClientBuilder.defaultClient();
        return factoryClient;
    }

//----------------------------------------------------------------------------
//  JUnit Scaffolding
//----------------------------------------------------------------------------

    protected static void beforeClass()
    {
        helperClient = AmazonKinesisClientBuilder.defaultClient();
    }


    protected void tearDown()
    {
        if (factoryClient != null)
        {
            factoryClient.shutdown();
            factoryClient = null;
        }
        localLogger.info("finished");
    }

//----------------------------------------------------------------------------
//  Test Bodies
//----------------------------------------------------------------------------

    protected void smoketest(LoggerAccessor accessor)
    throws Exception
    {
        final int numMessages = 1001;

        accessor.createMessageWriter(numMessages).run();

        localLogger.info("reading messages");
        List<RetrievedRecord> messages = testHelper.retrieveAllMessages(numMessages);

        testHelper.assertMessages(messages, 1, numMessages);
        testHelper.assertPartitionKeys(messages, numMessages, "test");

        testHelper.assertShardCount(3);
        testHelper.assertRetentionPeriod(48);

        testHelper.assertStats(accessor.getStats(), numMessages);

        assertNull("factory should not have been used to create client", factoryClient);
    }


    protected void testMultipleThreadsSingleAppender(LoggerAccessor accessor)
    throws Exception
    {
        int messagesPerThread = 500;

        MessageWriter[] writers = new MessageWriter[]
        {
            accessor.createMessageWriter(messagesPerThread),
            accessor.createMessageWriter(messagesPerThread),
            accessor.createMessageWriter(messagesPerThread),
            accessor.createMessageWriter(messagesPerThread),
            accessor.createMessageWriter(messagesPerThread)
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
    }


    protected void testMultipleThreadsMultipleAppendersDistinctPartitions(LoggerAccessor... accessors)
    throws Exception
    {
        int messagesPerThread = 500;

        MessageWriter[] writers = new MessageWriter[]
        {
            accessors[0].createMessageWriter(messagesPerThread),
            accessors[1].createMessageWriter(messagesPerThread),
            accessors[2].createMessageWriter(messagesPerThread),
            accessors[0].createMessageWriter(messagesPerThread),
            accessors[1].createMessageWriter(messagesPerThread),
            accessors[2].createMessageWriter(messagesPerThread),
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
    }


    protected void testRandomPartitionKeys(LoggerAccessor accessor)
    throws Exception
    {
        final int numMessages = 250;

        accessor.createMessageWriter(numMessages).run();

        localLogger.info("reading messages");
        List<RetrievedRecord> messages = testHelper.retrieveAllMessages(numMessages);

        testHelper.assertShardCount(2);
        testHelper.assertMessages(messages, 1, numMessages);
        testHelper.assertRandomPartitionKeys(messages, numMessages);
    }


    protected void testFailsIfNoStreamPresent(LoggerAccessor accessor)
    throws Exception
    {
        final String streamName = "AppenderIntegrationTest-testFailsIfNoStreamPresent";
        final int numMessages = 1001;

        accessor.createMessageWriter(numMessages).run();

        localLogger.info("waiting for writer initialization to finish");
        String initializationMessage = accessor.waitUntilWriterInitialized();

        StringAsserts.assertRegex(
            "initialization message did not indicate missing stream (was \"" + initializationMessage + "\")",
            ".*stream.*" + streamName + ".* not exist .*",
            initializationMessage);
    }


    protected void testFactoryMethod(LoggerAccessor accessor)
    throws Exception
    {
        final int numMessages = 1001;

        localLogger.info("writing messages");
        accessor.createMessageWriter(numMessages).run();

        localLogger.info("reading messages");
        List<RetrievedRecord> messages = testHelper.retrieveAllMessages(numMessages);

        testHelper.assertMessages(messages, 1, numMessages);
        testHelper.assertPartitionKeys(messages, numMessages, "test");

        testHelper.assertStats(accessor.getStats(), numMessages);

        KinesisLogWriter writer = accessor.getWriter();
        AmazonKinesis actualClient = ClassUtil.getFieldValue(writer, "client", AmazonKinesis.class);
        assertSame("factory should have been used to create client", factoryClient, actualClient);
    }


    protected void testAlternateRegion(LoggerAccessor accessor, KinesisTestHelper altTestHelper)
    throws Exception
    {
        final int numMessages = 1001;

        localLogger.info("writing messages");
        accessor.createMessageWriter(numMessages).run();

        localLogger.info("reading messages");
        List<RetrievedRecord> messages = altTestHelper.retrieveAllMessages(numMessages);

        testHelper.assertMessages(messages, 1, numMessages);
        assertNull("stream does not exist in default region", testHelper.describeStream());
    }
}
