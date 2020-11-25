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

package com.kdgregory.logging.aws.kinesis;

import com.kdgregory.logging.aws.internal.AbstractWriterConfig;


/**
 *  Configuration for KinesisLogWriter.
 */
public class KinesisWriterConfig
extends AbstractWriterConfig<KinesisWriterConfig>
{
    private String  streamName;
    private String  partitionKey;
    private boolean autoCreate;
    private int     shardCount;
    private Integer retentionPeriod;


    public String getStreamName()
    {
        return streamName;
    }

    public KinesisWriterConfig setStreamName(String value)
    {
        streamName = value;
        return this;
    }


    public String getPartitionKey()
    {
        return partitionKey;
    }

    public KinesisWriterConfig setPartitionKey(String value)
    {
        partitionKey = value;
        return this;
    }


    public boolean getAutoCreate()
    {
        return autoCreate;
    }

    public KinesisWriterConfig setAutoCreate(boolean value)
    {
        autoCreate = value;
        return this;
    }


    public int getShardCount()
    {
        return shardCount;
    }

    public KinesisWriterConfig setShardCount(int value)
    {
        shardCount = value;
        return this;
    }


    public Integer getRetentionPeriod()
    {
        return retentionPeriod;
    }

    public KinesisWriterConfig setRetentionPeriod(Integer value)
    {
        retentionPeriod = value;
        return this;
    }
}
