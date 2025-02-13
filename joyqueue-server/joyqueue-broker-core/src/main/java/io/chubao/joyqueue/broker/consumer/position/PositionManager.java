/**
 * Copyright 2019 The JoyQueue Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.chubao.joyqueue.broker.consumer.position;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import io.chubao.joyqueue.broker.cluster.ClusterManager;
import io.chubao.joyqueue.broker.consumer.ConsumeConfig;
import io.chubao.joyqueue.broker.consumer.model.ConsumePartition;
import io.chubao.joyqueue.broker.consumer.position.model.Position;
import io.chubao.joyqueue.domain.Consumer;
import io.chubao.joyqueue.domain.PartitionGroup;
import io.chubao.joyqueue.domain.TopicName;
import io.chubao.joyqueue.event.ConsumerEvent;
import io.chubao.joyqueue.event.EventType;
import io.chubao.joyqueue.event.MetaEvent;
import io.chubao.joyqueue.event.PartitionGroupEvent;
import io.chubao.joyqueue.exception.JoyQueueCode;
import io.chubao.joyqueue.exception.JoyQueueException;
import io.chubao.joyqueue.store.PartitionGroupStore;
import io.chubao.joyqueue.store.StoreService;
import io.chubao.joyqueue.toolkit.concurrent.EventListener;
import io.chubao.joyqueue.toolkit.concurrent.LoopThread;
import com.google.common.base.Preconditions;
import io.chubao.joyqueue.toolkit.service.Service;
import io.chubao.joyqueue.toolkit.time.SystemClock;
import com.jd.laf.extension.ExtensionManager;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消费位置管理
 * <p>
 * Created by chengzhiliang on 2018/8/23.
 */
public class PositionManager extends Service {

    private static Logger logger = LoggerFactory.getLogger(PositionManager.class);
    // 存储服务
    StoreService storeService;
    // 集群管理
    private ClusterManager clusterManager;
    // 消费配置
    private ConsumeConfig config;
    // 消费位点快照存储服务
    private PositionStore<ConsumePartition, Position> positionStore;
    // 补偿消费位置线程（10分钟跑一次）
    private LoopThread thread;
    // 最近应答时间跟踪器
    private Map<ConsumePartition, /* 最新应答时间 */ AtomicLong> lastAckTimeTrace = new ConcurrentHashMap<>();

    public PositionManager(ClusterManager clusterManager, StoreService storeService, ConsumeConfig consumeConfig) {
        this.clusterManager = clusterManager;
        this.storeService = storeService;
        this.config = consumeConfig;

        Preconditions.checkArgument(this.config != null, "config can not be null");
    }

    @Override
    protected void validate() throws Exception {
        super.validate();
        Preconditions.checkArgument(clusterManager != null, "cluster manager can not be null");

        if (positionStore == null) {
            positionStore = ExtensionManager.getOrLoadExtension(PositionStore.class);
            if (positionStore instanceof LocalFileStore) {
                ((LocalFileStore) positionStore).setBasePath(config.getConsumePositionPath());
            }
        }
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();

        positionStore.start();

        this.thread = LoopThread.builder()
                .sleepTime(1000 * 60 * 10, 1000 * 60 * 10)
                .name("Check-Subscribe-Thread")
                .onException(e -> logger.error(e.getMessage(), e))
                .doWork(this::compensationPosition)
                .build();

        this.thread.start();

        clusterManager.addListener(new AddConsumeListener());
        clusterManager.addListener(new RemoveConsumeListener());
        clusterManager.addListener(new AddPartitionGroupListener());
        clusterManager.addListener(new RemovePartitionGroupListener());
        clusterManager.addListener(new UpdatePartitionGroupListener());

        logger.info("PositionManager is started.");
    }

    /**
     * 补偿消费位置
     * 迭代当前broker上全部的
     */
    private void compensationPosition() {
        Iterator<ConsumePartition> iterator = positionStore.iterator();
        while (iterator.hasNext()) {
            ConsumePartition next = iterator.next();
            // 检查是否有订阅关系
            Consumer.ConsumerPolicy consumerPolicy = clusterManager.tryGetConsumerPolicy(TopicName.parse(next.getTopic()), next.getApp());
            if (consumerPolicy == null) {
                // 没有订阅关系，则删除消费位置
                iterator.remove();

                logger.info("Remove consume position by ConsumePosition:[{}]", next.toString());
            }
        }
    }

