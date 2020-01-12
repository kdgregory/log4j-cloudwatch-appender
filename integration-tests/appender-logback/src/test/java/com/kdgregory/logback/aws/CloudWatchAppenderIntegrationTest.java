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

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;

import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import net.sf.kdgcommons.lang.ClassUtil;

import com.kdgregory.logback.aws.testhelpers.MessageWriter;
import com.kdgregory.logging.aws.cloudwatch.CloudWatchLogWriter;
import com.kdgregory.logging.aws.cloudwatch.CloudWatchWriterStatistics;
import com.kdgregory.logging.test.AbstractCloudWatchAppenderIntegrationTest;
import com.kdgregory.logging.testhelpers.CloudWatchTestHelper;


public class CloudWatchAppenderIntegrationTest
extends AbstractCloudWatchAppenderIntegrationTest
{
//----------------------------------------------------------------------------
//  Helpers
//----------------------------------------------------------------------------

    /**
     *  Holds a logger instance and related objects, to support assertions.
     */
    public static class LoggerInfo
    implements LoggerAccessor
    {
        public ch.qos.logback.classic.Logger logger;
        public CloudWatchAppender<ILoggingEvent> appender;
        public CloudWatchWriterStatistics stats;

        public LoggerInfo(String loggerName, String appenderName)
        {
            logger = (ch.qos.logback.classic.Logger)LoggerFactory.getLogger(loggerName);
            appender = (CloudWatchAppender<ILoggingEvent>)logger.getAppender(appenderName);
            stats = appender.getAppenderStatistics();
        }

        @Override
        public MessageWriter createMessageWriter(int numMessages)
        {
            return new MessageWriter(logger, numMessages);
        }

        @Override
        public CloudWatchLogWriter getWriter()
        throws Exception
        {
            return ClassUtil.getFieldValue(appender, "writer", CloudWatchLogWriter.class);
        }

        @Override
        public CloudWatchWriterStatistics getStats()
        {
            return stats;
        }

        @Override
        public void setBatchDelay(long value)
        {
            appender.setBatchDelay(value);
        }
    }


    /**
     *  Loads the test-specific Logback configuration and resets the environment.
     */
    public void init(String testName) throws Exception
    {
        MDC.put("testName", testName);
        localLogger.info("starting");

        testHelper = new CloudWatchTestHelper(helperClient, "AppenderIntegrationTest-" + testName);

        // this has to happen before the logger is initialized or we have a race condition
        testHelper.deleteLogGroupIfExists();

        String propertiesName = "CloudWatchAppenderIntegrationTest/" + testName + ".xml";
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
        AbstractCloudWatchAppenderIntegrationTest.beforeClass();
    }


    @After
    @Override
    public void tearDown()
    {
        super.tearDown();
        MDC.clear();
    }

//----------------------------------------------------------------------------
//  Tests
//----------------------------------------------------------------------------

    @Test
    public void smoketest() throws Exception
    {
        init("smoketest");
        super.smoketest(new LoggerInfo("TestLogger", "test"));
    }


    @Test
    public void testMultipleThreadsSingleAppender() throws Exception
    {
        init("testMultipleThreadsSingleAppender");
        super.testMultipleThreadsSingleAppender(new LoggerInfo("TestLogger", "test"));
    }


    @Test
    public void testMultipleThreadsMultipleAppendersDifferentDestinations() throws Exception
    {
        init("testMultipleThreadsMultipleAppendersDifferentDestinations");
        super.testMultipleThreadsMultipleAppendersDifferentDestinations(
            new LoggerInfo("TestLogger1", "test1"),
            new LoggerInfo("TestLogger2", "test2"),
            new LoggerInfo("TestLogger3", "test3"));
    }


    @Test
    public void testMultipleThreadsMultipleAppendersSameDestination() throws Exception
    {
        init("testMultipleThreadsMultipleAppendersSameDestination");
        super.testMultipleThreadsMultipleAppendersSameDestination(
            new LoggerInfo("TestLogger1", "test1"),
            new LoggerInfo("TestLogger2", "test2"),
            new LoggerInfo("TestLogger3", "test3"),
            new LoggerInfo("TestLogger4", "test4"),
            new LoggerInfo("TestLogger5", "test5"));
    }


    @Test
    public void testLogstreamDeletionAndRecreation() throws Exception
    {
        init("testLogstreamDeletionAndRecreation");
        super.testLogstreamDeletionAndRecreation(new LoggerInfo("TestLogger", "test"));
    }


    @Test
    public void testFactoryMethod() throws Exception
    {
        init("testFactoryMethod");
        super.testFactoryMethod(new LoggerInfo("TestLogger", "test"));
    }


    @Test
    public void testAlternateRegion() throws Exception
    {
        init("testAlternateRegion");
        super.testAlternateRegion(new LoggerInfo("TestLogger", "test"));
    }

//----------------------------------------------------------------------------
//  Tests for synchronous operation -- this is handled in AbstractAppender,
//  so only needs to be tested for one appender type
//----------------------------------------------------------------------------

    @Test
    public void testSynchronousModeSingleThread() throws Exception
    {
        init("testSynchronousModeSingleThread");
        super.testSynchronousModeSingleThread(new LoggerInfo("TestLogger", "test"));
    }


    @Test
    public void testSynchronousModeMultiThread() throws Exception
    {
        init("testSynchronousModeMultiThread");
        super.testSynchronousModeMultiThread(new LoggerInfo("TestLogger", "test"));
    }
}
