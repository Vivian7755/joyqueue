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
package io.chubao.joyqueue.broker.archive;

import com.google.common.base.Preconditions;
import io.chubao.joyqueue.broker.Plugins;
import io.chubao.joyqueue.broker.buffer.Serializer;
import io.chubao.joyqueue.broker.cluster.ClusterManager;
import io.chubao.joyqueue.broker.consumer.Consume;
import io.chubao.joyqueue.broker.consumer.MessageConvertSupport;
import io.chubao.joyqueue.broker.consumer.model.PullResult;
import io.chubao.joyqueue.domain.TopicConfig;
import io.chubao.joyqueue.domain.TopicName;
import io.chubao.joyqueue.exception.JoyQueueException;
import io.chubao.joyqueue.message.BrokerMessage;
import io.chubao.joyqueue.message.SourceType;
import io.chubao.joyqueue.network.session.Consumer;
import io.chubao.joyqueue.server.archive.store.api.ArchiveStore;
import io.chubao.joyqueue.server.archive.store.model.AchivePosition;
import io.chubao.joyqueue.server.archive.store.model.SendLog;
import io.chubao.joyqueue.store.PositionUnderflowException;
import io.chubao.joyqueue.toolkit.concurrent.LoopThread;
import io.chubao.joyqueue.toolkit.concurrent.NamedThreadFactory;
import io.chubao.joyqueue.toolkit.lang.Close;
import io.chubao.joyqueue.toolkit.service.Service;
import io.chubao.joyqueue.toolkit.time.SystemClock;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 发送归档服务
 * <p>
 * Created by chengzhiliang on 2018/12/4.
 */
public class ProduceArchiveService extends Service {

    private static final Logger logger = LoggerFactory.getLogger(ProduceArchiveService.class);
    // 每批读取/写入量
    private int batchNum = 1000;
    // 集群管理
    private ClusterManager clusterManager;
    // 消费管理
    private Consume consume;
    // 发送归档任务池
    private ItemList itemList = new ItemList();
    // 归档存储服务
    private ArchiveStore archiveStore;
    // 归档消息队列
    private BlockingQueue<SendLog> archiveQueue;
    // 写入存储计数器
    private ConcurrentMap<String, AtomicInteger> storeCounter = new ConcurrentHashMap<>();
    // 分隔符
    private String separator = ":";
    // 写存储服务线程池
    private ExecutorService executorService;
    // 操作存储是否失败
    AtomicBoolean hasStoreError = new AtomicBoolean(false);
    // 读不到消息暂停Map
    private final Map<String, Long> pauseMap = new HashMap<>();


    // 负责监听元数据变化,并且同步归档位置
    private LoopThread updateItemThread;
    // 负责读取消息写入队列
    private LoopThread readMsgThread;
    // 消费队列写入存储
    private LoopThread writeMsgThread;

    private ArchiveConfig archiveConfig;
    private MessageConvertSupport messageConvertSupport;

    public ProduceArchiveService(ArchiveConfig archiveConfig, ClusterManager clusterManager, Consume consume, MessageConvertSupport messageConvertSupport) {
        this.clusterManager = clusterManager;
        this.consume = consume;
        this.archiveConfig = archiveConfig;
        this.messageConvertSupport = messageConvertSupport;
    }

