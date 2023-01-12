/*
 * Copyright 2013-2023 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package brave.rocketmq.clients;

import brave.Span;
import org.apache.rocketmq.client.hook.SendMessageContext;
import org.apache.rocketmq.client.hook.SendMessageHook;
import org.apache.rocketmq.client.impl.CommunicationMode;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;

/**
 * @author JoeKerouac
 * @date 2023-01-10 15:40
 */
public class SendMessageBraveHookImpl implements SendMessageHook {

  final RocketMQTracing tracing;

  public SendMessageBraveHookImpl(RocketMQTracing tracing) {
    this.tracing = tracing;
  }

  @Override
  public String hookName() {
    return "SendMessageBraveHook";
  }

  @Override
  public void sendMessageBefore(SendMessageContext context) {
    if (context == null || context.getMessage() == null) {
      return;
    }

    Message msg = context.getMessage();
    MessageProducerRequest request = new MessageProducerRequest(msg);
    Span span =
      SpanUtil.createAndStartSpan(tracing, tracing.producerExtractor, tracing.producerSampler,
        request,
        msg.getProperties());
    span.name(TraceConstants.TO_PREFIX + msg.getTopic());
    span.tag(TraceConstants.ROCKETMQ_TAGS, StringUtils.getOrEmpty(msg.getTags()));
    span.tag(TraceConstants.ROCKETMQ_KEYS, StringUtils.getOrEmpty(msg.getKeys()));
    span.tag(TraceConstants.ROCKETMQ_SOTRE_HOST, StringUtils.getOrEmpty(context.getBrokerAddr()));
    span.tag(TraceConstants.ROCKETMQ_MSG_TYPE,
      context.getMsgType() == null ? StringUtils.EMPTY : context.getMsgType().name());
    span.tag(TraceConstants.ROCKETMQ_BODY_LENGTH,
      Integer.toString(msg.getBody() == null ? 0 : msg.getBody().length));
    context.setMqTraceContext(span);
    tracing.producerInjector.inject(span.context(), request);
  }

  @Override
  public void sendMessageAfter(SendMessageContext context) {
    if (context == null || context.getMessage() == null || context.getMqTraceContext() == null) {
      return;
    }

    SendResult sendResult = context.getSendResult();
    Span span = (Span) context.getMqTraceContext();
    MessageProducerRequest request = new MessageProducerRequest(context.getMessage());

    long timestamp = tracing.tracing.clock(span.context()).currentTimeMicroseconds();
    if (sendResult == null) {
      if (context.getCommunicationMode() == CommunicationMode.ASYNC) {
        return;
      }
      span.tag(TraceConstants.ROCKETMQ_SUCCESS, Boolean.toString(false));
      span.finish(timestamp);
      tracing.producerInjector.inject(span.context(), request);
      return;
    }

    span.tag(TraceConstants.ROCKETMQ_REGION_ID, sendResult.getRegionId());
    span.tag(TraceConstants.ROCKETMQ_MSG_ID, StringUtils.getOrEmpty(sendResult.getMsgId()));
    span.tag(TraceConstants.ROCKETMQ_SUCCESS,
      Boolean.toString(sendResult.getSendStatus() == SendStatus.SEND_OK));
    tracing.producerInjector.inject(span.context(), request);
    span.finish(timestamp);
  }
}
