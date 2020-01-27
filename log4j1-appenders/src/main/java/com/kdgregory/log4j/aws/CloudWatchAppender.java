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

package com.kdgregory.log4j.aws;

import java.util.Date;

import com.kdgregory.logging.aws.cloudwatch.CloudWatchWriterStatistics;
import com.kdgregory.logging.aws.cloudwatch.CloudWatchWriterStatisticsMXBean;
import com.kdgregory.log4j.aws.internal.AbstractAppender;
import com.kdgregory.logging.aws.cloudwatch.CloudWatchConstants;
import com.kdgregory.logging.aws.cloudwatch.CloudWatchWriterConfig;
import com.kdgregory.logging.aws.cloudwatch.CloudWatchWriterFactory;
import com.kdgregory.logging.aws.common.Substitutions;
import com.kdgregory.logging.common.factories.DefaultThreadFactory;


/**
 *  Appender that writes to a CloudWatch log stream.
 */
public class CloudWatchAppender
extends AbstractAppender<CloudWatchWriterConfig,CloudWatchWriterStatistics,CloudWatchWriterStatisticsMXBean>
{
    private String  logGroup;
    private String  logStream;
    private Integer retentionPeriod;
    private boolean dedicatedWriter;


    /**
     *  Base constructor: assigns default values to configuration properties.
     */
    public CloudWatchAppender()
    {
        super(new DefaultThreadFactory("log4j-cloudwatch"),
              new CloudWatchWriterFactory(),
              new CloudWatchWriterStatistics(),
              CloudWatchWriterStatisticsMXBean.class);

        logStream = "{startupTimestamp}";
    }

//----------------------------------------------------------------------------
//  Configuration
//----------------------------------------------------------------------------

    /**
     *  Sets the CloudWatch Log Group associated with this appender.
     *  <p>
     *  You typically assign a single log group to an application, and then
     *  use multiple log streams for instances of that application.
     *  <p>
     *  There is no default value. If you do not configure the log group, the
     *  appender will be disabled and will report its misconfiguration.
     */
    public void setLogGroup(String value)
    {
        logGroup = value;
    }


    /**
     *  Returns the log group name; see {@link #setLogGroup}. Primarily used
     *  for testing.
     */
    public String getLogGroup()
    {
        return logGroup;
    }


    /**
     *  Sets the CloudWatch Log Stream associated with this appender.
     *  <p>
     *  You typically create a separate log stream for each instance of the
     *  application.
     *  <p>
     *  Default value is <code>{startTimestamp}</code>, the JVM startup timestamp.
     */
    public void setLogStream(String value)
    {
        logStream = value;
    }


    /**
     *  Returns the log stream name; see {@link #setLogStream}. Primarily used
     *  for testing.
     */
    public String getLogStream()
    {
        return logStream;
    }


    /**
     *  Sets the retention period, in days, for auto-created log groups. Beware that AWS
     *  limits the allowable values; see API documentation for details.
     */
    public void setRetentionPeriod(int value)
    {
        this.retentionPeriod = CloudWatchConstants.validateRetentionPeriod(value);
    }


    /**
     *  Returns the current retention period; 0 indicates that retention
     *  period has not been set (Log4J gets confused when setter is of a
     *  different type than getter, and it can't accept an Integer for
     *  the setter).
     */
    public int getRetentionPeriod()
    {
        return (retentionPeriod == null) ? 0 : retentionPeriod.intValue();
    }


    /**
     *  Sets a flag indicating that this appender will be the only writer to
     *  the stream. This allows the appender to cache the sequence token from
     *  each write, rather than requesting the current token (which will be
     *  throttled with large numbers of writers).
     */
    public void setDedicatedWriter(boolean value)
    {
        dedicatedWriter = value;
    }


    /**
     *  Returns the flag indicating whether appender is a dedicated writer.
     */
    public boolean getDedicatedWriter()
    {
        return dedicatedWriter;
    }

//----------------------------------------------------------------------------
//  AbstractAppender overrides
//----------------------------------------------------------------------------

    @Override
    /** {@inheritDoc} */
    public void setRotationMode(String value)
    {
        super.setRotationMode(value);
    }


    @Override
    /** {@inheritDoc} */
    public void rotate()
    {
        super.rotate();
    }


    @Override
    protected CloudWatchWriterConfig generateWriterConfig()
    {
        Substitutions subs      = new Substitutions(new Date(), sequence.get());
        String actualLogGroup   = subs.perform(logGroup);
        String actualLogStream  = subs.perform(logStream);

        return new CloudWatchWriterConfig(
            actualLogGroup, actualLogStream, retentionPeriod, dedicatedWriter,
            batchDelay, discardThreshold, discardAction,
            clientFactory, clientRegion, clientEndpoint);
    }
}
