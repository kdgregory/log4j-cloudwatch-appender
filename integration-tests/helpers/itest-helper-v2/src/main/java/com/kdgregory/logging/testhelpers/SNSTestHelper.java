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

package com.kdgregory.logging.testhelpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.*;
import static net.sf.kdgcommons.test.StringAsserts.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sf.kdgcommons.lang.StringUtil;

import com.fasterxml.jackson.databind.ObjectMapper;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.*;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;


/**
 *  A collection of utility methods to support integration tests. This is an
 *  instantiable class, internally tracking the AWS clients and test resources.
 *  <p>
 *  Not intended for production use outside of this library.
 */
public class SNSTestHelper
{
    // the top-level keys in an SQS message body
    public final static String SQS_KEY_MESSAGE_TYPE        = "Type";
    public final static String SQS_KEY_MESSAGE_ID          = "MessageId";
    public final static String SQS_KEY_TIMESTAMP           = "Timestamp";
    public final static String SQS_KEY_TOPIC_ARN           = "TopicArn";
    public final static String SQS_KEY_SUBJECT             = "Subject";
    public final static String SQS_KEY_MESSAGE             = "Message";
    public final static String SQS_KEY_SIGNATURE           = "Signature";
    public final static String SQS_KEY_SIGNATURE_VERSION   = "SignatureVersion";
    public final static String SQS_KEY_SIGNATURE_CERT      = "SigningCertURL";
    public final static String SQS_KEY_UNSUBSCRIBE_URL     = "UnsubscribeURL";

    private Logger localLogger = LoggerFactory.getLogger(getClass());

    private SnsClient snsClient;
    private SqsClient sqsClient;

    private String runId;           // set in ctor, used to generate unique names
    private String resourceName;    // base name for topic and queue

    private String topicArn;        // these are set by createTopicAndQueue()
    private String queueArn;
    private String queueUrl;

    private ObjectMapper objectMapper = new ObjectMapper();


    /**
     *  Default constructor.
     */
    public SNSTestHelper(SnsClient snsClient, SqsClient sqsClient)
    {
        this.snsClient = snsClient;
        this.sqsClient = sqsClient;

        runId = String.valueOf(System.currentTimeMillis());
        resourceName = "SNSAppenderIntegrationTest-" + runId;

        System.setProperty("SNSAppenderIntegrationTest.resourceName", resourceName);
    }


    /**
     *  Constructor for cross-region tests, which copies run ID and resource name
     *  from another instance.
     */
    public SNSTestHelper(SNSTestHelper that, SnsClient snsClient, SqsClient sqsClient)
    {
        this.snsClient = snsClient;
        this.sqsClient = sqsClient;

        this.runId = that.runId;
        this.resourceName = that.resourceName;
    }


    /**
     *  Returns the generated topic name.
     */
    public String getTopicName()
    {
        return resourceName;
    }


    /**
     *  Returns the topic's ARN. This will be set by {@link #createTopicAndQueue} or
     *  {@link #lookupTopic}.
     */
    public String getTopicARN()
    {
        return topicArn;
    }


    /**
     *  Creates a topic and queue, and subscribes the queue to the topic. Returns
     *  the topic ARN.
     */
    public String createTopicAndQueue()
    throws Exception
    {
        createTopic();
        createQueue();
        subscribeQueueToTopic();
        return topicArn;
    }


    /**
     *  Deletes the topic and queue created by {@link #createTopicAndQueue}.
     */
    public void deleteTopicAndQueue()
    throws Exception
    {
        deleteTopic();
        deleteQueue();
    }

    /**
     *  Loops through all topic names, looking for the one that matches the provided name.
     *  Stores and returns the topic's ARN, null if unable to find it.
     */
    public String lookupTopic()
    {
        localLogger.debug("looking for topic {}", resourceName);

        for (Topic topic : snsClient.listTopicsPaginator().topics())
        {
            if (topic.topicArn().endsWith(resourceName))
            {
                topicArn = topic.topicArn();
                return topicArn;
            }
        }

        return null;
    }


