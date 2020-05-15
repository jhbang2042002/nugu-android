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

package com.skt.nugu.sdk.agent.ext.message

import com.google.gson.JsonObject

data class Contact(
    val name: String,
    val type: Type,
    val number: String?,
    val profileImgUrl: String?,
    val message: String?,
    val time: String?,
    val score: String?
) {
    enum class Type {
        CONTACT,
        EXCHANGE,
        T114,
        NONE
    }

    fun toJsonObject() = JsonObject().apply {
        addProperty("name", name)
        addProperty("type", type.name)
        number?.let {
            addProperty("number", it)
        }
        profileImgUrl?.let {
            addProperty("profileImgUrl", it)
        }
        message?.let {
            addProperty("message", it)
        }
        time?.let {
            addProperty("time", it)
        }
        score?.let {
            addProperty("score", it)
        }
    }
}