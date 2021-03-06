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

package com.kdgregory.logback.testhelpers.cloudwatch;

import com.kdgregory.logging.aws.cloudwatch.CloudWatchWriterConfig;
import com.kdgregory.logging.aws.cloudwatch.CloudWatchWriterStatistics;
import com.kdgregory.logging.common.util.DefaultThreadFactory;
import com.kdgregory.logging.testhelpers.ThrowingWriterFactory;


/**
 *  This class is used to test uncaught exception handling.
 */
public class ThrowingWriterCloudWatchAppender
extends TestableCloudWatchAppender
{
    public ThrowingWriterCloudWatchAppender()
    {
        super();
        setThreadFactory(new DefaultThreadFactory("test"));
        setWriterFactory(new ThrowingWriterFactory<CloudWatchWriterConfig,CloudWatchWriterStatistics>());
    }
}