    /**
     *  Attempts to read the expected number of messages from the queue, extracting
     *  the message message body (which is a JSON blob).
     */
    public List<Map<String,Object>> retrieveMessages(int expectedMessageCount)
    throws Exception
    {
        localLogger.debug("retrieving messages from queue {}", queueUrl);

        List<Map<String,Object>> result = new ArrayList<>();
        int emptyBatchCount = 0;
        while ((expectedMessageCount > 0) && (emptyBatchCount < 3))
        {
            ReceiveMessageRequest retrieveRequest = ReceiveMessageRequest.builder()
                                                    .queueUrl(queueUrl)
                                                    .waitTimeSeconds(5)
                                                    .build();
            ReceiveMessageResponse retrieveResponse = sqsClient.receiveMessage(retrieveRequest);
            if (retrieveResponse.messages().isEmpty())
            {
                emptyBatchCount++;
            }
            else
            {
                for (Message message : retrieveResponse.messages())
                {
                    String messageBody = message.body();
                    Map<String,Object> converted = objectMapper.readValue(messageBody, Map.class);
                    result.add(converted);
                    DeleteMessageRequest deleteRequest = DeleteMessageRequest.builder()
                                                         .queueUrl(queueUrl)
                                                         .receiptHandle(message.receiptHandle())
                                                         .build();
                    sqsClient.deleteMessage(deleteRequest);
                }
            }
        }
        return result;
    }


    /**
     *  Performs assertions on the content of each message. In general, these should
     *  fail on the first bad message.
     */
    public void assertMessageContent(List<Map<String,Object>> messages, String... expectedSubjects0)
    {
        Set<String> expectedSubjects = new TreeSet<String>(Arrays.asList(expectedSubjects0));
        Set<String> actualSubjects = new TreeSet<String>();

        for (Map<String,Object> message : messages)
        {
            assertEquals("topic ARN",       topicArn,               message.get(SQS_KEY_TOPIC_ARN));
            assertRegex("message text",     MessageWriter.REGEX,    (String)message.get(SQS_KEY_MESSAGE));

            String actualSubject = (String)message.get(SQS_KEY_SUBJECT);
            if (! StringUtil.isEmpty(actualSubject))
                actualSubjects.add(actualSubject);
        }

        assertEquals("message subject(s)", expectedSubjects, actualSubjects);
    }

//----------------------------------------------------------------------------
//  Internals
//----------------------------------------------------------------------------

    /**
     *  Creates the topic and waits for it to become available.
     */
    private void createTopic()
    throws Exception
    {
        localLogger.debug("creating topic {}", resourceName);

        CreateTopicRequest createTopicRequest = CreateTopicRequest.builder().name(resourceName).build();
        CreateTopicResponse createTopicResponse = snsClient.createTopic(createTopicRequest);
        topicArn = createTopicResponse.topicArn();

        // this is used so the tests don't have to construct a topic ARN from a string
        System.setProperty("SNSAppenderIntegrationTest.topicArn", topicArn);

        for (int ii = 0 ; ii <  30 ; ii++)
        {
            try
            {
                GetTopicAttributesRequest attribsRequest = GetTopicAttributesRequest.builder().topicArn(topicArn).build();
                snsClient.getTopicAttributes(attribsRequest);
                // no exception means the topic is available
                return;
            }
            catch (NotFoundException ex)
            {
                // ignored; topic isn't ready
            }
            Thread.sleep(1000);
        }

        throw new IllegalStateException("topic not ready within 30 seconds");
    }


    /**
     *  Deletes the topic if it exists, along with its subscriptions.
     */
    private void deleteTopic()
    throws Exception
    {
        if (StringUtil.isEmpty(topicArn))
            return;

        localLogger.debug("deleting topic {}", topicArn);

        // the SNS API docs claim that deleting a topic deletes all of its subscriptions, but I
        // have observed this to be false
        try
        {
            // there should not be more than one subscription per topic, so pagination is moot
            ListSubscriptionsByTopicRequest listSubsRequest = ListSubscriptionsByTopicRequest.builder().topicArn(topicArn).build();
            ListSubscriptionsByTopicResponse listSubsResponse = snsClient.listSubscriptionsByTopic(listSubsRequest);
            for (Subscription subscription : listSubsResponse.subscriptions())
            {
                UnsubscribeRequest unsubscribeRequest = UnsubscribeRequest.builder().subscriptionArn(subscription.subscriptionArn()).build();
                snsClient.unsubscribe(unsubscribeRequest);
            }
        }
        catch (Exception ex)
        {
            localLogger.warn("unexpected exception when removing subscriptions from topic {}: {}", topicArn, ex.getMessage());
        }

        try
        {
           // according to the docs, this won't throw if the topic doesn't exist
            DeleteTopicRequest deleteRequest = DeleteTopicRequest.builder().topicArn(topicArn).build();
            snsClient.deleteTopic(deleteRequest);
        }
        catch (Exception ex)
        {
            localLogger.warn("unexpected exception when deleting topic {}: {}", topicArn, ex.getMessage());
        }
    }


