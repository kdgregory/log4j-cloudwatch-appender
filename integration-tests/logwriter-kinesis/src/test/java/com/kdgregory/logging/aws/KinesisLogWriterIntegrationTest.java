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

package com.kdgregory.logging.aws;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import net.sf.kdgcommons.lang.ClassUtil;
import net.sf.kdgcommons.lang.StringUtil;

import org.slf4j.Logger;

import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder;

import com.kdgregory.logging.aws.internal.facade.KinesisFacade;
import com.kdgregory.logging.aws.kinesis.KinesisConstants;
import com.kdgregory.logging.aws.kinesis.KinesisLogWriter;
import com.kdgregory.logging.aws.kinesis.KinesisWriterConfig;
import com.kdgregory.logging.aws.kinesis.KinesisWriterFactory;
import com.kdgregory.logging.aws.kinesis.KinesisWriterStatistics;
import com.kdgregory.logging.common.LogMessage;
import com.kdgregory.logging.common.factories.DefaultThreadFactory;
import com.kdgregory.logging.common.util.DiscardAction;
import com.kdgregory.logging.testhelpers.KinesisTestHelper;
import com.kdgregory.logging.testhelpers.KinesisTestHelper.RetrievedRecord;
import com.kdgregory.logging.testhelpers.TestableInternalLogger;


public class KinesisLogWriterIntegrationTest
{
    // single "helper" client that's shared by all tests
    private static AmazonKinesis helperClient;

    // this one is created by the "alternate region" tests
    private AmazonKinesis altClient;

    // this client is used in testFactoryMethod(), should be null everywhere else
    private static AmazonKinesis factoryClient;

    // this is for logging within the test
    private Logger localLogger = LoggerFactory.getLogger(getClass());

    // this will be configued prior to ini()
    private KinesisWriterConfig config = new KinesisWriterConfig()
                                         .setPartitionKey("{random}")
                                         .setAutoCreate(true)
                                         .setShardCount(1)
                                         .setBatchDelay(250)
                                         .setDiscardThreshold(10000)
                                         .setDiscardAction(DiscardAction.oldest);

    // these are all assigned by init()
    private KinesisTestHelper testHelper;
    private KinesisWriterStatistics stats;
    private TestableInternalLogger internalLogger;
    private KinesisWriterFactory factory;
    private KinesisLogWriter writer;

//----------------------------------------------------------------------------
//  Helpers
//----------------------------------------------------------------------------

    private void init(String testName, AmazonKinesis client)
    throws Exception
    {
        MDC.put("testName", testName);
        localLogger.info("starting");

        testHelper = new KinesisTestHelper(client, testName);

        testHelper.deleteStreamIfExists();

        config.setStreamName(testHelper.getStreamName());

        stats = new KinesisWriterStatistics();
        internalLogger = new TestableInternalLogger();
        factory = new KinesisWriterFactory();
        writer = (KinesisLogWriter)factory.newLogWriter(config, stats, internalLogger);

        new DefaultThreadFactory("test").startWriterThread(writer, null);
    }


    private class MessageWriter
    extends com.kdgregory.logging.testhelpers.MessageWriter
    {
        public MessageWriter(int numMessages)
        {
            super(numMessages);
        }

        @Override
        protected void writeLogMessage(String message)
        {
            writer.addMessage(new LogMessage(System.currentTimeMillis(), message));
        }
    }


    public static AmazonKinesis staticClientFactory()
    {
        factoryClient = AmazonKinesisClientBuilder.defaultClient();
        return factoryClient;
    }

//----------------------------------------------------------------------------
//  JUnit Scaffolding
//----------------------------------------------------------------------------

    @BeforeClass
    public static void beforeClass()
    {
        helperClient = AmazonKinesisClientBuilder.defaultClient();
    }


    @After
    public void tearDown()
    {
        if (writer != null)
        {
            writer.stop();
        }

        if (altClient != null)
        {
            altClient.shutdown();
        }

        if (factoryClient != null)
        {
            factoryClient.shutdown();
            factoryClient = null;
        }

        localLogger.info("finished");
        MDC.clear();
    }


    @AfterClass
    public static void afterClass()
    {
        helperClient.shutdown();
    }

//----------------------------------------------------------------------------
//  Tests
//----------------------------------------------------------------------------

