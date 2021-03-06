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

package com.kdgregory.logging.aws.facade.v2;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import static org.junit.Assert.*;

import static net.sf.kdgcommons.test.StringAsserts.*;

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;

import com.kdgregory.logging.aws.cloudwatch.CloudWatchWriterConfig;
import com.kdgregory.logging.aws.facade.CloudWatchFacade;
import com.kdgregory.logging.aws.facade.CloudWatchFacadeException;
import com.kdgregory.logging.aws.facade.CloudWatchFacadeException.ReasonCode;
import com.kdgregory.logging.aws.facade.v2.CloudWatchFacadeImpl;
import com.kdgregory.logging.aws.testhelpers.CloudWatchClientMock;
import com.kdgregory.logging.common.LogMessage;


public class TestCloudWatchFacadeImpl
{
    private final static List<String> KNOWN_LOG_GROUPS = Arrays.asList("argle", "bargle", "wargle", "zargle");
    private final static List<String> KNOWN_LOG_STREAMS = Arrays.asList("foo", "bar", "baz", "biff");

    // note: these are at the end of the lists above, to trigger pagination and iteration
    private final static String TEST_LOG_GROUP = "zargle";
    private final static String TEST_LOG_STREAM = "biff";

    // this is "testing the mock", but makes the code look cleaner
    private final static String TEST_LOG_GROUP_ARN = "arn:aws:logs:us-east-1:123456789012:log-group:" + TEST_LOG_GROUP;

    private CloudWatchClientMock mock;

    // note: group/stream names are from the end of the above list to verify pagination
    private CloudWatchWriterConfig config = new CloudWatchWriterConfig()
                                            .setLogGroupName(TEST_LOG_GROUP)
                                            .setLogStreamName(TEST_LOG_STREAM);

    // note: can update config or mock any time before making first call
    private CloudWatchFacade facade = new CloudWatchFacadeImpl(config)
    {
        @Override
        protected CloudWatchLogsClient client()
        {
            if (client == null)
            {
                client = mock.createClient();
            }
            return client;
        }
    };


//----------------------------------------------------------------------------
//  Helpers
//----------------------------------------------------------------------------

    /**
     *  Verifies that an exception contains a properly structured message.
     */
    private void assertException(
        CloudWatchFacadeException ex,
        String expectedFunctionName, String expectedContainedMessage,
        ReasonCode expectedReason, boolean expectedRetryable, Throwable expectedCause)
    {
        assertEquals("exception reason",  expectedReason, ex.getReason());

        assertRegex("exception message (was: " + ex.getMessage() + ")",
                    expectedFunctionName + ".*" + config.getLogGroupName()
                                         + "(" + config.getLogStreamName() + ")*"
                                         + ".*" + expectedContainedMessage,
                    ex.getMessage());

        assertEquals("retryable", expectedRetryable, ex.isRetryable());

        if (expectedCause != null)
        {
            assertSame("exception contains cause", expectedCause, ex.getCause());
        }
    }



    /**
     *  Constructs an exception indicating the request was throttled. This exists
     *  to reduce code duplication.
     *  <p>
     *  Exception contents were determined via experimentation (AWS _still_ doesn't
     *  use a separate exception to represent throttled requests!).
     */
    private static CloudWatchLogsException throttlingException()
    {
        return (CloudWatchLogsException)
               CloudWatchLogsException.builder()
                                      .message("Rate exceeded")
                                      .awsErrorDetails(
                                      AwsErrorDetails.builder()
                                                     .errorCode("ThrottlingException")
                                                     .errorMessage("Rate exceeded")
                                                     .build())
                                      .build();
    }


//----------------------------------------------------------------------------
//  Testcases
//----------------------------------------------------------------------------

