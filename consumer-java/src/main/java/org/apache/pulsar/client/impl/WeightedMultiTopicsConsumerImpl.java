/*
 *
 *  * Copyright 2022-2026, the original author or authors.
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */
package org.apache.pulsar.client.impl;

import org.apache.pulsar.client.api.*;
import org.apache.pulsar.client.api.PulsarClientException.NotSupportedException;
import org.apache.pulsar.client.impl.conf.ConsumerConfigurationData;
import org.apache.pulsar.client.impl.transaction.TransactionImpl;
import org.apache.pulsar.client.impl.weight.TopicMessageCountTracker;
import org.apache.pulsar.client.impl.weight.TopicThresholdDistribution;
import org.apache.pulsar.client.impl.weight.TopicThresholdDistributionImpl;
import org.apache.pulsar.client.impl.weight.WeightedConsumerConfiguration;
import org.apache.pulsar.client.util.ConsumerName;
import org.apache.pulsar.client.util.ExecutorProvider;
import org.apache.pulsar.common.api.proto.CommandAck.AckType;
import org.apache.pulsar.common.naming.TopicDomain;
import org.apache.pulsar.common.naming.TopicName;
import org.apache.pulsar.common.partition.PartitionedTopicMetadata;
import org.apache.pulsar.common.util.CompletableFutureCancellationHandler;
import org.apache.pulsar.common.util.FutureUtil;
import org.apache.pulsar.shade.com.google.common.annotations.VisibleForTesting;
import org.apache.pulsar.shade.com.google.common.collect.ImmutableMap;
import org.apache.pulsar.shade.com.google.common.collect.Lists;
import org.apache.pulsar.shade.io.netty.util.Timeout;
import org.apache.pulsar.shade.io.netty.util.TimerTask;
import org.apache.pulsar.shade.org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.apache.pulsar.shade.com.google.common.base.Preconditions.checkArgument;
import static org.apache.pulsar.shade.com.google.common.base.Preconditions.checkState;
import static org.apache.pulsar.shade.org.apache.commons.lang3.StringUtils.isBlank;

public class WeightedMultiTopicsConsumerImpl<T> extends ConsumerBase<T> {

    public static final String DUMMY_TOPIC_NAME_PREFIX = "WeightedMultiConsumerImpl-";
    private static final Logger log = LoggerFactory.getLogger(WeightedMultiTopicsConsumerImpl.class);
    // Map <topic, numPartitions>, store partition number for each topic
    protected final ConcurrentHashMap<String, Integer> partitionedTopics;
    // Map <topic+partition, consumer>, when get do ACK, consumer will by find by topic name
    private final ConcurrentHashMap<String, ConsumerImpl<T>> consumers;
    // Queue of partition consumers on which we have stopped calling receiveAsync() because the
    // topic level thresholds have exceeded
    private final ConcurrentLinkedQueue<ConsumerImpl<T>> pausedConsumers;
    // Threshold for the shared queue. When the size of the shared queue goes below the threshold, we are going to
    // resume receiving from the paused consumer partitions
    private final int sharedQueueResumeThreshold;
    private final boolean throttleReceiverQueue;
    private final TopicThresholdDistribution topicThresholdDistribution;
    private final ConcurrentHashMap<String, AtomicInteger> messageCounters = new ConcurrentHashMap<>();
    private final Object pauseMutex = new Object();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final ConsumerStatsRecorder stats;
    private final UnAckedMessageTracker unAckedMessageTracker;
    private final ConsumerConfigurationData<T> internalConfig;
    private final long startMessageRollbackDurationInSec;
    // sum of topicPartitions, simple topic has 1, partitioned topic equals to partition number.
    AtomicInteger allTopicPartitionsNumber;
    TopicsPartitionChangedListener topicsPartitionChangedListener;
    CompletableFuture<Void> partitionsAutoUpdateFuture = null;
    private boolean paused = false;
    // timeout related to auto check and subscribe partition increasement
    private volatile Timeout partitionsAutoUpdateTimeout = null;
    private volatile BatchMessageIdImpl startMessageId = null;

    WeightedMultiTopicsConsumerImpl(PulsarClientImpl client, ConsumerConfigurationData<T> conf,
                                    ExecutorProvider executorProvider, CompletableFuture<Consumer<T>> subscribeFuture, Schema<T> schema,
                                    ConsumerInterceptors<T> interceptors, boolean createTopicIfDoesNotExist) {
        this(client, DUMMY_TOPIC_NAME_PREFIX + ConsumerName.generateRandomName(), conf, executorProvider,
                subscribeFuture, schema, interceptors, createTopicIfDoesNotExist);
    }

    WeightedMultiTopicsConsumerImpl(PulsarClientImpl client, ConsumerConfigurationData<T> conf,
                                    ExecutorProvider executorProvider, CompletableFuture<Consumer<T>> subscribeFuture, Schema<T> schema,
                                    ConsumerInterceptors<T> interceptors, boolean createTopicIfDoesNotExist, MessageId startMessageId,
                                    long startMessageRollbackDurationInSec) {
        this(client, DUMMY_TOPIC_NAME_PREFIX + ConsumerName.generateRandomName(), conf, executorProvider,
                subscribeFuture, schema, interceptors, createTopicIfDoesNotExist, startMessageId,
                startMessageRollbackDurationInSec);
    }

    WeightedMultiTopicsConsumerImpl(PulsarClientImpl client, String singleTopic, ConsumerConfigurationData<T> conf,
                                    ExecutorProvider executorProvider, CompletableFuture<Consumer<T>> subscribeFuture, Schema<T> schema,
                                    ConsumerInterceptors<T> interceptors, boolean createTopicIfDoesNotExist) {
        this(client, singleTopic, conf, executorProvider, subscribeFuture, schema, interceptors,
                createTopicIfDoesNotExist, null, 0);
    }

