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
package com.skt.nugu.sdk.agent

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.skt.nugu.sdk.agent.asr.ExpectSpeechPayload
import com.skt.nugu.sdk.agent.text.TextAgentInterface
import com.skt.nugu.sdk.agent.util.IgnoreErrorContextRequestor
import com.skt.nugu.sdk.agent.util.MessageFactory
import com.skt.nugu.sdk.agent.version.Version
import com.skt.nugu.sdk.core.interfaces.common.NamespaceAndName
import com.skt.nugu.sdk.core.interfaces.context.ContextManagerInterface
import com.skt.nugu.sdk.core.interfaces.context.ContextSetterInterface
import com.skt.nugu.sdk.core.interfaces.context.ContextState
import com.skt.nugu.sdk.core.interfaces.context.StateRefreshPolicy
import com.skt.nugu.sdk.core.interfaces.dialog.DialogAttributeStorageInterface
import com.skt.nugu.sdk.core.interfaces.directive.BlockingPolicy
import com.skt.nugu.sdk.core.interfaces.inputprocessor.InputProcessor
import com.skt.nugu.sdk.core.interfaces.inputprocessor.InputProcessorManagerInterface
import com.skt.nugu.sdk.core.interfaces.message.Directive
import com.skt.nugu.sdk.core.interfaces.message.MessageSender
import com.skt.nugu.sdk.core.interfaces.message.request.EventMessageRequest
import com.skt.nugu.sdk.core.utils.Logger
import com.skt.nugu.sdk.core.utils.UUIDGeneration
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