    @Override
    protected void doStop() {
        super.doStop();

        positionStore.stop();

        logger.info("PositionManager is stopped.");
    }

    /**
     * 获取最近一次应答消费位置跟踪器
     *
     * @return
     */
    public Map<ConsumePartition, AtomicLong> getLastAckTimeTrace() {
        return lastAckTimeTrace;
    }

    /**
     * 根据主题+应用+分区分组获取消费位置信息
     *
     * @param topic
     * @param app
     * @param partitionGroup
     * @return
     */
    public String getConsumePosition(TopicName topic, String app, int partitionGroup) {
        final List<String> appList = new ArrayList<>();
        if (app == null) {
            appList.addAll(clusterManager.getAppByTopic(topic));
        } else {
            appList.add(app);
        }
        Map<ConsumePartition, Position> consumeInfo = new HashMap<>();
        List<PartitionGroup> partitionGroupList = clusterManager.getPartitionGroup(topic);
        for (PartitionGroup group : partitionGroupList) {
            if (group.getGroup() == partitionGroup) {
                Set<Short> partitions = group.getPartitions();
                partitions.stream().forEach(partition ->
                        appList.stream().forEach(element -> {
                            ConsumePartition consumePartition = new ConsumePartition(topic.getFullName(), element, partition);
                            consumePartition.setPartitionGroup(partitionGroup);
                            Position position = positionStore.get(consumePartition);
                            consumeInfo.put(consumePartition, position);
                        })
                );
            }
        }
        return JSON.toJSONString(consumeInfo);
    }

    /**
     * 消费位置复制，可覆盖
     *
     * @param consumeInfoStr
     * @return
     */
    public boolean setConsumePosition(String consumeInfoStr) {
        try {
            // 将json解析成对象
            Map<ConsumePartition, Position> consumePositionMap = JSON.parseObject(consumeInfoStr, new TypeReference<Map<ConsumePartition, Position>>() {
            });
            // 替换内存中的位置信息
            Set<Map.Entry<ConsumePartition, Position>> entries = consumePositionMap.entrySet();
            entries.stream().forEach(entry -> {
                ConsumePartition key = entry.getKey();
                Position val = entry.getValue();
                positionStore.put(key, val);
            });
            // 刷盘
            positionStore.forceFlush();
        } catch (Exception ex) {
            logger.error("set consume position error.", ex);
            return false;
        }

        return true;
    }

    /**
     * 获取指定分区的应答消息序号
     *
     * @param topic     消费主题
     * @param app       消费应用
     * @param partition 消费分区
     * @return 指定分区已经消费到的消息序号
     */
    public long getLastMsgAckIndex(TopicName topic, String app, short partition) throws JoyQueueException {
        ConsumePartition consumePartition = new ConsumePartition(topic.getFullName(), app, partition);
        Position position = positionStore.get(consumePartition);
        // 消费位置对象为空时，无此位置信息抛出异常
        if (position == null) {
            throw new JoyQueueException(JoyQueueCode.CONSUME_POSITION_NULL, "topic=" + topic + ",app=" + app + ",partition=" + partition);
        }
        return position.getAckCurIndex();
    }

    /**
     * 更新指定分区的应答消息序号
     *
     * @param topic     消费主题
     * @param app       消费应用
     * @param partition 消费分区
     * @param index     起始消息序号
     * @return 是否更新成功
     */
    public boolean updateLastMsgAckIndex(TopicName topic, String app, short partition, long index) throws JoyQueueException {
        logger.debug("Update last ack index, topic:{}, app:{}, partition:{}, index:{}", topic, app, partition, index);
        // 检查索引有效性
        checkIndex(topic, partition, index);
        // 标记最近一次更新应答位置时间
        markLastAckTime(topic, app, partition);

        ConsumePartition consumePartition = new ConsumePartition(topic.getFullName(), app, partition);
        Position position = positionStore.get(consumePartition);
        if (position != null) {
            position.setAckCurIndex(index);
        } else {
            logger.error("Position is null, topic:{}, app:{}, partition:{}, index:{}", topic, app, partition, index);
            // 补偿逻辑：如果当前broker是指定partition对应partitionGroup的leader，则按照给定index初始化Position，否则不处理
            addAndUpdatePosition(topic, app, partition, index);
        }
        return true;
    }