    @Test
    public void smoketest() throws Exception
    {
        final int numMessages = 1001;

        init("logwriter-smoketest", helperClient);

        new MessageWriter(numMessages).run();

        List<RetrievedRecord> records = testHelper.retrieveAllMessages(numMessages);
        testHelper.assertMessages(records, 1, numMessages);
        testHelper.assertRandomPartitionKeys(records, numMessages);

        testHelper.deleteStreamIfExists();
    }


    @Test
    public void testFactoryMethod() throws Exception
    {
        final int numMessages = 1001;

        config.setClientFactoryMethod(getClass().getName() + ".staticClientFactory");
        init("testFactoryMethod", helperClient);

        new MessageWriter(numMessages).run();

        List<RetrievedRecord> records = testHelper.retrieveAllMessages(numMessages);
        testHelper.assertMessages(records, 1, numMessages);

        assertNotNull("factory method was called", factoryClient);

        // this is getting a little obsessive...
        Object facade = ClassUtil.getFieldValue(writer, "facade", KinesisFacade.class);
        Object client = ClassUtil.getFieldValue(facade, "client", AmazonKinesis.class);
        assertSame("factory-created client used by writer", factoryClient, client);

        testHelper.deleteStreamIfExists();
    }


    @Test
    public void testAlternateRegion() throws Exception
    {
        final int numMessages = 1001;

        altClient = AmazonKinesisClientBuilder.standard().withRegion(Regions.US_WEST_1).build();

        config.setClientRegion("us-west-1");
        init("logwriter-testAlternateRegion", altClient);

        new MessageWriter(numMessages).run();

        List<RetrievedRecord> records = testHelper.retrieveAllMessages(numMessages);
        testHelper.assertMessages(records, 1, numMessages);
        testHelper.assertRandomPartitionKeys(records, numMessages);

        assertNull("stream does not exist in default region",
                   (new KinesisTestHelper(helperClient, "logwriter-testAlternateRegion")).describeStream());

        testHelper.deleteStreamIfExists();
    }


    @Test
    public void testAlternateEndpoint() throws Exception
    {
        final int numMessages = 1001;

        altClient = AmazonKinesisClientBuilder.standard()
                    .withEndpointConfiguration(
                        new EndpointConfiguration("kinesis.us-east-2.amazonaws.com", null))
                    .build();

        config.setClientEndpoint("kinesis.us-east-2.amazonaws.com");
        init("logwriter-testAlternateEndpoint", altClient);

        new MessageWriter(numMessages).run();

        List<RetrievedRecord> records = testHelper.retrieveAllMessages(numMessages);
        testHelper.assertMessages(records, 1, numMessages);
        testHelper.assertRandomPartitionKeys(records, numMessages);

        assertNull("stream does not exist in default region",
                   (new KinesisTestHelper(helperClient, "logwriter-testAlternateEndpoint")).describeStream());

        testHelper.deleteStreamIfExists();
    }


    @Test
    public void testOversizeMessageTruncation() throws Exception
    {
        final int numMessages = 100;

        // this test verifies that what I think is the maximum message size is acceptable to Kinesis
        final int maxMessageSize = KinesisConstants.MAX_MESSAGE_BYTES - 7;

        final String expectedMessage = StringUtil.repeat('X', maxMessageSize - 1) + "Y";
        final String messageToWrite = expectedMessage + "Z";

        config.setPartitionKey("abcdefg");
        config.setTruncateOversizeMessages(true);
        init("testOversizeMessageTruncation", helperClient);

        new MessageWriter(numMessages)
        {
            @Override
            protected void writeLogMessage(String ignored)
            {
                super.writeLogMessage(messageToWrite);
            }
        }.run();

        List<RetrievedRecord> records = testHelper.retrieveAllMessages(numMessages);
        Set<String> messages = new HashSet<>();
        for (RetrievedRecord record : records)
        {
            messages.add(record.message);
        }

        assertEquals("all messages should be truncated to same value", 1, messages.size());
        assertEquals("message actually written",                       expectedMessage, messages.iterator().next());

        testHelper.deleteStreamIfExists();
    }
}