    @Override
    protected void validate() throws Exception {
        super.validate();
        archiveStore = archiveStore != null ? archiveStore : Plugins.ARCHIVESTORE.get();
        archiveStore.setNameSpace(archiveConfig.getNamespace());

        logger.info("Get archive store namespace [{}] by archive config.", archiveConfig.getNamespace());

        Preconditions.checkArgument(archiveStore != null, "archive store can not be null.");
        Preconditions.checkArgument(archiveConfig != null, "archive config can not be null.");

        this.batchNum = archiveConfig.getReadBatchNum();
        this.archiveQueue = new LinkedBlockingDeque<>(archiveConfig.getLogQueueSize());
        this.executorService = new ThreadPoolExecutor(archiveConfig.getWriteThreadNum(), archiveConfig.getWriteThreadNum(),
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(archiveConfig.getThreadPoolQueueSize()), new NamedThreadFactory("sendLog-archive"), new ThreadPoolExecutor.CallerRunsPolicy());
        this.updateItemThread = LoopThread.builder()
                .sleepTime(1000 * 10, 1000 * 10)
                .name("UpdateArchiveItem-Thread")
                .onException(e -> logger.warn("Exception:", e))
                .doWork(() -> {
                    // 更新item列表
                    updateArchiveItem();
                    // 同步归档位置
                    syncArchivePosition();
                }).build();

        this.readMsgThread = LoopThread.builder()
                .sleepTime(0, 10)
                .name("ReadArchiveMsg-Thread")
                .onException(e -> logger.warn("Exception:", e))
                .doWork(() -> {
                    // 消费接口读取消息，放入队列
                    readArchiveMsg();
                }).build();

        this.writeMsgThread = LoopThread.builder()
                .sleepTime(10, 10)
                .name("WriteArchiveMsg-Thread")
                .onException(e -> logger.warn("Exception:", e))
                .doWork(() -> {
                    // 队列读取消息，放入归档存储
                    write2Store();
                }).build();


    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();
        archiveStore.start();
        // 更新item列表
        updateArchiveItem();
        updateItemThread.start();
        readMsgThread.start();
        writeMsgThread.start();
        logger.info("produce archive service started.");
    }

    @Override
    protected void doStop() {
        super.doStop();
        Close.close(updateItemThread);
        Close.close(readMsgThread);
        Close.close(writeMsgThread);
        Close.close(executorService);
        Close.close(archiveStore);
        logger.info("produce archive service stopped.");
    }

    /**
     * 更新归档项
     */
    private void updateArchiveItem() throws JoyQueueException {
        List<SendArchiveItem> list = new ArrayList<>();

        List<TopicConfig> topics = clusterManager.getTopics();
        int brokerId = clusterManager.getBroker().getId();
        topics.stream().forEach(topicConfig -> {
            TopicName name = topicConfig.getName();
            // 检查是否开启发送归档
            if (clusterManager.checkArchiveable(name)) {
                logger.info("Topic:{} send archive is enable.", name.getFullName());

                List<Short> partitionSet = topicConfig.fetchPartitionByBroker(brokerId);
                partitionSet.stream().forEach(partition -> {
                    list.add(new SendArchiveItem(name.getFullName(), partition));
                });
            }
        });
        // 新增或更新归档项
        itemList.addAndUpdate(list);
    }

    /**
     * 读取归档消息
     */
    private void readArchiveMsg() throws Exception {
        int counter = 0;
        List<SendArchiveItem> all = itemList.getAll();
        for (SendArchiveItem item : all) {
            if (isPause(item.getTopic(), item.getPartition())) {
                continue;
            }
            PullResult pullResult;
            try {
                pullResult = consume.getMessage(new Consumer(item.topic, ""), item.partition, item.getReadIndex(), batchNum);
            } catch (Throwable th) {
                logger.error("read message from topic:" + item.topic + " partition:" + item.partition
                        + " index:" + item.getReadIndex() + " error.", th);

                if (th instanceof PositionUnderflowException) {
                    // 如果读取位置小于存储索引的最小位置，将位置重置为可读到的最小位置
                    PositionUnderflowException positionException = (PositionUnderflowException) th;

                    logger.info("repair read message position SendArchiveItem info:[{}], currentIndex:[{}]", item, positionException.getLeft());

                    item.setReadIndex(positionException.getLeft());
                }

                // 报错暂停一会
                put2PauseMap(item.getTopic(), item.getPartition());
                continue;
            }

            if (pullResult.getBuffers().size() == 0) {
                put2PauseMap(item.getTopic(), item.getPartition());
            } else {
                int size = putSendLog2Queue(pullResult);
                // 更新下次拉取位置(当前位置序号 + 拉取到的消息条数)
                item.setReadIndex(item.getReadIndex() + size);
                // 计数
                counter += pullResult.getBuffers().size();
            }
        }
        if (counter == 0) {
            Thread.sleep(10);
        }
    }