    /**
     * 检查更新的位置是否有效
     *
     * @param topic
     * @param partition
     * @param index
     * @throws JoyQueueException
     */
    private void checkIndex(TopicName topic, short partition, long index) throws JoyQueueException {
        Integer partitionGroupId = clusterManager.getPartitionGroupId(topic, partition);
        if (partitionGroupId == null) {
            // 元数据获取不到partitionGroup
            throw new JoyQueueException(JoyQueueCode.CONSUME_POSITION_META_DATA_NULL, String.format("topic:[%s], partition:[%s], index:[%s]", topic, partition, index));
        }

        PartitionGroupStore store = storeService.getStore(topic.getFullName(), partitionGroupId);

        long leftIndex = store.getLeftIndex(partition);
        if (index < leftIndex) {
            throw new JoyQueueException(JoyQueueCode.SE_INDEX_UNDERFLOW , "index less than leftIndex error.");
        }

        long rightIndex = store.getRightIndex(partition);
        if (index > rightIndex) {
            throw new JoyQueueException(JoyQueueCode.SE_INDEX_UNDERFLOW , "index more than rightIndex error.");
        }


    }

    /**
     * 标记最后一次应答时间
     * @param topic
     * @param app
     * @param partition
     */
    private void markLastAckTime(TopicName topic, String app, short partition) {
        ConsumePartition consumePartition = new ConsumePartition(topic.getFullName(), app, partition);
        AtomicLong lastAckTime = lastAckTimeTrace.get(consumePartition);
        if (lastAckTime == null) {
            lastAckTime = new AtomicLong(SystemClock.now());
            lastAckTimeTrace.put(consumePartition, lastAckTime);
        } else {
            lastAckTime.set(SystemClock.now());
        }
    }

    /**
     * 添加并更新消费位置
     *
     * @param topic
     * @param app
     * @param partition
     * @param index
     */
    private void addAndUpdatePosition(TopicName topic, String app, short partition, long index) throws JoyQueueException {
        logger.info("Try to init a position by topic:{}, app:{}, partition:{}, curIndex:{}", topic.getFullName(), app, partition, index);

        if (topic == null || app == null || app.isEmpty()) {
            return;
        }
        checkState();
        // 从元数据中获取分区分组，如何分区分组不为空，则添加该partition的位置，否则不添加
        PartitionGroup partitionGroup = clusterManager.getPartitionGroup(topic, partition);
        if(partitionGroup == null) {
            logger.error("Fail to add and update partition consume position by topic:[{}], app:[{}], partition:[{}], index:[{}]",
                    topic.getFullName(), app, partition, index);
            throw new JoyQueueException(JoyQueueCode.FW_PARTITION_BROKER_NOT_LEADER, "");
        }
        ConsumePartition consumePartition = new ConsumePartition(topic.getFullName(), app, partition);
        consumePartition.setPartitionGroup(partitionGroup.getGroup());

        long currentIndex = Math.max(index, 0);
        // 为新订阅的应用初始化消费位置对象
        Position position = new Position(currentIndex, currentIndex, currentIndex, currentIndex);
        positionStore.putIfAbsent(consumePartition, position);

        logger.info("Success to add and update partition consume position by topic:{}, app:{}, partition:{}, curIndex:{}", topic.getFullName(), app, partition, currentIndex);
        // 落盘
        positionStore.forceFlush();
    }

    /**
     * 更新指定分区的应答消息开始序号
     *
     * @param topic     消费主题
     * @param app       消费应用
     * @param partition 消费分区
     * @param index     起始消息序号
     * @return 是否更新成功
     */
    public boolean updateStartMsgAckIndex(TopicName topic, String app, short partition, long index) throws JoyQueueException {
        logger.debug("Update stater ack index, topic:{}, app:{}, partition:{}, index:{}", topic, app, partition, index);
        ConsumePartition consumePartition = new ConsumePartition(topic.getFullName(), app, partition);
        Position position = positionStore.get(consumePartition);
        if (position != null) {
            position.setAckStartIndex(index);
        } else {
            logger.error("Position is null, topic:{}, app:{}, partition:{}, index:{}", topic, app, partition, index);
            // 补偿逻辑：如果当前broker是指定partition对应partitionGroup的leader，则按照给定index初始化Position，否则不处理
            addAndUpdatePosition(topic, app, partition, index);
        }
        return true;
    }

