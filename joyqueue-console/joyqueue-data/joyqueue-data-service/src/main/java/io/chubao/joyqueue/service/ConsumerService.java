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
package io.chubao.joyqueue.service;

import io.chubao.joyqueue.model.domain.Consumer;
import io.chubao.joyqueue.model.query.QConsumer;
import io.chubao.joyqueue.nsr.NsrService;

import java.util.List;


public interface ConsumerService extends NsrService<Consumer, QConsumer,String> {

    Consumer findByTopicAppGroup(String namespace,String topic,String app,String group);

    List<String> findAllSubscribeGroups();

}
