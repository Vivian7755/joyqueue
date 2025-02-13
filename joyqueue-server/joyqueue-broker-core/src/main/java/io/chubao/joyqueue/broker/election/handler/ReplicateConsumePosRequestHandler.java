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
package io.chubao.joyqueue.broker.election.handler;

import io.chubao.joyqueue.broker.consumer.Consume;
import io.chubao.joyqueue.broker.election.ElectionConfig;
import io.chubao.joyqueue.broker.election.command.ReplicateConsumePosRequest;
import io.chubao.joyqueue.broker.election.command.ReplicateConsumePosResponse;
import io.chubao.joyqueue.broker.BrokerContext;
import io.chubao.joyqueue.network.transport.codec.JoyQueueHeader;
import io.chubao.joyqueue.network.transport.command.Command;
import io.chubao.joyqueue.network.command.CommandType;
import io.chubao.joyqueue.network.transport.command.Direction;
import io.chubao.joyqueue.network.transport.command.Type;
import io.chubao.joyqueue.network.transport.command.handler.CommandHandler;
import io.chubao.joyqueue.network.transport.Transport;
import io.chubao.joyqueue.network.transport.exception.TransportException;
import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * author: zhuduohui
 * email: zhuduohui@jd.com
 * date: 2018/9/29
 */
public class ReplicateConsumePosRequestHandler implements CommandHandler, Type {
    private static Logger logger = LoggerFactory.getLogger(ReplicateConsumePosRequestHandler.class);

    private Consume consume;
    private ElectionConfig electionConfig;

    public ReplicateConsumePosRequestHandler(ElectionConfig electionConfig, Consume consume) {
        Preconditions.checkArgument(consume != null, "consume is null");

        this.electionConfig = electionConfig;
        this.consume = consume;
    }

    public ReplicateConsumePosRequestHandler(BrokerContext brokerContext) {
        Preconditions.checkArgument(brokerContext != null, "broker context is null");
        Preconditions.checkArgument(brokerContext.getConsume() != null, "consume is null");

        this.consume = brokerContext.getConsume();
        this.electionConfig = new ElectionConfig(brokerContext.getPropertySupplier());
    }

    @Override
    public Command handle(Transport transport, Command command) throws TransportException {
        if (!(command.getPayload() instanceof ReplicateConsumePosRequest)) {
            throw new IllegalArgumentException();
        }

        ReplicateConsumePosRequest request = (ReplicateConsumePosRequest)command.getPayload();
        boolean success;
        JoyQueueHeader header = new JoyQueueHeader(Direction.RESPONSE, CommandType.REPLICATE_CONSUME_POS_RESPONSE);
        ReplicateConsumePosResponse response = new ReplicateConsumePosResponse(false);

        if (request.getConsumePositions() == null) {
            logger.info("Receive consume pos request, consume position is null");
            return new Command(header, response);
        }

        if (logger.isDebugEnabled() || electionConfig.getOutputConsumePos()) {
            logger.info("Receive consume pos request {}", request.getConsumePositions());
        }

        try {
            success = consume.setConsumeInfo(request.getConsumePositions());
            response.setSuccess(success);
        } catch (Exception e) {
            logger.warn("Set consume info {} fail", request.getConsumePositions(), e);
        }

        return new Command(header, response);
    }

    @Override
    public int type() {
        return CommandType.REPLICATE_CONSUME_POS_REQUEST;
    }
}
