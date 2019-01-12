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

package com.kdgregory.logging.aws.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.kdgregory.logging.common.LogMessage;
import com.kdgregory.logging.common.LogWriter;
import com.kdgregory.logging.common.factories.ClientFactory;
import com.kdgregory.logging.common.util.DiscardAction;
import com.kdgregory.logging.common.util.InternalLogger;
import com.kdgregory.logging.common.util.MessageQueue;


/**
 *  Manages common LogWriter activities.
 */
public abstract class AbstractLogWriter
<
    ConfigType extends AbstractWriterConfig,
    StatsType extends AbstractWriterStatistics,
    AWSClientType
>
implements LogWriter
{
    // these three are provided to constructor, used both here and in subclass
    protected ConfigType config;
    protected StatsType stats;
    protected InternalLogger logger;

    // this is provided to constructor, used only here
    private ClientFactory<AWSClientType> clientFactory;

    // this is assigned during initialization, used only by subclass
    protected AWSClientType client;

    // created during constructor or by initializat()
    private MessageQueue messageQueue;
    private Thread dispatchThread;

    // this will be set to true on either success or failure
    private volatile boolean initializationComplete;

    // updated by stop()
    private volatile long shutdownTime = Long.MAX_VALUE;

    // this is intended for testing
    private volatile int batchCount;


    public AbstractLogWriter(ConfigType config, StatsType appenderStats, InternalLogger logger, ClientFactory<AWSClientType> clientFactory)
    {
        this.config = config;
        this.stats = appenderStats;
        this.logger = logger;
        this.clientFactory = clientFactory;

        messageQueue = new MessageQueue(config.discardThreshold, config.discardAction);
        this.stats.setMessageQueue(messageQueue);
    }

//----------------------------------------------------------------------------
//  Accessors
//----------------------------------------------------------------------------

    /**
     *  Returns the current batch delay. This is intended for testing.
     */
    public long getBatchDelay()
    {
        return config.batchDelay;
    }


    /**
     *  Returns the number of batches processed. This is intended for testing
     */
    public int getBatchCount()
    {
        return batchCount;
    }

//----------------------------------------------------------------------------
//  Implementation of Runnable
//----------------------------------------------------------------------------

    @Override
    public void run()
    {
        logger.debug("log writer starting (thread: " + Thread.currentThread().getName() + ")");

        if (! initialize())
            return;

        logger.debug("log writer initialization complete (thread: " + Thread.currentThread().getName() + ")");

        // to avoid any mid-initialization interrupts, we don't set the thread until done
        // (was affecting integration tests, shouldn't be an issue in real-world use)

        dispatchThread = Thread.currentThread();

        // the do-while loop ensures that we attempt to process at least one batch, even if
        // the writer is started and immediately stopped; that's not likely to happen in the
        // real world, but was causing problems with the smoketest (which is configured to
        // quickly transition writers)

        do
        {
            processBatch(shutdownTime);
        } while (keepRunning());

        cleanup();
        logger.debug("log-writer shut down (thread: " + Thread.currentThread().getName()
                     + " (#" + Thread.currentThread().getId() + ")");
    }

//----------------------------------------------------------------------------
//  Implementation of LogWriter
//----------------------------------------------------------------------------

    @Override
    public void setBatchDelay(long value)
    {
        config.batchDelay = value;
    }


    @Override
    public void setDiscardThreshold(int value)
    {
        messageQueue.setDiscardThreshold(value);
    }


    @Override
    public void setDiscardAction(DiscardAction value)
    {
        messageQueue.setDiscardAction(value);
    }


    @Override
    public void addMessage(LogMessage message)
    {
        // we're going to assume that the appender has already checked this, and
        // fail hard if that assumption is not valid
        if (isMessageTooLarge(message))
            throw new IllegalArgumentException("attempted to enqueue a too-large message");

        messageQueue.enqueue(message);
    }


    @Override
    public boolean initialize()
    {
        boolean success = true;

        try
        {
            client = clientFactory.createClient();
            success = ensureDestinationAvailable();
        }
        catch (Exception ex)
        {
            reportError("exception in initializer", ex);
            success = false;
        }

        if (!success)
        {
            messageQueue.setDiscardThreshold(0);
            messageQueue.setDiscardAction(DiscardAction.oldest);
        }

        initializationComplete = true;
        return success;
    }


    @Override
    public boolean waitUntilInitialized(long millisToWait)
    {
        long timeoutAt = System.currentTimeMillis() + millisToWait;
        while (! initializationComplete && (System.currentTimeMillis() < timeoutAt))
        {
            try
            {
                Thread.sleep(100);
            }
            catch (InterruptedException ex)
            {
                return false;
            }
        }
        return initializationComplete;
    }


    @Override
    public synchronized void processBatch(long waitUntil)
    {
        List<LogMessage> currentBatch = buildBatch(waitUntil);
        if (currentBatch.size() > 0)
        {
            batchCount++;
            List<LogMessage> failures = sendBatch(currentBatch);
            requeueMessages(failures);
        }
    }


    @Override
    public void stop()
    {
        shutdownTime = System.currentTimeMillis() + config.batchDelay;
        if (dispatchThread != null)
        {
            dispatchThread.interrupt();
        }
    }


    @Override
    public void cleanup()
    {
        stopAWSClient();
    }

//----------------------------------------------------------------------------
//  Internals
//----------------------------------------------------------------------------

    /**
     *  A check for whether we should keep running: either we haven't been shut
     *  down or there's still messages to process
     */
    private boolean keepRunning()
    {
        return shutdownTime > System.currentTimeMillis()
            || ! messageQueue.isEmpty();
    }


    /**
     *  Attempts to read a list of messages from the queue. Will wait "forever"
     *  (or until shutdown) for the first message, then read as many messages
     *  as possible within the batch delay.
     *  <p>
     *  For each message, the subclass is called to determine the effective size
     *  of the message, and whether the aggregate batch size is within the range
     *  accepted by the service.
     */
    protected List<LogMessage> buildBatch(long waitUntil)
    {
        // presizing to a small-but-possible size to avoid repeated resizes
        List<LogMessage> batch = new ArrayList<LogMessage>(512);

        // we'll wait "forever" unless there's a shutdown timestamp in effect
        LogMessage message = waitForMessage(waitUntil);
        if (message == null)
            return batch;

        long batchTimeout = System.currentTimeMillis() + config.batchDelay;
        int batchBytes = 0;
        int batchMsgs = 0;
        while (message != null)
        {
            batchBytes += effectiveSize(message);
            batchMsgs++;

            // if this message would exceed the batch limits, push it back onto the queue
            // the first message must never break this rule -- and shouldn't, as long as
            // appender checks size
            if (! withinServiceLimits(batchBytes, batchMsgs))
            {
                messageQueue.requeue(message);
                break;
            }

            batch.add(message);
            message = waitForMessage(batchTimeout);
        }

        return batch;
    }


    /**
     *  Attempts to read the message queue, waiting until the specified timestamp
     *  (not elapsed time!).
     */
    private LogMessage waitForMessage(long waitUntil)
    {
        long waitTime = waitUntil - System.currentTimeMillis();
        return messageQueue.dequeue(waitTime);
    }


    /**
     *  Requeues all messages in the passed list, preserving order (ie, the first
     *  passed message in the list will be the first in the queue).
     */
    private void requeueMessages(List<LogMessage> messages)
    {
        Collections.reverse(messages);
        for (LogMessage message : messages)
        {
            messageQueue.requeue(message);
        }
    }

//----------------------------------------------------------------------------
//  Subclass hooks
//----------------------------------------------------------------------------

    /**
     *  Verifies that the logging destination is available (which may involve
     *  creating it). When called, {@link #client} will be initialized. Return
     *  <code>true</code> if successful, <code>false</code> if not (which will
     *  cause the appender to stop running).
     */
    protected abstract boolean ensureDestinationAvailable();


    /**
     *  Sends a batch of messages. The subclass is responsible for returning
     *  any messages that weren't sent, in order, so that they can be requeued.
     */
    protected abstract List<LogMessage> sendBatch(List<LogMessage> currentBatch);


    /**
     *  Calculates the effective size of the message. This includes the message
     *  bytes plus any overhead.
     */
    protected abstract int effectiveSize(LogMessage message);


    /**
     *  Determines whether the provided batch size or number of messages would
     *  exceed the service's limits.
     */
    protected abstract boolean withinServiceLimits(int batchBytes, int numMessages);


    /**
     *  This is called when the logwriter is stopped, to explicitly close the
     *  AWS service client. It must be implemented by the subclass because we
     *  don't know the actual type and there's no abstract super-interface.
     */
    protected abstract void stopAWSClient();

//----------------------------------------------------------------------------
//  Subclass helpers
//----------------------------------------------------------------------------

    /**
     *  Reports an operational error to both the internal logger and the stats
     *  bean..
     */
    protected void reportError(String message, Exception exception)
    {
        logger.error(message, exception);
        stats.setLastError(message, exception);
    }
}
