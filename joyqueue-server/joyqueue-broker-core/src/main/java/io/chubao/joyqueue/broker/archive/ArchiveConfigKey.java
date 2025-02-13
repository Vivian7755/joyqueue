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

import io.chubao.joyqueue.toolkit.config.PropertyDef;

/**
 * 归档配置
 * <p>
 * Created by chengzhiliang on 2018/12/6.
 */
public enum ArchiveConfigKey implements PropertyDef {
    WRITE_BATCH_NUM("archive.write.batch.num", 1000, Type.INT),
    READ_BATCH_NUM("archive.read.batch.num", 1000, Type.INT),
    LOG_QUEUE_SIZE("archive.send.log.queue.size", 10000, Type.INT),
    WRITE_THREAD_NUM("archive.thread.num", 5, Type.INT),
    ARCHIVE_SWITCH("archive.switch", true, Type.BOOLEAN),
    ARCHIVE_THREAD_POOL_QUEUE_SIZE("archive.thread.pool.queue.size", 10, Type.INT),
    ARCHIVE_STORE_NAMESPACE("archive.store.namespace", "joyqueue", Type.STRING);

    private String name;
    private Object value;
    private PropertyDef.Type type;

    ArchiveConfigKey(String name, Object value, PropertyDef.Type type) {
        this.name = name;
        this.value = value;
        this.type = type;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getValue() {
        return value;
    }

    @Override
    public Type getType() {
        return type;
    }

}
