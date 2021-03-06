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

import com.kdgregory.logging.aws.facade.FacadeFactory;
import com.kdgregory.logging.aws.facade.KinesisFacade;
import com.kdgregory.logging.common.LogWriter;
import com.kdgregory.logging.common.util.InternalLogger;
import com.kdgregory.logging.common.util.WriterFactory;


/**
 *  Factory to create {@link KinesisLogWriter} instances.
 */
public class KinesisWriterFactory implements WriterFactory<KinesisWriterConfig, KinesisWriterStatistics>
{
    @Override
    public LogWriter newLogWriter(KinesisWriterConfig config, KinesisWriterStatistics stats, InternalLogger logger)
    {
        return new KinesisLogWriter(
                config, stats, logger,
                FacadeFactory.createFacade(KinesisFacade.class, config));
    }
}
