// Copyright (c) Keith D Gregory, all rights reserved
package com.kdgregory.log4j.aws;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import org.junit.Test;
import static org.junit.Assert.*;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

import net.sf.kdgcommons.collections.DefaultMap;

import com.amazonaws.services.kinesis.AmazonKinesis;
import com.amazonaws.services.kinesis.AmazonKinesisClientBuilder;
import com.amazonaws.services.kinesis.model.*;
import com.amazonaws.util.BinaryUtils;

import com.kdgregory.log4j.aws.testhelpers.MessageWriter;


public class KinesisAppenderIntegrationTest
{
    // CHANGE THESE IF YOU CHANGE THE CONFIG
    private final static String STREAM_NAME     = "AppenderIntegratonTest";
    private final static int    BATCH_DELAY     = 3000;
    private final static int    NUM_SHARDS      = 2;

    private Logger mainLogger;
    private AmazonKinesis client;


    public void setUp(String propertiesName) throws Exception
    {
        URL config = ClassLoader.getSystemResource(propertiesName);
        PropertyConfigurator.configure(config);

        mainLogger = Logger.getLogger(getClass());

        client = AmazonKinesisClientBuilder.defaultClient();
        deleteStreamIfExists();
    }


//----------------------------------------------------------------------------
//  Tests
//----------------------------------------------------------------------------

    @Test
    public void smoketest() throws Exception
    {
        setUp("KinesisAppenderIntegrationTest-smoketest.properties");
        mainLogger.info("smoketest: starting");

        final int numMessages = 1001;

        Logger testLogger = Logger.getLogger("TestLogger");
        (new MessageWriter(testLogger, numMessages)).run();

        mainLogger.info("smoketest: waiting for stream to become ready");
        waitForStreamToBeReady();

        assertEquals("shard count", NUM_SHARDS, describeStream().getShards().size());

        mainLogger.info("smoketest: reading messages");
        List<RetrievedRecord> messages = retrieveAllMessages(numMessages);

        assertMessages(messages, 1, numMessages, "test");

        mainLogger.info("smoketest: finished");
    }


    @Test
    public void testMultipleThreadsSingleAppender() throws Exception
    {
        setUp("KinesisAppenderIntegrationTest-testMultipleThreadsSingleAppender.properties");
        mainLogger.info("multi-thread/single-appender: starting");

        int messagesPerThread = 500;
        Logger testLogger = Logger.getLogger("TestLogger");

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

        mainLogger.info("multi-thread/single-appender: waiting for stream to become ready");
        waitForStreamToBeReady();

        assertEquals("shard count", NUM_SHARDS, describeStream().getShards().size());

        mainLogger.info("multi-thread/single-appender: reading messages");
        List<RetrievedRecord> messages = retrieveAllMessages(expectedMessages);

        assertMessages(messages, writers.length, messagesPerThread * writers.length, "test");

        Map<String,List<RetrievedRecord>> groupedByShard = groupByShard(messages);
        assertEquals("all messages written to same shard", 1, groupedByShard.size());

        mainLogger.info("multi-thread/single-appender: finished");
    }


    @Test
    public void testMultipleThreadsMultipleAppendersDistinctPartitions() throws Exception
    {
        setUp("KinesisAppenderIntegrationTest-testMultipleThreadsMultipleAppendersMultiplePartitions.properties");
        mainLogger.info("multi-thread/multi-appender: starting");

        int messagesPerThread = 500;
        Logger testLogger1 = Logger.getLogger("TestLogger1");
        Logger testLogger2 = Logger.getLogger("TestLogger2");
        Logger testLogger3 = Logger.getLogger("TestLogger3");

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

        mainLogger.info("multi-thread/multi-appender: waiting for stream to become ready");
        waitForStreamToBeReady();

        assertEquals("shard count", NUM_SHARDS, describeStream().getShards().size());

        mainLogger.info("multi-thread/multi-appender: reading messages");
        List<RetrievedRecord> messages = retrieveAllMessages(expectedMessages);

        assertMessages(messages, writers.length, messagesPerThread * 2, "test1", "test2", "test3");

        Map<String,List<RetrievedRecord>> groupedByShard = groupByShard(messages);
        assertEquals("messages written to multiple shards", NUM_SHARDS, groupedByShard.size());

        mainLogger.info("multi-thread/multi-appender: finished");
    }


//----------------------------------------------------------------------------
//  Helpers
//----------------------------------------------------------------------------

    /**
     *  Returns the stream description, null if the stream doesn't exist.
     *  @return
     */
    private StreamDescription describeStream()
    {
        try
        {
            DescribeStreamRequest describeRequest = new DescribeStreamRequest().withStreamName(STREAM_NAME);
            DescribeStreamResult describeReponse  = client.describeStream(describeRequest);
            return describeReponse.getStreamDescription();
        }
        catch (ResourceNotFoundException ignored)
        {
            return null;
        }
    }


    private void deleteStreamIfExists() throws Exception
    {
        client.deleteStream(new DeleteStreamRequest().withStreamName(STREAM_NAME));
        mainLogger.info("deleted stream; waiting for it to be gone");
        while (describeStream() != null)
        {
            Thread.sleep(1000);
        }
    }


    private StreamDescription waitForStreamToBeReady() throws Exception
    {
        for (int ii = 0 ; ii < 60 ; ii++)
        {
            Thread.sleep(1000);
            StreamDescription desc = describeStream();
            if ((desc != null) && (StreamStatus.ACTIVE.toString().equals(desc.getStreamStatus())))
            {
                return desc;
            }
        }
        throw new RuntimeException("stream wasn't ready within 60 seconds");
    }


