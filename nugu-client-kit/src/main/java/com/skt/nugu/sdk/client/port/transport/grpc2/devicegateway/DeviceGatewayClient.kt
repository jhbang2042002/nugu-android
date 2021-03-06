/**
 * Copyright (c) 2019 SK Telecom Co., Ltd. All rights reserved.
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

import com.skt.nugu.sdk.client.port.transport.grpc2.Policy
import com.skt.nugu.sdk.client.port.transport.grpc2.ServerPolicy
import com.skt.nugu.sdk.client.port.transport.grpc2.utils.BackOff
import com.skt.nugu.sdk.client.port.transport.grpc2.utils.ChannelBuilderUtils
import com.skt.nugu.sdk.client.port.transport.grpc2.utils.MessageRequestConverter.toAttachmentMessage
import com.skt.nugu.sdk.client.port.transport.grpc2.utils.MessageRequestConverter.toDirectives
import com.skt.nugu.sdk.client.port.transport.grpc2.utils.MessageRequestConverter.toStringMessage
import com.skt.nugu.sdk.core.interfaces.connection.ConnectionStatusListener.ChangedReason
import com.skt.nugu.sdk.core.interfaces.message.MessageConsumer
import com.skt.nugu.sdk.core.interfaces.message.MessageRequest
import com.skt.nugu.sdk.core.interfaces.message.request.AttachmentMessageRequest
import com.skt.nugu.sdk.core.interfaces.message.request.CrashReportMessageRequest
import com.skt.nugu.sdk.core.interfaces.message.request.EventMessageRequest
import com.skt.nugu.sdk.core.utils.Logger
import devicegateway.grpc.AttachmentMessage
import devicegateway.grpc.DirectiveMessage
import io.grpc.ManagedChannel
import io.grpc.Status
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 *  Implementation of DeviceGateway
 **/