    /**
     * 获取指定分区的拉取消息序号
     *
     * @param topic     消费主题
     * @param app       消费应用
     * @param partition 消费分区
     * @return 指定分区已经消费到的消息序号
     */
    public long getLastMsgPullIndex(TopicName topic, String app, short partition) throws JoyQueueException {
        ConsumePartition consumePartition = new ConsumePartition(topic.getFullName(), app, partition);
        Position position = positionStore.get(consumePartition);
        // 消费位置对象为空时，无此位置信息抛出异常
        if (position == null) {
            throw new JoyQueueException(JoyQueueCode.CONSUME_POSITION_NULL, "topic=" + topic + ",app=" + app + ",partition=" + partition);
        }
        return position.getPullCurIndex();
    }

    /**
     * 更新指定分区的拉取消息序号
     *
     * @param topic     消费主题
     * @param app       消费应用
     * @param partition 消费分区
     * @param index     起始消息序号
     * @return 是否更新成功
     */
    public boolean updateLastMsgPullIndex(TopicName topic, String app, short partition, long index) throws JoyQueueException {
        logger.debug("Update last pull index, topic:{}, app:{}, partition:{}, index:{}", topic, app, partition, index);
        ConsumePartition consumePartition = new ConsumePartition(topic.getFullName(), app, partition);
        Position position = positionStore.get(consumePartition);
        if (position != null) {
            position.setPullCurIndex(index);
        } else {
            logger.error("Position is null, topic:{}, app:{}, partition:{}, index:{}", topic, app, partition, index);
            // 补偿逻辑：如果当前broker是指定partition对应partitionGroup的leader，则按照给定index初始化Position，否则不处理
            addAndUpdatePosition(topic, app, partition, index);
        }
        return true;
    }


    /**
     * 增加指定分区的拉取消息序号
     *
     * @param topic     消费主题
     * @param app       消费应用
     * @param partition 消费分区
     * @param count     增加的连续序号
     * @return 是否更新成功
     */
    public boolean increaseMsgPullIndex(TopicName topic, String app, short partition, int count) throws JoyQueueException {
        long lastMsgPullIndex = getLastMsgPullIndex(topic, app, partition);
        long updateMsgPullIndex = lastMsgPullIndex + count;
        return updateLastMsgPullIndex(topic, app, partition, updateMsgPullIndex);
    }

    /**
     * 获取指定分区写入的最大消息序号
     *
     * @param topic     消费主题
     * @param partition 消费分区
     * @return 指定分区已经消费到的消息序号
     */
    private long getMaxMsgIndex(TopicName topic, short partition) {
        Integer partitionGroupId = clusterManager.getPartitionGroupId(topic, partition);
        PartitionGroupStore store = storeService.getStore(topic.getFullName(), partitionGroupId);
        long rightIndex = store.getRightIndex(partition);
        return rightIndex;
    }

    protected void checkState() {
        if (!isStarted()) {
            throw new IllegalStateException("offset manager was stopped");
        }
    }

