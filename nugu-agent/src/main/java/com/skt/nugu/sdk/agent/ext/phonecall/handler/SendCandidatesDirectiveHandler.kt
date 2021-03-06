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

package com.skt.nugu.sdk.agent.ext.phonecall.handler

import com.google.gson.JsonObject
import com.skt.nugu.sdk.agent.AbstractDirectiveHandler
import com.skt.nugu.sdk.agent.ext.phonecall.Context
import com.skt.nugu.sdk.agent.ext.phonecall.Person
import com.skt.nugu.sdk.agent.ext.phonecall.PhoneCallAgent
import com.skt.nugu.sdk.agent.ext.phonecall.payload.SendCandidatesPayload
import com.skt.nugu.sdk.agent.util.IgnoreErrorContextRequestor
import com.skt.nugu.sdk.agent.util.MessageFactory
import com.skt.nugu.sdk.core.interfaces.common.NamespaceAndName
import com.skt.nugu.sdk.core.interfaces.context.ContextGetterInterface
import com.skt.nugu.sdk.core.interfaces.directive.BlockingPolicy
import com.skt.nugu.sdk.core.interfaces.message.MessageSender
import com.skt.nugu.sdk.core.interfaces.message.request.EventMessageRequest

class SendCandidatesDirectiveHandler(
    private val controller: Controller,
    private val messageSender: MessageSender,
    private val contextGetter: ContextGetterInterface,
    private val namespaceAndName: NamespaceAndName
) : AbstractDirectiveHandler() {
    companion object {
        private const val NAME_SEND_CANDIDATES = "SendCandidates"
        private const val NAME_CANDIDATES_LISTED = "CandidatesListed"

        private val SEND_CANDIDATES =
            NamespaceAndName(PhoneCallAgent.NAMESPACE, NAME_SEND_CANDIDATES)
    }

    interface Callback {
        fun onSuccess(context: Context)
        fun onFailure()
    }

    interface Controller {
        fun sendCandidates(payload: SendCandidatesPayload, callback: Callback)
    }

    override fun preHandleDirective(info: DirectiveInfo) {
    }

    override fun handleDirective(info: DirectiveInfo) {
        val payload =
            MessageFactory.create(info.directive.payload, SendCandidatesPayload::class.java)
        if (payload == null) {
            info.result.setFailed("Invalid Payload")
        } else {
            info.result.setCompleted()
            controller.sendCandidates(payload, object: Callback {
                override fun onSuccess(context: Context) {
                    val jsonContext = contextGetter.getCompactContextWith(namespaceAndName, PhoneCallAgent.StateContext(context).toFullJsonString())
                    messageSender.sendMessage(
                        EventMessageRequest.Builder(
                            jsonContext,
                            PhoneCallAgent.NAMESPACE,
                            NAME_CANDIDATES_LISTED,
                            PhoneCallAgent.VERSION.toString()
                        ).payload(JsonObject().apply {
                            addProperty("playServiceId", payload.playServiceId)
                        }.toString())
                            .referrerDialogRequestId(info.directive.getDialogRequestId())
                            .build()
                    )
                }

                override fun onFailure() {
                    // can't send without context
                }
            })
        }
    }

    override fun cancelDirective(info: DirectiveInfo) {
        // no-op
    }

    override fun getConfiguration(): Map<NamespaceAndName, BlockingPolicy> {
        val configurations = HashMap<NamespaceAndName, BlockingPolicy>()

        configurations[SEND_CANDIDATES] = BlockingPolicy()

        return configurations
    }
}