    /**
     *  Attempts to retrieve messages from the stream for up to 60 seconds, reading each
     *  shard once per second. Once the expected number of records has been read, will
     *  continue to read for an additional several seconds to pick up unexpected records.
     *  <p>
     *  Returns the records grouped by shard, so that multi-shard tests can verify that
     *  all shards were written.
     */
    List<RetrievedRecord> retrieveAllMessages(int expectedRecords)
    throws Exception
    {
        List<RetrievedRecord> result = new ArrayList<RetrievedRecord>();

        // this sleep gives all writers a chance to do their work
        Thread.sleep(BATCH_DELAY);

        Map<String,String> shardItxs = getInitialShardIterators();
        List<String> shardIds = new ArrayList<String>(shardItxs.keySet());

        int readAttempts = 60;
        while (readAttempts > 0)
        {
            for (String shardId : shardIds)
            {
                String shardItx = shardItxs.get(shardId);
                String newShardItx = readMessagesFromShard(shardId, shardItx, result);
                shardItxs.put(shardId, newShardItx);
            }

            // short-circuit if we've read all expected records
            readAttempts = (result.size() >= expectedRecords) && (readAttempts > 5)
                         ? 5
                         : readAttempts - 1;

            Thread.sleep(1000);
        }

        return result;
    }


    private Map<String,String> getInitialShardIterators()
    {
        Map<String,String> result = new HashMap<String,String>();
        for (Shard shard : describeStream().getShards())
        {
            String shardId = shard.getShardId();
            GetShardIteratorRequest shardItxRequest = new GetShardIteratorRequest()
                                                      .withStreamName(STREAM_NAME)
                                                      .withShardIteratorType(ShardIteratorType.TRIM_HORIZON)
                                                      .withShardId(shardId);
            GetShardIteratorResult shardItxResponse = client.getShardIterator(shardItxRequest);
            result.put(shardId, shardItxResponse.getShardIterator());
        }
        return result;
    }


    private String readMessagesFromShard(String shardId, String shardItx, List<RetrievedRecord> messages)
    throws Exception
    {
        GetRecordsRequest recordsRequest = new GetRecordsRequest().withShardIterator(shardItx);
        GetRecordsResult recordsResponse = client.getRecords(recordsRequest);
        for (Record record : recordsResponse.getRecords())
        {
            messages.add(new RetrievedRecord(shardId, record));
        }
        return recordsResponse.getNextShardIterator();
    }


    private void assertMessages(List<RetrievedRecord> messages, int expectedThreadCount, int expectedMessagesPerPartitionKey, String... expectedPartitionKeys)
    throws Exception
    {
        assertEquals("overall message count",
                     expectedMessagesPerPartitionKey * expectedPartitionKeys.length,
                     messages.size());

        Set<Integer> threadIds = new HashSet<Integer>();
        Map<Integer,Integer> countsByMessageNumber = new DefaultMap<Integer,Integer>(new HashMap<Integer,Integer>(), Integer.valueOf(0));
        Map<String,Integer> countsByPartitionKey = new DefaultMap<String,Integer>(new HashMap<String,Integer>(), Integer.valueOf(0));

        for (RetrievedRecord message : messages)
        {
            Matcher matcher = MessageWriter.PATTERN.matcher(message.message);
            assertTrue("message matches pattern: " + message, matcher.matches());

            Integer threadNum = MessageWriter.getThreadId(matcher);
            threadIds.add(threadNum);

            Integer messageNum = MessageWriter.getMessageNumber(matcher);
            int oldMessageCount = countsByMessageNumber.get(messageNum);
            countsByMessageNumber.put(messageNum, oldMessageCount + 1);

            int oldPartitionCount = countsByPartitionKey.get(message.partitionKey);
            countsByPartitionKey.put(message.partitionKey, oldPartitionCount + 1);
        }

        assertEquals("number of threads that were writing", expectedThreadCount, threadIds.size());

        for (Integer messageNum : countsByMessageNumber.keySet())
        {
            assertEquals("number of instance of message " + messageNum,
                         Integer.valueOf(expectedThreadCount),
                         countsByMessageNumber.get(messageNum));
        }

        for (String partitionKey : expectedPartitionKeys)
        {
            assertEquals("messages for partition key \"" + partitionKey + "\"",
                         expectedMessagesPerPartitionKey,
                         countsByPartitionKey.get(partitionKey).intValue());
        }
    }


    private Map<String,List<RetrievedRecord>> groupByShard(List<RetrievedRecord> messages)
    {
        Map<String,List<RetrievedRecord>> result = new HashMap<String,List<RetrievedRecord>>();
        for (RetrievedRecord record : messages)
        {
            List<RetrievedRecord> byShard = result.get(record.shardId);
            if (byShard == null)
            {
                byShard = new ArrayList<KinesisAppenderIntegrationTest.RetrievedRecord>();
                result.put(record.shardId, byShard);
            }
            byShard.add(record);
        }
        return result;
    }


    /**
     *  Holder for a retrieved record. Extracts the record data and partition key,
     *  and adds the shard ID (passed in).
     */
    private static class RetrievedRecord
    {
        public String shardId;
        public String partitionKey;
        public String message;

        public RetrievedRecord(String shardId, Record record)
        throws Exception
        {
            this.shardId = shardId;
            this.partitionKey = record.getPartitionKey();
            this.message = new String(BinaryUtils.copyAllBytesFrom(record.getData()), "UTF-8").trim();
        }
    }
}