    /**
     * 新增订阅者，用于记录第一次订阅时开始的位置
     * 或运行时动态增加分区时需要初始化消费位置
     *
     * @param topic 主题
     * @param app   消费者
     */
    public void addConsumer(final TopicName topic, final String app) {
        if (topic == null || app == null || app.isEmpty()) {
            return;
        }
        checkState();
        // 从元数据中获取分组和分区数据，初始化拉取和应答位置
        List<Short> partitionList = clusterManager.getMasterPartitionList(topic);

        logger.debug("add consumer partitionList:[{}]", partitionList.toString());

        partitionList.stream().forEach(partition -> {
            ConsumePartition consumePartition = new ConsumePartition(topic.getFullName(), app, partition);

            // 获取partitionGroup
            Integer partitionGroupId = clusterManager.getPartitionGroupId(topic, partition);
            consumePartition.setPartitionGroup(partitionGroupId);

            // 获取当前（主题+分区）的最大消息序号
            long currentIndex = getMaxMsgIndex(topic, partition);
            currentIndex = Math.max(currentIndex, 0);
            // 为新订阅的应用初始化消费位置对象
            Position position = new Position(currentIndex, currentIndex, currentIndex, currentIndex);
            positionStore.putIfAbsent(consumePartition, position);

            logger.debug("Add ConsumePartition by topic:{}, app:{}, partition:{}, curIndex:{}", topic.getFullName(), app, partition, currentIndex);
        });
        // 落盘
        positionStore.forceFlush();
    }

    /**
     * 移除订阅者
     *
     * @param topic 主题
     * @param app   消费者
     */
    public void removeConsumer(final TopicName topic, final String app) {
        if (topic == null || app == null || app.isEmpty()) {
            return;
        }
        checkState();
        // 从元数据中获取分组和分区数据，初始化拉取和应答位置
        List<Short> partitionList = clusterManager.getPartitionList(topic);

        logger.debug("remove consumer partitionList:[{}]", partitionList.toString());

        partitionList.stream().forEach(partition -> {
            ConsumePartition consumePartition = new ConsumePartition(topic.getFullName(), app, partition);
            Position remove = positionStore.remove(consumePartition);

            logger.info("Remove ConsumePartition by topic:{}, app:{}, partition:{}, curIndex:{}",
                    consumePartition.getTopic(), consumePartition.getApp(), consumePartition.getPartition(), remove.toString());
        });
        // 落盘
        positionStore.forceFlush();
    }

    /**
     * 获取消费位置
     *
     * @param topic     主题
     * @param app       应用
     * @param partition 分区
     * @return
     */
    public Position getPosition(TopicName topic, String app, short partition) {
        ConsumePartition consumePartition = new ConsumePartition(topic.getFullName(), app, partition);
        return positionStore.get(consumePartition);
    }

    /**
     * 指定主题、消费者、分区添加消费位置
     *
     * @param topic          主题
     * @param partitionGroup 分区分组
     */
    private void addPartitionGroup(TopicName topic, int partitionGroup) {
        PartitionGroup partitionGroupByGroup = clusterManager.getPartitionGroupByGroup(topic, partitionGroup);
        List<String> appList = clusterManager.getAppByTopic(topic);
        Set<Short> partitions = partitionGroupByGroup.getPartitions();

        logger.debug("add partitionGroup appList:[{}], partitions:[{}]", appList.toString(), partitions.toString());

        partitions.stream().forEach(partition -> {
            // 获取当前（主题+分区）的最大消息序号
            long currentIndex = getMaxMsgIndex(topic, partition);
            long currentIndexVal = Math.max(currentIndex, 0);

            appList.stream().forEach(app -> {
                ConsumePartition consumePartition = new ConsumePartition(topic.getFullName(), app, partition);
                consumePartition.setPartitionGroup(partitionGroup);

                // 为新订阅的应用初始化消费位置对象
                Position position = new Position(currentIndex, currentIndex, currentIndex, currentIndex);

                positionStore.putIfAbsent(consumePartition, position);

                logger.info("Add consume partition by topic:{}, app:{}, partition:{}, curIndex:{}", topic.getFullName(), app, partition, currentIndexVal);
            });
        });

        positionStore.forceFlush();
    }

    /**
     * 指定主题、消费者、分区移除消费位置
     *
     * @param topic          主题
     * @param partitionGroup 分区分组
     */
    private void removePartitionGroup(TopicName topic, int partitionGroup) {
        logger.debug("remove partitionGroup topic:[{}], partitionGroup:[{}]", topic.getFullName(), partitionGroup);

        Iterator<ConsumePartition> iterator = positionStore.iterator();
        while(iterator.hasNext()) {
            ConsumePartition consumePartition = iterator.next();
            if (consumePartition != null && consumePartition.getPartitionGroup() == partitionGroup && StringUtils.equals(consumePartition.getTopic(), topic.getFullName())) {
                iterator.remove();

                logger.info("Remove ConsumePartition by topic:{}, app:{}, partition:{}", consumePartition.getTopic(), consumePartition.getApp(), consumePartition.getPartition());
            }
        }

        positionStore.forceFlush();
    }