    /**
     * @param topic
     * @param partition
     */
    private void put2PauseMap(String topic, Short partition) {
        int pauseTime = 2000; // 读取不到消息时，间隔多久去读取
        pauseMap.put(topic + separator + partition, SystemClock.now() + pauseTime);
    }


    /**
     * 是否需要暂停
     *
     * @param topic
     * @param partition
     * @return
     */
    private boolean isPause(String topic, Short partition) {
        Long expireTime = pauseMap.get(topic + separator + partition);
        if (expireTime == null) {
            return false;
        }
        return expireTime > SystemClock.now();
    }

    /**
     * 入队
     *
     * @param pullResult
     * @throws Exception
     */
    private int putSendLog2Queue(PullResult pullResult) throws Exception {
        int readCount = 0;
        List<ByteBuffer> buffers = pullResult.getBuffers();
        for (ByteBuffer buffer : buffers) {

            List<BrokerMessage> brokerMessageList = parseMessage(buffer);

            for (BrokerMessage brokerMessage : brokerMessageList) {
                brokerMessage.setTopic(pullResult.getTopic());
                brokerMessage.setPartition(pullResult.getPartition());
                SendLog sendLog = convert(brokerMessage, buffer);
                archiveQueue.put(sendLog);
            }

            readCount += brokerMessageList.size();
        }

        return readCount;
    }

    /**
     * 解析消息
     *
     * @param buffer
     * @return
     * @throws Exception
     */
    private List<BrokerMessage> parseMessage(ByteBuffer buffer) throws Exception {
        BrokerMessage brokerMessage = Serializer.readBrokerMessage(buffer);
        List<BrokerMessage> brokerMessageList = new LinkedList<>();
        if (brokerMessage.getSource() == SourceType.KAFKA.getValue() && brokerMessage.isBatch()) {
            brokerMessageList = messageConvertSupport.convertBatch(brokerMessage, SourceType.INTERNAL.getValue());
        } else {
            brokerMessageList.add(brokerMessage);
        }
        return brokerMessageList;
    }

    /**
     * 转换消息信息转换发送日志
     *
     * @param brokerMessage 消息信息
     * @return 发送日志
     */
    private SendLog convert(BrokerMessage brokerMessage, ByteBuffer buffer) {
        SendLog sendLog = new SendLog();
        sendLog.setTopic(brokerMessage.getTopic());
        sendLog.setSendTime(brokerMessage.getStartTime());
        sendLog.setBusinessId(brokerMessage.getBusinessId() == null ? "" : brokerMessage.getBusinessId());
        sendLog.setMessageId(brokerMessage.getTopic() + brokerMessage.getPartition() + brokerMessage.getMsgIndexNo());
        sendLog.setBrokerId(clusterManager.getBrokerId());
        sendLog.setApp(brokerMessage.getApp());
        sendLog.setClientIp(brokerMessage.getClientIp());
        sendLog.setCompressType((short) -1);
        sendLog.setMessageBody(buffer.array());
        sendLog.setPartition(brokerMessage.getPartition());
        sendLog.setIndex(brokerMessage.getMsgIndexNo());

        return sendLog;
    }