    /**
     *  Creates the queue and waits for it to become available.
     */
    private void createQueue()
    throws Exception
    {
        localLogger.debug("creating queue {}", resourceName);

        CreateQueueRequest createRequest = CreateQueueRequest.builder().queueName(resourceName).build();
        CreateQueueResponse createResponse = sqsClient.createQueue(createRequest);
        queueUrl = createResponse.queueUrl();
        // I'm going to assume that this won't succeed until the queue is available
        queueArn = retrieveQueueAttribute(QueueAttributeName.QUEUE_ARN);
    }


    /**
     *  Deletes the queue (if it exists).
     */
    private void deleteQueue()
    throws Exception
    {
        if (StringUtil.isEmpty(queueArn))
            return;

        localLogger.debug("deleting queue {}", queueArn);

        try
        {
            DeleteQueueRequest deleteRequest = DeleteQueueRequest.builder().queueUrl(queueUrl).build();
            sqsClient.deleteQueue(deleteRequest);
        }
        catch (Exception ex)
        {
            localLogger.warn("unexpected exception when deleting queue {}: {}", queueArn, ex.getMessage());
        }
    }


    /**
     *  Creates and configures the subscription, as well as giving the topic permission
     *  to publish to the queue.
     */
    private void subscribeQueueToTopic()
    throws Exception
    {
        localLogger.debug("subscribing queue to topic {}", resourceName);

        String queueAccessPolicy
                = "{"
                + "  \"Version\": \"2012-10-17\","
                + "  \"Id\": \"" + resourceName + "-SubscriptionPolicy\","
                + "  \"Statement\": ["
                + "    {"
                + "      \"Effect\": \"Allow\","
                + "      \"Principal\": {"
                + "        \"AWS\": \"*\""
                + "      },"
                + "      \"Action\": \"SQS:SendMessage\","
                + "      \"Resource\": \"" + queueArn + "\","
                + "      \"Condition\": {"
                + "        \"ArnEquals\": {"
                + "          \"aws:SourceArn\": \"" + topicArn + "\""
                + "        }"
                + "      }"
                + "    }"
                + "  ]"
                + "}";

        Map<QueueAttributeName,String> queueAttributes = new HashMap<>();
        queueAttributes.put(QueueAttributeName.POLICY, queueAccessPolicy);
        SetQueueAttributesRequest setPolicyRequest = SetQueueAttributesRequest.builder()
                                                     .queueUrl(queueUrl)
                                                     .attributes(queueAttributes)
                                                     .build();
        sqsClient.setQueueAttributes(setPolicyRequest);

        // according to docs, it can take up to 60 seconds for an attribute to propagate
        // we'll just wait until it's non-blank
        retrieveQueueAttribute(QueueAttributeName.POLICY);

        SubscribeRequest subscribeRequest = SubscribeRequest.builder()
                                            .topicArn(topicArn)
                                            .protocol("sqs")
                                            .endpoint(queueArn)
                                            .build();
        snsClient.subscribe(subscribeRequest);
    }


    /**
     *  Loops until an attribute is set, throwing after a timeout.
     */
    private String retrieveQueueAttribute(QueueAttributeName attributeName)
    throws Exception
    {
        localLogger.debug("retrieving attribute \"{}\" for queue {}", attributeName, queueUrl);

        for (int ii = 0 ; ii < 60 ; ii++)
        {
            GetQueueAttributesRequest attribsRequest = GetQueueAttributesRequest.builder()
                                                       .queueUrl(queueUrl)
                                                       .attributeNames(attributeName)
                                                       .build();
            GetQueueAttributesResponse attribsResponse = sqsClient.getQueueAttributes(attribsRequest);
            Map<QueueAttributeName,String> attribs = attribsResponse.attributes();
            if (! StringUtil.isEmpty(attribs.get(attributeName)))
                return attribs.get(attributeName);

            // it's unclear to me whether this will ever happen
            Thread.sleep(1000);
        }

        throw new IllegalStateException("unable to retrieve attribute: " + attributeName);
    }
}
