/**
 * Copyright (c) 2020 SK Telecom Co., Ltd. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http:www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.skt.nugu.sdk.client.port.transport.grpc2.devicegateway

import com.skt.nugu.sdk.client.port.transport.grpc2.utils.DirectivePreconditions.checkIfDirectiveIsUnauthorizedRequestException
import com.skt.nugu.sdk.client.port.transport.grpc2.utils.DirectivePreconditions.checkIfEventMessageIsAsrRecognize
import com.skt.nugu.sdk.client.port.transport.grpc2.utils.MessageRequestConverter.toProtobufMessage
import com.skt.nugu.sdk.core.interfaces.message.request.AttachmentMessageRequest
import com.skt.nugu.sdk.core.interfaces.message.request.EventMessageRequest
import com.skt.nugu.sdk.core.utils.Logger
import devicegateway.grpc.Downstream
import devicegateway.grpc.Upstream
import devicegateway.grpc.VoiceServiceGrpc
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.stub.StreamObserver
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


/**
 * This class is designed to manage upstream of DeviceGateway
 */
internal class EventsService(
    private val channel: ManagedChannel,
    private val observer: DeviceGatewayTransport
) {
    companion object {
        private const val TAG = "EventsService"
        private const val defaultTimeout: Long = 1000 * 10L
    }

    private val isShutdown = AtomicBoolean(false)
    private val streamLock = ReentrantLock()
    //private var lastSentEventMessage : EventMessageRequest? = null

    private val requestStreamMap = ConcurrentHashMap<String, ClientChannel?>()

    internal data class ClientChannel (
        val clientCall : StreamObserver<Upstream>,
        val responseObserver : ClientCallStreamObserver
    )

    private fun obtainChannel(streamId: String): ClientChannel? {
        if (isShutdown.get()) {
            return null
        }

        return run {
            if (requestStreamMap[streamId] == null) {
                val responseObserver = ClientCallStreamObserver(streamId)
                VoiceServiceGrpc.newStub(channel)
                    .withDeadlineAfter(defaultTimeout, TimeUnit.MILLISECONDS)
                    .withWaitForReady()?.events(responseObserver)?.apply {
                        requestStreamMap[streamId] =
                            ClientChannel(
                                this,
                                responseObserver
                            )
                    }
            }
            return requestStreamMap[streamId]
        }
    }

    inner class ClientCallStreamObserver(val streamId: String) : StreamObserver<Downstream> {
        private var startAttachmentTimeMillis = 0L
        private var isReceivedDownstream = false
        var isSendingAttachmentMessage = false
        var isAsrRecognize = false

        override fun onNext(downstream: Downstream) {
            isReceivedDownstream = true

            when (downstream.messageCase) {
                Downstream.MessageCase.DIRECTIVE_MESSAGE -> {
                    downstream.directiveMessage?.let {
                        if (it.directivesCount > 0) {
                            val log = StringBuilder()
                            val beginTimeStamp = System.currentTimeMillis()
                            observer.onReceiveDirectives(it)
                            log.append("[EventsService] directive, messageId=")
                            val elapsed = System.currentTimeMillis() - beginTimeStamp
                            it.directivesList.forEach {
                                log.append(it.header.messageId)
                                log.append(", ")
                            }
                            if(elapsed  > 100) {
                                log.append("elapsed=$elapsed")
                            }
                            Logger.d(TAG, log.toString())
                        }
                        if (it.checkIfDirectiveIsUnauthorizedRequestException()) {
                            observer.onError(Status.UNAUTHENTICATED)
                        }
                    }
                }
                Downstream.MessageCase.ATTACHMENT_MESSAGE -> {
                    downstream.attachmentMessage?.let {
                        if (it.hasAttachment()) {
                            val currentTimeMillis =  System.currentTimeMillis()
                            if (it.attachment.seq == 0) {
                                startAttachmentTimeMillis = currentTimeMillis
                                Logger.d(TAG, "[EventsService] attachment start, seq=${it.attachment.seq}, parentMessageId=${it.attachment.parentMessageId}")
                            }
                            if (it.attachment.isEnd) {
                                val elapsed = currentTimeMillis - startAttachmentTimeMillis
                                Logger.d(TAG, "[EventsService] attachment end, seq=${it.attachment.seq}, parentMessageId=${it.attachment.parentMessageId}, elapsed=${elapsed}ms")
                            }
                            observer.onReceiveAttachment(it)

                            val dispatchTimestamp = currentTimeMillis - System.currentTimeMillis()
                            if(dispatchTimestamp  > 100) {
                                Logger.w(TAG, "[EventsService] attachment, operation has been delayed (${dispatchTimestamp}ms), messageId=${it.attachment.header.messageId} ")
                            }
                        }
                    }
                }
                else -> {
                    Logger.e(TAG, "[EventsService] unknown messageCase : ${downstream.messageCase}")
                }
            }
        }

        override fun onError(t: Throwable) {
            if (!isShutdown.get()) {
                val status = Status.fromThrowable(t)

                val log = StringBuilder()
                log.append("[onError] ${status.code}, ${status.description}, $streamId")
                if(status.code == Status.Code.DEADLINE_EXCEEDED) {
                    // TODO :: When sending Asr.Recognize and not sending Attachment
                    if(isAsrRecognize && !isSendingAttachmentMessage) {
                        halfClose(streamId)
                        log.append(", It occurs because the attachment was not sent after Asr.Recognize.")
                        Logger.w(TAG, log.toString())
                        return
                    }
                    if(isReceivedDownstream) {
                        Logger.e(TAG, log.toString())
                        return
                    }
                }
                Logger.e(TAG, log.toString())
                halfClose(streamId)

                if(status.code == Status.Code.CANCELLED) {
                    return
                }
                observer.onError(status)
            }
        }

        override fun onCompleted() {
            Logger.d(TAG, "[onCompleted] messageId=$streamId")
            halfClose(streamId)
        }
    }

    private fun halfClose(streamId : String) {
        streamLock.withLock {
            try {
                requestStreamMap.remove(streamId)?.clientCall?.onCompleted()
            } catch (e: IllegalStateException) {
                Logger.w(TAG, "[close] Exception : ${e.cause} ${e.message}")
            }
        }
    }

    fun sendAttachmentMessage(attachment: AttachmentMessageRequest): Boolean {
        if (isShutdown.get()) {
            Logger.w(TAG, "[sendAttachmentMessage] already shutdown")
            return false
        }

        try {
            streamLock.withLock {
                obtainChannel(attachment.parentMessageId)?.apply {
                    this.responseObserver.isSendingAttachmentMessage = true
                    this.clientCall.onNext(
                        Upstream.newBuilder()
                            .setAttachmentMessage(attachment.toProtobufMessage())
                            .build()
                    )
                }
            }
        } catch (e: IllegalStateException) {
            // Perhaps, Stream is already completed, no further calls are allowed
            Logger.w(TAG, "[sendAttachmentMessage] Exception : ${e.cause} ${e.message}")
            return false
        }
        return true
    }

    fun sendEventMessage(event: EventMessageRequest): Boolean {
        if (isShutdown.get()) {
            Logger.w(TAG, "[sendEventMessage] already shutdown")
            return false
        }

        try {
            streamLock.withLock {
                obtainChannel(event.messageId)?.apply {
                    this.responseObserver.isAsrRecognize = event.checkIfEventMessageIsAsrRecognize()
                    this.clientCall.onNext(
                        Upstream.newBuilder()
                            .setEventMessage(event.toProtobufMessage())
                            .build()
                    )
                }
            }
        } catch (e: IllegalStateException) {
            // Perhaps, Stream is already completed, no further calls are allowed
            Logger.w(TAG, "[sendEventMessage] Exception : ${e.cause} ${e.message}")
            return false
        }
        return true
    }

    fun shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            requestStreamMap.forEach {
                halfClose(it.key)
            }
            requestStreamMap.clear()
        } else {
            Logger.w(TAG, "[shutdown] already shutdown")
        }
    }
}