internal class DeviceGatewayClient(policy: Policy,
                                   private val keepConnection : Boolean,
                                   private var messageConsumer: MessageConsumer?,
                                   private var transportObserver: DeviceGatewayTransport.TransportObserver?,
                                   private val authorization: String?,
                                   var isHandOff: Boolean)
    :
    DeviceGatewayTransport {
    companion object {
        private const val TAG = "DeviceGatewayClient"
    }

    private val policies = ConcurrentLinkedQueue(policy.serverPolicy)
    private var backoff : BackOff = BackOff.DEFAULT()

    private var currentChannel: ManagedChannel? = null

    private var pingService: PingService? = null
    private var eventsService: EventsService? = null
    private var crashReportService: CrashReportService? = null
    private var directivesService: DirectivesService? = null

    private var currentPolicy : ServerPolicy? = nextPolicy()
    private var healthCheckPolicy = policy.healthCheckPolicy

    private val isConnected = AtomicBoolean(false)

    /**
     * Set a policy.
     * @return the ServerPolicy
     */
    private fun nextPolicy(): ServerPolicy? {
        backoff.reset()
        currentPolicy = policies.poll()
        currentPolicy?.let {
            backoff = BackOff.Builder(maxAttempts = it.retryCountLimit).build()
        }
        return currentPolicy
    }

    /**
     * Connect to DeviceGateway.
     * @return true is success, otherwise false
     */
    override fun connect(): Boolean {
        if (isConnected()) {
            return false
        }

        val policy = currentPolicy ?: run {
            Logger.w(TAG, "[connect] no more policy")
            transportObserver?.onError(
                ChangedReason.UNRECOVERABLE_ERROR
            )
            return false
        }

        policy.apply {
            currentChannel = ChannelBuilderUtils.createChannelBuilderWith(this, authorization).build()
            currentChannel?.apply {
                eventsService =
                    EventsService(
                        this,
                        this@DeviceGatewayClient
                    )
                crashReportService =
                    CrashReportService(
                        this
                    )
                if(keepConnection) {
                    directivesService =
                        DirectivesService(
                            this,
                            this@DeviceGatewayClient
                        )
                    pingService =
                        PingService(
                            this,
                            healthCheckPolicy,
                            this@DeviceGatewayClient
                        )
                }
                else {
                    handleConnectedIfNeeded()
                }
            } ?: run {
                Logger.w(TAG, "[connect] Can't create a new channel")
                transportObserver?.onError(
                    ChangedReason.UNRECOVERABLE_ERROR
                )
                return false
            }
        }
        return true
    }

    /**
     * disconnect from DeviceGateway
     */
    override fun disconnect() {
        pingService?.shutdown()
        pingService = null
        eventsService?.shutdown()
        eventsService = null
        directivesService?.shutdown()
        directivesService = null
        crashReportService?.shutdown()
        crashReportService = null
        ChannelBuilderUtils.shutdown(currentChannel)
        currentChannel = null
        isConnected.set(false)
    }

    /**
     * Returns whether this object is currently connected to DeviceGateway.
     */
    override fun isConnected(): Boolean = isConnected.get()

    override fun isConnectedOrConnecting(): Boolean {
        throw NotImplementedError("not implemented")
    }

    /**
     * Sends a message request.
     * @param request the messageRequest to be sent
     * @return true is success, otherwise false
     */
    override fun send(request: MessageRequest) : Boolean {
        val event = eventsService
        val crash = crashReportService

        val result = when(request) {
            is AttachmentMessageRequest -> event?.sendAttachmentMessage(request)
            is EventMessageRequest -> event?.sendEventMessage(request)
            is CrashReportMessageRequest -> crash?.sendCrashReport(request)
            else -> false
        } ?: false

        Logger.d(TAG, "sendMessage : ${request.toStringMessage()}, result : $result")
        return result
    }

    /**
     * Receive an error.
     * @param the status of grpc
     */
    override fun onError(status: Status) {
        Logger.w(TAG, "[onError] Error : ${status.code}")

        when(status.code) {
            Status.Code.PERMISSION_DENIED,
            Status.Code.UNAUTHENTICATED -> {
                // nothing to do
            }
            else -> {
                transportObserver?.onReconnecting( when(status.code) {
                    Status.Code.OK -> ChangedReason.SUCCESS
                    Status.Code.UNAVAILABLE -> {
                        var cause = status.cause
                        var reason =
                            if (isConnected()) ChangedReason.SERVER_SIDE_DISCONNECT
                            else ChangedReason.CONNECTION_ERROR
                        while (cause != null) {
                            if (cause is UnknownHostException) {
                                reason = ChangedReason.DNS_TIMEDOUT
                            }  else if(cause is SocketTimeoutException) {
                                reason = ChangedReason.CONNECTION_TIMEDOUT
                            } else if( cause is ConnectException) {
                                reason = ChangedReason.CONNECTION_ERROR
                            }
                            cause = cause.cause
                        }
                        reason
                    }
                    Status.Code.UNKNOWN -> ChangedReason.SERVER_SIDE_DISCONNECT
                    Status.Code.DEADLINE_EXCEEDED -> {
                        if (isConnected()) {
                            if(pingService?.isStop() == false) {
                                ChangedReason.PING_TIMEDOUT
                            } else {
                                ChangedReason.REQUEST_TIMEDOUT
                            }
                        }
                        else ChangedReason.CONNECTION_TIMEDOUT
                    }
                    Status.Code.UNIMPLEMENTED -> ChangedReason.FAILURE_PROTOCOL_ERROR
                    Status.Code.NOT_FOUND ,
                    Status.Code.ALREADY_EXISTS ,
                    Status.Code.RESOURCE_EXHAUSTED ,
                    Status.Code.FAILED_PRECONDITION ,
                    Status.Code.ABORTED ,
                    Status.Code.INTERNAL -> ChangedReason.SERVER_INTERNAL_ERROR
                    Status.Code.OUT_OF_RANGE,
                    Status.Code.DATA_LOSS,
                    Status.Code.CANCELLED,
                    Status.Code.INVALID_ARGUMENT -> ChangedReason.INTERNAL_ERROR
                    else -> {
                        throw NotImplementedError()
                    }
                })
            }
        }

        isConnected.compareAndSet(true, false)

        backoff.awaitRetry(status.code, object : BackOff.Observer {
            override fun onError(error: BackOff.BackoffError) {
                Logger.w(TAG, "[awaitRetry] Error : $error")

                when (status.code) {
                    Status.Code.PERMISSION_DENIED,
                    Status.Code.UNAUTHENTICATED -> {
                        transportObserver?.onError(ChangedReason.INVALID_AUTH)
                    }
                    else -> {
                        nextPolicy()
                        disconnect()
                        connect()
                    }
                }
            }

            override fun onRetry(retriesAttempted: Int) {
                if (isConnected()) {
                    Logger.w(TAG, "[awaitRetry] connected")
                } else {
                    disconnect()
                    connect()
                }
            }
        })
    }

    override fun shutdown() {
        Logger.d(TAG, "[shutdown]")
        messageConsumer = null
        transportObserver = null

        disconnect()
        backoff.reset()
    }

    /**
     * Connected event received
     * @return boolean value, true if the connection has changed, false otherwise.
     */
    private fun handleConnectedIfNeeded()  {
        if(isConnected.compareAndSet(false, true)) {
            if(isHandOff) {
                isHandOff = false
                Logger.d(TAG,"[handleConnectedIfNeeded] Handoff complete")
            }
            backoff.reset()
            transportObserver?.onConnected()
        }
    }

    /**
     * Notification that sending a ping to DeviceGateway has been acknowledged by DeviceGateway.
     */
    override fun onPingRequestAcknowledged() {
        Logger.d(TAG, "onPingRequestAcknowledged, isConnected:${isConnected()}")
        handleConnectedIfNeeded()
    }

    /**

     * attachment received
     * @param attachmentMessage
     */
    override fun onReceiveAttachment(attachmentMessage: AttachmentMessage) {
        messageConsumer?.consumeAttachment(attachmentMessage.toAttachmentMessage())
    }

    /**
     * directive received
     * @param directiveMessage
     */
    override fun onReceiveDirectives(directiveMessage: DirectiveMessage) {
        handleConnectedIfNeeded()
        messageConsumer?.consumeDirectives(directiveMessage.toDirectives())
    }
}