    WeightedMultiTopicsConsumerImpl(PulsarClientImpl client, String singleTopic, ConsumerConfigurationData<T> conf,
                                    ExecutorProvider executorProvider, CompletableFuture<Consumer<T>> subscribeFuture, Schema<T> schema,
                                    ConsumerInterceptors<T> interceptors, boolean createTopicIfDoesNotExist, MessageId startMessageId,
                                    long startMessageRollbackDurationInSec) {
        super(client, singleTopic, conf, Math.max(2, conf.getReceiverQueueSize()), executorProvider, subscribeFuture,
                schema, interceptors);

        checkArgument(conf.getReceiverQueueSize() > 0,
                "Receiver queue size needs to be greater than 0 for Topics Consumer");
        log.info("Receive queue for multi consumer {}", maxReceiverQueueSize);

        this.partitionedTopics = new ConcurrentHashMap<>();
        this.consumers = new ConcurrentHashMap<>();
        this.pausedConsumers = new ConcurrentLinkedQueue<>();
        this.allTopicPartitionsNumber = new AtomicInteger(0);
        this.startMessageId = startMessageId != null ? new BatchMessageIdImpl(MessageIdImpl.convertToMessageIdImpl(startMessageId)) : null;
        this.startMessageRollbackDurationInSec = startMessageRollbackDurationInSec;

        WeightedConsumerConfiguration weightConf = WeightedConsumerConfiguration.loadFromConf(conf);
        log.info("{}", weightConf);
        this.topicThresholdDistribution = TopicThresholdDistributionImpl.loadFromConf(weightConf);
        this.sharedQueueResumeThreshold = Math.max(0, Math.min(topicThresholdDistribution.getMinValue(), weightConf.getQueueResumeThreshold()));
        this.throttleReceiverQueue = weightConf.isThrottleReceiveQueue();

        if (conf.getAckTimeoutMillis() != 0) {
            if (conf.getTickDurationMillis() > 0) {
                this.unAckedMessageTracker = new UnAckedTopicMessageTracker(client, this, conf);
            } else {
                this.unAckedMessageTracker = new UnAckedTopicMessageTracker(client, this, conf);
            }
        } else {
            this.unAckedMessageTracker = UnAckedMessageTracker.UNACKED_MESSAGE_TRACKER_DISABLED;
        }

        this.internalConfig = getInternalConsumerConfig();
        this.stats = client.getConfiguration().getStatsIntervalSeconds() > 0 ? new ConsumerStatsRecorderImpl(this) : null;

        // start track and auto subscribe partition increment
        if (conf.isAutoUpdatePartitions()) {
            topicsPartitionChangedListener = new TopicsPartitionChangedListener();
            partitionsAutoUpdateTimeout = client.timer()
                    .newTimeout(partitionsAutoUpdateTimerTask, conf.getAutoUpdatePartitionsIntervalSeconds(), TimeUnit.SECONDS);
        }

        if (conf.getTopicNames().isEmpty()) {
            setState(State.Ready);
            subscribeFuture().complete(WeightedMultiTopicsConsumerImpl.this);
            return;
        }

        checkArgument(conf.getTopicNames().isEmpty() || topicNamesValid(conf.getTopicNames()), "Topics is empty or invalid.");

        List<CompletableFuture<Void>> futures = conf.getTopicNames().stream().map(t -> subscribeAsync(t, createTopicIfDoesNotExist))
                .collect(Collectors.toList());
        FutureUtil.waitForAll(futures)
                .thenAccept(finalFuture -> {
                    if (allTopicPartitionsNumber.get() > maxReceiverQueueSize) {
                        setMaxReceiverQueueSize(allTopicPartitionsNumber.get());
                    }
                    setState(State.Ready);
                    // We have successfully created N consumers, so we can start receiving messages now
                    startReceivingMessages(new ArrayList<>(consumers.values()));
                    log.info("[{}] [{}] Created topics consumer with {} sub-consumers",
                            topic, subscription, allTopicPartitionsNumber.get());
                    subscribeFuture().complete(WeightedMultiTopicsConsumerImpl.this);
                })
                .exceptionally(ex -> {
                    log.warn("[{}] Failed to subscribe topics: {}, closing consumer", topic, ex.getMessage());
                    closeAsync().whenComplete((res, closeEx) -> {
                        if (closeEx != null) {
                            log.error("[{}] Failed to unsubscribe after failed consumer creation: {}", topic, closeEx.getMessage());
                        }
                        subscribeFuture.completeExceptionally(ex);
                    });
                    return null;
                });
    }

    // Check topics are valid.
    // - each topic is valid,
    // - topic names are unique.
    private static boolean topicNamesValid(Collection<String> topics) {
        checkState(topics != null && topics.size() >= 1,
                "topics should contain more than 1 topic");

        Optional<String> result = topics.stream()
                .filter(topic -> !TopicName.isValid(topic))
                .findFirst();

        if (result.isPresent()) {
            log.warn("Received invalid topic name: {}", result.get());
            return false;
        }

        // check topic names are unique
        HashSet<String> set = new HashSet<>(topics);
        if (set.size() == topics.size()) {
            return true;
        } else {
            log.warn("Topic names not unique. unique/all : {}/{}", set.size(), topics.size());
            return false;
        }
    }

    // create consumer for a single topic with already known partitions.
    // first create a consumer with no topic, then do subscription for already know partitionedTopic.
    public static <T> WeightedMultiTopicsConsumerImpl<T> createPartitionedConsumer(PulsarClientImpl client,
                                                                                   ConsumerConfigurationData<T> conf,
                                                                                   ExecutorProvider executorProvider,
                                                                                   CompletableFuture<Consumer<T>> subscribeFuture,
                                                                                   int numPartitions,
                                                                                   Schema<T> schema, ConsumerInterceptors<T> interceptors) {
        checkArgument(conf.getTopicNames().size() == 1, "Should have only 1 topic for partitioned consumer");

        // get topic name, then remove it from conf, so constructor will create a consumer with no topic.
        ConsumerConfigurationData cloneConf = conf.clone();
        String topicName = cloneConf.getSingleTopic();
        cloneConf.getTopicNames().remove(topicName);

        CompletableFuture<Consumer> future = new CompletableFuture<>();
        WeightedMultiTopicsConsumerImpl consumer = new WeightedMultiTopicsConsumerImpl(client, topicName, cloneConf, executorProvider,
                future, schema, interceptors, true /* createTopicIfDoesNotExist */);

        future.thenCompose(c -> ((WeightedMultiTopicsConsumerImpl) c).subscribeAsync(topicName, numPartitions))
                .thenRun(() -> subscribeFuture.complete(consumer))
                .exceptionally(e -> {
                    log.warn("Failed subscription for createPartitionedConsumer: {} {}, e:{}",
                            topicName, numPartitions, e);
                    consumer.cleanupMultiConsumer();
                    subscribeFuture.completeExceptionally(
                            PulsarClientException.wrap(((Throwable) e).getCause(), String.format("Failed to subscribe %s with %d partitions", topicName, numPartitions)));
                    return null;
                });
        return consumer;
    }

    public static boolean isIllegalMultiTopicsMessageId(MessageId messageId) {
        //only support earliest/latest
        return !MessageId.earliest.equals(messageId) && !MessageId.latest.equals(messageId);
    }

    public static <T> CompletableFuture<Consumer<T>> subscribeAsync(PulsarClient client, ConsumerConfigurationData<T> conf, WeightedConsumerConfiguration weightConf, Schema<T> schema, ConsumerInterceptors<T> interceptors) {
        if (!(client instanceof PulsarClientImpl)) {
            return FutureUtil.failedFuture(new IllegalArgumentException("Expected pulsar client to be of type: " + PulsarClientImpl.class.getName()));
        }
        PulsarClientImpl clientImpl = (PulsarClientImpl) client;
        AtomicReference<PulsarClientImpl.State> state = clientImpl.getState();

        Set<ConsumerBase<?>> internalClientConsumers = null;
        try {
            Field consumersField = clientImpl.getClass().getDeclaredField("consumers");
            consumersField.setAccessible(true);
            internalClientConsumers = (Set<ConsumerBase<?>>) consumersField.get(clientImpl);
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            log.debug("Cannot retrieve internal consumers of the pulsar client, fatal failure");
            return FutureUtil.failedFuture(new PulsarClientException("Cannot retrieve internal consumers of the pulsar client, fatal failure", ex));
        }

        if (state.get() != PulsarClientImpl.State.Open) {
            return FutureUtil.failedFuture(new PulsarClientException.AlreadyClosedException("Client already closed"));
        }

        if (conf == null) {
            return FutureUtil.failedFuture(
                    new PulsarClientException.InvalidConfigurationException("Consumer configuration undefined"));
        }

        for (String topic : conf.getTopicNames()) {
            if (!TopicName.isValid(topic)) {
                return FutureUtil.failedFuture(new PulsarClientException.InvalidTopicNameException("Invalid topic name: '" + topic + "'"));
            }
        }

        if (isBlank(conf.getSubscriptionName())) {
            return FutureUtil
                    .failedFuture(new PulsarClientException.InvalidConfigurationException("Empty subscription name"));
        }

        if (conf.isReadCompacted() && (!conf.getTopicNames().stream()
                .allMatch(topic -> TopicName.get(topic).getDomain() == TopicDomain.persistent)
                || (conf.getSubscriptionType() != SubscriptionType.Exclusive
                && conf.getSubscriptionType() != SubscriptionType.Failover))) {
            return FutureUtil.failedFuture(new PulsarClientException.InvalidConfigurationException(
                    "Read compacted can only be used with exclusive or failover persistent subscriptions"));
        }

        if (conf.getConsumerEventListener() != null && conf.getSubscriptionType() != SubscriptionType.Failover) {
            return FutureUtil.failedFuture(new PulsarClientException.InvalidConfigurationException(
                    "Active consumer listener is only supported for failover subscription"));
        }

        conf.setReceiverQueueSize(weightConf.getMaxBound());
        if (conf.getTopicsPattern() != null) {
            return FutureUtil.failedFuture(new IllegalArgumentException("Topics pattern not supported in weighted consumer"));
        } else {
            CompletableFuture<Consumer<T>> consumerSubscribedFuture = new CompletableFuture<>();
            ConsumerBase<T> consumer = new WeightedMultiTopicsConsumerImpl<T>(clientImpl, conf,
                    clientImpl.externalExecutorProvider(), consumerSubscribedFuture, schema, interceptors,
                    true);
            internalClientConsumers.add(consumer);

            return consumerSubscribedFuture;
        }
    }