    /**
     * 添加事件
     */
    class AddConsumeListener implements EventListener {

        @Override
        public void onEvent(Object event) {
            try {
                if (((MetaEvent) event).getEventType() == EventType.ADD_CONSUMER) {
                    ConsumerEvent addConsumerEvent = (ConsumerEvent) event;

                    logger.info("listen add consume event:[{}]", addConsumerEvent.toString());

                    addConsumer(addConsumerEvent.getTopic(), addConsumerEvent.getApp());
                }
            } catch (Exception ex) {
                logger.error("AddConsumeListener error.", ex);
            }
        }
    }

    /**
     * 移除事件
     */
    class RemoveConsumeListener implements EventListener {

        @Override
        public void onEvent(Object event) {
            try {
                if (((MetaEvent) event).getEventType() == EventType.REMOVE_CONSUMER) {
                    ConsumerEvent removeConsumerEvent = (ConsumerEvent) event;

                    logger.info("listen remove consume event:[{}]", removeConsumerEvent.toString());

                    removeConsumer(removeConsumerEvent.getTopic(), removeConsumerEvent.getApp());
                }
            } catch (Exception ex) {
                logger.error("RemoveConsumeListener error.", ex);
            }
        }
    }

    /**
     * 添加分区分组事件
     */
    class AddPartitionGroupListener implements EventListener {

        @Override
        public void onEvent(Object event) {
            try {
                if (((MetaEvent) event).getEventType() == EventType.ADD_PARTITION_GROUP) {
                    PartitionGroupEvent partitionGroupEvent = (PartitionGroupEvent) event;

                    logger.info("listen add partition group event:[{}]", partitionGroupEvent.toString());

                    addPartitionGroup(partitionGroupEvent.getTopic(), partitionGroupEvent.getPartitionGroup());
                }
            } catch (Exception ex) {
                logger.error("AddPartitionGroupListener error.", ex);
            }
        }
    }

    /**
     * 删除分区分组事件
     */
    class RemovePartitionGroupListener implements EventListener {

        @Override
        public void onEvent(Object event) {
            try {
                if (((MetaEvent) event).getEventType() == EventType.REMOVE_PARTITION_GROUP) {
                    PartitionGroupEvent partitionGroupEvent = (PartitionGroupEvent) event;

                    logger.info("listen remove partition group event:[{}]", partitionGroupEvent.toString());

                    removePartitionGroup(partitionGroupEvent.getTopic(), partitionGroupEvent.getPartitionGroup());
                }
            } catch (Exception ex) {
                logger.error("RemovePartitionGroupListener error.", ex);
            }

        }
    }

    /**
     * 更新分区分组事件(添加分区或减少分区)
     */
    class UpdatePartitionGroupListener implements EventListener {

        @Override
        public void onEvent(Object event) {
            try {
                if (((MetaEvent) event).getEventType() == EventType.UPDATE_PARTITION_GROUP) {
                    PartitionGroupEvent partitionGroupEvent = (PartitionGroupEvent) event;

                    logger.info("listen update partition group event:[{}]", partitionGroupEvent.toString());

                    TopicName topic = partitionGroupEvent.getTopic();
                    int partitionGroup = partitionGroupEvent.getPartitionGroup();

                    Set<Short> newPartitionSet = clusterManager.getTopicConfig(topic).fetchAllPartitions();

                    Iterator<ConsumePartition> iterator = positionStore.iterator();
                    while (iterator.hasNext()) {
                        ConsumePartition next = iterator.next();
                        if (StringUtils.equals(next.getTopic(), topic.getFullName()) && /* 不在最新分区集合中 */ !newPartitionSet.contains(next.getPartition())) {
                            // 缓存中的分区位置信息，不在最新的分区集合中，则删除
                            iterator.remove();
                        }
                    }

                    addPartitionGroup(topic, partitionGroup);
                }
            } catch (Exception ex) {
                logger.error("UpdatePartitionGroupListener error.", ex);
            }
        }
    }

}