    @Test
    public void testFindLogGroupHappyPath() throws Exception
    {
        mock = new CloudWatchClientMock(KNOWN_LOG_GROUPS, KNOWN_LOG_STREAMS);

        String result = facade.findLogGroup();

        assertEquals("name passed to describeLogGroups",    TEST_LOG_GROUP,     mock.describeLogGroupsGroupNamePrefix);
        assertEquals("returned log group ARN",              TEST_LOG_GROUP_ARN, result);

        // common set of asserts; here it asserts we make no unnecessary calls
        assertEquals("calls to describeLogGroups",          1,          mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",         0,          mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",            0,          mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",           0,          mock.createLogStreamInvocationCount);
    }


    @Test
    public void testFindLogGroupPaginated() throws Exception
    {
        mock = new CloudWatchClientMock(KNOWN_LOG_GROUPS, 2, KNOWN_LOG_STREAMS, 2);

        String result = facade.findLogGroup();

        assertEquals("name passed to describeLogGroups",    TEST_LOG_GROUP,     mock.describeLogGroupsGroupNamePrefix);
        assertEquals("returned log group ARN",              TEST_LOG_GROUP_ARN, result);

        assertEquals("calls to describeLogGroups",          2,                  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",         0,                  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",            0,                  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",           0,                  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testFindLogGroupNoSuchGroup() throws Exception
    {
        mock = new CloudWatchClientMock();

        String result = facade.findLogGroup();

        assertNull("missing log group returns null",        result);

        assertEquals("calls to describeLogGroups",      1,  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     0,  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        0,  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       0,  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testFindLogGroupThrottled() throws Exception
    {
        mock = new CloudWatchClientMock(KNOWN_LOG_GROUPS, KNOWN_LOG_STREAMS)
        {
            @Override
            protected DescribeLogGroupsResponse describeLogGroups(DescribeLogGroupsRequest request)
            {
                throw throttlingException();
            }
        };

        String result = facade.findLogGroup();

        assertNull("throttled call returns null",           result);

        assertEquals("calls to describeLogGroups",      1,  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     0,  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        0,  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       0,  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testFindLogGroupAborted() throws Exception
    {
        mock = new CloudWatchClientMock(KNOWN_LOG_GROUPS, KNOWN_LOG_STREAMS)
        {
            @Override
            protected DescribeLogGroupsResponse describeLogGroups(DescribeLogGroupsRequest request)
            {
                throw OperationAbortedException.builder().message("message irrelevant").build();
            }
        };

        String result = facade.findLogGroup();

        assertNull("aborted operation returns null",    result);

        assertEquals("calls to describeLogGroups",      1,  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     0,  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        0,  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       0,  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testFindLogGroupError() throws Exception
    {
        final RuntimeException cause = new RuntimeException("test");
        mock = new CloudWatchClientMock(KNOWN_LOG_GROUPS, KNOWN_LOG_STREAMS)
        {
            @Override
            protected DescribeLogGroupsResponse describeLogGroups(DescribeLogGroupsRequest request)
            {
                throw cause;
            }
        };

        try
        {
            facade.findLogGroup();
            fail("should have thrown");
        }
        catch (CloudWatchFacadeException ex)
        {
            assertException(ex, "findLogGroup", "unexpected exception: test", ReasonCode.UNEXPECTED_EXCEPTION, false, cause);
        }

        assertEquals("calls to describeLogGroups",      1,  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     0,  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        0,  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       0,  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testCreateLogGroupHappyPath() throws Exception
    {
        mock = new CloudWatchClientMock();

        facade.createLogGroup();

        assertEquals("group name passed to create",     config.getLogGroupName(),   mock.createLogGroupGroupName);

        assertEquals("calls to describeLogGroups",      0,  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     0,  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        1,  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       0,  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testCreateLogGroupAlreadyExists() throws Exception
    {
        mock = new CloudWatchClientMock()
        {
            @Override
            protected CreateLogGroupResponse createLogGroup(CreateLogGroupRequest request)
            {
                throw ResourceAlreadyExistsException.builder().message("message irrelevant").build();
            }
        };

        facade.createLogGroup();

        assertEquals("calls to describeLogGroups",      0,  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     0,  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        1,  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       0,  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testCreateLogGroupThrottled() throws Exception
    {
        mock = new CloudWatchClientMock()
        {
            @Override
            protected CreateLogGroupResponse createLogGroup(CreateLogGroupRequest request)
            {
                if (createLogGroupInvocationCount < 2)
                {
                    throw throttlingException();
                }
                return super.createLogGroup(request);
            }
        };

        try
        {
            facade.createLogGroup();
            fail("should have thrown");
        }
        catch (CloudWatchFacadeException ex)
        {
            assertException(ex, "createLogGroup", "throttled", ReasonCode.THROTTLING, true, null);
        }

        assertEquals("calls to describeLogGroups",      0,  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     0,  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        1,  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       0,  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testCreateLogGroupAborted() throws Exception
    {
        mock = new CloudWatchClientMock()
        {
            @Override
            protected CreateLogGroupResponse createLogGroup(CreateLogGroupRequest request)
            {
                if (createLogGroupInvocationCount == 1)
                {
                    throw OperationAbortedException.builder().message("message irrelevant").build();
                }
                // in real life, this would be followed by ResourceAlreadyExistsException
                return super.createLogGroup(request);
            }
        };

        try
        {
            facade.createLogGroup();
            fail("should have thrown");
        }
        catch (CloudWatchFacadeException ex)
        {
            assertException(ex, "createLogGroup", "aborted", ReasonCode.ABORTED, true, null);
        }

        assertEquals("calls to describeLogGroups",      0,  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     0,  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        1,  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       0,  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testCreateLogGroupUnexpectedError() throws Exception
    {
        final RuntimeException cause = new RuntimeException("test");
        mock = new CloudWatchClientMock()
        {
            @Override
            protected CreateLogGroupResponse createLogGroup(CreateLogGroupRequest request)
            {
                throw cause;
            }
        };

        try
        {
            facade.createLogGroup();
            fail("should have thrown");
        }
        catch (CloudWatchFacadeException ex)
        {
            assertException(ex, "createLogGroup", "unexpected exception: test", ReasonCode.UNEXPECTED_EXCEPTION, false, cause);
        }

        assertEquals("calls to describeLogGroups",      0,  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     0,  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        1,  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       0,  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testSetLogGroupRetentionHappyPath() throws Exception
    {
        config.setRetentionPeriod(Integer.valueOf(7));
        mock = new CloudWatchClientMock(KNOWN_LOG_GROUPS, KNOWN_LOG_STREAMS);

        facade.setLogGroupRetention();

        assertEquals("log group name passed to putRetentionPolicy", config.getLogGroupName(),   mock.putRetentionPolicyGroupName);
        assertEquals("value passed to putRetentionPolicy",          Integer.valueOf(7),         mock.putRetentionPolicyValue);

        assertEquals("calls to putRetentionPolicy",                 1,                          mock.putRetentionPolicyInvocationCount);
    }


    @Test
    public void testSetLogGroupRetentionNoValue() throws Exception
    {
        mock = new CloudWatchClientMock(KNOWN_LOG_GROUPS, KNOWN_LOG_STREAMS);

        facade.setLogGroupRetention();

        assertEquals("calls to putRetentionPolicy",     0,      mock.putRetentionPolicyInvocationCount);
    }


    @Test
    public void testSetLogGroupRetentionInvalidConfiguration() throws Exception
    {
        // setting the log group and stream name to values that I can then assert
        config.setLogGroupName("argle");
        config.setLogStreamName("bargle");
        config.setRetentionPeriod(Integer.valueOf(19));
        mock = new CloudWatchClientMock(KNOWN_LOG_GROUPS, KNOWN_LOG_STREAMS);

        try
        {
            facade.setLogGroupRetention();
            fail("should have thrown");
        }
        catch (CloudWatchFacadeException ex)
        {
            assertException(ex, "setLogGroupRetention", "invalid retention period: 19", ReasonCode.INVALID_CONFIGURATION, false, null);
        }

        assertEquals("calls to putRetentionPolicy",                 1,                          mock.putRetentionPolicyInvocationCount);
    }


    @Test
    public void testSetLogGroupRetentionUnexpectedError() throws Exception
    {
        final RuntimeException cause = new RuntimeException("test");
        mock = new CloudWatchClientMock()
        {
            @Override
            protected PutRetentionPolicyResponse putRetentionPolicy(PutRetentionPolicyRequest request)
            {
                throw cause;
            }
        };

        // setting the log group and stream name to values that I can then assert
        config.setLogGroupName("argle");
        config.setLogStreamName("bargle");
        config.setRetentionPeriod(Integer.valueOf(7));

        try
        {
            facade.setLogGroupRetention();
            fail("should have thrown");
        }
        catch (CloudWatchFacadeException ex)
        {
            assertException(ex, "setLogGroupRetention", "unexpected exception: test", ReasonCode.UNEXPECTED_EXCEPTION, false, cause);
        }

        assertEquals("calls to putRetentionPolicy",     1,                  mock.putRetentionPolicyInvocationCount);
    }


    @Test
    public void testCreateLogStreamHappyPath() throws Exception
    {
        mock = new CloudWatchClientMock();

        facade.createLogStream();

        assertEquals("group name passed to create",     TEST_LOG_GROUP,     mock.createLogStreamGroupName);
        assertEquals("stream name passed to create",    TEST_LOG_STREAM,    mock.createLogStreamStreamName);

        assertEquals("calls to describeLogGroups",      0,                  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     0,                  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        0,                  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       1,                  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testCreateLogStreamAlreadyExists() throws Exception
    {
        mock = new CloudWatchClientMock()
        {
            @Override
            protected CreateLogStreamResponse createLogStream(CreateLogStreamRequest request)
            {
                throw ResourceAlreadyExistsException.builder().message("message irrelevant").build();
            }
        };

        facade.createLogStream();

        assertEquals("calls to describeLogGroups",      0,  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     0,  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        0,  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       1,  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testCreateLogStreamThrottled() throws Exception
    {
        mock = new CloudWatchClientMock()
        {
            @Override
            protected CreateLogStreamResponse createLogStream(CreateLogStreamRequest request)
            {
                if (createLogStreamInvocationCount < 2)
                {
                    throw throttlingException();
                }
                return super.createLogStream(request);
            }
        };

        try
        {
            facade.createLogStream();
            fail("should have thrown");
        }
        catch (CloudWatchFacadeException ex)
        {
            assertException(ex, "createLogStream", "throttled", ReasonCode.THROTTLING, true, null);
        }

        assertEquals("calls to describeLogGroups",      0,  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     0,  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        0,  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       1,  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testCreateLogStreamAborted() throws Exception
    {
        mock = new CloudWatchClientMock()
        {
            @Override
            protected CreateLogStreamResponse createLogStream(CreateLogStreamRequest request)
            {
                if (createLogStreamInvocationCount < 2)
                {
                    throw OperationAbortedException.builder().message("message irrelevant").build();
                }
                // in real life, this would be followed by ResourceAlreadyExistsException
                return super.createLogStream(request);
            }
        };

        try
        {
            facade.createLogStream();
            fail("should have thrown");
        }
        catch (CloudWatchFacadeException ex)
        {
            assertException(ex, "createLogStream", "aborted", ReasonCode.ABORTED, true, null);
        }

        assertEquals("calls to describeLogGroups",      0,  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     0,  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        0,  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       1,  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testCreateLogStreamMissingLogGroup() throws Exception
    {
        mock = new CloudWatchClientMock()
        {
            @Override
            protected CreateLogStreamResponse createLogStream(CreateLogStreamRequest request)
            {
                throw ResourceNotFoundException.builder().message("message irrelevant").build();
            }
        };

        try
        {
            facade.createLogStream();
            fail("should have thrown");
        }
        catch (CloudWatchFacadeException ex)
        {
            assertException(ex, "createLogStream", "missing", ReasonCode.MISSING_LOG_GROUP, false, null);
        }

        assertEquals("calls to describeLogGroups",      0,  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     0,  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        0,  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       1,  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testCreateLogStreamUnexpectedError() throws Exception
    {
        final RuntimeException cause = new RuntimeException("test");
        mock = new CloudWatchClientMock()
        {
            @Override
            protected CreateLogStreamResponse createLogStream(CreateLogStreamRequest request)
            {
                throw cause;
            }
        };

        try
        {
            facade.createLogStream();
            fail("should have thrown");
        }
        catch (CloudWatchFacadeException ex)
        {
            assertException(ex, "createLogStream", "unexpected exception: test", ReasonCode.UNEXPECTED_EXCEPTION, false, cause);
        }

        assertEquals("calls to describeLogGroups",      0,  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     0,  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        0,  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       1,  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testRetrieveSequenceTokenHappyPath() throws Exception
    {
        mock = new CloudWatchClientMock(KNOWN_LOG_GROUPS, KNOWN_LOG_STREAMS);

        assertNotEmpty("returned sequence token", facade.retrieveSequenceToken());

        assertEquals("group name passed to create",     TEST_LOG_GROUP,     mock.describeLogStreamsGroupName);
        assertEquals("stream name passed to create",    TEST_LOG_STREAM,    mock.describeLogStreamsStreamPrefix);

        assertEquals("calls to describeLogGroups",      0,  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     1,  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        0,  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       0,  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testRetrieveSequenceTokenPaginated() throws Exception
    {
        mock = new CloudWatchClientMock(KNOWN_LOG_GROUPS, 2, KNOWN_LOG_STREAMS, 2);

        assertNotEmpty("returned sequence token", facade.retrieveSequenceToken());

        assertEquals("calls to describeLogGroups",      0,  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     2,  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        0,  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       0,  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testRetrieveSequenceTokenMissingStream() throws Exception
    {
        mock = new CloudWatchClientMock(KNOWN_LOG_GROUPS, Collections.emptyList());

        assertNull("returned sequence token", facade.retrieveSequenceToken());

        assertEquals("calls to describeLogGroups",      0,  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     1,  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        0,  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       0,  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testRetrieveSequenceTokenThrottled() throws Exception
    {
        mock = new CloudWatchClientMock(KNOWN_LOG_GROUPS, KNOWN_LOG_STREAMS)
        {
            @Override
            protected DescribeLogStreamsResponse describeLogStreams(DescribeLogStreamsRequest request)
            {
                throw throttlingException();
            }
        };

        assertNull("returned null sequence token",      facade.retrieveSequenceToken());

        assertEquals("calls to describeLogGroups",      0,  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     1,  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        0,  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       0,  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testRetrieveSequenceTokenAborted() throws Exception
    {
        mock = new CloudWatchClientMock(KNOWN_LOG_GROUPS, KNOWN_LOG_STREAMS)
        {
            @Override
            protected DescribeLogStreamsResponse describeLogStreams(DescribeLogStreamsRequest request)
            {
                    throw OperationAbortedException.builder().message("message irrelevant").build();
            }
        };

        assertNull("returned null sequence token",      facade.retrieveSequenceToken());

        assertEquals("calls to describeLogGroups",      0,  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     1,  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        0,  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       0,  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testRetrieveSequenceTokenMissingLogGroup() throws Exception
    {
        mock = new CloudWatchClientMock();

        assertNull("call returned null", facade.retrieveSequenceToken());

        assertEquals("calls to describeLogGroups",      0,  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     1,  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        0,  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       0,  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testRetrieveSequenceTokenUnexpectedError() throws Exception
    {
        final RuntimeException cause = new RuntimeException("test");
        mock = new CloudWatchClientMock(KNOWN_LOG_GROUPS, KNOWN_LOG_STREAMS)
        {
            @Override
            protected DescribeLogStreamsResponse describeLogStreams(DescribeLogStreamsRequest request)
            {
                throw cause;
            }
        };

        try
        {
            facade.retrieveSequenceToken();
            fail("should have thrown");
        }
        catch (CloudWatchFacadeException ex)
        {
            assertException(ex, "retrieveSequenceToken", "unexpected exception: test", ReasonCode.UNEXPECTED_EXCEPTION, false, cause);
        }

        assertEquals("calls to describeLogGroups",      0,  mock.describeLogGroupsInvocationCount);
        assertEquals("calls to describeLogStreams",     1,  mock.describeLogStreamsInvocationCount);
        assertEquals("calls to createLogGroups",        0,  mock.createLogGroupInvocationCount);
        assertEquals("calls to createLogStreams",       0,  mock.createLogStreamInvocationCount);
    }


    @Test
    public void testPutEventsHappyPath() throws Exception
    {
        mock = new CloudWatchClientMock(KNOWN_LOG_GROUPS, KNOWN_LOG_STREAMS);

        long now = System.currentTimeMillis();

        LogMessage msg1 = new LogMessage(now - 10, "message 1");
        LogMessage msg2 = new LogMessage(now,      "message 2");
        LogMessage msg3 = new LogMessage(now + 10, "message 3");

        String sequenceToken = mock.getCurrentSequenceToken();
        List<LogMessage> messages = Arrays.asList(msg1, msg2, msg3);

        String newSequenceToken = facade.putEvents(sequenceToken, messages);

        assertEquals("calls to putLogEvents",                   1,                          mock.putLogEventsInvocationCount);
        assertEquals("group name passed to putLogEvents",       config.getLogGroupName(),   mock.putLogEventsGroupName);
        assertEquals("stream name passed to putLogEvents",      config.getLogStreamName(),  mock.putLogEventsStreamName);

        assertEquals("number of events passed to putLogEvents", 3,                          mock.putLogEventsEvents.size());
        assertEquals("event 0 timestamp",                       now - 10,                   mock.putLogEventsEvents.get(0).timestamp().longValue());
        assertEquals("event 0 message",                         "message 1",                mock.putLogEventsEvents.get(0).message());
        assertEquals("event 1 timestamp",                       now,                        mock.putLogEventsEvents.get(1).timestamp().longValue());
        assertEquals("event 1 message",                         "message 2",                mock.putLogEventsEvents.get(1).message());
        assertEquals("event 2 timestamp",                       now + 10,                   mock.putLogEventsEvents.get(2).timestamp().longValue());
        assertEquals("event 2 message",                         "message 3",                mock.putLogEventsEvents.get(2).message());

        assertNotNull("returned sequence token",                                            newSequenceToken);
    }


    @Test
    public void testPutEventsEmptyBatch() throws Exception
    {
        mock = new CloudWatchClientMock(KNOWN_LOG_GROUPS, KNOWN_LOG_STREAMS);

        String sequenceToken = mock.getCurrentSequenceToken();
        List<LogMessage> messages = Arrays.asList();

        String newSequenceToken = facade.putEvents(sequenceToken, messages);

        assertEquals("calls to putLogEvents",               0,                          mock.putLogEventsInvocationCount);
        assertEquals("returned sequence token",             sequenceToken,              newSequenceToken);
    }


    @Test
    public void testPutEventsThrottled() throws Exception
    {
        mock = new CloudWatchClientMock(KNOWN_LOG_GROUPS, KNOWN_LOG_STREAMS)
        {
            @Override
            protected PutLogEventsResponse putLogEvents(PutLogEventsRequest request)
            {
                throw throttlingException();
            }
        };

        String sequenceToken = mock.getCurrentSequenceToken();
        List<LogMessage> messages = Arrays.asList(new LogMessage(0, "doesn't matter"));

        try
        {
            facade.putEvents(sequenceToken, messages);
            fail("should have thrown");
        }
        catch (CloudWatchFacadeException ex)
        {
            assertException(ex, "putEvents", "throttled", ReasonCode.THROTTLING, true, null);
        }

        assertEquals("calls to putLogEvents",                   1,                          mock.putLogEventsInvocationCount);
        assertEquals("group name passed to putLogEvents",       config.getLogGroupName(),   mock.putLogEventsGroupName);
        assertEquals("stream name passed to putLogEvents",      config.getLogStreamName(),  mock.putLogEventsStreamName);
        assertEquals("number of events passed to putLogEvents", 1,                          mock.putLogEventsEvents.size());
    }


    @Test
    public void testPutEventsInvalidSequenceToken() throws Exception
    {
        mock = new CloudWatchClientMock(KNOWN_LOG_GROUPS, KNOWN_LOG_STREAMS);

        String sequenceToken = "9999";
        List<LogMessage> messages = Arrays.asList(new LogMessage(0, "doesn't matter"));

        try
        {
            facade.putEvents(sequenceToken, messages);
            fail("should have thrown");
        }
        catch (CloudWatchFacadeException ex)
        {
            assertException(ex, "putEvents", "invalid sequence token: 9999", ReasonCode.INVALID_SEQUENCE_TOKEN, false, null);
        }

        assertEquals("calls to putLogEvents",                   1,                          mock.putLogEventsInvocationCount);
        assertEquals("group name passed to putLogEvents",       config.getLogGroupName(),   mock.putLogEventsGroupName);
        assertEquals("stream name passed to putLogEvents",      config.getLogStreamName(),  mock.putLogEventsStreamName);
        assertEquals("number of events passed to putLogEvents", 1,                          mock.putLogEventsEvents.size());
    }


    @Test
    public void testPutEventsMissingLogGroup() throws Exception
    {
        mock = new CloudWatchClientMock();

        String sequenceToken = mock.getCurrentSequenceToken();
        List<LogMessage> messages = Arrays.asList(new LogMessage(0, "doesn't matter"));

        try
        {
            facade.putEvents(sequenceToken, messages);
            fail("should have thrown");
        }
        catch (CloudWatchFacadeException ex)
        {
            assertException(ex, "putEvents", "missing log group", ReasonCode.MISSING_LOG_GROUP, false, null);
        }

        assertEquals("calls to putLogEvents",                   1,                          mock.putLogEventsInvocationCount);
        assertEquals("group name passed to putLogEvents",       config.getLogGroupName(),   mock.putLogEventsGroupName);
        assertEquals("stream name passed to putLogEvents",      config.getLogStreamName(),  mock.putLogEventsStreamName);
        assertEquals("number of events passed to putLogEvents", 1,                          mock.putLogEventsEvents.size());
    }


    @Test
    public void testPutEventsDataAlreadyAccepted() throws Exception
    {
        mock = new CloudWatchClientMock(KNOWN_LOG_GROUPS, KNOWN_LOG_STREAMS)
        {
            @Override
            protected PutLogEventsResponse putLogEvents(PutLogEventsRequest request)
            {
                throw DataAlreadyAcceptedException.builder().message("message irrelevant").build();
            }
        };

        String sequenceToken = mock.getCurrentSequenceToken();
        List<LogMessage> messages = Arrays.asList(new LogMessage(0, "doesn't matter"));

        try
        {
            facade.putEvents(sequenceToken, messages);
            fail("should have thrown");
        }
        catch (CloudWatchFacadeException ex)
        {
            assertException(ex, "putEvents", "already processed", ReasonCode.ALREADY_PROCESSED, false, null);
        }

        assertEquals("calls to putLogEvents",                   1,                          mock.putLogEventsInvocationCount);
        assertEquals("group name passed to putLogEvents",       config.getLogGroupName(),   mock.putLogEventsGroupName);
        assertEquals("stream name passed to putLogEvents",      config.getLogStreamName(),  mock.putLogEventsStreamName);
        assertEquals("number of events passed to putLogEvents", 1,                          mock.putLogEventsEvents.size());
    }


    @Test
    public void testPutEventsUnexpectedException() throws Exception
    {
        final RuntimeException cause = new RuntimeException("test");
        mock = new CloudWatchClientMock(KNOWN_LOG_GROUPS, KNOWN_LOG_STREAMS)
        {
            @Override
            protected PutLogEventsResponse putLogEvents(PutLogEventsRequest request)
            {
                throw cause;
            }
        };

        String sequenceToken = mock.getCurrentSequenceToken();
        List<LogMessage> messages = Arrays.asList(new LogMessage(0, "doesn't matter"));

        try
        {
            facade.putEvents(sequenceToken, messages);
            fail("should have thrown");
        }
        catch (CloudWatchFacadeException ex)
        {
            assertException(ex, "putEvents", "unexpected exception: test", ReasonCode.UNEXPECTED_EXCEPTION, false, cause);
        }

        assertEquals("calls to putLogEvents",                   1,                          mock.putLogEventsInvocationCount);
        assertEquals("group name passed to putLogEvents",       config.getLogGroupName(),   mock.putLogEventsGroupName);
        assertEquals("stream name passed to putLogEvents",      config.getLogStreamName(),  mock.putLogEventsStreamName);
        assertEquals("number of events passed to putLogEvents", 1,                          mock.putLogEventsEvents.size());
    }


    @Test
    public void testShutdown() throws Exception
    {
        mock = new CloudWatchClientMock();

        facade.shutdown();

        assertEquals("calls to close",                          1,                          mock.closeInvocationCount);
    }
}