    private static String partitionTopic(String topic) {
        TopicName topicName = TopicName.get(topic);
        if (topicName.isPartitioned()) {
            topic = topicName.getPartitionedTopicName();
        }
        return topic;
    }

    private void startReceivingMessages(List<ConsumerImpl<T>> newConsumers) {
        if (log.isDebugEnabled()) {
            log.debug("[{}] startReceivingMessages for {} new consumers in topics consumer, state: {}",
                    topic, newConsumers.size(), getState());
        }

        if (getState() == State.Ready) {
            newConsumers.forEach(consumer -> {
                int permits = getReceiverQueueSize(consumer.getTopic());
                if (log.isDebugEnabled()) {
                    log.debug("Increasing permits of consumer for topic {} by {}", consumer.getTopic(), permits);
                }
                consumer.increaseAvailablePermits(consumer.getConnectionHandler().cnx(), permits);
                internalPinnedExecutor.execute(() -> receiveMessageFromConsumer(consumer));
            });
        }
    }

    private void receiveMessageFromConsumer(ConsumerImpl<T> consumer) {
        consumer.receiveAsync().thenAccept(message -> {
            if (log.isDebugEnabled()) {
                log.debug("[{}] [{}] Receive message from sub consumer:{}",
                        topic, subscription, consumer.getTopic());
            }
            // Process the message, add to the queue and trigger listener or async callback
            messageReceived(consumer, message);

            String partitionTopic = partitionTopic(consumer.getTopic());
            int threshold = topicThresholdDistribution.getValue(partitionTopic);
            int messagesInQueue = messageCounters.get(partitionTopic).get();
            if (messagesInQueue > threshold) {
                // mark this consumer to be resumed later: if No more space left in shared queue
                pausedConsumers.add(consumer);
                if (log.isDebugEnabled()) {
                    log.debug("Consumer {} paused with state {}/{} and total={}", topic, messagesInQueue, threshold, incomingMessages.size());
                }
                // Since we din't get a mutex, the condition on the incoming queue might have changed after
                // we have paused the current consumer. We need to re-check in order to avoid this consumer
                // from getting stalled.
                resumeReceivingFromPausedConsumersIfNeeded();
            } else {
                // Schedule next receiveAsync() if the incoming queue is not full. Use a different thread to avoid
                // recursion and stack overflow
                internalPinnedExecutor.execute(() -> receiveMessageFromConsumer(consumer));
            }
        });
    }

    private void messageReceived(ConsumerImpl<T> consumer, Message<T> message) {
        checkArgument(message instanceof MessageImpl);
        TopicMessageImpl<T> topicMessage = new TopicMessageImpl<>(consumer.getTopic(),
                consumer.getTopicNameWithoutPartition(), message, consumer);

        if (log.isDebugEnabled()) {
            log.debug("[{}][{}] Received message from topics-consumer {}",
                    topic, subscription, message.getMessageId());
        }

        // if asyncReceive is waiting : return message to callback without adding to incomingMessages queue
        CompletableFuture<Message<T>> receivedFuture = nextPendingReceive();
        if (receivedFuture != null) {
            unAckedMessageTracker.add(topicMessage.getMessageId());
            completePendingReceive(receivedFuture, topicMessage);
        } else if (enqueueMessageAndCheckBatchReceive(topicMessage) && hasPendingBatchReceive()) {
            notifyPendingBatchReceivedCallBack();
        }

        if (listener != null) {
            triggerListener();
        }
    }

    @Override
    protected synchronized void messageProcessed(Message<?> msg) {
        unAckedMessageTracker.add(msg.getMessageId());
        decreaseIncomingMessageSize(msg);
    }

    private void resumeReceivingFromPausedConsumersIfNeeded() {
        if (incomingMessages.size() <= sharedQueueResumeThreshold && !pausedConsumers.isEmpty()) {
            while (true) {
                ConsumerImpl<T> consumer = pausedConsumers.poll();
                if (consumer == null) {
                    break;
                }

                internalPinnedExecutor.execute(() -> {
                    receiveMessageFromConsumer(consumer);
                });
            }
        }
    }

    @Override
    protected boolean enqueueMessageAndCheckBatchReceive(Message<T> message) {
        Boolean val=super.enqueueMessageAndCheckBatchReceive(message);
        messageCounters.get(partitionTopic(message.getTopicName())).incrementAndGet();
        return val;
    }

    @Override
    protected void decreaseIncomingMessageSize(Message<?> message) {
        super.decreaseIncomingMessageSize(message);
        messageCounters.get(partitionTopic(message.getTopicName())).decrementAndGet();
    }

    @Override
    protected void resetIncomingMessageSize() {
        super.resetIncomingMessageSize();

        TopicMessageCountTracker topicMessageCountTracker = new TopicMessageCountTracker();
        incomingMessages.drainTo(topicMessageCountTracker);
        topicMessageCountTracker.getCounters().forEach((topic, count) ->
                messageCounters.get(partitionTopic(topic)).addAndGet(-1 * count));
    }

    protected void addMessageCounterForTopic(String topic) {
        messageCounters.putIfAbsent(partitionTopic(topic), new AtomicInteger());
    }

    @Override
    protected Message<T> internalReceive() throws PulsarClientException {
        Message<T> message;
        try {
            message = incomingMessages.take();
            decreaseIncomingMessageSize(message);
            checkState(message instanceof TopicMessageImpl);
            unAckedMessageTracker.add(message.getMessageId());
            resumeReceivingFromPausedConsumersIfNeeded();
            return message;
        } catch (Exception e) {
            throw PulsarClientException.unwrap(e);
        }
    }

    @Override
    protected Message<T> internalReceive(int timeout, TimeUnit unit) throws PulsarClientException {
        Message<T> message;
        try {
            message = incomingMessages.poll(timeout, unit);
            if (message != null) {
                decreaseIncomingMessageSize(message);
                checkArgument(message instanceof TopicMessageImpl);
                unAckedMessageTracker.add(message.getMessageId());
            }
            resumeReceivingFromPausedConsumersIfNeeded();
            return message;
        } catch (Exception e) {
            throw PulsarClientException.unwrap(e);
        }
    }

    @Override
    protected Messages<T> internalBatchReceive() throws PulsarClientException {
        try {
            return internalBatchReceiveAsync().get();
        } catch (InterruptedException | ExecutionException e) {
            State state = getState();
            if (state != State.Closing && state != State.Closed) {
                stats.incrementNumBatchReceiveFailed();
                throw PulsarClientException.unwrap(e);
            } else {
                return null;
            }
        }
    }