    /**
     * 写入数据
     *
     * @throws InterruptedException
     * @throws JoyQueueException
     */
    private void write2Store() throws InterruptedException {
        List<SendLog> sendLogs = new ArrayList<>(batchNum);
        for (int i = 0; i < batchNum; i++) {
            SendLog sendLog = archiveQueue.poll(10, TimeUnit.MILLISECONDS);
            if (sendLog == null) {
                break;
            }
            sendLogs.add(sendLog);
        }
        if (sendLogs.size() > 0) {
            // 提交任务
            executorService.submit(() -> {
                try {
                    // 写入存储
                    archiveStore.putSendLog(sendLogs);
                    logger.debug("Write sendLogs size:{} to archive store.", sendLogs.size());
                    // 写入计数（用于归档位置）
                    writeCounter(sendLogs);
                } catch (JoyQueueException e) {
                    // 写入存储失败
                    hasStoreError.set(true);
                    // 回滚读取位置
                    rollBackReadIndex(sendLogs);
                }
            });
        }
        if (hasStoreError.getAndSet(false)) {
            // 操作存储失败，等待1000ms
            Thread.sleep(1000);
        }
    }

    /**
     * 写入计数
     */
    private void writeCounter(List<SendLog> sendLogs) {
        sendLogs.stream().forEach(sendLog -> {
            AtomicInteger counter = storeCounter.get(sendLog.getTopic() + separator + sendLog.getPartition());
            if (counter == null &&
                    null == storeCounter.putIfAbsent(sendLog.getTopic() + separator + sendLog.getPartition(), new AtomicInteger())) {
                counter = storeCounter.get(sendLog.getTopic() + separator + sendLog.getPartition());
            }
            counter.incrementAndGet();
        });
    }

    /**
     * 回滚读取消息的消费位置
     *
     * @param sendLogs
     */
    private void rollBackReadIndex(List<SendLog> sendLogs) {
        // 合并同主题、分区的位置
        SendLog first = sendLogs.get(0);
        String topic = first.getTopic();
        short partition = first.getPartition();
        long index = first.getIndex();
        for (SendLog sendLog : sendLogs) {
            if (topic.equals(sendLog.getTopic()) && partition == sendLog.getPartition()) {
                // 同分区、同主题，取最小位置
                index = Math.min(index, sendLog.getIndex());
            } else {
                // 更新读取位置
                itemList.updateReadIndex(topic, partition, index);

                topic = sendLog.getTopic();
                partition = sendLog.getPartition();
                index = sendLog.getIndex();
            }
        }

        // 更新读取位置
        itemList.updateReadIndex(topic, partition, index);
    }

    /**
     * 同步归档位置到存储（异常恢复、重启等场景）
     */
    private void syncArchivePosition() {
        Iterator<String> iterator = storeCounter.keySet().iterator();
        while (iterator.hasNext()) {
            String key = iterator.next();
            String[] split = key.split(separator);
            String topic = split[0];
            short partition = Short.parseShort(split[1]);
            AtomicInteger counter = storeCounter.get(key);
            int num;
            while (!counter.compareAndSet(num = counter.get(), 0)) {
            }
            try {
                // 从存储中读取消息
                Long position = archiveStore.getPosition(topic, partition);
                position = position == null ? 0 : position;
                // 将新的位置写入存储
                archiveStore.putPosition(new AchivePosition(topic, partition, position + num));
            } catch (Throwable th) {
                // 回滚
                int rollbackNum;
                while (!counter.compareAndSet(rollbackNum = counter.get(), rollbackNum + num)) {
                }
            }
        }
    }

    /**
     * 获取当前broker上的待归档的消息数量
     *
     * @return
     */
    public long remainMessagesSum() {
        List<TopicConfig> topics = clusterManager.getTopics();
        long sum = topics.stream().mapToLong(topic -> remainMessagesSum(topic.getName().getFullName())).sum();
        return sum;
    }

    /**
     * 获取指定topic待归档的消息数量
     *
     * @param topic
     * @return
     */
    public long remainMessagesSum(String topic) {
        return getCurrentIndexSum(topic) - getArchiveIndexSum(topic);
    }