class DefaultTextAgent(
    private val messageSender: MessageSender,
    private val contextManager: ContextManagerInterface,
    private val inputProcessorManager: InputProcessorManagerInterface,
    private val dialogAttributeStorage: DialogAttributeStorageInterface,
    private val textSourceHandler: TextAgentInterface.TextSourceHandler?
) : AbstractCapabilityAgent(NAMESPACE)
    , InputProcessor
    , TextAgentInterface{
    internal data class TextSourcePayload(
        @SerializedName("playServiceId")
        val playServiceId: String,
        @SerializedName("text")
        val text: String,
        @SerializedName("token")
        val token: String
    )

    companion object {
        private const val TAG = "TextAgent"

        const val NAMESPACE = "Text"
        private val VERSION = Version(1,1)

        private const val NAME_TEXT_SOURCE = "TextSource"

        val TEXT_SOURCE = NamespaceAndName(
            NAMESPACE,
            NAME_TEXT_SOURCE
        )

        const val NAME_TEXT_INPUT = "TextInput"
    }

    private val requestListeners = HashMap<String, TextAgentInterface.RequestListener>()
    private val internalTextSourceHandleListeners = CopyOnWriteArraySet<TextAgentInterface.InternalTextSourceHandlerListener>()
    private val executor = Executors.newSingleThreadExecutor()

    init {
        contextManager.setStateProvider(namespaceAndName, this)
    }

    override fun addInternalTextSourceHandlerListener(listener: TextAgentInterface.InternalTextSourceHandlerListener) {
        internalTextSourceHandleListeners.add(listener)
    }

    override fun removeInternalTextSourceHandlerListener(listener: TextAgentInterface.InternalTextSourceHandlerListener) {
        internalTextSourceHandleListeners.remove(listener)
    }

    override fun preHandleDirective(info: DirectiveInfo) {
        // no-op
        Logger.d(TAG, "[preHandleDirective] info: $info")
    }

    override fun handleDirective(info: DirectiveInfo) {
        Logger.d(TAG, "[handleDirective] info: $info")
        executor.submit {
            executeHandleDirective(info)
        }
    }

    private fun executeHandleDirective(info: DirectiveInfo) {
        Logger.d(TAG, "[executeHandleDirective] info: $info")
        val payload =
            MessageFactory.create(info.directive.payload, TextSourcePayload::class.java)
        if (payload == null) {
            Logger.d(TAG, "[executeHandleDirective] invalid payload: ${info.directive.payload}")
            executeSetHandlingFailed(
                info,
                "[executeHandleDirective] invalid payload: ${info.directive.payload}"
            )
            return
        }

        executeSetHandlingCompleted(info)
        if(textSourceHandler?.handleTextSource(info.directive.payload, info.directive.header) == true) {
            Logger.d(TAG, "[executeHandleDirective] handled at TextSourceHandler($textSourceHandler)")
        } else {
            val dialogRequestId = executeSendTextInputEventInternal(payload.text, payload.token, info.directive.header.dialogRequestId, object: TextAgentInterface.RequestListener {
                override fun onReceiveResponse(dialogRequestId: String) {
                    internalTextSourceHandleListeners.forEach {
                        it.onReceiveResponse(dialogRequestId)
                    }
                }

                override fun onError(dialogRequestId: String, type: TextAgentInterface.ErrorType) {
                    internalTextSourceHandleListeners.forEach {
                        it.onError(dialogRequestId, type)
                    }
                }
            })
            internalTextSourceHandleListeners.forEach {
                it.onRequested(dialogRequestId)
            }
        }
    }

    private fun executeSetHandlingCompleted(info: DirectiveInfo) {
        Logger.d(TAG, "[executeSetHandlingCompleted] info: $info")
        info.result.setCompleted()
    }

    private fun executeSetHandlingFailed(info: DirectiveInfo, msg: String) {
        Logger.d(TAG, "[executeSetHandlingFailed] info: $info")
        info.result.setFailed(msg)
    }

    override fun cancelDirective(info: DirectiveInfo) {
    }

    override fun getConfiguration(): Map<NamespaceAndName, BlockingPolicy> {
        val nonBlockingPolicy = BlockingPolicy()

        val configuration = HashMap<NamespaceAndName, BlockingPolicy>()

        configuration[TEXT_SOURCE] = nonBlockingPolicy

        return configuration
    }

    override fun provideState(
        contextSetter: ContextSetterInterface,
        namespaceAndName: NamespaceAndName,
        stateRequestToken: Int
    ) {
        contextSetter.setState(namespaceAndName, object: ContextState{
            val state = JsonObject().apply {
                addProperty("version", VERSION.toString())
            }.toString()

            override fun toFullJsonString(): String = state
            override fun toCompactJsonString(): String = state
        }, StateRefreshPolicy.NEVER, stateRequestToken)
    }

    override fun requestTextInput(text: String, listener: TextAgentInterface.RequestListener?): String {
        Logger.d(TAG, "[requestTextInput] text: $text")
        return executeSendTextInputEventInternal(text, null, null, listener)
    }

    private fun createMessage(text: String, context: String, token: String?, dialogRequestId: String, referrerDialogRequestId: String?) =
        EventMessageRequest.Builder(
            context,
            NAMESPACE,
            NAME_TEXT_INPUT,
            VERSION.toString()
        ).dialogRequestId(dialogRequestId)
            .payload(
            JsonObject().apply
            {
                addProperty("text", text)
                token?.let {
                    addProperty("token", it)
                }

                dialogAttributeStorage.getAttributes()?.let { attrs ->
                    attrs.forEach {attr ->
                        add(attr.key, Gson().toJsonTree(attr.value))
                    }
                }
            }.toString()
        ).referrerDialogRequestId(referrerDialogRequestId ?: "").build()

    private fun executeSendTextInputEventInternal(
        text: String,
        token: String?,
        referrerDialogRequestId: String?,
        listener: TextAgentInterface.RequestListener?
    ): String {
        val dialogRequestId = UUIDGeneration.timeUUID().toString()

        contextManager.getContext(object : IgnoreErrorContextRequestor() {
            override fun onContext(jsonContext: String) {
                Logger.d(TAG, "[onContextAvailable] jsonContext: $jsonContext")
                executor.submit {
                    createMessage(text, jsonContext, token, dialogRequestId, referrerDialogRequestId).let {
                        if(messageSender.sendMessage(it)) {
                            if (listener != null) {
                                requestListeners[it.dialogRequestId] = listener
                            }
                            onSendEventFinished(it.dialogRequestId)
                        } else {
                            listener?.onError(dialogRequestId, TextAgentInterface.ErrorType.ERROR_NETWORK)
                        }
                    }
                }
            }
        })

        return dialogRequestId
    }

    override fun onSendEventFinished(dialogRequestId: String) {
        inputProcessorManager.onRequested(this, dialogRequestId)
    }

    override fun onReceiveDirectives(
        dialogRequestId: String,
        directives: List<Directive>
    ): Boolean {
        executor.submit {
            requestListeners.remove(dialogRequestId)?.onReceiveResponse(dialogRequestId)
        }
        return true
    }

    override fun onResponseTimeout(dialogRequestId: String) {
        Logger.d(TAG, "[onResponseTimeout] $dialogRequestId")
        executor.submit {
            requestListeners.remove(dialogRequestId)
                ?.onError(dialogRequestId, TextAgentInterface.ErrorType.ERROR_RESPONSE_TIMEOUT)
        }
    }
}