    @Override
    protected CompletableFuture<Messages<T>> internalBatchReceiveAsync() {
        CompletableFutureCancellationHandler cancellationHandler = new CompletableFutureCancellationHandler();
        CompletableFuture<Messages<T>> result = cancellationHandler.createFuture();
        try {
            lock.writeLock().lock();
            if (hasEnoughMessagesForBatchReceive()) {
                MessagesImpl<T> messages = getNewMessagesImpl();
                Message<T> msgPeeked = incomingMessages.peek();
                while (msgPeeked != null && messages.canAdd(msgPeeked)) {
                    Message<T> msg = incomingMessages.poll();
                    if (msg != null) {
                        decreaseIncomingMessageSize(msg);
                        Message<T> interceptMsg = beforeConsume(msg);
                        messages.add(interceptMsg);
                    }
                    msgPeeked = incomingMessages.peek();
                }
                result.complete(messages);
            } else {
                OpBatchReceive<T> opBatchReceive = OpBatchReceive.of(result);
                pendingBatchReceives.add(opBatchReceive);
                cancellationHandler.setCancelAction(() -> pendingBatchReceives.remove(opBatchReceive));
            }
            resumeReceivingFromPausedConsumersIfNeeded();
        } finally {
            lock.writeLock().unlock();
        }
        return result;
    }

    @Override
    protected CompletableFuture<Message<T>> internalReceiveAsync() {
        CompletableFutureCancellationHandler cancellationHandler = new CompletableFutureCancellationHandler();
        CompletableFuture<Message<T>> result = cancellationHandler.createFuture();
        Message<T> message = incomingMessages.poll();
        if (message == null) {
            pendingReceives.add(result);
            cancellationHandler.setCancelAction(() -> pendingReceives.remove(result));
        } else {
            decreaseIncomingMessageSize(message);
            checkState(message instanceof TopicMessageImpl);
            unAckedMessageTracker.add(message.getMessageId());
            resumeReceivingFromPausedConsumersIfNeeded();
            result.complete(message);
        }
        return result;
    }

    @Override
    protected CompletableFuture<Void> doAcknowledge(MessageId messageId, AckType ackType,
                                                    Map<String, Long> properties,
                                                    TransactionImpl txnImpl) {
        checkArgument(messageId instanceof TopicMessageIdImpl);
        TopicMessageIdImpl topicMessageId = (TopicMessageIdImpl) messageId;

        if (getState() != State.Ready) {
            return FutureUtil.failedFuture(new PulsarClientException("Consumer already closed"));
        }

        if (ackType == AckType.Cumulative) {
            Consumer individualConsumer = consumers.get(topicMessageId.getTopicPartitionName());
            if (individualConsumer != null) {
                MessageId innerId = topicMessageId.getInnerMessageId();
                return individualConsumer.acknowledgeCumulativeAsync(innerId);
            } else {
                return FutureUtil.failedFuture(new PulsarClientException.NotConnectedException());
            }
        } else {
            ConsumerImpl<T> consumer = consumers.get(topicMessageId.getTopicPartitionName());

            MessageId innerId = topicMessageId.getInnerMessageId();
            return consumer.doAcknowledgeWithTxn(innerId, ackType, properties, txnImpl)
                    .thenRun(() ->
                            unAckedMessageTracker.remove(topicMessageId));
        }
    }

    @Override
    protected CompletableFuture<Void> doAcknowledge(List<MessageId> messageIdList, AckType ackType, Map<String, Long> properties, TransactionImpl txn) {
        List<CompletableFuture<Void>> resultFutures = new ArrayList<>();
        if (ackType == AckType.Cumulative) {
            messageIdList.forEach(messageId -> resultFutures.add(doAcknowledge(messageId, ackType, properties, txn)));
            return CompletableFuture.allOf(resultFutures.toArray(new CompletableFuture[0]));
        } else {
            if (getState() != State.Ready) {
                return FutureUtil.failedFuture(new PulsarClientException("Consumer already closed"));
            }
            Map<String, List<MessageId>> topicToMessageIdMap = new HashMap<>();
            for (MessageId messageId : messageIdList) {
                if (!(messageId instanceof TopicMessageIdImpl)) {
                    return FutureUtil.failedFuture(new IllegalArgumentException("messageId is not instance of TopicMessageIdImpl"));
                }
                TopicMessageIdImpl topicMessageId = (TopicMessageIdImpl) messageId;
                topicToMessageIdMap.putIfAbsent(topicMessageId.getTopicPartitionName(), new ArrayList<>());
                topicToMessageIdMap.get(topicMessageId.getTopicPartitionName()).add(topicMessageId.getInnerMessageId());
            }
            topicToMessageIdMap.forEach((topicPartitionName, messageIds) -> {
                ConsumerImpl<T> consumer = consumers.get(topicPartitionName);
                resultFutures.add(consumer.doAcknowledgeWithTxn(messageIds, ackType, properties, txn)
                        .thenAccept((res) -> messageIdList.forEach(unAckedMessageTracker::remove)));
            });
            return CompletableFuture.allOf(resultFutures.toArray(new CompletableFuture[0]));
        }
    }

    @Override
    protected CompletableFuture<Void> doReconsumeLater(Message<?> message, AckType ackType,
                                                       Map<String, String> properties,
                                                       long delayTime,
                                                       TimeUnit unit) {
        MessageId messageId = message.getMessageId();
        checkArgument(messageId instanceof TopicMessageIdImpl);
        TopicMessageIdImpl topicMessageId = (TopicMessageIdImpl) messageId;
        if (getState() != State.Ready) {
            return FutureUtil.failedFuture(new PulsarClientException("Consumer already closed"));
        }

        if (ackType == AckType.Cumulative) {
            Consumer individualConsumer = consumers.get(topicMessageId.getTopicPartitionName());
            if (individualConsumer != null) {
                MessageId innerId = topicMessageId.getInnerMessageId();
                return individualConsumer.reconsumeLaterCumulativeAsync(message, delayTime, unit);
            } else {
                return FutureUtil.failedFuture(new PulsarClientException.NotConnectedException());
            }
        } else {
            ConsumerImpl<T> consumer = consumers.get(topicMessageId.getTopicPartitionName());
            MessageId innerId = topicMessageId.getInnerMessageId();
            return consumer.doReconsumeLater(message, ackType, properties, delayTime, unit)
                    .thenRun(() -> unAckedMessageTracker.remove(topicMessageId));
        }
    }

    @Override
    public void negativeAcknowledge(MessageId messageId) {
        checkArgument(messageId instanceof TopicMessageIdImpl);
        TopicMessageIdImpl topicMessageId = (TopicMessageIdImpl) messageId;

        ConsumerImpl<T> consumer = consumers.get(topicMessageId.getTopicPartitionName());
        consumer.negativeAcknowledge(topicMessageId.getInnerMessageId());
    }

    @Override
    public CompletableFuture<Void> unsubscribeAsync() {
        if (getState() == State.Closing || getState() == State.Closed) {
            return FutureUtil.failedFuture(
                    new PulsarClientException.AlreadyClosedException("Topics Consumer was already closed"));
        }
        setState(State.Closing);

        CompletableFuture<Void> unsubscribeFuture = new CompletableFuture<>();
        List<CompletableFuture<Void>> futureList = consumers.values().stream()
                .map(c -> c.unsubscribeAsync()).collect(Collectors.toList());

        FutureUtil.waitForAll(futureList)
                .thenCompose((r) -> {
                    setState(State.Closed);
                    cleanupMultiConsumer();
                    log.info("[{}] [{}] [{}] Unsubscribed Topics Consumer",
                            topic, subscription, consumerName);
                    // fail all pending-receive futures to notify application
                    return failPendingReceive();
                })
                .whenComplete((r, ex) -> {
                    if (ex == null) {
                        unsubscribeFuture.complete(null);
                    } else {
                        setState(State.Failed);
                        unsubscribeFuture.completeExceptionally(ex);
                        log.error("[{}] [{}] [{}] Could not unsubscribe Topics Consumer",
                                topic, subscription, consumerName, ex.getCause());
                    }
                });

        return unsubscribeFuture;
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        if (getState() == State.Closing || getState() == State.Closed) {
            unAckedMessageTracker.close();
            return CompletableFuture.completedFuture(null);
        }
        setState(State.Closing);

        if (partitionsAutoUpdateTimeout != null) {
            partitionsAutoUpdateTimeout.cancel();
            partitionsAutoUpdateTimeout = null;
        }

        CompletableFuture<Void> closeFuture = new CompletableFuture<>();
        List<CompletableFuture<Void>> futureList = consumers.values().stream()
                .map(c -> c.closeAsync()).collect(Collectors.toList());

        FutureUtil.waitForAll(futureList)
                .thenCompose((r) -> {
                    setState(State.Closed);
                    cleanupMultiConsumer();
                    log.info("[{}] [{}] Closed Topics Consumer", topic, subscription);
                    // fail all pending-receive futures to notify application
                    return failPendingReceive();
                })
                .whenComplete((r, ex) -> {
                    if (ex == null) {
                        closeFuture.complete(null);
                    } else {
                        setState(State.Failed);
                        closeFuture.completeExceptionally(ex);
                        log.error("[{}] [{}] Could not close Topics Consumer", topic, subscription,
                                ex.getCause());
                    }
                });

        return closeFuture;
    }

