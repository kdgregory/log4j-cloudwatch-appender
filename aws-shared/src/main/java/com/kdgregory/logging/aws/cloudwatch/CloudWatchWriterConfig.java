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

package com.kdgregory.logging.aws.cloudwatch;

import com.kdgregory.logging.aws.internal.AbstractWriterConfig;


/**
 *  Configuration for CloudWatchLogWriter.
 */
public class CloudWatchWriterConfig
extends AbstractWriterConfig<CloudWatchWriterConfig>
{
    private String  logGroupName;
    private String  logStreamName;
    private Integer retentionPeriod;
    private boolean dedicatedWriter;


    public String getLogGroupName()
    {
        return logGroupName;
    }

    public CloudWatchWriterConfig setLogGroupName(String value)
    {
        logGroupName = value;
        return this;
    }


    public String getLogStreamName()
    {
        return logStreamName;
    }

    public CloudWatchWriterConfig setLogStreamName(String value)
    {
        logStreamName = value;
        return this;
    }


    public Integer getRetentionPeriod()
    {
        return retentionPeriod;
    }

    public CloudWatchWriterConfig setRetentionPeriod(Integer value)
    {
        retentionPeriod = value;
        return this;
    }


    public boolean getDedicatedWriter()
    {
        return dedicatedWriter;
    }

    public CloudWatchWriterConfig setDedicatedWriter(boolean value)
    {
        dedicatedWriter = value;
        return this;
    }
}
