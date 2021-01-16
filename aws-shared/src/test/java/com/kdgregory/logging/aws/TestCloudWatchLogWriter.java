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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import net.sf.kdgcommons.lang.ClassUtil;
import net.sf.kdgcommons.lang.StringUtil;
import static net.sf.kdgcommons.test.NumericAsserts.*;

import com.kdgregory.logging.aws.cloudwatch.CloudWatchWriterStatistics;
import com.kdgregory.logging.aws.internal.Utils;
import com.kdgregory.logging.aws.internal.facade.CloudWatchFacade;
import com.kdgregory.logging.aws.internal.facade.CloudWatchFacadeException;
import com.kdgregory.logging.aws.internal.facade.CloudWatchFacadeException.ReasonCode;
import com.kdgregory.logging.aws.cloudwatch.CloudWatchLogWriter;
import com.kdgregory.logging.aws.cloudwatch.CloudWatchWriterConfig;
import com.kdgregory.logging.common.LogMessage;
import com.kdgregory.logging.common.LogWriter;
import com.kdgregory.logging.common.factories.WriterFactory;
import com.kdgregory.logging.common.util.DiscardAction;
import com.kdgregory.logging.common.util.InternalLogger;
import com.kdgregory.logging.testhelpers.cloudwatch.MockCloudWatchFacade;
import com.kdgregory.logging.testhelpers.cloudwatch.TestableCloudWatchLogWriter;


/**
 *  Performs mock-client testing of the CloudWatch writer. This exercises
 *  normal writer operation, including running on a background thread. To
 *  test messaging, you will need to synchronize the main (test) thread
 *  with the background thread; the mock provides {@link #waitForWriterThread}
 *  to do this. Most tests just need to wait for the writer to finish its
 *  initialization, which happens before {@link #createWriter} returns.
 */