    /**
     * 获取指定topic在当前broker上分区最大序号和
     *
     * @param topic 消费主题
     * @return
     */
    public long getCurrentIndexSum(String topic) {
        List<Short> partitionList = clusterManager.getMasterPartitionList(TopicName.parse(topic));
        long sum = partitionList.stream().mapToLong(partition ->
                Math.max(0, (consume.getMaxIndex(new Consumer(topic, ""), partition) - 1))).sum();
        return sum;
    }

    /**
     * 获取指定topic在当前broker上分区归档序号和
     *
     * @param topic 消费主题
     * @return
     */
    public long getArchiveIndexSum(String topic) {
        List<SendArchiveItem> all = itemList.getAll();
        long sum = all.stream().filter(task -> task.topic.equals(topic)).mapToLong(task -> task.getReadIndex()).sum();
        return sum;
    }

    /**
     * 发送归档项
     */
    class SendArchiveItem {
        private final String topic;
        private final Short partition;
        private AtomicLong readIndex; // 读序号

        SendArchiveItem(String topic, Short partition) {
            this.topic = topic;
            this.partition = partition;
        }

        public long getReadIndex() {
            if (readIndex == null) {
                this.readIndex = new AtomicLong(0);
            }
            return readIndex.get();
        }

        public void setReadIndex(long index) {
            if (this.readIndex == null) {
                this.readIndex = new AtomicLong(index);
            } else {
                this.readIndex.set(index);
            }
        }

        public String getTopic() {
            return topic;
        }

        public Short getPartition() {
            return partition;
        }

        @Override
        public int hashCode() {
            return super.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            SendArchiveItem that = (SendArchiveItem) obj;
            if (!StringUtils.equals(this.topic, that.topic)) {
                return false;
            }
            if (this.partition != that.partition) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return "SendArchiveItem{" +
                    "topic='" + topic + '\'' +
                    ", partition=" + partition +
                    ", readIndex=" + readIndex +
                    '}';
        }
    }


    /**
     * 发送归档项列表
     * <br>
     * 负责任务管理
     */
    class ItemList {
        // 发送归档任务容器 TODO 替换成跳跃表结构？？
        private CopyOnWriteArrayList<SendArchiveItem> cpList = new CopyOnWriteArrayList<>();

        /**
         * 获取列表中所有归档项
         *
         * @return 容器中所有任务
         */
        public List<SendArchiveItem> getAll() {
            return cpList;
        }

        /**
         * 移除列表中指定项
         *
         * @param item 发送归档项
         */
        public void remove(SendArchiveItem item) {
            // 移除列表
            cpList.remove(item);
        }

        /**
         * 更新归档项列表（元数据变化情况下可能调用）
         * <br>
         * 删除失效归档项、添加新归档项
         *
         * @param newItemList
         */
        public void addAndUpdate(List<SendArchiveItem> newItemList) throws JoyQueueException {
            // 删除当前失效的归档项
            cpList.stream().forEach(item -> {
                // 最新的归档项列表不包含这个项，则删除该项
                if (!newItemList.contains(item)) {
                    remove(item);
                }
            });
            // 添加新增归档项
            for (SendArchiveItem item : newItemList) {
                // 列表中不包含则添加
                if (!cpList.contains(item)) {
                    Long index = archiveStore.getPosition(item.topic, item.partition);
                    if (index == null) {
                        // 从头拉起
                        index = 0L;
                    }
                    item.setReadIndex(index);
                    cpList.add(item);
                }
            }
        }

        /**
         * 更新拉取消息的位置（异常回滚场景）
         *
         * @param topic
         * @param partition
         * @param newReadIndex
         */
        public void updateReadIndex(String topic, short partition, long newReadIndex) {
            Optional<SendArchiveItem> any = cpList.stream().filter(item -> topic.equals(item.getTopic()) && item.getPartition().equals(partition)).findAny();
            if (any.isPresent()) {
                SendArchiveItem sendArchiveItem = any.get();
                long index = sendArchiveItem.getReadIndex();
                if (index > newReadIndex) {
                    sendArchiveItem.setReadIndex(newReadIndex);
                }
            }
        }
    }

}