    private void cleanupMultiConsumer() {
        unAckedMessageTracker.close();
        client.cleanupConsumer(this);
    }

    @Override
    public boolean isConnected() {
        return consumers.values().stream().allMatch(consumer -> consumer.isConnected());
    }

    @Override
    String getHandlerName() {
        return subscription;
    }

    private ConsumerConfigurationData<T> getInternalConsumerConfig() {
        ConsumerConfigurationData<T> internalConsumerConfig = conf.clone();
        internalConsumerConfig.setSubscriptionName(subscription);
        internalConsumerConfig.setConsumerName(consumerName);
        internalConsumerConfig.setMessageListener(null);
        return internalConsumerConfig;
    }

    @Override
    public void redeliverUnacknowledgedMessages() {
        lock.writeLock().lock();
        try {
            consumers.values().stream().forEach(consumer -> {
                consumer.redeliverUnacknowledgedMessages();
                consumer.unAckedChunkedMessageIdSequenceMap.clear();
            });
            resetIncomingMessageSize();
            unAckedMessageTracker.clear();
        } finally {
            lock.writeLock().unlock();
        }
        resumeReceivingFromPausedConsumersIfNeeded();
    }

    @Override
    public void redeliverUnacknowledgedMessages(Set<MessageId> messageIds) {
        if (messageIds.isEmpty()) {
            return;
        }

        checkArgument(messageIds.stream().findFirst().get() instanceof TopicMessageIdImpl);

        if (conf.getSubscriptionType() != SubscriptionType.Shared) {
            // We cannot redeliver single messages if subscription type is not Shared
            redeliverUnacknowledgedMessages();
            return;
        }
        removeExpiredMessagesFromQueue(messageIds);
        messageIds.stream().map(messageId -> (TopicMessageIdImpl) messageId)
                .collect(Collectors.groupingBy(TopicMessageIdImpl::getTopicPartitionName, Collectors.toSet()))
                .forEach((topicName, messageIds1) ->
                        consumers.get(topicName)
                                .redeliverUnacknowledgedMessages(messageIds1.stream()
                                        .map(mid -> mid.getInnerMessageId()).collect(Collectors.toSet())));
        resumeReceivingFromPausedConsumersIfNeeded();
    }

    @Override
    protected void completeOpBatchReceive(OpBatchReceive<T> op) {
        notifyPendingBatchReceivedCallBack(op);
        resumeReceivingFromPausedConsumersIfNeeded();
    }

    @Override
    public void seek(MessageId messageId) throws PulsarClientException {
        try {
            seekAsync(messageId).get();
        } catch (Exception e) {
            throw PulsarClientException.unwrap(e);
        }
    }

    @Override
    public void seek(long timestamp) throws PulsarClientException {
        try {
            seekAsync(timestamp).get();
        } catch (Exception e) {
            throw PulsarClientException.unwrap(e);
        }
    }

    @Override
    public void seek(Function<String, Object> function) throws PulsarClientException {
        try {
            this.seekAsync(function).get();
        } catch (Exception var3) {
            throw PulsarClientException.unwrap(var3);
        }
    }

    @Override
    public CompletableFuture<Void> seekAsync(Function<String, Object> function) {
        List<CompletableFuture<Void>> futures = new ArrayList(this.consumers.size());
        this.consumers.values().forEach((consumer) -> {
            futures.add(consumer.seekAsync(function));
        });
        this.unAckedMessageTracker.clear();
        this.resetIncomingMessageSize();
        return FutureUtil.waitForAll(futures);
    }