public class TestCloudWatchLogWriter
extends AbstractLogWriterTest<CloudWatchLogWriter,CloudWatchWriterConfig,CloudWatchWriterStatistics>
{
    private MockCloudWatchFacade mock;

//----------------------------------------------------------------------------
//  Support Code
//----------------------------------------------------------------------------

    /**
     *  Creates a new writer and starts it on a background thread. This uses
     *  the current configuration and mock instance.
     */
    private void createWriter()
    throws Exception
    {
        final CloudWatchFacade facade = mock.newInstance();
        WriterFactory<CloudWatchWriterConfig,CloudWatchWriterStatistics> writerFactory
            = new WriterFactory<CloudWatchWriterConfig,CloudWatchWriterStatistics>()
            {
                @Override
                public LogWriter newLogWriter(
                        CloudWatchWriterConfig passedConfig,
                        CloudWatchWriterStatistics passedStats,
                        InternalLogger passedLogger)
                {
                    return new TestableCloudWatchLogWriter(passedConfig, passedStats, passedLogger, facade);
                }
            };

        super.createWriter(writerFactory);
    }


    /**
     *  A convenience function that knows the writer supports a semaphore (so
     *  that we don't need to cast within testcases).
     */
    private void waitForWriterThread()
    throws Exception
    {
        ((TestableCloudWatchLogWriter)writer).waitForWriterThread();
    }


//----------------------------------------------------------------------------
//  JUnit scaffolding
//----------------------------------------------------------------------------

    @Before
    public void setUp()
    {
        // this is the default configuration; may be updated or replaced by test;
        // batch delay is short enough to not be annoying, long enough to ensure
        // that we can get multiple messages in a batch
        config = new CloudWatchWriterConfig()
                 .setLogGroupName("argle")
                 .setLogStreamName("bargle")
                 .setDedicatedWriter(true)
                 .setBatchDelay(100)
                 .setDiscardThreshold(10000)
                 .setDiscardAction(DiscardAction.oldest);

        stats = new CloudWatchWriterStatistics();
    }


    @After
    public void checkUncaughtExceptions()
    throws Throwable
    {
        if (uncaughtException != null)
            throw uncaughtException;
    }

//----------------------------------------------------------------------------
//  Testcases
//----------------------------------------------------------------------------

    @Test
    public void testInitialization() throws Exception
    {
        // really, we're just testing that the writer propagates its configuration where it needs to

        mock = new MockCloudWatchFacade(config);
        createWriter();

        assertTrue("writer is running", writer.isRunning());

        assertEquals("writer batch delay",                      100L,                   writer.getBatchDelay());
        assertEquals("message queue discard policy",            DiscardAction.oldest,   messageQueue.getDiscardAction());
        assertEquals("message queue discard threshold",         10000,                  messageQueue.getDiscardThreshold());

        assertEquals("stats: actual log group name",            "argle",                stats.getActualLogGroupName());
        assertEquals("stats: actual log stream name",           "bargle",               stats.getActualLogStreamName());
    }


    @Test
    public void testInitializationInvalidConfiguration() throws Exception
    {
        config.setLogGroupName("I'm No Good!");
        config.setLogStreamName("");
        config.setRetentionPeriod(897);

        mock = new MockCloudWatchFacade(config);
        createWriter();

        assertFalse("writer is running", writer.isRunning());

        // we should never have gotten to checking for group/stream existence
        assertEquals("findLogGroup: invocation count",              0,                      mock.findLogGroupInvocationCount);
        assertEquals("retrieveSequenceToken: invocation count",     0,                      mock.retrieveSequenceTokenInvocationCount);

        internalLogger.assertInternalDebugLog(
                        "log writer starting.*");
        internalLogger.assertInternalWarningLog();
        internalLogger.assertInternalErrorLog(
                        "invalid log group name: I'm No Good!",
                        "blank log stream name",
                        "invalid retention period: 897.*",
                        "log writer failed to initialize.*");
    }


    @Test
    public void testReconfigureDiscardProperties() throws Exception
    {
        config.setDiscardAction(DiscardAction.none);
        config.setDiscardThreshold(123);

        mock = new MockCloudWatchFacade(config);
        createWriter();

        assertEquals("initial discard threshold",   123,                    messageQueue.getDiscardThreshold());
        assertEquals("initial discard action",      DiscardAction.none,     messageQueue.getDiscardAction());

        writer.setDiscardAction(DiscardAction.newest);
        writer.setDiscardThreshold(456);

        assertEquals("updated discard threshold",   456,                    messageQueue.getDiscardThreshold());
        assertEquals("updated discard action",      DiscardAction.newest,   messageQueue.getDiscardAction());
    }


    @Test
    public void testCreateGroupAndStream() throws Exception
    {
        mock = new MockCloudWatchFacade(config)
        {
            @Override
            public String findLogGroup() throws CloudWatchFacadeException
            {
                // first call is during initialization, second and third are waiting for created group
                if (findLogGroupInvocationCount < 4)
                    return null;

                return super.findLogGroup();
            }

            @Override
            public String retrieveSequenceToken() throws CloudWatchFacadeException
            {
                // first call is during initialization, second and third are waiting for created stream
                if (retrieveSequenceTokenInvocationCount < 4)
                    return null;

                return super.retrieveSequenceToken();
            }
        };

        // everything tested here happens during initialization
        createWriter();

        // will call findLogGroup when checking group existence
        // will call retrieveSequenceToken when checking stream existence, and caches returned value

        assertEquals("findLogGroup: invocation count",              4,                      mock.findLogGroupInvocationCount);
        assertEquals("retrieveSequenceToken: invocation count",     4,                      mock.retrieveSequenceTokenInvocationCount);
        assertEquals("createLogGroup: invocation count",            1,                      mock.createLogGroupInvocationCount);
        assertEquals("createLogStream: invocation count",           1,                      mock.createLogStreamInvocationCount);

        internalLogger.assertInternalDebugLog("log writer starting.*",
                                              "creating CloudWatch log group: argle",
                                              "creating CloudWatch log stream: bargle",
                                              "log writer initialization complete.*");
        internalLogger.assertInternalWarningLog();
        internalLogger.assertInternalErrorLog();
    }


    @Test
    public void testCreateGroupThrottled() throws Exception
    {
        // as above, the mock will claim that the stream already exists
        mock = new MockCloudWatchFacade(config)
        {
            @Override
            public String findLogGroup() throws CloudWatchFacadeException
            {
                // first call triggers create; after that we don't want to wait to conclude test
                if (findLogGroupInvocationCount == 1)
                    return null;

                return super.findLogGroup();
            }

            @Override
            public void createLogGroup() throws CloudWatchFacadeException
            {
                if (createLogGroupInvocationCount == 1)
                    throw new CloudWatchFacadeException(ReasonCode.THROTTLING, true, null);

                super.createLogGroup();
            }
        };

        // everything tested here happens during initialization
        createWriter();

        // will call findLogGroup when checking group existence, and after non-exception create
        // the createLogStream invocation count is 0 because the mock will tell us the stream exists

        assertEquals("findLogGroup: invocation count",              2,                      mock.findLogGroupInvocationCount);
        assertEquals("retrieveSequenceToken: invocation count",     1,                      mock.retrieveSequenceTokenInvocationCount);
        assertEquals("createLogGroup: invocation count",            2,                      mock.createLogGroupInvocationCount);
        assertEquals("createLogStream: invocation count",           0,                      mock.createLogStreamInvocationCount);

        internalLogger.assertInternalDebugLog("log writer starting.*",
                                              "creating CloudWatch log group: argle",
                                              "using existing CloudWatch log stream: bargle",   // because mock said it doesn't need to be created
                                              "log writer initialization complete.*");
        internalLogger.assertInternalWarningLog();
        internalLogger.assertInternalErrorLog();
    }


    @Test
    public void testCreateGroupAborted() throws Exception
    {
        // as above, the mock will claim that the stream already exists
        mock = new MockCloudWatchFacade(config)
        {
            @Override
            public String findLogGroup() throws CloudWatchFacadeException
            {
                // first call triggers create; after that we don't want to wait to conclude test
                if (findLogGroupInvocationCount == 1)
                    return null;

                return super.findLogGroup();
            }

            @Override
            public void createLogGroup() throws CloudWatchFacadeException
            {
                // note: exception message is asserted later
                if (createLogGroupInvocationCount == 1)
                    throw new CloudWatchFacadeException(ReasonCode.ABORTED, true, null);

                // at the level of the client, aborted is normally followed by reasource-exists;
                // the facade handles that, so we'll just return success
                super.createLogGroup();
            }
        };

        // everything tested here happens during initialization
        createWriter();

        // will call findLogGroup when checking group existence, and after non-exception create
        // the createLogStream invocation count is 0 because the mock will tell us the stream exists

        assertEquals("findLogGroup: invocation count",              2,                      mock.findLogGroupInvocationCount);
        assertEquals("retrieveSequenceToken: invocation count",     1,                      mock.retrieveSequenceTokenInvocationCount);
        assertEquals("createLogGroup: invocation count",            2,                      mock.createLogGroupInvocationCount);
        assertEquals("createLogStream: invocation count",           0,                      mock.createLogStreamInvocationCount);

        internalLogger.assertInternalDebugLog("log writer starting.*",
                                              "creating CloudWatch log group: argle",
                                              "using existing CloudWatch log stream: bargle",   // because mock said it doesn't need to be created
                                              "log writer initialization complete.*");
        internalLogger.assertInternalWarningLog();
        internalLogger.assertInternalErrorLog();
    }


    @Test
    public void testCreateGroupException() throws Exception
    {
        final Exception cause = new RuntimeException("message irrelevant");
        mock = new MockCloudWatchFacade(config)
        {
            @Override
            public String findLogGroup() throws CloudWatchFacadeException
            {
                // first call triggers create; after that we don't want to wait to conclude test
                if (findLogGroupInvocationCount == 1)
                    return null;

                return super.findLogGroup();
            }

            @Override
            public void createLogGroup() throws CloudWatchFacadeException
            {
                throw new CloudWatchFacadeException(ReasonCode.UNEXPECTED_EXCEPTION, false, cause);
            }
        };

        // everything tested here happens during initialization
        createWriter();

        assertFalse("writer is running", writer.isRunning());

        // will call findLogGroup when checking group existence, and after non-exception create
        // the createLogStream invocation count is 0 because the mock will tell us the stream exists

        assertEquals("findLogGroup: invocation count",              1,                      mock.findLogGroupInvocationCount);
        assertEquals("retrieveSequenceToken: invocation count",     0,                      mock.retrieveSequenceTokenInvocationCount);
        assertEquals("createLogGroup: invocation count",            1,                      mock.createLogGroupInvocationCount);
        assertEquals("createLogStream: invocation count",           0,                      mock.createLogStreamInvocationCount);

        internalLogger.assertInternalDebugLog(
                            "log writer starting.*",
                            "creating CloudWatch log group: argle");
        internalLogger.assertInternalWarningLog();
        internalLogger.assertInternalErrorLog(
                            "unable to configure log group/stream",
                        "log writer failed to initialize.*");

        assertUltimateCause("original exception reported", cause, internalLogger.errorExceptions.get(0));
    }


    @Test
    public void testCreateGroupWaitException() throws Exception
    {
        final Exception cause = new RuntimeException("message irrelevant");
        mock = new MockCloudWatchFacade(config)
        {
            @Override
            public String findLogGroup() throws CloudWatchFacadeException
            {
                // this will trigger create
                if (findLogGroupInvocationCount == 1)
                    return null;

                // this will be called in wait loop
                throw new CloudWatchFacadeException(ReasonCode.UNEXPECTED_EXCEPTION, false, cause);
            }
        };

        // everything tested here happens during initialization
        createWriter();

        assertFalse("writer is running", writer.isRunning());

        // will call findLogGroup when checking group existence, and after non-exception create
        // the createLogStream invocation count is 0 because the mock will tell us the stream exists

        assertEquals("findLogGroup: invocation count",              2,                      mock.findLogGroupInvocationCount);
        assertEquals("retrieveSequenceToken: invocation count",     0,                      mock.retrieveSequenceTokenInvocationCount);
        assertEquals("createLogGroup: invocation count",            1,                      mock.createLogGroupInvocationCount);
        assertEquals("createLogStream: invocation count",           0,                      mock.createLogStreamInvocationCount);

        internalLogger.assertInternalDebugLog(
                        "log writer starting.*",
                        "creating CloudWatch log group: argle");
        internalLogger.assertInternalWarningLog();
        internalLogger.assertInternalErrorLog(
                        "unable to configure log group/stream",
                        "log writer failed to initialize.*");

        assertUltimateCause("original exception reported", cause, internalLogger.errorExceptions.get(0));
    }


    @Test
    public void testCreateGroupWaitTimeout() throws Exception
    {
        // as above, the mock will claim that the stream already exists
        mock = new MockCloudWatchFacade(config)
        {
            @Override
            public String findLogGroup() throws CloudWatchFacadeException
            {
                // this will never complete successfully
                return null;
            }
        };

        // everything tested here happens during initialization
        createWriter();

        assertFalse("writer is running", writer.isRunning());

        // an initial findLogGroup(), followed by 4 attempts while waiting

        assertEquals("findLogGroup: invocation count",              5,                      mock.findLogGroupInvocationCount);
        assertEquals("retrieveSequenceToken: invocation count",     0,                      mock.retrieveSequenceTokenInvocationCount);
        assertEquals("createLogGroup: invocation count",            1,                      mock.createLogGroupInvocationCount);
        assertEquals("createLogStream: invocation count",           0,                      mock.createLogStreamInvocationCount);

        internalLogger.assertInternalDebugLog(
                        "log writer starting.*",
                        "creating CloudWatch log group: argle");
        internalLogger.assertInternalWarningLog();
        internalLogger.assertInternalErrorLog(
                        "unable to configure log group/stream",
                        "log writer failed to initialize.*");

        assertEquals("timeout exception recorded",
                     "timed out while waiting for CloudWatch log group",
                     internalLogger.errorExceptions.get(0).getMessage());
    }


    @Test
    public void testCreateGroupWithRetentionPolicy() throws Exception
    {
        // note: this mock doesn't override retrieveSequenceToken(), so writer thinks stream already exists
        //       not something that would ever happen in real life, but we're testing the log group only
        mock = new MockCloudWatchFacade(config)
        {
            @Override
            public String findLogGroup() throws CloudWatchFacadeException
            {
                // called once to discover group doesn't exist, a second time after createLogGroup()
                if (findLogGroupInvocationCount == 1)
                    return null;

                return super.findLogGroup();
            }
        };

        // everything tested here happens during initialization
        config.setRetentionPeriod(3);
        createWriter();

        assertEquals("findLogGroup: invocation count",              2,                      mock.findLogGroupInvocationCount);
        assertEquals("retrieveSequenceToken: invocation count",     1,                      mock.retrieveSequenceTokenInvocationCount);
        assertEquals("createLogGroup: invocation count",            1,                      mock.createLogGroupInvocationCount);
        assertEquals("createLogStream: invocation count",           0,                      mock.createLogStreamInvocationCount);
        assertEquals("setLogGroupRetention: invocation count",      1,                      mock.setLogGroupRetentionInvocationCount);

        internalLogger.assertInternalDebugLog("log writer starting.*",
                                              "creating CloudWatch log group: argle",
                                              "setting retention period to: 3",
                                              "using existing CloudWatch log stream: bargle",   // because mock said it doesn't need to be created
                                              "log writer initialization complete.*");
        internalLogger.assertInternalWarningLog();
        internalLogger.assertInternalErrorLog();
    }


    @Test
    public void testCreateGroupWithRetentionPolicyException() throws Exception
    {
        final Exception cause = new RuntimeException("message irrelevant");
        mock = new MockCloudWatchFacade(config)
        {
            @Override
            public String findLogGroup() throws CloudWatchFacadeException
            {
                // called once to discover group doesn't exist, a second time after createLogGroup()
                if (findLogGroupInvocationCount == 1)
                    return null;

                return super.findLogGroup();
            }

            @Override
            public void setLogGroupRetention() throws CloudWatchFacadeException
            {
                throw new CloudWatchFacadeException(ReasonCode.UNEXPECTED_EXCEPTION, false, cause);
            }

            @Override
            public String retrieveSequenceToken() throws CloudWatchFacadeException
            {
                // for this test I want to go through a "normal creation sequence
                if (retrieveSequenceTokenInvocationCount == 1)
                    return null;
                return super.retrieveSequenceToken();
            }

        };

        // everything tested here happens during initialization
        config.setRetentionPeriod(3);
        createWriter();

        // an exception when setting retention policy should not affect subsequent operation

        assertEquals("findLogGroup: invocation count",              2,                      mock.findLogGroupInvocationCount);
        assertEquals("retrieveSequenceToken: invocation count",     2,                      mock.retrieveSequenceTokenInvocationCount);
        assertEquals("createLogGroup: invocation count",            1,                      mock.createLogGroupInvocationCount);
        assertEquals("createLogStream: invocation count",           1,                      mock.createLogStreamInvocationCount);
        assertEquals("setLogGroupRetention: invocation count",      1,                      mock.setLogGroupRetentionInvocationCount);

        internalLogger.assertInternalDebugLog("log writer starting.*",
                                              "creating CloudWatch log group: argle",
                                              "setting retention period to: 3",
                                              "creating CloudWatch log stream: bargle",
                                              "log writer initialization complete.*");
        internalLogger.assertInternalWarningLog();
        internalLogger.assertInternalErrorLog("exception setting retention policy");

        assertUltimateCause("original exception reported", cause, internalLogger.errorExceptions.get(0));
    }


    @Test
    public void testCreateStream() throws Exception
    {
        mock = new MockCloudWatchFacade(config)
        {
            @Override
            public String retrieveSequenceToken() throws CloudWatchFacadeException
            {
                // first call is during initialization, second and third are waiting for stream
                if (retrieveSequenceTokenInvocationCount < 4)
                    return null;

                return super.retrieveSequenceToken();
            }
        };

        // everything tested here happens during initialization
        createWriter();

        // will call findLogGroup when checking group existence
        // will call retrieveSequenceToken when checking stream existence, and while waiting for stream

        assertEquals("findLogGroup: invocation count",              1,                      mock.findLogGroupInvocationCount);
        assertEquals("retrieveSequenceToken: invocation count",     4,                      mock.retrieveSequenceTokenInvocationCount);
        assertEquals("createLogGroup: invocation count",            0,                      mock.createLogGroupInvocationCount);
        assertEquals("createLogStream: invocation count",           1,                      mock.createLogStreamInvocationCount);

        internalLogger.assertInternalDebugLog("log writer starting.*",
                                              "using existing CloudWatch log group: argle",
                                              "creating CloudWatch log stream: bargle",
                                              "log writer initialization complete.*");
        internalLogger.assertInternalWarningLog();
        internalLogger.assertInternalErrorLog();
    }


    @Test
    public void testCreateStreamWithThrottling() throws Exception
    {
        mock = new MockCloudWatchFacade(config)
        {
            @Override
            public void createLogStream() throws CloudWatchFacadeException
            {
                // note: exception message is asserted later
                if (createLogStreamInvocationCount == 1)
                    throw new CloudWatchFacadeException(ReasonCode.THROTTLING, true, null);

                super.createLogStream();
            }

            @Override
            public String retrieveSequenceToken() throws CloudWatchFacadeException
            {
                // we'll assume creation is instant, so only return null to trigger it
                if (retrieveSequenceTokenInvocationCount == 1)
                    return null;

                return super.retrieveSequenceToken();
            }
        };

        // everything tested here happens during initialization
        createWriter();

        // will call findLogGroup when checking group existence
        // will call retrieveSequenceToken when checking stream existence, and after stream creation

        assertEquals("findLogGroup: invocation count",              1,                      mock.findLogGroupInvocationCount);
        assertEquals("retrieveSequenceToken: invocation count",     2,                      mock.retrieveSequenceTokenInvocationCount);
        assertEquals("createLogGroup: invocation count",            0,                      mock.createLogGroupInvocationCount);
        assertEquals("createLogStream: invocation count",           2,                      mock.createLogStreamInvocationCount);

        internalLogger.assertInternalDebugLog("log writer starting.*",
                                              "using existing CloudWatch log group: argle",
                                              "creating CloudWatch log stream: bargle",
                                              "log writer initialization complete.*");
        internalLogger.assertInternalErrorLog();
    }


    @Test
    public void testCreateStreamException() throws Exception
    {
        final RuntimeException cause = new RuntimeException("I should be preserved");
        mock = new MockCloudWatchFacade(config)
        {
            @Override
            public void createLogStream() throws CloudWatchFacadeException
            {
                if (createLogStreamInvocationCount == 1)
                    throw new CloudWatchFacadeException(ReasonCode.UNEXPECTED_EXCEPTION, false, cause);

                super.createLogStream();
            }

            @Override
            public String retrieveSequenceToken() throws CloudWatchFacadeException
            {
                // we'll assume creation is instant, so only return null to trigger it
                if (retrieveSequenceTokenInvocationCount == 1)
                {
                    return null;
                }
                return super.retrieveSequenceToken();
            }
        };

        // everything tested here happens during initialization
        createWriter();

        assertFalse("writer is running", writer.isRunning());

        // will call findLogGroup when checking group existence
        // will call retrieveSequenceToken when checking stream existence, and after stream creation

        assertEquals("findLogGroup: invocation count",              1,                      mock.findLogGroupInvocationCount);
        assertEquals("retrieveSequenceToken: invocation count",     1,                      mock.retrieveSequenceTokenInvocationCount);
        assertEquals("createLogGroup: invocation count",            0,                      mock.createLogGroupInvocationCount);
        assertEquals("createLogStream: invocation count",           1,                      mock.createLogStreamInvocationCount);

        internalLogger.assertInternalDebugLog(
                        "log writer starting.*",
                        "using existing CloudWatch log group: argle",
                        "creating CloudWatch log stream: bargle");
        internalLogger.assertInternalWarningLog();
        internalLogger.assertInternalErrorLog(
                        "unable to configure log group/stream",
                        "log writer failed to initialize.*");

        assertUltimateCause("original exception reported", cause, internalLogger.errorExceptions.get(0));
    }


    @Test
    public void testCreateStreamExceptionWhileWaiting() throws Exception
    {
        final RuntimeException cause = new RuntimeException("I should be preserved");
        mock = new MockCloudWatchFacade(config)
        {
            @Override
            public String retrieveSequenceToken() throws CloudWatchFacadeException
            {
                // this will trigger create
                if (retrieveSequenceTokenInvocationCount == 1)
                    return null;

                // this will be called in wait loop
                throw new CloudWatchFacadeException(ReasonCode.UNEXPECTED_EXCEPTION, false, cause);
            }
        };

        // everything tested here happens during initialization
        createWriter();

        assertFalse("writer is running", writer.isRunning());

        // will call findLogGroup when checking group existence
        // will call retrieveSequenceToken when checking stream existence, and after stream creation

        assertEquals("findLogGroup: invocation count",              1,                      mock.findLogGroupInvocationCount);
        assertEquals("retrieveSequenceToken: invocation count",     2,                      mock.retrieveSequenceTokenInvocationCount);
        assertEquals("createLogGroup: invocation count",            0,                      mock.createLogGroupInvocationCount);
        assertEquals("createLogStream: invocation count",           1,                      mock.createLogStreamInvocationCount);

        internalLogger.assertInternalDebugLog(
                        "log writer starting.*",
                        "using existing CloudWatch log group: argle",
                        "creating CloudWatch log stream: bargle");
        internalLogger.assertInternalWarningLog();
        internalLogger.assertInternalErrorLog(
                        "unable to configure log group/stream",
                        "log writer failed to initialize.*");

        assertUltimateCause("original exception reported", cause, internalLogger.errorExceptions.get(0));
    }


    @Test
    public void testWriteDedicatedWriterHappyPath() throws Exception
    {
        mock = new MockCloudWatchFacade(config);
        createWriter();

        String expectedToken = mock.nextSequenceToken;
        writer.addMessage(new LogMessage(System.currentTimeMillis(), "message one"));
        waitForWriterThread();

        // will call findLogGroup when checking group existence
        // will call retrieveSequenceToken when checking stream existence, then again when sending first batch

        assertEquals("findLogGroup: invocation count",              1,                      mock.findLogGroupInvocationCount);
        assertEquals("retrieveSequenceToken: invocation count",     2,                      mock.retrieveSequenceTokenInvocationCount);
        assertEquals("createLogGroup: invocation count",            0,                      mock.createLogGroupInvocationCount);
        assertEquals("createLogStream: invocation count",           0,                      mock.createLogStreamInvocationCount);
        assertEquals("putEvents: invocation count",                 1,                      mock.putEventsInvocationCount);
        assertEquals("putEvents: sequence token",                   expectedToken,          mock.putEventsSequenceToken);
        assertEquals("putEvents: last call #/messages",             1,                      mock.putEventsMessages.size());
        assertEquals("putEvents: last call message",                "message one",          mock.putEventsMessages.get(0).getMessage());
        assertNotSame("putEvents: invocation thread",               Thread.currentThread(), mock.putEventsThread);

        assertEquals("message has been removed from queue",         0,                      messageQueue.size());

        assertStatisticsTotalMessagesSent(1);
        assertEquals("statistics: last batch messages sent",        1,                      stats.getMessagesSentLastBatch());

        expectedToken = mock.nextSequenceToken;

        writer.addMessage(new LogMessage(System.currentTimeMillis(), "message two"));
        writer.addMessage(new LogMessage(System.currentTimeMillis(), "message three"));
        waitForWriterThread();

        // both messages should end up on the same batch; sequence token is cached, so no further calls to retrieve

        assertEquals("findLogGroup: invocation count",              1,                      mock.findLogGroupInvocationCount);
        assertEquals("retrieveSequenceToken: invocation count",     2,                      mock.retrieveSequenceTokenInvocationCount);
        assertEquals("createLogGroup: invocation count",            0,                      mock.createLogGroupInvocationCount);
        assertEquals("createLogStream: invocation count",           0,                      mock.createLogStreamInvocationCount);
        assertEquals("putEvents: invocation count",                 2,                      mock.putEventsInvocationCount);
        assertEquals("putEvents: sequence token",                   expectedToken,          mock.putEventsSequenceToken);
        assertEquals("putEvents: last call #/messages",             2,                      mock.putEventsMessages.size());
        assertEquals("putEvents: last call first message",          "message two",          mock.putEventsMessages.get(0).getMessage());
        assertEquals("putEvents: last call second message",         "message three",        mock.putEventsMessages.get(1).getMessage());

        assertStatisticsTotalMessagesSent(3);
        assertEquals("statistics: last batch messages sent",        2,                      stats.getMessagesSentLastBatch());

        assertEquals("all messages processed", Arrays.asList("message one", "message two", "message three"), mock.allMessagesSent);

        internalLogger.assertInternalDebugLog("log writer starting.*",
                                              "using existing CloudWatch log group: argle",
                                              "using existing CloudWatch log stream: bargle",
                                              "log writer initialization complete.*");
        internalLogger.assertInternalWarningLog();
        internalLogger.assertInternalErrorLog();
    }


    @Test
    public void testWriteNonDedicatedWriter() throws Exception
    {
        config.setDedicatedWriter(false);
        mock = new MockCloudWatchFacade(config);
        createWriter();

        String expectedToken = mock.nextSequenceToken;
        writer.addMessage(new LogMessage(System.currentTimeMillis(), "message one"));
        waitForWriterThread();

        // will call findLogGroup when checking group existence
        // will call retrieveSequenceToken when checking stream existence, and before first putLogEvents

        assertEquals("findLogGroup: invocation count",              1,                      mock.findLogGroupInvocationCount);
        assertEquals("retrieveSequenceToken: invocation count",     2,                      mock.retrieveSequenceTokenInvocationCount);
        assertEquals("createLogGroup: invocation count",            0,                      mock.createLogGroupInvocationCount);
        assertEquals("createLogStream: invocation count",           0,                      mock.createLogStreamInvocationCount);
        assertEquals("putEvents: invocation count",                 1,                      mock.putEventsInvocationCount);
        assertEquals("putEvents: sequence token",                   expectedToken,          mock.putEventsSequenceToken);
        assertEquals("putEvents: last call #/messages",             1,                      mock.putEventsMessages.size());
        assertEquals("putEvents: last call message",                "message one",          mock.putEventsMessages.get(0).getMessage());
        assertNotSame("putEvents: invocation thread",               Thread.currentThread(), mock.putEventsThread);

        assertEquals("message has been removed from queue",         0,                      messageQueue.size());

        assertStatisticsTotalMessagesSent(1);
        assertEquals("statistics: last batch messages sent",        1,                      stats.getMessagesSentLastBatch());

        expectedToken = mock.nextSequenceToken;
        writer.addMessage(new LogMessage(System.currentTimeMillis(), "message two"));
        writer.addMessage(new LogMessage(System.currentTimeMillis(), "message three"));
        waitForWriterThread();

        // next batch requires another call to retrieveSequenceToken

        assertEquals("findLogGroup: invocation count",              1,                      mock.findLogGroupInvocationCount);
        assertEquals("retrieveSequenceToken: invocation count",     3,                      mock.retrieveSequenceTokenInvocationCount);
        assertEquals("createLogGroup: invocation count",            0,                      mock.createLogGroupInvocationCount);
        assertEquals("createLogStream: invocation count",           0,                      mock.createLogStreamInvocationCount);
        assertEquals("putEvents: invocation count",                 2,                      mock.putEventsInvocationCount);
        assertEquals("putEvents: sequence token",                   expectedToken,          mock.putEventsSequenceToken);
        assertEquals("putEvents: last call #/messages",             2,                      mock.putEventsMessages.size());
        assertEquals("putEvents: last call first message",          "message two",          mock.putEventsMessages.get(0).getMessage());
        assertEquals("putEvents: last call second message",         "message three",        mock.putEventsMessages.get(1).getMessage());

        assertStatisticsTotalMessagesSent(3);
        assertEquals("statistics: last batch messages sent",        2,                      stats.getMessagesSentLastBatch());

        assertEquals("all messages processed", Arrays.asList("message one", "message two", "message three"), mock.allMessagesSent);

        internalLogger.assertInternalDebugLog("log writer starting.*",
                                              "using existing CloudWatch log group: argle",
                                              "using existing CloudWatch log stream: bargle",
                                              "log writer initialization complete.*");
        internalLogger.assertInternalErrorLog();
    }


    @Test
    public void testWriteThrottling() throws Exception
    {
        mock = new MockCloudWatchFacade(config)
        {
            @Override
            public String sendMessages(String sequenceToken, List<LogMessage> messages)
            throws CloudWatchFacadeException
            {
                // we'll do every other batch
                if (putEventsInvocationCount % 2 == 1)
                    throw new CloudWatchFacadeException(ReasonCode.THROTTLING, true, null);

                return super.sendMessages(sequenceToken, messages);
            }
        };

        createWriter();

        String expectedToken = mock.nextSequenceToken;
        writer.addMessage(new LogMessage(System.currentTimeMillis(), "message one"));
        waitForWriterThread();

        assertEquals("putEvents: invocation count",                 2,                      mock.putEventsInvocationCount);
        assertEquals("putEvents: sequence token",                   expectedToken,          mock.putEventsSequenceToken);
        assertEquals("putEvents: last call #/messages",             1,                      mock.putEventsMessages.size());
        assertEquals("putEvents: last call message",                "message one",          mock.putEventsMessages.get(0).getMessage());

        assertEquals("message has been removed from queue",         0,                      messageQueue.size());

        expectedToken = mock.nextSequenceToken;
        writer.addMessage(new LogMessage(System.currentTimeMillis(), "message two"));
        waitForWriterThread();

        assertEquals("putEvents: invocation count",                 4,                      mock.putEventsInvocationCount);
        assertEquals("putEvents: sequence token",                   expectedToken,          mock.putEventsSequenceToken);
        assertEquals("putEvents: last call #/messages",             1,                      mock.putEventsMessages.size());
        assertEquals("putEvents: last call message",                "message two",          mock.putEventsMessages.get(0).getMessage());

        assertEquals("message has been removed from queue",         0,                      messageQueue.size());

        assertEquals("all messages processed", Arrays.asList("message one", "message two"), mock.allMessagesSent);

        internalLogger.assertInternalDebugLog("log writer starting.*",
                                              "using existing CloudWatch log group: argle",
                                              "using existing CloudWatch log stream: bargle",
                                              "log writer initialization complete.*");
        internalLogger.assertInternalWarningLog();
        internalLogger.assertInternalErrorLog();
    }


    @Test
    public void testWriteUnrecoveredThrottling() throws Exception
    {
        mock = new MockCloudWatchFacade(config)
        {
            @Override
            public String sendMessages(String sequenceToken, List<LogMessage> messages)
            throws CloudWatchFacadeException
            {
                throw new CloudWatchFacadeException(ReasonCode.THROTTLING, true, null);
            }
        };

        createWriter();

        String expectedToken = mock.nextSequenceToken;
        writer.addMessage(new LogMessage(System.currentTimeMillis(), "message one"));
        waitForWriterThread();

        // the retry managers used by the testable logwriter will timeout after 4 retries

        assertEquals("putEvents: invocation count",                 4,                      mock.putEventsInvocationCount);
        assertEquals("putEvents: sequence token",                   expectedToken,          mock.putEventsSequenceToken);
        assertEquals("putEvents: last call #/messages",             1,                      mock.putEventsMessages.size());
        assertEquals("putEvents: last call message",                "message one",          mock.putEventsMessages.get(0).getMessage());

        assertEquals("message has been returned to queue",          1,                      messageQueue.size());

        internalLogger.assertInternalDebugLog("log writer starting.*",
                                              "using existing CloudWatch log group: argle",
                                              "using existing CloudWatch log stream: bargle",
                                              "log writer initialization complete.*");
        internalLogger.assertInternalWarningLog("batch failed: repeated throttling");
        internalLogger.assertInternalErrorLog();
    }


    @Test
    public void testWriteInvalidSequenceToken() throws Exception
    {
        mock = new MockCloudWatchFacade(config)
        {
            @Override
            public String sendMessages(String sequenceToken, List<LogMessage> messages)
            throws CloudWatchFacadeException
            {
                if (putEventsInvocationCount < 2)
                {
                    throw new CloudWatchFacadeException(ReasonCode.INVALID_SEQUENCE_TOKEN, true, null);
                }
                return super.sendMessages(sequenceToken, messages);
            }
        };

        createWriter();

        writer.addMessage(new LogMessage(System.currentTimeMillis(), "message one"));
        waitForWriterThread();

        // will call retrieveSequenceToken once when checking stream existence, before sending batch, and to reset after exception

        assertEquals("findLogGroup: invocation count",              1,                      mock.findLogGroupInvocationCount);
        assertEquals("retrieveSequenceToken: invocation count",     3,                      mock.retrieveSequenceTokenInvocationCount);
        assertEquals("createLogGroup: invocation count",            0,                      mock.createLogGroupInvocationCount);
        assertEquals("createLogStream: invocation count",           0,                      mock.createLogStreamInvocationCount);
        assertEquals("putEvents: invocation count",                 2,                      mock.putEventsInvocationCount);

        assertEquals("message has been removed from queue",         0,                      messageQueue.size());

        assertStatisticsTotalMessagesSent(1);
        assertEquals("statistics: last batch messages sent",        1,                      stats.getMessagesSentLastBatch());

        internalLogger.assertInternalDebugLog("log writer starting.*",
                                              "using existing CloudWatch log group: argle",
                                              "using existing CloudWatch log stream: bargle",
                                              "log writer initialization complete.*");
        internalLogger.assertInternalWarningLog();
        internalLogger.assertInternalErrorLog();
    }


    @Test
    public void testWriteUnrecoveredInvalidSequenceToken() throws Exception
    {
        mock = new MockCloudWatchFacade(config)
        {
            @Override
            public String sendMessages(String sequenceToken, List<LogMessage> messages)
            throws CloudWatchFacadeException
            {
                throw new CloudWatchFacadeException(ReasonCode.INVALID_SEQUENCE_TOKEN, true, null);
            }
        };

        createWriter();

        String expectedToken = mock.nextSequenceToken;
        writer.addMessage(new LogMessage(System.currentTimeMillis(), "message one"));
        waitForWriterThread();

        // the retry managers used by the testable logwriter will timeout after 4 retries

        assertEquals("putEvents: invocation count",                 4,                      mock.putEventsInvocationCount);
        assertEquals("putEvents: sequence token",                   expectedToken,          mock.putEventsSequenceToken);
        assertEquals("putEvents: last call #/messages",             1,                      mock.putEventsMessages.size());
        assertEquals("putEvents: last call message",                "message one",          mock.putEventsMessages.get(0).getMessage());

        assertEquals("message has been returned to queue",          1,                      messageQueue.size());

        assertEquals("stats: reported sequence token race",         4,                      stats.getWriterRaceRetries());
        assertEquals("stats: reported sequence token race failure", 1,                      stats.getUnrecoveredWriterRaceRetries());

        internalLogger.assertInternalDebugLog("log writer starting.*",
                                              "using existing CloudWatch log group: argle",
                                              "using existing CloudWatch log stream: bargle",
                                              "log writer initialization complete.*");
        internalLogger.assertInternalWarningLog("batch failed: unrecovered sequence token race");
        internalLogger.assertInternalErrorLog();
    }


    @Test
    public void testWriteAlreadyAccepted() throws Exception
    {
        mock = new MockCloudWatchFacade(config)
        {
            @Override
            public String sendMessages(String sequenceToken, List<LogMessage> messages)
            throws CloudWatchFacadeException
            {
                if (putEventsInvocationCount == 1)
                    throw new CloudWatchFacadeException(ReasonCode.ALREADY_PROCESSED, false, null);

                return super.sendMessages(sequenceToken, messages);
            }
        };

        createWriter();

        String expectedToken = mock.nextSequenceToken;
        writer.addMessage(new LogMessage(System.currentTimeMillis(), "message one"));
        waitForWriterThread();

        assertEquals("putEvents: invocation count",                 1,                      mock.putEventsInvocationCount);
        assertEquals("putEvents: sequence token",                   expectedToken,          mock.putEventsSequenceToken);
        assertEquals("putEvents: last call #/messages",             1,                      mock.putEventsMessages.size());
        assertEquals("putEvents: last call message",                "message one",          mock.putEventsMessages.get(0).getMessage());

        assertEquals("message has been removed from queue",         0,                      messageQueue.size());

        // now a different message

        expectedToken = mock.nextSequenceToken;
        writer.addMessage(new LogMessage(System.currentTimeMillis(), "message two"));
        waitForWriterThread();

        assertEquals("putEvents: invocation count",                 2,                      mock.putEventsInvocationCount);
        assertEquals("putEvents: sequence token",                   expectedToken,          mock.putEventsSequenceToken);
        assertEquals("putEvents: last call #/messages",             1,                      mock.putEventsMessages.size());
        assertEquals("putEvents: last call message",                "message two",          mock.putEventsMessages.get(0).getMessage());

        assertEquals("message has been removed from queue",         0,                      messageQueue.size());

        assertEquals("all messages processed", Arrays.asList("message two"), mock.allMessagesSent);

        internalLogger.assertInternalDebugLog("log writer starting.*",
                                              "using existing CloudWatch log group: argle",
                                              "using existing CloudWatch log stream: bargle",
                                              "log writer initialization complete.*");
        internalLogger.assertInternalWarningLog("received DataAlreadyAcceptedException.*");
        internalLogger.assertInternalErrorLog();
    }


    @Test
    public void testWriteUnexpectedException() throws Exception
    {
        RuntimeException cause = new RuntimeException("that call never works");
        mock = new MockCloudWatchFacade(config)
        {
            @Override
            public String sendMessages(String sequenceToken, List<LogMessage> messages)
            throws CloudWatchFacadeException
            {
                throw new CloudWatchFacadeException(ReasonCode.UNEXPECTED_EXCEPTION, false, cause);
            }
        };

        createWriter();

        String expectedToken = mock.nextSequenceToken;
        writer.addMessage(new LogMessage(System.currentTimeMillis(), "message one"));
        waitForWriterThread();

        assertEquals("putEvents: invocation count",                 1,                      mock.putEventsInvocationCount);
        assertEquals("putEvents: sequence token",                   expectedToken,          mock.putEventsSequenceToken);
        assertEquals("putEvents: last call #/messages",             1,                      mock.putEventsMessages.size());
        assertEquals("putEvents: last call message",                "message one",          mock.putEventsMessages.get(0).getMessage());

        assertEquals("message has been returned to queue",          1,                      messageQueue.size());

        // let writer try again

        waitForWriterThread();

        assertEquals("putEvents: invocation count",                 2,                      mock.putEventsInvocationCount);
        assertEquals("putEvents: sequence token",                   expectedToken,          mock.putEventsSequenceToken);
        assertEquals("putEvents: last call #/messages",             1,                      mock.putEventsMessages.size());
        assertEquals("putEvents: last call message",                "message one",          mock.putEventsMessages.get(0).getMessage());

        assertEquals("message has been returned to queue",          1,                      messageQueue.size());

        // we could keep doing this all day ... in the real world, messages would eventually drop from queue

        internalLogger.assertInternalDebugLog("log writer starting.*",
                                              "using existing CloudWatch log group: argle",
                                              "using existing CloudWatch log stream: bargle",
                                              "log writer initialization complete.*");
        internalLogger.assertInternalWarningLog();
        internalLogger.assertInternalErrorLog("failed to send: use for testing only",
                                              "failed to send: use for testing only");

        assertUltimateCause("original exception reported, first try",  cause, internalLogger.errorExceptions.get(0));
        assertUltimateCause("original exception reported, second try", cause, internalLogger.errorExceptions.get(1));
    }


    @Test
    public void testWriteStreamDeleted() throws Exception
    {
        RuntimeException cause = new RuntimeException("that call never works");
        mock = new MockCloudWatchFacade(config)
        {
            @Override
            public String retrieveSequenceToken() throws CloudWatchFacadeException
            {
                // third call is check after exception
                if (retrieveSequenceTokenInvocationCount == 3)
                    return null;

                return super.retrieveSequenceToken();
            }

            @Override
            public String sendMessages(String sequenceToken, List<LogMessage> messages)
            throws CloudWatchFacadeException
            {
                // we assert this message; actual message has more detail
                if (putEventsInvocationCount == 1)
                    throw new CloudWatchFacadeException("stream deleted", cause, ReasonCode.MISSING_LOG_STREAM, false,null);

                return super.sendMessages(sequenceToken, messages);
            }
        };

        createWriter();

        String expectedToken = mock.nextSequenceToken;
        writer.addMessage(new LogMessage(System.currentTimeMillis(), "message one"));
        waitForWriterThread();

        // the writer should recover while sending its batch, but put the message back on the queue for next time
        // 2 calls to find log group: initial check, existence check after failure
        // 4 calls to retrieve sequence token: initial check, retrieve before send, existence check after failure, wait for stream creation

        assertEquals("findLogGroup: invocation count",              2,                      mock.findLogGroupInvocationCount);
        assertEquals("retrieveSequenceToken: invocation count",     4,                      mock.retrieveSequenceTokenInvocationCount);
        assertEquals("createLogGroup: invocation count",            0,                      mock.createLogGroupInvocationCount);
        assertEquals("createLogStream: invocation count",           1,                      mock.createLogStreamInvocationCount);

        assertEquals("putEvents: invocation count",                 1,                      mock.putEventsInvocationCount);
        assertEquals("putEvents: sequence token",                   expectedToken,          mock.putEventsSequenceToken);
        assertEquals("putEvents: last call #/messages",             1,                      mock.putEventsMessages.size());
        assertEquals("putEvents: last call message",                "message one",          mock.putEventsMessages.get(0).getMessage());

        assertEquals("message has been returned to queue",          1,                      messageQueue.size());

        internalLogger.assertInternalDebugLog("log writer starting.*",
                                              "using existing CloudWatch log group: argle",
                                              "using existing CloudWatch log stream: bargle",
                                              "log writer initialization complete.*",
                                              "using existing CloudWatch log group: argle",
                                              "creating CloudWatch log stream: bargle");
        internalLogger.assertInternalWarningLog();
        internalLogger.assertInternalErrorLog("stream deleted");
    }


    @Test
    public void testDiscardEmptyMessages() throws Exception
    {
        // note: this actually tests superclass behavior

        mock = new MockCloudWatchFacade(config);
        createWriter();

        // nothing up my sleeve!
        internalLogger.assertInternalWarningLog();

        writer.addMessage(new LogMessage(System.currentTimeMillis(), ""));
        internalLogger.assertInternalWarningLog("discarded empty message");

        writer.addMessage(new LogMessage(System.currentTimeMillis(), "this one's good"));

        // we'll let the writer run, to ensure that only one message is passed to send
        waitForWriterThread();

        assertEquals("putEvents: invocation count",                 1,                      mock.putEventsInvocationCount);
        assertEquals("putEvents: last call #/messages",             1,                      mock.putEventsMessages.size());
        assertEquals("putEvents: last call message",                "this one's good",      mock.putEventsMessages.get(0).getMessage());

        internalLogger.assertInternalErrorLog();
    }


    @Test
    public void testDiscardOversizeMessages() throws Exception
    {
        final int cloudwatchMaximumEventSize    = 256 * 1024;   // copied from https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/cloudwatch_limits_cwl.html
        final int cloudwatchOverhead            = 26;           // copied from https://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_PutLogEvents.html
        final int cloudwatchMaximumMessageSize  = cloudwatchMaximumEventSize - cloudwatchOverhead;

        // using different characters at the end of the message makes JUnit output easer to read
        final String bigMessage                 = StringUtil.repeat('X', cloudwatchMaximumMessageSize - 1) + "Y";
        final String biggerMessage              = bigMessage + "X";

        mock = new MockCloudWatchFacade(config);
        createWriter();

        internalLogger.assertInternalWarningLog();

        writer.addMessage(new LogMessage(System.currentTimeMillis(), biggerMessage));
        internalLogger.assertInternalWarningLog("discarded oversize message.*");

        writer.addMessage(new LogMessage(System.currentTimeMillis(), bigMessage));

        // we'll let the writer run, to ensure that only one message is passed to send
        waitForWriterThread();

        assertEquals("putEvents: invocation count",                 1,              mock.putEventsInvocationCount);
        assertEquals("putEvents: last call #/messages",             1,              mock.putEventsMessages.size());
        assertEquals("putEvents: last call message",                bigMessage,     mock.putEventsMessages.get(0).getMessage());

        internalLogger.assertInternalErrorLog();
    }


    @Test
    public void testTruncateOversizeMessages() throws Exception
    {
        final int cloudwatchMaximumEventSize    = 256 * 1024;   // copied from https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/cloudwatch_limits_cwl.html
        final int cloudwatchOverhead            = 26;           // copied from https://docs.aws.amazon.com/AmazonCloudWatchLogs/latest/APIReference/API_PutLogEvents.html
        final int cloudwatchMaximumMessageSize  = cloudwatchMaximumEventSize - cloudwatchOverhead;

        // using different characters at the end of the message makes JUnit output easer to read
        final String bigMessage                 = StringUtil.repeat('X', cloudwatchMaximumMessageSize - 1) + "Y";
        final String biggerMessage              = bigMessage + "X";

        config.setTruncateOversizeMessages(true);
        mock = new MockCloudWatchFacade(config);
        createWriter();

        internalLogger.assertInternalWarningLog();

        writer.addMessage(new LogMessage(System.currentTimeMillis(), biggerMessage));
        internalLogger.assertInternalWarningLog("truncated oversize message.*");

        writer.addMessage(new LogMessage(System.currentTimeMillis(), bigMessage));

        // we'll let the writer run, to ensure that only one message is passed to send
        waitForWriterThread();

        assertEquals("putEvents: invocation count",                 1,              mock.putEventsInvocationCount);
        assertEquals("putEvents: last call #/messages",             2,              mock.putEventsMessages.size());
        assertEquals("putEvents: last call first message",          bigMessage,     mock.putEventsMessages.get(0).getMessage());
        assertEquals("putEvents: last call second message",         bigMessage,     mock.putEventsMessages.get(1).getMessage());

        internalLogger.assertInternalErrorLog();
    }


    @Test
    public void testBatchConstructionByRecordCount() throws Exception
    {
        // don't let discard threshold get in the way of the test
        config.setDiscardAction(DiscardAction.none);
        config.setDiscardThreshold(Integer.MAX_VALUE);

        // increasing delay because it will may take time to add the messages -- 500 ms is way higher than we need
        config.setBatchDelay(500);

        mock = new MockCloudWatchFacade(config);
        createWriter();

        List<String> expectedMessages = new ArrayList<>();
        for (int ii = 0 ; ii < 15000 ; ii++)
        {
            String message = String.valueOf(ii);
            expectedMessages.add(message);
            writer.addMessage(new LogMessage(System.currentTimeMillis(), message));
        }

        // based on count, this should be broken into batches of 10,000 and 5,000

        waitForWriterThread();

        assertEquals("putEvents: invocation count",                 1,                      mock.putEventsInvocationCount);
        assertEquals("putEvents: last call #/messages",             10000,                  mock.putEventsMessages.size());
        assertEquals("putEvents: last call first message",          "0",                    mock.putEventsMessages.get(0).getMessage());
        assertEquals("putEvents: last call last message",           "9999",                 mock.putEventsMessages.get(9999).getMessage());
        assertEquals("unsent messages remain on queue",             5000,                   messageQueue.size());

        waitForWriterThread();

        assertEquals("putEvents: invocation count",                 2,                      mock.putEventsInvocationCount);
        assertEquals("putEvents: last call #/messages",             5000,                   mock.putEventsMessages.size());
        assertEquals("putEvents: last call first message",          "10000",                mock.putEventsMessages.get(0).getMessage());
        assertEquals("putEvents: last call last message",           "14999",                mock.putEventsMessages.get(4999).getMessage());
        assertEquals("no unsent messages remain on queue",          0,                      messageQueue.size());

        assertEquals("all messages sent, in order",                 expectedMessages,       mock.allMessagesSent);
    }


    @Test
    public void testBatchConstructionByTotalSize() throws Exception
    {
        // don't let discard threshold get in the way of the test
        config.setDiscardAction(DiscardAction.none);
        config.setDiscardThreshold(Integer.MAX_VALUE);

        // increasing delay because it will may take time to add the messages -- 500 ms is way higher than we need
        config.setBatchDelay(500);

        mock = new MockCloudWatchFacade(config);
        createWriter();

        List<String> expectedMessages = new ArrayList<>();
        for (int ii = 0 ; ii < 1500 ; ii++)
        {
            String message =  StringUtil.randomAlphaString(1024, 1024);
            expectedMessages.add(message);
            writer.addMessage(new LogMessage(System.currentTimeMillis(), message));
        }

        // based on size, this should be broken into batches of 998 and 502

        waitForWriterThread();

        assertEquals("putEvents: invocation count",                 1,                          mock.putEventsInvocationCount);
        assertEquals("putEvents: last call #/messages",             998,                        mock.putEventsMessages.size());
        assertEquals("putEvents: last call first message",          expectedMessages.get(0),    mock.putEventsMessages.get(0).getMessage());
        assertEquals("putEvents: last call last message",           expectedMessages.get(997),  mock.putEventsMessages.get(997).getMessage());
        assertEquals("unsent messages remain on queue",             502,                        messageQueue.size());

        waitForWriterThread();

        assertEquals("putEvents: invocation count",                 2,                          mock.putEventsInvocationCount);
        assertEquals("putEvents: last call #/messages",             502,                        mock.putEventsMessages.size());
        assertEquals("putEvents: last call first message",          expectedMessages.get(998),  mock.putEventsMessages.get(0).getMessage());
        assertEquals("putEvents: last call last message",           expectedMessages.get(1499), mock.putEventsMessages.get(501).getMessage());
        assertEquals("no unsent messages remain on queue",          0,                          messageQueue.size());

        assertEquals("all messages sent, in order",                 expectedMessages,           mock.allMessagesSent);
    }


    @Test
    public void testSynchronousOperation() throws Exception
    {
        config.setSynchronousMode(true);
        mock = new MockCloudWatchFacade(config);

        createWriter();
        ((TestableCloudWatchLogWriter)writer).disableThreadSynchronization();

        writer.addMessage(new LogMessage(System.currentTimeMillis(), "message one"));
        writer.addMessage(new LogMessage(System.currentTimeMillis(), "message two"));

        // no need to wait for thread, it should be sent by the call to addMessage();

        assertEquals("putEvents: invocation count",                 2,                      mock.putEventsInvocationCount);
        assertEquals("putEvents: last call #/messages",             1,                      mock.putEventsMessages.size());
        assertEquals("putEvents: last call message",                "message two",          mock.putEventsMessages.get(0).getMessage());
        assertSame("putEvents: invocation thread",                  Thread.currentThread(), mock.putEventsThread);

        assertEquals("message has been removed from queue",         0,                      messageQueue.size());

        internalLogger.assertInternalDebugLog("log writer starting.*",
                                              "using existing CloudWatch log group: argle",
                                              "using existing CloudWatch log stream: bargle",
                                              "log writer initialization complete.*");
        internalLogger.assertInternalWarningLog();
        internalLogger.assertInternalErrorLog();
    }

    // note: the following two tests actually exercies AbstractLogWriter functionality; they're not
    // replicated for other writers

    @Test
    public void testShutdown() throws Exception
    {
        // we want a longish delay so that the main thread can do stuff while waiting,
        // but not so long that the test takes forever
        final int batchDelay = 500;
        final int stopDelay = batchDelay / 2;
        config.setBatchDelay(batchDelay);

        mock = new MockCloudWatchFacade(config);
        createWriter();

        writer.addMessage(new LogMessage(System.currentTimeMillis(), "message one"));
        writer.addMessage(new LogMessage(System.currentTimeMillis(), "message two"));

        // at this point the writer should be blocked by the semaphore; the main thread will
        // become blocked by waitForWriterThread(); we'll trigger the stop() from a new thread,
        // and verify its behavior by the time taken to complete processing

        AtomicInteger messagesOnQueueAtStop = new AtomicInteger();
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                Utils.sleepQuietly(stopDelay);
                messagesOnQueueAtStop.set(messageQueue.size());
                writer.stop();
            }
        }).start();

        assertEquals("messages on queue before writer release",     2,                                  messageQueue.size());

        long writerReleasedAt = System.currentTimeMillis();
        waitForWriterThread();
        long mainReturnedAt = System.currentTimeMillis();

        assertTrue("writer is still running",                                                           writer.isRunning());
        assertEquals("messages on queue when stop() called",        0,                                  messagesOnQueueAtStop.get());
        assertEquals("putEvents: invocation count",                 1,                                  mock.putEventsInvocationCount);
        assertEquals("putEvents: #/messages",                       2,                                  mock.putEventsMessages.size());
        assertInRange("time to process",                            stopDelay - 10, stopDelay + 100,    mainReturnedAt - writerReleasedAt);

        // after the stop, writer should wait for batchDelay for any more messages, so let it run again
        // ... if this never returns, we know that the shutdown processing was incorrect
        waitForWriterThread();

        // the writer thread should be fully done at this point; the join() won't return if not
        ((TestableCloudWatchLogWriter)writer).writerThread.join();
        long shutdownTime = System.currentTimeMillis();

        assertFalse("writer has stopped",                           writer.isRunning());
        assertInRange("time to process",                            batchDelay - 100, batchDelay + 100, shutdownTime - mainReturnedAt);

        internalLogger.assertInternalDebugLog("log writer starting.*",
                                              "using existing CloudWatch log group: argle",
                                              "using existing CloudWatch log stream: bargle",
                                              "log writer initialization complete.*",
                                              "log.writer shut down.*");
        internalLogger.assertInternalWarningLog();
        internalLogger.assertInternalErrorLog();
    }


    @Test
    public void testShutdownHook() throws Exception
    {
        config.setUseShutdownHook(true);
        mock = new MockCloudWatchFacade(config);

        createWriter();
        ((TestableCloudWatchLogWriter)writer).disableThreadSynchronization();

        assertTrue("writer is running", writer.isRunning());

        Thread shutdownHook = ClassUtil.getFieldValue(writer, "shutdownHook", Thread.class);
        assertNotNull("writer has shutdown hook", shutdownHook);

        // this is an easy way to assert that the writer added the hook, without digging into
        // JVM internals; plus, we don't want it to actually run
        assertTrue("writer set shutdown hook", Runtime.getRuntime().removeShutdownHook(shutdownHook));

        shutdownHook.start();

        writer.waitUntilStopped(10000);
        assertFalse("writer has stopped", writer.isRunning());

        // this will never return if the shutdown hook hasn't run
        shutdownHook.join();
        internalLogger.assertInternalDebugLog("log writer starting.*",
                                              "using existing CloudWatch log group: argle",
                                              "using existing CloudWatch log stream: bargle",
                                              "log writer initialization complete.*",
                                              "shutdown hook invoked",
                                              "log.writer shut down.*");
        internalLogger.assertInternalWarningLog();
        internalLogger.assertInternalErrorLog();
    }
}
