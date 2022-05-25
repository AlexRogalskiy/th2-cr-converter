/*
 * Copyright 2020-2022 Exactpro (Exactpro Systems Limited)
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

package com.exactpro.th2.converter.model.v1.link

import com.exactpro.th2.converter.model.latest.link.BoxesRelation
import com.exactpro.th2.converter.model.latest.link.RouterLink
import com.fasterxml.jackson.annotation.JsonProperty

data class BoxesRelationV1(
    @JsonProperty("router-grpc") val routerGrpc: List<RouterLink>?,
    @JsonProperty("router-mq") val routerMq: List<RouterLink>?
) {
    fun toBoxesRelation(): BoxesRelation {
        return BoxesRelation(routerGrpc, routerMq)
    }
}