    @Override
    public CompletableFuture<Void> seekAsync(MessageId messageId) {
        CompletableFuture<Void> seekFuture = new CompletableFuture<>();
        MessageIdImpl targetMessageId = MessageIdImpl.convertToMessageIdImpl(messageId);
        if (targetMessageId == null || isIllegalMultiTopicsMessageId(messageId)) {
            seekFuture.completeExceptionally(
                    new PulsarClientException("Illegal messageId, messageId can only be earliest/latest"));
            return seekFuture;
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>(consumers.size());
        consumers.values().forEach(consumerImpl -> futures.add(consumerImpl.seekAsync(targetMessageId)));

        unAckedMessageTracker.clear();
        resetIncomingMessageSize();

        FutureUtil.waitForAll(futures).whenComplete((result, exception) -> {
            if (exception != null) {
                seekFuture.completeExceptionally(exception);
            } else {
                seekFuture.complete(result);
            }
        });
        return seekFuture;
    }

    @Override
    public CompletableFuture<Void> seekAsync(long timestamp) {
        List<CompletableFuture<Void>> futures = new ArrayList<>(consumers.size());
        consumers.values().forEach(consumer -> futures.add(consumer.seekAsync(timestamp)));
        return FutureUtil.waitForAll(futures);
    }

    @Override
    public int getAvailablePermits() {
        return consumers.values().stream().mapToInt(ConsumerImpl::getAvailablePermits).sum();
    }

    @Override
    public boolean hasReachedEndOfTopic() {
        return consumers.values().stream().allMatch(Consumer::hasReachedEndOfTopic);
    }

    public boolean hasMessageAvailable() throws PulsarClientException {
        try {
            return hasMessageAvailableAsync().get();
        } catch (Exception e) {
            throw PulsarClientException.unwrap(e);
        }
    }

    public CompletableFuture<Boolean> hasMessageAvailableAsync() {
        List<CompletableFuture<Void>> futureList = new ArrayList<>();
        final AtomicBoolean hasMessageAvailable = new AtomicBoolean(false);
        for (ConsumerImpl<T> consumer : consumers.values()) {
            futureList.add(consumer.hasMessageAvailableAsync().thenAccept(isAvailable -> {
                if (isAvailable) {
                    hasMessageAvailable.compareAndSet(false, true);
                }
            }));
        }
        CompletableFuture<Boolean> completableFuture = new CompletableFuture<>();
        FutureUtil.waitForAll(futureList).whenComplete((result, exception) -> {
            if (exception != null) {
                completableFuture.completeExceptionally(exception);
            } else {
                completableFuture.complete(hasMessageAvailable.get());
            }
        });
        return completableFuture;
    }

    @Override
    public int numMessagesInQueue() {
        return incomingMessages.size() + consumers.values().stream().mapToInt(ConsumerImpl::numMessagesInQueue).sum();
    }

    @Override
    public synchronized ConsumerStats getStats() {
        if (stats == null) {
            return null;
        }
        stats.reset();

        consumers.values().stream().forEach(consumer -> stats.updateCumulativeStats(consumer.getStats()));
        return stats;
    }

    public UnAckedMessageTracker getUnAckedMessageTracker() {
        return unAckedMessageTracker;
    }

    private void removeExpiredMessagesFromQueue(Set<MessageId> messageIds) {
        Message<T> peek = incomingMessages.peek();
        if (peek != null) {
            if (!messageIds.contains(peek.getMessageId())) {
                // first message is not expired, then no message is expired in queue.
                return;
            }

            // try not to remove elements that are added while we remove
            Message<T> message = incomingMessages.poll();
            checkState(message instanceof TopicMessageImpl);
            while (message != null) {
                decreaseIncomingMessageSize(message);
                MessageId messageId = message.getMessageId();
                if (!messageIds.contains(messageId)) {
                    messageIds.add(messageId);
                    break;
                }
                message = incomingMessages.poll();
            }
        }
    }

    private TopicName getTopicName(String topic) {
        try {
            return TopicName.get(topic);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String getFullTopicName(String topic) {
        TopicName topicName = getTopicName(topic);
        return (topicName != null) ? topicName.toString() : null;
    }

    private void removeTopic(String topic) {
        String fullTopicName = getFullTopicName(topic);
        if (fullTopicName != null) {
            partitionedTopics.remove(topic);
        }
    }

    // subscribe one more given topic
    public CompletableFuture<Void> subscribeAsync(String topicName, boolean createTopicIfDoesNotExist) {
        TopicName topicNameInstance = getTopicName(topicName);
        if (topicNameInstance == null) {
            return FutureUtil.failedFuture(
                    new PulsarClientException.AlreadyClosedException("Topic name not valid"));
        }
        String fullTopicName = topicNameInstance.toString();
        if (consumers.containsKey(fullTopicName) || partitionedTopics.containsKey(topicNameInstance.getPartitionedTopicName())) {
            return FutureUtil.failedFuture(
                    new PulsarClientException.AlreadyClosedException("Already subscribed to " + topicName));
        }

        if (getState() == State.Closing || getState() == State.Closed) {
            return FutureUtil.failedFuture(
                    new PulsarClientException.AlreadyClosedException("Topics Consumer was already closed"));
        }

        CompletableFuture<Void> subscribeResult = new CompletableFuture<>();

        client.getPartitionedTopicMetadata(topicName)
                .thenAccept(metadata -> subscribeTopicPartitions(subscribeResult, fullTopicName, metadata.partitions,
                        createTopicIfDoesNotExist))
                .exceptionally(ex1 -> {
                    log.warn("[{}] Failed to get partitioned topic metadata: {}", fullTopicName, ex1.getMessage());
                    subscribeResult.completeExceptionally(ex1);
                    return null;
                });

        return subscribeResult;
    }

    // subscribe one more given topic, but already know the numberPartitions
    @VisibleForTesting
    CompletableFuture<Void> subscribeAsync(String topicName, int numberPartitions) {
        TopicName topicNameInstance = getTopicName(topicName);
        if (topicNameInstance == null) {
            return FutureUtil.failedFuture(
                    new PulsarClientException.AlreadyClosedException("Topic name not valid"));
        }
        String fullTopicName = topicNameInstance.toString();
        if (consumers.containsKey(fullTopicName) || partitionedTopics.containsKey(topicNameInstance.getPartitionedTopicName())) {
            return FutureUtil.failedFuture(
                    new PulsarClientException.AlreadyClosedException("Already subscribed to " + topicName));
        }

        if (getState() == State.Closing || getState() == State.Closed) {
            return FutureUtil.failedFuture(
                    new PulsarClientException.AlreadyClosedException("Topics Consumer was already closed"));
        }

        CompletableFuture<Void> subscribeResult = new CompletableFuture<>();
        subscribeTopicPartitions(subscribeResult, fullTopicName, numberPartitions, true /* createTopicIfDoesNotExist */);

        return subscribeResult;
    }

    private void subscribeTopicPartitions(CompletableFuture<Void> subscribeResult, String topicName, int numPartitions,
                                          boolean createIfDoesNotExist) {
        client.preProcessSchemaBeforeSubscribe(client, schema, topicName).whenComplete((schema, cause) -> {
            if (null == cause) {
                doSubscribeTopicPartitions(schema, subscribeResult, topicName, numPartitions, createIfDoesNotExist);
            } else {
                subscribeResult.completeExceptionally(cause);
            }
        });
    }

    private void doSubscribeTopicPartitions(Schema<T> schema,
                                            CompletableFuture<Void> subscribeResult, String topicName, int numPartitions,
                                            boolean createIfDoesNotExist) {
        if (log.isDebugEnabled()) {
            log.debug("Subscribe to topic {} metadata.partitions: {}", topicName, numPartitions);
        }

        List<CompletableFuture<Consumer<T>>> futureList;
        if (numPartitions != PartitionedTopicMetadata.NON_PARTITIONED) {
            // Below condition is true if subscribeAsync() has been invoked second time with same
            // topicName before the first invocation had reached this point.
            boolean isTopicBeingSubscribedForInOtherThread = this.partitionedTopics.putIfAbsent(topicName, numPartitions) != null;
            if (isTopicBeingSubscribedForInOtherThread) {
                String errorMessage = String.format("[%s] Failed to subscribe for topic [%s] in topics consumer. "
                        + "Topic is already being subscribed for in other thread.", topic, topicName);
                log.warn(errorMessage);
                subscribeResult.completeExceptionally(new PulsarClientException(errorMessage));
                return;
            }
            allTopicPartitionsNumber.addAndGet(numPartitions);

            ConsumerConfigurationData<T> configurationData = getInternalConsumerConfig();
            try {
                int receiverQueueSize = getReceiverQueueSize(topicName);
                configurationData.setReceiverQueueSize(receiverQueueSize);
            } catch (Exception ex) {
                subscribeResult.completeExceptionally(new PulsarClientException(ex));
                return;
            }

            futureList = IntStream
                    .range(0, numPartitions)
                    .mapToObj(
                            partitionIndex -> {
                                String partitionName = TopicName.get(topicName).getPartition(partitionIndex).toString();
                                CompletableFuture<Consumer<T>> subFuture = new CompletableFuture<>();
                                ConsumerImpl<T> newConsumer = ConsumerImpl.newConsumerImpl(client, partitionName,
                                        configurationData, client.externalExecutorProvider(),
                                        partitionIndex, true, subFuture,
                                        startMessageId, schema, interceptors,
                                        createIfDoesNotExist, startMessageRollbackDurationInSec);
                                addMessageCounterForTopic(newConsumer.getTopic());
                                consumers.putIfAbsent(newConsumer.getTopic(), newConsumer);
                                return subFuture;
                            })
                    .collect(Collectors.toList());
        } else {
            allTopicPartitionsNumber.incrementAndGet();

            CompletableFuture<Consumer<T>> subFuture = new CompletableFuture<>();
            ConsumerConfigurationData<T> configurationData = internalConfig.clone();
            try {
                int receiverQueueSize = getReceiverQueueSize(topicName);
                configurationData.setReceiverQueueSize(receiverQueueSize);
            } catch (Exception ex) {
                subscribeResult.completeExceptionally(new PulsarClientException(ex));
                return;
            }
            consumers.compute(topicName, (key, existingValue) -> {
                if (existingValue != null) {
                    String errorMessage = String.format("[%s] Failed to subscribe for topic [%s] in topics consumer. "
                            + "Topic is already being subscribed for in other thread.", topic, topicName);
                    log.warn(errorMessage);
                    subscribeResult.completeExceptionally(new PulsarClientException(errorMessage));
                    return existingValue;
                } else {
                    ConsumerImpl<T> newConsumer = ConsumerImpl.newConsumerImpl(client, topicName, configurationData,
                            client.externalExecutorProvider(), -1,
                            true, subFuture, null, schema, interceptors,
                            createIfDoesNotExist);
                    addMessageCounterForTopic(newConsumer.getTopic());
                    synchronized (pauseMutex) {
                        if (paused) {
                            newConsumer.pause();
                        }
                    }
                    return newConsumer;
                }
            });

            futureList = Collections.singletonList(subFuture);
        }

        FutureUtil.waitForAll(futureList)
                .thenAccept(finalFuture -> {
                    if (allTopicPartitionsNumber.get() > maxReceiverQueueSize) {
                        setMaxReceiverQueueSize(allTopicPartitionsNumber.get());
                    }

                    // We have successfully created new consumers, so we can start receiving messages for them
                    startReceivingMessages(consumers.values().stream()
                            .filter(consumer1 -> {
                                String consumerTopicName = consumer1.getTopic();
                                return TopicName.get(consumerTopicName).getPartitionedTopicName().equals(
                                        TopicName.get(topicName).getPartitionedTopicName());
                            })
                            .collect(Collectors.toList()));

                    subscribeResult.complete(null);
                    log.info("[{}] [{}] Success subscribe new topic {} in topics consumer, partitions: {}, allTopicPartitionsNumber: {}",
                            topic, subscription, topicName, numPartitions, allTopicPartitionsNumber.get());
                    return;
                })
                .exceptionally(ex -> {
                    handleSubscribeOneTopicError(topicName, ex, subscribeResult);
                    return null;
                });
    }

    private int getReceiverQueueSize(String topicName) {
        int receiverQueueSize = throttleReceiverQueue
                ? topicThresholdDistribution.getValue(topicName)
                : topicThresholdDistribution.getMaxValue();
        return receiverQueueSize;
    }

    // handling failure during subscribe new topic, unsubscribe success created partitions
    private void handleSubscribeOneTopicError(String topicName, Throwable error, CompletableFuture<Void> subscribeFuture) {
        log.warn("[{}] Failed to subscribe for topic [{}] in topics consumer {}", topic, topicName, error.getMessage());
        client.externalExecutorProvider().getExecutor().submit(() -> {
            AtomicInteger toCloseNum = new AtomicInteger(0);
            consumers.values().stream().filter(consumer1 -> {
                String consumerTopicName = consumer1.getTopic();
                if (TopicName.get(consumerTopicName).getPartitionedTopicName().equals(TopicName.get(topicName).getPartitionedTopicName())) {
                    toCloseNum.incrementAndGet();
                    return true;
                } else {
                    return false;
                }
            }).collect(Collectors.toList()).forEach(consumer2 -> {
                consumer2.closeAsync().whenComplete((r, ex) -> {
                    consumer2.subscribeFuture().completeExceptionally(error);
                    allTopicPartitionsNumber.decrementAndGet();
                    consumers.remove(consumer2.getTopic());
                    if (toCloseNum.decrementAndGet() == 0) {
                        log.warn("[{}] Failed to subscribe for topic [{}] in topics consumer, subscribe error: {}",
                                topic, topicName, error.getMessage());
                        removeTopic(topicName);
                        subscribeFuture.completeExceptionally(error);
                    }
                    return;
                });
            });
        });
    }

    // un-subscribe a given topic
    public CompletableFuture<Void> unsubscribeAsync(String topicName) {
        checkArgument(TopicName.isValid(topicName), "Invalid topic name:" + topicName);

        if (getState() == State.Closing || getState() == State.Closed) {
            return FutureUtil.failedFuture(
                    new PulsarClientException.AlreadyClosedException("Topics Consumer was already closed"));
        }

        if (partitionsAutoUpdateTimeout != null) {
            partitionsAutoUpdateTimeout.cancel();
            partitionsAutoUpdateTimeout = null;
        }

        CompletableFuture<Void> unsubscribeFuture = new CompletableFuture<>();
        String topicPartName = TopicName.get(topicName).getPartitionedTopicName();

        List<ConsumerImpl<T>> consumersToUnsub = consumers.values().stream()
                .filter(consumer -> {
                    String consumerTopicName = consumer.getTopic();
                    return TopicName.get(consumerTopicName).getPartitionedTopicName().equals(topicPartName);
                }).collect(Collectors.toList());

        List<CompletableFuture<Void>> futureList = consumersToUnsub.stream()
                .map(ConsumerImpl::unsubscribeAsync).collect(Collectors.toList());

        FutureUtil.waitForAll(futureList)
                .whenComplete((r, ex) -> {
                    if (ex == null) {
                        consumersToUnsub.forEach(consumer1 -> {
                            consumers.remove(consumer1.getTopic());
                            pausedConsumers.remove(consumer1);
                            allTopicPartitionsNumber.decrementAndGet();
                        });

                        removeTopic(topicName);
                        ((UnAckedTopicMessageTracker) unAckedMessageTracker).removeTopicMessages(topicName);

                        unsubscribeFuture.complete(null);
                        log.info("[{}] [{}] [{}] Unsubscribed Topics Consumer, allTopicPartitionsNumber: {}",
                                topicName, subscription, consumerName, allTopicPartitionsNumber);
                    } else {
                        unsubscribeFuture.completeExceptionally(ex);
                        setState(State.Failed);
                        log.error("[{}] [{}] [{}] Could not unsubscribe Topics Consumer",
                                topicName, subscription, consumerName, ex.getCause());
                    }
                });

        return unsubscribeFuture;
    }

    // Remove a consumer for a topic
    public CompletableFuture<Void> removeConsumerAsync(String topicName) {
        checkArgument(TopicName.isValid(topicName), "Invalid topic name:" + topicName);

        if (getState() == State.Closing || getState() == State.Closed) {
            return FutureUtil.failedFuture(
                    new PulsarClientException.AlreadyClosedException("Topics Consumer was already closed"));
        }

        CompletableFuture<Void> unsubscribeFuture = new CompletableFuture<>();
        String topicPartName = TopicName.get(topicName).getPartitionedTopicName();


        List<ConsumerImpl<T>> consumersToClose = consumers.values().stream()
                .filter(consumer -> {
                    String consumerTopicName = consumer.getTopic();
                    return TopicName.get(consumerTopicName).getPartitionedTopicName().equals(topicPartName);
                }).collect(Collectors.toList());

        List<CompletableFuture<Void>> futureList = consumersToClose.stream()
                .map(ConsumerImpl::closeAsync).collect(Collectors.toList());

        FutureUtil.waitForAll(futureList)
                .whenComplete((r, ex) -> {
                    if (ex == null) {
                        consumersToClose.forEach(consumer1 -> {
                            consumers.remove(consumer1.getTopic());
                            pausedConsumers.remove(consumer1);
                            allTopicPartitionsNumber.decrementAndGet();
                        });

                        removeTopic(topicName);
                        ((UnAckedTopicMessageTracker) unAckedMessageTracker).removeTopicMessages(topicName);

                        unsubscribeFuture.complete(null);
                        log.info("[{}] [{}] [{}] Removed Topics Consumer, allTopicPartitionsNumber: {}",
                                topicName, subscription, consumerName, allTopicPartitionsNumber);
                    } else {
                        unsubscribeFuture.completeExceptionally(ex);
                        setState(State.Failed);
                        log.error("[{}] [{}] [{}] Could not remove Topics Consumer",
                                topicName, subscription, consumerName, ex.getCause());
                    }
                });

        return unsubscribeFuture;
    }

    // get topics name
    public List<String> getPartitionedTopics() {
        return partitionedTopics.keySet().stream().collect(Collectors.toList());
    }

    // get partitioned topics name
    public List<String> getPartitions() {
        return consumers.keySet().stream().collect(Collectors.toList());
    }

    // get partitioned consumers
    public List<ConsumerImpl<T>> getConsumers() {
        return consumers.values().stream().collect(Collectors.toList());
    }

    // get all partitions that in the topics map
    int getPartitionsOfTheTopicMap() {
        return partitionedTopics.values().stream().mapToInt(Integer::intValue).sum();
    }

    @Override
    public void pause() {
        synchronized (pauseMutex) {
            paused = true;
            consumers.forEach((name, consumer) -> consumer.pause());
        }
    }

    @Override
    public void resume() {
        synchronized (pauseMutex) {
            paused = false;
            consumers.forEach((name, consumer) -> consumer.resume());
        }
    }

    @Override
    public long getLastDisconnectedTimestamp() {
        long lastDisconnectedTimestamp = 0;
        Optional<ConsumerImpl<T>> c = consumers.values().stream().max(Comparator.comparingLong(ConsumerImpl::getLastDisconnectedTimestamp));
        if (c.isPresent()) {
            lastDisconnectedTimestamp = c.get().getLastDisconnectedTimestamp();
        }
        return lastDisconnectedTimestamp;
    }

    // subscribe increased partitions for a given topic
    private CompletableFuture<Void> subscribeIncreasedTopicPartitions(String topicName) {
        int oldPartitionNumber = partitionedTopics.get(topicName);

        return client.getPartitionsForTopic(topicName).thenCompose(list -> {
            int currentPartitionNumber = Long.valueOf(list.stream().filter(t -> TopicName.get(t).isPartitioned()).count()).intValue();

            if (log.isDebugEnabled()) {
                log.debug("[{}] partitions number. old: {}, new: {}",
                        topicName, oldPartitionNumber, currentPartitionNumber);
            }

            if (oldPartitionNumber == currentPartitionNumber) {
                // topic partition number not changed
                return CompletableFuture.completedFuture(null);
            } else if (currentPartitionNumber == PartitionedTopicMetadata.NON_PARTITIONED) {
                // The topic was initially partitioned but then it was deleted. We keep it in the topics
                partitionedTopics.put(topicName, 0);

                allTopicPartitionsNumber.addAndGet(-oldPartitionNumber);
                List<CompletableFuture<Void>> futures = new ArrayList<>();
                for (Iterator<Map.Entry<String, ConsumerImpl<T>>> it = consumers.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<String, ConsumerImpl<T>> e = it.next();
                    String partitionedTopicName = TopicName.get(e.getKey()).getPartitionedTopicName();

                    // Remove the consumers that belong to the deleted partitioned topic
                    if (partitionedTopicName.equals(topicName)) {
                        futures.add(e.getValue().closeAsync());
                        consumers.remove(e.getKey());
                    }
                }

                return FutureUtil.waitForAll(futures);
            } else if (oldPartitionNumber < currentPartitionNumber) {
                allTopicPartitionsNumber.addAndGet(currentPartitionNumber - oldPartitionNumber);
                partitionedTopics.put(topicName, currentPartitionNumber);
                List<String> newPartitions = list.subList(oldPartitionNumber, currentPartitionNumber);
                // subscribe new added partitions
                List<CompletableFuture<Consumer<T>>> futureList = newPartitions
                        .stream()
                        .map(partitionName -> {
                            int partitionIndex = TopicName.getPartitionIndex(partitionName);
                            CompletableFuture<Consumer<T>> subFuture = new CompletableFuture<>();
                            ConsumerConfigurationData<T> configurationData = getInternalConsumerConfig();
                            int receiverQueueSize = getReceiverQueueSize(topicName);
                            configurationData.setReceiverQueueSize(receiverQueueSize);
                            ConsumerImpl<T> newConsumer = ConsumerImpl.newConsumerImpl(
                                    client, partitionName, configurationData,
                                    client.externalExecutorProvider(),
                                    partitionIndex, true, subFuture, null, schema, interceptors,
                                    true /* createTopicIfDoesNotExist */);
                            synchronized (pauseMutex) {
                                if (paused) {
                                    newConsumer.pause();
                                }
                                addMessageCounterForTopic(newConsumer.getTopic());
                                consumers.putIfAbsent(newConsumer.getTopic(), newConsumer);
                            }
                            if (log.isDebugEnabled()) {
                                log.debug("[{}] create consumer {} for partitionName: {}",
                                        topicName, newConsumer.getTopic(), partitionName);
                            }
                            return subFuture;
                        })
                        .collect(Collectors.toList());

                // wait for all partitions subscribe future complete, then startReceivingMessages
                return FutureUtil.waitForAll(futureList)
                        .thenAccept(finalFuture -> {
                            List<ConsumerImpl<T>> newConsumerList = newPartitions.stream()
                                    .map(partitionTopic -> consumers.get(partitionTopic))
                                    .collect(Collectors.toList());
                            startReceivingMessages(newConsumerList);
                        });
            } else {
                log.error("[{}] not support shrink topic partitions. old: {}, new: {}",
                        topicName, oldPartitionNumber, currentPartitionNumber);
                return FutureUtil.failedFuture(new NotSupportedException("not support shrink topic partitions"));
            }
        });
    }

    private final TimerTask partitionsAutoUpdateTimerTask = new TimerTask() {
        @Override
        public void run(Timeout timeout) throws Exception {
            if (timeout.isCancelled() || getState() != State.Ready) {
                return;
            }

            if (log.isDebugEnabled()) {
                log.debug("[{}] run partitionsAutoUpdateTimerTask", topic);
            }

            // if last auto update not completed yet, do nothing.
            if (partitionsAutoUpdateFuture == null || partitionsAutoUpdateFuture.isDone()) {
                partitionsAutoUpdateFuture = topicsPartitionChangedListener.onTopicsExtended(partitionedTopics.keySet());
            }

            // schedule the next re-check task
            partitionsAutoUpdateTimeout = client.timer()
                    .newTimeout(partitionsAutoUpdateTimerTask, conf.getAutoUpdatePartitionsIntervalSeconds(), TimeUnit.SECONDS);
        }
    };

    @VisibleForTesting
    public Timeout getPartitionsAutoUpdateTimeout() {
        return partitionsAutoUpdateTimeout;
    }

    @Override
    public CompletableFuture<MessageId> getLastMessageIdAsync() {
        CompletableFuture<MessageId> returnFuture = new CompletableFuture<>();

        Map<String, CompletableFuture<MessageId>> messageIdFutures = consumers.entrySet().stream()
                .map(entry -> Pair.of(entry.getKey(), entry.getValue().getLastMessageIdAsync()))
                .collect(Collectors.toMap(Pair::getKey, Pair::getValue));

        CompletableFuture
                .allOf(messageIdFutures.entrySet().stream().map(Map.Entry::getValue).toArray(CompletableFuture<?>[]::new))
                .whenComplete((ignore, ex) -> {
                    ImmutableMap.Builder<String, MessageId> builder = ImmutableMap.builder();
                    messageIdFutures.forEach((key, future) -> {
                        MessageId messageId;
                        try {
                            messageId = future.get();
                        } catch (Exception e) {
                            log.warn("[{}] Exception when topic {} getLastMessageId.", key, e);
                            messageId = MessageId.earliest;
                        }
                        builder.put(key, messageId);
                    });
                    returnFuture.complete(new MultiMessageIdImpl(builder.build()));
                });

        return returnFuture;
    }

    public void tryAcknowledgeMessage(Message<T> msg) {
        if (msg != null) {
            acknowledgeCumulativeAsync(msg);
        }
    }

    // This listener is triggered when topics partitions are updated.
    private class TopicsPartitionChangedListener implements PartitionsChangedListener {
        // Check partitions changes of passed in topics, and subscribe new added partitions.
        @Override
        public CompletableFuture<Void> onTopicsExtended(Collection<String> topicsExtended) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            if (topicsExtended.isEmpty()) {
                future.complete(null);
                return future;
            }

            if (log.isDebugEnabled()) {
                log.debug("[{}]  run onTopicsExtended: {}, size: {}",
                        topic, topicsExtended, topicsExtended.size());
            }

            List<CompletableFuture<Void>> futureList = Lists.newArrayListWithExpectedSize(topicsExtended.size());
            topicsExtended.forEach(topic -> futureList.add(subscribeIncreasedTopicPartitions(topic)));
            FutureUtil.waitForAll(futureList)
                    .thenAccept(finalFuture -> future.complete(null))
                    .exceptionally(ex -> {
                        log.warn("[{}] Failed to subscribe increased topics partitions: {}", topic, ex.getMessage());
                        future.completeExceptionally(ex);
                        return null;
                    });

            return future;
        }
    }


}
