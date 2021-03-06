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

package com.kdgregory.logging.testhelpers.sns;

import com.kdgregory.logging.aws.sns.SNSWriterConfig;
import com.kdgregory.logging.testhelpers.MockLogWriter;


/**
 *  A mock equivalent of SNSLogWriter, used by appender tests.
 */
public class MockSNSWriter
extends MockLogWriter<SNSWriterConfig>
{
    public MockSNSWriter(SNSWriterConfig config)
    {
        super(config);
    }


    @Override
    public void setBatchDelay(long value)
    {
        throw new IllegalStateException("this function should never be called");
    }
}
