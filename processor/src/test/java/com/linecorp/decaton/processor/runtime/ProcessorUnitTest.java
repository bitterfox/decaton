/*
 * Copyright 2020 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.decaton.processor.runtime;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import org.apache.kafka.common.TopicPartition;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.linecorp.decaton.processor.DeferredCompletion;
import com.linecorp.decaton.processor.ProcessorProperties;
import com.linecorp.decaton.protocol.Decaton.DecatonTaskRequest;
import com.linecorp.decaton.protocol.Decaton.TaskMetadataProto;
import com.linecorp.decaton.protocol.Sample.HelloTask;

public class ProcessorUnitTest {
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    private static final TopicPartition topicPartition = new TopicPartition("topic", 1);

    @Mock
    private DeferredCompletion completion;

    @Mock
    private ProcessPipeline<?> pipeline;

    private TaskRequest taskRequest;

    private ProcessorUnit unit;

    @Before
    public void setUp() {
        ThreadScope scope = new ThreadScope(
                new PartitionScope(
                        new SubscriptionScope("subscription", "topic",
                                              Optional.empty(), ProcessorProperties.builder().build()),
                        new TopicPartition("topic", 0)),
                0);

        unit = spy(new ProcessorUnit(scope, pipeline));
        DecatonTaskRequest request =
                DecatonTaskRequest.newBuilder()
                                  .setMetadata(TaskMetadataProto.getDefaultInstance())
                                  .setSerializedTask(HelloTask.getDefaultInstance().toByteString())
                                  .build();

        taskRequest = new TaskRequest(topicPartition, 1, completion, null, request.toByteArray());
    }

    @Test(timeout = 1000)
    public void testProcessNormally() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return CompletableFuture.completedFuture(null);
        }).when(pipeline).scheduleThenProcess(taskRequest);

        unit.putTask(taskRequest);
        latch.await();
        unit.close();

        verify(pipeline, times(1)).scheduleThenProcess(taskRequest);
        verify(completion, times(1)).complete();
    }

    @Test(timeout = 1000)
    public void testProcessDeferredCompletion() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return new CompletableFuture<>();
        }).when(pipeline).scheduleThenProcess(taskRequest);

        unit.putTask(taskRequest);
        latch.await();
        unit.close();

        verify(pipeline, times(1)).scheduleThenProcess(taskRequest);
        verify(completion, times(0)).complete();
    }

    @Test(timeout = 1000)
    public void testProcessThrowExceptionNotDuringShutdown() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        doAnswer(invocation -> {
            latch.countDown();
            throw new RuntimeException("runtime exception");
        }).when(pipeline).scheduleThenProcess(taskRequest);

        unit.putTask(taskRequest);
        unit.putTask(taskRequest);
        latch.await();
        unit.close();

        // Even if the first process throw it should keep processing it
        verify(pipeline, times(2)).scheduleThenProcess(taskRequest);
        // Hence completion should occur even twice
        verify(completion, times(2)).complete();
    }

    @Test(timeout = 1000)
    public void testProcessThrowRuntimeExceptionDuringShutdown() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            unit.initiateShutdown();
            latch.countDown();
            throw new RuntimeException("runtime exception");
        }).when(pipeline).scheduleThenProcess(taskRequest);

        unit.putTask(taskRequest);
        unit.putTask(taskRequest);
        latch.await();
        unit.close();

        // The first process throw and at the time shutdown has been started then it should abort there.
        verify(pipeline, times(1)).scheduleThenProcess(taskRequest);
        // When process throw RuntimeException during shutdown sequence we still consider the task as completed.
        verify(completion, times(1)).complete();
    }

    @Test(timeout = 1000)
    public void testProcessThrowInterruptedExceptionDuringShutdown() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            unit.initiateShutdown();
            latch.countDown();
            throw new InterruptedException();
        }).when(pipeline).scheduleThenProcess(taskRequest);

        unit.putTask(taskRequest);
        unit.putTask(taskRequest);
        latch.await();
        unit.close();

        // The first process throw and at the time shutdown has been started then it should abort there.
        verify(pipeline, times(1)).scheduleThenProcess(taskRequest);
        // When process throw InterruptedException during shutdown sequence we shouldn't consider the task
        // completed.
        verify(completion, times(0)).complete();
    }
}
